package nx.pingwheel.common.client.outline;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.platform.IPlatformClientEventService.WorldSubmitContext;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * M7a block outline pass.
 *
 * <p>Reproduces the upstream outline spec — 3.75px, NO_DEPTH_TEST, TRANSLUCENT,
 * NO_CULL, COLOR_WRITE on {@code rendertype_lines} — with a custom pipeline
 * ({@link PingOutlineRenderType}). Every built-in line render type keeps depth testing
 * enabled, so the custom pipeline is the only way to get the upstream "visible through
 * walls" behaviour.
 *
 * <p><strong>Bucket routing.</strong>
 * {@code SubmitNodeCollection.submitShapeOutline} routes any colour whose
 * {@code ARGB.alpha(color) == 255} to the solid phase and ignores its
 * {@code afterTerrain} argument. This pass keeps the upstream colour algorithm
 * {@code 0xFF000000 | outlineColor} (alpha 255) and therefore always submits with
 * {@code afterTerrain = true}; {@code SubmitNodeCollectionMixin} recognises the custom
 * render type by identity and moves the submission into the after-terrain bucket.
 * Because that mixin is declared {@code require = 0}, it may silently not apply — the
 * outline then stays in the solid bucket, which still draws and still shows through
 * walls (depth testing is off), but translucent geometry may tint it. That case is
 * reported once via {@link OutlineRoutingState} and is deliberately <em>not</em>
 * treated as a failure.
 *
 * <p><strong>Fallback.</strong>
 * When {@link PingOutlineRenderType#create()} returns {@code null} the pass does not
 * draw at all: it warns once and disables itself for the session. Falling back to a
 * built-in line type was rejected because those keep depth testing, which would
 * silently change the upstream look — and a silently wrong outline is worse than a
 * missing one under the fidelity rule.
 *
 * <p><strong>Selection.</strong>
 * Only visible marker winners in the current dimension whose target type participates
 * in block-model rendering ({@code block}, {@code entity_block}) are considered. Each
 * winner is resolved into a client presentation
 * ({@code BlockPresentationResolverRegistry}); the pass then draws every render subject
 * of that presentation, so a door or bed is outlined as one object instead of the
 * captured half alone. A subject whose position is not loaded, whose live block
 * type no longer matches the frozen registry id, or whose shape is empty is skipped — there
 * is no full-cube fallback. {@code entity_block} subjects whose block entity already drew
 * its own model glow this frame (M7c C2) are skipped as well, so the wireframe does not
 * duplicate that subject. BER and baked-model overlap remains a source-order limitation
 * outside this presentation change.
 */
public final class BlockOutlinePass {

	/** Upstream {@code BlockOutlineRenderType.LINE_WIDTH}. */
	private static final float LINE_WIDTH = 3.75f;

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final int FALLBACK_COLOR = 0xFFFFFFFF;

	/** Target types that are rendered through the block-model outline path upstream. */
	private static final String TARGET_TYPE_BLOCK = "block";
	private static final String TARGET_TYPE_ENTITY_BLOCK = "entity_block";
	private static final String BLOCK_ENTITY_RENDERER_SOURCE_ID = "pingforit:block_entity_renderer";

	/** Frames with submissions but no interception before the routing warning fires (~2s at 60fps). */
	private static final int ROUTING_WARN_FRAMES = 120;

	private static RenderType renderType = null;
	private static boolean registered = false;
	private static boolean disabled = false;
	private static boolean failureLogged = false;
	private static boolean routingWarned = false;
	private static int framesWithoutRouting = 0;
	private static int lastSubmitted = -1;

	private BlockOutlinePass() {}

	/** Creates the outline pipeline once; the common client registers {@link #onWorldSubmit(WorldSubmitContext)} via SPI. */
	public static void register() {
		if (registered) {
			return;
		}

		registered = true;
		RenderType custom = PingOutlineRenderType.create();

		if (custom == null) {
			disabled = true;
			LOGGER.warn("custom outline pipeline unavailable; block outline disabled for this session");
			return;
		}

		renderType = custom;
		LOGGER.info("block outline pass pipeline ready; common client registers the world-submit callback");
	}

	/** Common world-submit entry point; the loader adapter forwards its native event here. */
	public static void onWorldSubmit(WorldSubmitContext context) {
		if (disabled) {
			return;
		}

		try {
			Minecraft game = Minecraft.getInstance();

			if (game == null || game.level == null || game.player == null || context == null) {
				return;
			}

			PoseStack poseStack = context.poseStack();

			if (poseStack == null) {
				return;
			}

			var runtime = CommonClient.INSTANCE.getPingRuntime();

			if (runtime == null) {
				return;
			}

			Camera camera = context.camera();

			if (camera == null || !camera.isInitialized()) {
				return;
			}

			String dimension = game.level.dimension().identifier().toString();
			Vec3 cameraPosition = camera.position();
			SubmitNodeCollector collector = context.collector();
			Entity cameraEntity = game.getCameraEntity();
			CollisionContext collisionContext = cameraEntity == null
				? CollisionContext.empty()
				: CollisionContext.of(cameraEntity);
			int submitted = 0;
			int wireframeSubmitted = 0;

			for (ClientMarker marker : runtime.store().visibleWinnersInDimension(dimension).values()) {
				if (!acceptsForBlockRendering(marker.targetTypeId())) {
					continue;
				}

				Optional<BlockOutlineSpec> spec = BlockPresentationLookup.specFor(marker);

				if (spec.isEmpty()) {
					continue;
				}

				BlockPresentation presentation;

				try {
					presentation = BlockPresentationResolverRegistry.INSTANCE.resolve(game.level, spec.get());
				} catch (RuntimeException | LinkageError | AssertionError failure) {
					continue;
				}

				if (presentation.isEmpty()) {
					continue;
				}

				int color = PING_TYPES.findById(marker.pingTypeId())
					.map(pingType -> 0xFF000000 | pingType.outlineColor())
					.orElse(FALLBACK_COLOR);

				// A presentation resolves one captured marker into one or more render subjects
				// (door halves, bed parts). Each subject is outlined on its own; the marker still
				// owns the colour, and the counters below are per subject, so a composite outline
				// can never look like a single captured half.
				Set<String> coveredSubjectIds = coveredSubjectIds(presentation);
				for (BlockRenderSubject subject : presentation.renderSubjects()) {
					if (coveredSubjectIds.contains(subject.subjectId())) {
						continue;
					}

					if (!BlockPresentationSubjectValidation.isLoadedAndCurrent(game.level, subject)) {
						continue;
					}

					BlockPos pos = subject.blockPos();
					BlockState state = game.level.getBlockState(pos);

					if (state.isAir()) {
						continue;
					}

					// M7c C1: ordinary blocks prefer the native model glow, and the attempt runs
					// before the VoxelShape is computed — upstream's model pass does not depend on
					// the shape, so a MODEL block with an empty shape still glows. Accepting the
					// subject suppresses the wireframe for it, so a block is never drawn twice.
					if (BlockModelGlowPass.trySubmit(
						game.level, poseStack, collector, subject.renderTargetTypeId(),
						state, pos, cameraPosition, color)) {
						submitted++;
						continue;
					}

					// M7c C2: entity_block glows through its real block-entity model, so the
					// wireframe must not be drawn on top of it.  The gate is this frame's own
					// evidence — the model submit actually consumed the colour for this position —
					// so "wireframe suppressed" and "glow drawn" cannot drift apart: a missing
					// renderer, a text-only block entity or an inactive submitModel hook all leave
					// the wireframe in place (upstream fallback).  The policy call mirrors the glow
					// side, so a policy-rejected block keeps its wireframe.  Not counted in
					// wireframeSubmitted: that feeds diagnoseRouting and means "drew an outline".
					// Depends on Fabric's COLLECT_SUBMITS firing at the RETURN of LevelRenderer#submitFeatures,
					// after submitBlockEntities (verified by bytecode): the glowing set is complete by then.
					// Were that order ever reversed, the failure direction is a double draw, not a missing marker.
					if (TARGET_TYPE_ENTITY_BLOCK.equals(subject.renderTargetTypeId())
						&& EntityBlockGlowState.isGlowing(pos)
						&& !EntityBlockGlowState.isDisabled()
						&& ClientConfig.HANDLER.getConfig().getBlockDisplayPolicy()
							.shouldUseNativeGlow(TARGET_TYPE_ENTITY_BLOCK, state)) {
						submitted++;
						continue;
					}

					VoxelShape shape = state.getShape(game.level, pos, collisionContext);

					if (shape.isEmpty()) {
						continue;
					}

					poseStack.pushPose();

					try {
						poseStack.translate(
							pos.getX() - cameraPosition.x,
							pos.getY() - cameraPosition.y,
							pos.getZ() - cameraPosition.z);
						collector.submitShapeOutline(poseStack, shape, renderType, color, LINE_WIDTH, true);
						submitted++;
						wireframeSubmitted++;
					} finally {
						poseStack.popPose();
					}
				}
			}

			logSubmitted(submitted);
			diagnoseRouting(wireframeSubmitted);
		} catch (RuntimeException | LinkageError failure) {
			disabled = true;

			if (!failureLogged) {
				failureLogged = true;
				LOGGER.warn("block outline: pass failed, disabled for this session ({})",
					failure.getClass().getSimpleName());
			}
		}
	}

	/**
	 * Subjects suppressed because an earlier subject finished the declared rendered
	 * source this frame. Only the BER source is currently consumed; other source ids
	 * remain future extension points and never suppress anything by themselves.
	 */
	private static Set<String> coveredSubjectIds(BlockPresentation presentation) {
		if (presentation.coverageRelations().isEmpty()) {
			return Set.of();
		}

		Map<String, BlockRenderSubject> subjectsById = new HashMap<>();
		for (BlockRenderSubject subject : presentation.renderSubjects()) {
			subjectsById.put(subject.subjectId(), subject);
		}

		Set<String> covered = new HashSet<>();
		for (BlockPresentationCoverageRelation relation : presentation.coverageRelations()) {
			if (!BLOCK_ENTITY_RENDERER_SOURCE_ID.equals(relation.requiredRenderedSourceId())) {
				continue;
			}

			BlockRenderSubject owner = subjectsById.get(relation.ownerSubjectId());
			if (owner != null
				&& EntityBlockGlowState.isGlowing(owner.blockPos())
				&& !EntityBlockGlowState.isDisabled()) {
				covered.add(relation.coveredSubjectId());
			}
		}
		return covered;
	}

	/** Mirrors upstream {@code BlockModelOutlineRoute.acceptsForBlockRendering}. */
	private static boolean acceptsForBlockRendering(String targetTypeId) {
		return TARGET_TYPE_BLOCK.equals(targetTypeId) || TARGET_TYPE_ENTITY_BLOCK.equals(targetTypeId);
	}

	/** DEBUG only, and only when the submitted count actually changes. */
	private static void logSubmitted(int submitted) {
		if (submitted == lastSubmitted) {
			return;
		}

		lastSubmitted = submitted;
		LOGGER.debug("block outline: submitted={}", submitted);
	}

	/**
	 * One-shot diagnostic for the {@code require = 0} routing mixin. Never disables the
	 * pass: an unrouted outline still draws and still ignores depth.
	 */
	private static void diagnoseRouting(int submitted) {
		if (routingWarned || submitted <= 0 || OutlineRoutingState.hasRouted()) {
			return;
		}

		framesWithoutRouting++;

		if (framesWithoutRouting >= ROUTING_WARN_FRAMES) {
			routingWarned = true;
			LOGGER.warn("block outline: routing mixin inactive; outline stays in the solid bucket "
				+ "(translucent blocks may tint it)");
		}
	}
}
