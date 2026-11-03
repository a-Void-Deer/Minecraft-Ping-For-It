package nx.pingwheel.common.client.outline;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;

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
 * in block-model rendering ({@code block}, {@code entity_block}) are outlined. A marker
 * whose block no longer matches the captured registry id, whose position is not
 * loaded, or whose shape is empty is skipped — there is no full-cube fallback.
 * {@code entity_block} markers whose block entity already drew its own model glow this
 * frame (M7c C2) are skipped as well, so one block is never outlined twice.
 */
public final class BlockOutlinePass {

	/** Upstream {@code BlockOutlineRenderType.LINE_WIDTH}. */
	private static final float LINE_WIDTH = 3.75f;

	private static final PingTypeCatalog PING_TYPES = PingTypeCatalog.builtIn();
	private static final int FALLBACK_COLOR = 0xFFFFFFFF;

	/** Target types that are rendered through the block-model outline path upstream. */
	private static final String TARGET_TYPE_BLOCK = "block";
	private static final String TARGET_TYPE_ENTITY_BLOCK = "entity_block";

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

	/** Registers the outline pass. Called once from the Fabric client initializer. */
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
		LevelRenderEvents.COLLECT_SUBMITS.register(BlockOutlinePass::onCollectSubmits);
		LOGGER.info("block outline pass registered (custom pipeline, afterTerrain routing)");
	}

	private static void onCollectSubmits(LevelRenderContext context) {
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

			Camera camera = game.gameRenderer.mainCamera();

			if (camera == null || !camera.isInitialized()) {
				return;
			}

			String dimension = game.level.dimension().identifier().toString();
			Vec3 cameraPosition = camera.position();
			SubmitNodeCollector collector = context.submitNodeCollector();
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

				if (!(marker.target() instanceof Target.BlockTarget block) || !block.dimensionId().equals(dimension)) {
					continue;
				}

				BlockPos pos = new BlockPos(block.x(), block.y(), block.z());

				if (!game.level.isLoaded(pos)) {
					continue;
				}

				BlockState state = game.level.getBlockState(pos);

				if (state.isAir()) {
					continue;
				}

				if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block.blockRegistryId())) {
					continue;
				}

				int color = PING_TYPES.findById(marker.pingTypeId())
					.map(pingType -> 0xFF000000 | pingType.outlineColor())
					.orElse(FALLBACK_COLOR);

				// M7c C1: ordinary blocks prefer the native model glow, and the attempt runs
				// before the VoxelShape is computed — upstream's model pass does not depend on
				// the shape, so a MODEL block with an empty shape still glows. Accepting the
				// marker suppresses the wireframe for it, so a block is never drawn twice.
				if (BlockModelGlowPass.trySubmit(
					game.level, poseStack, collector, marker, state, pos, cameraPosition, color)) {
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
				if (TARGET_TYPE_ENTITY_BLOCK.equals(marker.targetTypeId())
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
