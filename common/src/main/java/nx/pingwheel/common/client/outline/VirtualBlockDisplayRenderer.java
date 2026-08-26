package nx.pingwheel.common.client.outline;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.mixin.DisplayBlockDisplayAccessor;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.integration.sable.client.SableClientProvider;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * Main-thread model-outline pass: renders the vanilla model glow for winning
 * block markers, once per world render frame, immediately before the vanilla
 * entity-outline {@code endOutlineBatch()} call.
 *
 * <p>Two routes are supported, selected deterministically per spec by
 * {@link BlockModelOutlineRoute}:
 * <ul>
 *   <li>{@code entity_block} — the actual {@link BlockEntity} at the block
 *       position is rendered through its real {@link BlockEntityRenderer}
 *       (dynamic geometry: chest lids, sign text, furnace flames), after
 *       validating the renderer exists and
 *       {@code BlockEntityType#isValid(currentState)} holds. The live baked
 *       model is independently attempted as a second route when the current
 *       state has {@link RenderShape#MODEL}; either route may provide the
 *       native glow. The renderer is invoked directly inside a try/catch
 *       (never through the dispatcher's crash-report path), with an identity
 *       {@link PoseStack} translated by {@code blockPos - cameraPos}.</li>
 *   <li>{@code block} (whitelisted ordinary block) — a single cached, virtual
 *       {@code Display.BlockDisplay} that is <em>never added to or spawned
 *       into the level</em> is repositioned at the block MIN corner, its
 *       private {@code setBlockState} is invoked through the registered
 *       {@link DisplayBlockDisplayAccessor} invoker, {@code tick()} is called
 *       once so the render state exists, and the vanilla
 *       {@code BlockDisplayRenderer} runs through the prepared
 *       {@link EntityRenderDispatcher} with explicit camera-relative x/y/z and
 *       an identity {@link PoseStack} — no +0.5 offset, no extra translation.
 *       The camera-relative position adds the live {@code BlockState}'s
 *       vanilla model offset ({@code getOffset}, computed once per frame after
 *       the current-state validation), so short-grass/flower/bamboo-style
 *       shifted models glow exactly where they appear in the world.</li>
 *   <li>provider-owned Sable {@code block} targets use the same cached virtual
 *       display and baked-model outline route when the live local state is a
 *       compatible {@link RenderShape#MODEL}; provider-owned
 *       {@code entity_block} targets use the shared entity-block geometry
 *       runner, which attempts fresh local BER and baked-model geometry (and
 *       optional sources in {@code ALL}) before retaining the native
 *       VoxelShape fallback.</li>
 * </ul>
 *
	 * <p>Built-in BER/baked attempts write exclusively through an
	 * {@link OutlineOnlyBufferSource} backed by a fresh transient
	 * {@link ByteBufferBuilder}, so a recoverable failure discards the complete
	 * attempt-local buffer. Optional sources may use a different strategy: the
	 * Flywheel mask stages fixed per-texture batches first and only then commits
	 * them to vanilla's shared {@code OutlineBufferSource}. That shared source
	 * cannot roll back a recoverable failure after one or more vertices have been
	 * written; such a source is recorded as rendered for this frame so the
	 * VoxelShape fallback cannot overlay the partial mask, and it is retried on
	 * the next frame. A fatal JVM/resource error still propagates.
	 * The adapter applies the winning ping type's opaque marker color to every
	 * vertex itself. All local-buffer work and the shared commit run
	 * synchronously on the render thread.
 *
	 * <p>Built-in attempts that throw or emit zero vertices are fail-soft: the
	 * key is not recorded and the late VoxelShape fallback covers it. An
	 * optional shared-buffer attempt that has already written a vertex is
	 * recorded for this frame even when its recoverable commit exception leaves
	 * an incomplete mask; the fallback is suppressed for that frame and the
	 * attempt is retried normally on the next frame. Only
 * {@link Exception}s, {@link LinkageError}s, and {@link AssertionError}s are
 * caught — application, linkage, and assertion failures are quarantined —
 * while resource and JVM errors ({@code OutOfMemoryError},
 * {@code StackOverflowError}, {@code InternalError}/{@code VirtualMachineError},
 * {@code ThreadDeath}) propagate. Logging is aggregated per frame (debug)
 * and at most once per
 * block registry id (warn); never per-frame per-block spam. Diagnostics may
 * include complete target/component and exception details. No world, team,
 * scoreboard, or global glowing state is ever
 * mutated; optional-mod block entity classes are never referenced directly.
 */
public final class VirtualBlockDisplayRenderer {
	private enum FailureRoute {
		BLOCK_ENTITY("block entity"),
		ENTITY_BLOCK("entity-block"),
		BLOCK_DISPLAY("block display");

		private final String label;

		FailureRoute(String label) {
			this.label = label;
		}
	}

	private enum FailureStage {
		RENDER("render"),
		GEOMETRY_RUNNER("geometry-runner"),
		FLUSH("flush");

		private final String label;

		FailureStage(String label) {
			this.label = label;
		}
	}

	public static final VirtualBlockDisplayRenderer INSTANCE = new VirtualBlockDisplayRenderer();

	/** Registry ids already warned about this session; bounded by distinct failing blocks. */
	private final Set<String> warnOnceRegistryIds = new HashSet<>();

	/** Per-frame aggregate failure counts by block registry id (debug logged only). */
	private final Map<String, Integer> frameFailures = new LinkedHashMap<>();

	private boolean displayCreationWarned;
	private ClientLevel cachedLevel;
	private Display.BlockDisplay cachedDisplay;
	private final EntityBlockGeometryRunner entityBlockGeometryRunner;

	private VirtualBlockDisplayRenderer() {
		// These built-ins are owned by the runner and intentionally never enter
		// the optional/modded registry.
		this.entityBlockGeometryRunner = new EntityBlockGeometryRunner(
			EntityBlockGeometrySourceRegistry.INSTANCE,
			EntityBlockGeometrySource.of(
				EntityBlockGeometryRunner.BLOCK_ENTITY_RENDERER_SOURCE_ID,
				this::renderBlockEntity),
			EntityBlockGeometrySource.of(
				EntityBlockGeometryRunner.BAKED_MODEL_SOURCE_ID,
				this::renderBakedModel));
	}

	/**
	 * Runs the model-outline pass for the prepared {@code state} snapshot.
	 *
	 * <p>Specs whose dimension differs, whose chunk is unloaded, or whose
	 * current block registry id no longer matches the frozen identity are
	 * skipped silently (they keep their VoxelShape fallback). Successful keys
	 * are recorded into {@code frameState} so the late VoxelShape pass skips
	 * them. Main thread only.
	 */
	public void render(
		ClientLevel level,
		Camera camera,
		float builtInPartialTick,
		float flywheelPartialTick,
		BlockOutlineState state,
		BlockModelOutlineState frameState
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(frameState, "frameState");

		frameFailures.clear();

		String dimensionId = level.dimension().location().toString();
		Vec3 cameraPosition = camera.getPosition();
		Entity cameraEntity = camera.getEntity();
		CollisionContext collisionContext = cameraEntity == null
			? CollisionContext.empty() : CollisionContext.of(cameraEntity);
		Minecraft minecraft = Minecraft.getInstance();
		EntityRenderDispatcher entityDispatcher = minecraft.getEntityRenderDispatcher();
		BlockEntityRenderDispatcher blockEntityDispatcher = minecraft.getBlockEntityRenderDispatcher();

		for (Map.Entry<TargetKey.BlockKey, BlockOutlineSpec> entry : state.snapshot().entrySet()) {
			TargetKey.BlockKey blockKey = entry.getKey();
			BlockOutlineSpec spec = entry.getValue();

			if (!blockKey.dimensionId().equals(dimensionId)) {
				continue;
			}

			BlockPos pos = new BlockPos(blockKey.x(), blockKey.y(), blockKey.z());

			if (!level.hasChunkAt(pos)) {
				continue;
			}

			BlockState blockState = level.getBlockState(pos);

			if (!blockKey.blockRegistryId()
				.equals(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString())) {
				continue;
			}

			// The immutable policy is compiled by ClientConfig validation/load/set
			// paths. This frame only evaluates the current state against it; no
			// configured string is reparsed here. Both block target types consult
			// the whitelist and the blacklist, with target-safety gates in policy.
			boolean nativeGlowMatches = ClientConfig.HANDLER.getConfig()
				.getBlockDisplayPolicy()
				.shouldUseNativeGlow(spec.targetTypeId(), blockState);

			boolean success = switch (BlockModelOutlineRoute.route(spec.targetTypeId(), nativeGlowMatches)) {
				case ENTITY_BLOCK -> renderEntityBlock(
					level, pos, blockState, spec, blockEntityDispatcher, entityDispatcher,
					cameraPosition, builtInPartialTick, flywheelPartialTick, blockKey);
				case BLOCK_DISPLAY -> renderBlockDisplay(
					level, pos, blockState, entityDispatcher,
					cameraPosition, builtInPartialTick,
					spec.argbColor(), blockKey.blockRegistryId(), blockKey)
					== EntityBlockGeometryOutcome.RENDERED;
				case VOXEL -> false;
			};

			if (success) {
				frameState.addSuccess(blockKey);
			}
		}

		for (Map.Entry<TargetKey.ExternalBlockKey, ExternalBlockOutlineSpec> entry
			: state.externalSnapshot().entrySet()) {
			TargetKey.ExternalBlockKey blockKey = entry.getKey();
			ExternalBlockOutlineSpec spec = entry.getValue();

			if (!blockKey.dimensionId().equals(dimensionId)) {
				continue;
			}

			if (renderExternalBlockModel(
				level, cameraPosition, collisionContext, builtInPartialTick, flywheelPartialTick,
				entityDispatcher, blockEntityDispatcher, blockKey, spec) == EntityBlockGeometryOutcome.RENDERED) {
				frameState.addExternalSuccess(blockKey);
			}
		}

		if (!frameFailures.isEmpty()) {
			int attempts = frameFailures.values().stream().mapToInt(Integer::intValue).sum();
			LOGGER.debug(
				"block outline model pass: %d attempts failed across %d block types; voxel fallback applied"
					.formatted(attempts, frameFailures.size()));
		}
	}

	/**
	 * Attempts both native-glow routes for an {@code entity_block}. A block
	 * entity may have dynamic renderer geometry and a static baked model, so a
	 * successful BER attempt must not suppress the model attempt. The model
	 * route is restricted to {@link RenderShape#MODEL}; animated/no-model
	 * states must not produce a missing-model display.
	 */
	private boolean renderEntityBlock(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		BlockOutlineSpec spec,
		BlockEntityRenderDispatcher blockEntityDispatcher,
		EntityRenderDispatcher entityDispatcher,
		Vec3 cameraPosition,
		float builtInPartialTick,
		float flywheelPartialTick,
		TargetKey.BlockKey targetKey
	) {
		// Read the live local mode for every entity-block render attempt. It is
		// intentionally absent from the ordinary `block` route above and is not
		// cached by this renderer or by the per-frame outline state.
		EntityBlockRenderMode mode = ClientConfig.HANDLER.getConfig().getEntityBlockRenderMode();
		return entityBlockGeometryRunner.run(
			mode,
			() -> new EntityBlockGeometryContext(
				level,
				pos,
				blockState,
				level.getBlockEntity(pos),
				spec.argbColor(),
				cameraPosition,
				builtInPartialTick,
				FlywheelRenderClock.maskPartialTick(builtInPartialTick, flywheelPartialTick),
				LevelRenderer.getLightColor(level, pos),
				entityDispatcher,
				blockEntityDispatcher,
				Minecraft.getInstance().levelRenderer,
				targetKey,
				BlockModelOutlineState.INSTANCE.frameId(),
				null));
	}

	/**
	 * Attempts the configured geometry route for a provider-owned block outline.
	 * The provider resolves a fresh local state and pose for this frame; no
	 * BlockEntity instance is retained or looked up in the parent level.
	 * Ordinary {@code block} targets use the existing cached virtual-display
	 * route, while {@code entity_block} targets use the shared runner for direct
	 * BER, baked-model, and (when enabled) optional geometry sources.
	 */
	private EntityBlockGeometryOutcome renderExternalBlockModel(
		ClientLevel level,
		Vec3 cameraPosition,
		CollisionContext collisionContext,
		float builtInPartialTick,
		float flywheelPartialTick,
		EntityRenderDispatcher entityDispatcher,
		BlockEntityRenderDispatcher blockEntityDispatcher,
		TargetKey.ExternalBlockKey targetKey,
		ExternalBlockOutlineSpec spec
	) {
		FailureRoute failureRoute = FailureRoute.BLOCK_DISPLAY;
		FailureStage failureStage = FailureStage.RENDER;
		try {
			var presentationResult = SableClientProvider.resolvePresentation(
				level, spec.target(), builtInPartialTick, collisionContext);

			if (presentationResult.isEmpty()) {
				return EntityBlockGeometryOutcome.EMPTY;
			}

			var presentation = presentationResult.orElseThrow();
			BlockState blockState = presentation.blockState();
			var actualRegistryKey = BuiltInRegistries.BLOCK.getKey(blockState.getBlock());

			// The provider performs the same check at its boundary. Keep the
			// renderer-side comparison explicit so a changed/invalid presentation
			// can never turn into a model success.
			if (actualRegistryKey == null
				|| !targetKey.expectedBlockRegistryId().equals(actualRegistryKey.toString())) {
				return EntityBlockGeometryOutcome.EMPTY;
			}

			boolean nativeGlowMatches = ClientConfig.HANDLER.getConfig()
				.getBlockDisplayPolicy()
				.shouldUseNativeGlow(spec.targetTypeId(), blockState);
			BlockModelOutlineRoute route = BlockModelOutlineRoute.routeExternal(
				spec.targetTypeId(), nativeGlowMatches,
				blockState.getRenderShape() == RenderShape.MODEL);

			BlockPos localBlockPos = presentation.localBlockPos();

			if (route == BlockModelOutlineRoute.ENTITY_BLOCK) {
				failureRoute = FailureRoute.ENTITY_BLOCK;
				failureStage = FailureStage.GEOMETRY_RUNNER;
				EntityBlockRenderMode mode =
					ClientConfig.HANDLER.getConfig().getEntityBlockRenderMode();
				if (presentation.localLevel().getBlockEntity(localBlockPos) == null) {
					// A provider state can outlive or temporarily lose its live
					// BlockEntity. Do not let the static baked source turn that
					// incomplete entity_block back into a model success.
					return EntityBlockGeometryOutcome.EMPTY;
				}
				EntityBlockGeometryTransform transform =
					new EntityBlockGeometryTransform(presentation.renderPose());

				boolean rendered = entityBlockGeometryRunner.run(
					mode,
					() -> new EntityBlockGeometryContext(
						presentation.localLevel(),
						localBlockPos,
						blockState,
						// The runner asks for a new context for every source. Resolve the
						// local BlockEntity at that point rather than retaining an instance
						// identity across frames or source attempts.
						presentation.localLevel().getBlockEntity(localBlockPos),
						spec.argbColor(),
						cameraPosition,
						builtInPartialTick,
						flywheelPartialTick,
						LevelRenderer.getLightColor(presentation.localLevel(), localBlockPos),
						entityDispatcher,
						blockEntityDispatcher,
						Minecraft.getInstance().levelRenderer,
						targetKey,
						BlockModelOutlineState.INSTANCE.frameId(),
						transform));
				return rendered
					? EntityBlockGeometryOutcome.RENDERED : EntityBlockGeometryOutcome.EMPTY;
			}

			if (route != BlockModelOutlineRoute.BLOCK_DISPLAY) {
				return EntityBlockGeometryOutcome.EMPTY;
			}

			// Ordinary provider-owned blocks retain the existing virtual display
			// route. The entity_block runner's baked source owns its own display
			// attempt, so entity_block never reaches this branch.
			return renderBlockDisplay(
				level,
				localBlockPos,
				blockState,
				entityDispatcher,
				builtInPartialTick,
				() -> LevelRenderer.getLightColor(presentation.localLevel(), localBlockPos),
				spec.argbColor(),
				actualRegistryKey.toString(),
				targetKey,
				() -> {
					Vec3 modelOffset = blockState.getOffset(presentation.localLevel(), localBlockPos);
					Vec3 worldBlockOrigin = presentation.worldBlockOrigin(modelOffset);
					PoseStack poseStack = new PoseStack();
					ExternalBlockOutlineTransform.apply(
						poseStack,
						worldBlockOrigin,
						presentation.orientationScale(),
						cameraPosition);
					return new BlockDisplayRenderArguments(
						poseStack, 0.0D, 0.0D, 0.0D, blockState.getSeed(localBlockPos));
				});
		} catch (Exception | LinkageError | AssertionError failure) {
			recordFailure(
				targetKey.expectedBlockRegistryId(),
				null,
				null,
				targetKey,
				Display.BlockDisplay.class,
				failureRoute,
				failureStage,
				failure);
			return EntityBlockGeometryOutcome.FAILED;
		}
	}

	/**
	 * Renders the actual {@link BlockEntity} at {@code pos} through its real
	 * renderer into an attempt-local transient buffer that is flushed exactly
	 * once on success. Returns the honest source outcome so the runner can
	 * distinguish empty geometry from a failed attempt.
	 */
	private EntityBlockGeometryOutcome renderBlockEntity(EntityBlockGeometryContext context) {
		FailureStage stage = FailureStage.RENDER;
		PoseStack poseStack = null;
		boolean posePushed = false;

		// The whole attempt lives on its own transient buffer: a failure at
		// any point — even after an incomplete vertex — is discarded together
		// with the builder instead of corrupting a shared vanilla buffer that
		// some other code path will later flush.
		try {
			BlockEntity blockEntity = context.blockEntity();
			BlockPos pos = context.blockPos();
			BlockState blockState = context.blockState();
			BlockEntityRenderDispatcher dispatcher = context.blockEntityRenderDispatcher();

			if (blockEntity == null || pos == null || blockState == null || dispatcher == null
				|| !blockEntity.getType().isValid(blockState)) {
				return EntityBlockGeometryOutcome.EMPTY;
			}

			BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);

			if (renderer == null) {
				return EntityBlockGeometryOutcome.EMPTY;
			}

			if (context.transform() == null) {
				poseStack = new PoseStack();
				poseStack.pushPose();
				posePushed = true;
				poseStack.translate(
					pos.getX() - context.cameraPosition().x,
					pos.getY() - context.cameraPosition().y,
					pos.getZ() - context.cameraPosition().z);
			} else {
				// External geometry owns the local-to-world transform. The BER
				// route deliberately receives no block-state model offset.
				poseStack = context.transform().createPoseStack(pos, context.cameraPosition(), null);
			}

			try (ByteBufferBuilder builder = new ByteBufferBuilder(RenderType.TRANSIENT_BUFFER_SIZE)) {
				MultiBufferSource.BufferSource localSource = MultiBufferSource.immediate(builder);
				OutlineOnlyBufferSource buffer = new OutlineOnlyBufferSource(localSource, context.argbColor());

				renderer.render(
					blockEntity, context.partialTick(), poseStack, buffer,
					context.packedLight(), OverlayTexture.NO_OVERLAY);

				EntityBlockGeometryOutcome outcome =
					EntityBlockGeometryOutcome.fromEmittedVertices(buffer.vertexCount());
				if (outcome != EntityBlockGeometryOutcome.RENDERED) {
					return outcome;
				}

				stage = FailureStage.FLUSH;
				localSource.endBatch();
				return outcome;
			}
		} catch (Exception | LinkageError | AssertionError throwable) {
			recordFailure(
				failureRegistryId(context), context.blockPos(), context.blockState(),
				context.targetKey(), context.blockEntity(), FailureRoute.BLOCK_ENTITY, stage, throwable);
			return EntityBlockGeometryOutcome.FAILED;
		} finally {
			if (posePushed) {
				poseStack.popPose();
			}
		}
	}

	private EntityBlockGeometryOutcome renderBakedModel(EntityBlockGeometryContext context) {
		if (context.blockState() == null
			|| context.blockState().getRenderShape() != RenderShape.MODEL) {
			return EntityBlockGeometryOutcome.EMPTY;
		}

		return renderBlockDisplay(context);
	}

	/**
	 * Renders the cached virtual {@code BlockDisplay} at the block MIN corner
	 * through the vanilla {@code BlockDisplayRenderer} into an attempt-local
	 * transient buffer that is flushed exactly once on success. Returns
	 * the honest source outcome so empty/failed attempts retain the VoxelShape
	 * fallback.
	 */
	private EntityBlockGeometryOutcome renderBlockDisplay(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		EntityRenderDispatcher dispatcher,
		Vec3 cameraPosition,
		float partialTick,
		int argbColor,
		String failureRegistryId,
		TargetKey.BlockKey targetKey
	) {
		return renderBlockDisplay(
			level,
			pos,
			blockState,
			dispatcher,
			partialTick,
			() -> LevelRenderer.getLightColor(level, pos),
			argbColor,
			failureRegistryId,
			targetKey,
			() -> {
				Vec3 renderPosition = BlockDisplayPlacement.cameraRelative(
					pos, cameraPosition, blockState.getOffset(level, pos));
				return new BlockDisplayRenderArguments(
					new PoseStack(),
					renderPosition.x,
					renderPosition.y,
					renderPosition.z,
					blockState.getSeed(pos));
			});
	}

	private EntityBlockGeometryOutcome renderBlockDisplay(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		EntityRenderDispatcher dispatcher,
		float partialTick,
		IntSupplier packedLightSupplier,
		int argbColor,
		String failureRegistryId,
		TargetKey targetKey,
		Supplier<BlockDisplayRenderArguments> argumentsSupplier
	) {
		if (dispatcher == null) {
			return EntityBlockGeometryOutcome.EMPTY;
		}

		Objects.requireNonNull(packedLightSupplier, "packedLightSupplier");
		Objects.requireNonNull(argumentsSupplier, "argumentsSupplier");

		Display.BlockDisplay display = displayFor(level);

		if (display == null) {
			return EntityBlockGeometryOutcome.EMPTY;
		}

		FailureStage stage = FailureStage.RENDER;

		// The whole attempt lives on its own transient buffer: a failure at
		// any point — even after an incomplete vertex — is discarded together
		// with the builder instead of corrupting a shared vanilla buffer that
		// some other code path will later flush.
		try (ByteBufferBuilder builder = new ByteBufferBuilder(RenderType.TRANSIENT_BUFFER_SIZE)) {
			MultiBufferSource.BufferSource localSource = MultiBufferSource.immediate(builder);
			OutlineOnlyBufferSource buffer = new OutlineOnlyBufferSource(localSource, argbColor);

			display.setPos(pos.getX(), pos.getY(), pos.getZ());
			((DisplayBlockDisplayAccessor) display).pingForItSetBlockState(blockState);

			// One tick so the display's render state and block render state
			// exist; the vanilla BlockDisplayRenderer returns early without
			// them.
			display.tick();

			// The vanilla BlockDisplayRenderer places the model from the
			// translated block min corner/origin passed here, so the glow is
			// placed at the block MIN corner minus the camera, plus the live
			// state's vanilla model offset (BlockState#getOffset: short grass,
			// flowers, bamboo, ...) exactly as the block appears in the world.
			// The offset is computed exactly once per frame, after the chunk/
			// registry/whitelist validation above; `setPos` stays at the
			// integer MIN corner and no PoseStack translation applies the
			// offset.

			// The model render seed follows the live state and actual block
			// position (BlockState#getSeed), exactly as the vanilla chunk
			// renderer derives it, so weighted/rotated models (turtle eggs,
			// sea pickles, ...) resolve the same variant the world shows.
			// The seed is scoped only around the vanilla render dispatch:
			// the ModelBlockRenderer mixin substitutes it for the vanilla
			// seed inside the scope, and resolves to the vanilla seed outside
			// it. The scope is restored before any failure reaches the
			// fail-soft catch below.
			BlockDisplayRenderArguments arguments = argumentsSupplier.get();

			// This display is synthetic and must not participate in F3+B. The
			// dispatcher would otherwise append RenderType.lines() hitbox
			// vertices to this model-outline buffer. Save the exact vanilla flag,
			// disable it only for this dispatch, and restore it in finally so the
			// scoped guard cannot mutate persistent dispatcher state after an
			// ordinary return or any caught throwable.
			boolean shouldRenderHitBoxes = dispatcher.shouldRenderHitBoxes();
			if (shouldRenderHitBoxes) {
				dispatcher.setRenderHitBoxes(false);
			}

			try {
				// For external BlockDisplays, x/y/z MUST remain zero: the
				// precision-safe PoseStack already carries camera-relative
				// translation, orientation, and scale; vanilla applies coordinates
				// verbatim, so nonzero values would double-transform the display.
				BlockModelRenderSeed.runWithSeed(arguments.modelSeed(), () -> dispatcher.render(
					display,
					arguments.x(),
					arguments.y(),
					arguments.z(),
					0.0F,
					partialTick,
					arguments.poseStack(),
					buffer,
					packedLightSupplier.getAsInt()));
			} finally {
				if (shouldRenderHitBoxes) {
					dispatcher.setRenderHitBoxes(true);
				}
			}

			EntityBlockGeometryOutcome outcome =
				EntityBlockGeometryOutcome.fromEmittedVertices(buffer.vertexCount());
			if (outcome != EntityBlockGeometryOutcome.RENDERED) {
				return outcome;
			}

			stage = FailureStage.FLUSH;
			localSource.endBatch();
			return outcome;
		} catch (Exception | LinkageError | AssertionError throwable) {
			// Setup and render share one fail-soft path. A partially mutated
			// virtual display must never be reused: drop the cache so the next
			// attempt rebuilds it from scratch.
			cachedDisplay = null;
			cachedLevel = null;
			recordFailure(
				failureRegistryId, pos, blockState, targetKey, Display.BlockDisplay.class,
				FailureRoute.BLOCK_DISPLAY, stage, throwable);
			return EntityBlockGeometryOutcome.FAILED;
		}
	}

	private EntityBlockGeometryOutcome renderBlockDisplay(EntityBlockGeometryContext context) {
		if (context.level() == null
			|| context.blockPos() == null
			|| context.blockState() == null
			|| context.entityRenderDispatcher() == null) {
			return EntityBlockGeometryOutcome.EMPTY;
		}

		return renderBlockDisplay(
			context.level(),
			context.blockPos(),
			context.blockState(),
			context.entityRenderDispatcher(),
			context.partialTick(),
			() -> context.packedLight(),
			context.argbColor(),
			failureRegistryId(context),
			context.targetKey(),
			() -> {
				if (context.transform() != null) {
					return new BlockDisplayRenderArguments(
						context.transform().createPoseStack(
							context.blockPos(),
							context.cameraPosition(),
							context.blockState().getOffset(context.level(), context.blockPos())),
						0.0D,
						0.0D,
						0.0D,
						context.blockState().getSeed(context.blockPos()));
				}

				Vec3 renderPosition = BlockDisplayPlacement.cameraRelative(
					context.blockPos(),
					context.cameraPosition(),
					context.blockState().getOffset(context.level(), context.blockPos()));
				return new BlockDisplayRenderArguments(
					new PoseStack(),
					renderPosition.x,
					renderPosition.y,
					renderPosition.z,
					context.blockState().getSeed(context.blockPos()));
			});
	}

	private record BlockDisplayRenderArguments(
		PoseStack poseStack, double x, double y, double z, long modelSeed
	) {
		private BlockDisplayRenderArguments {
			Objects.requireNonNull(poseStack, "poseStack");
		}
	}

	/**
	 * Returns the single cached virtual block display for {@code level},
	 * recreating it when the level identity changed or after
	 * {@link #clear()}. The display is never added to the level.
	 */
	private Display.BlockDisplay displayFor(ClientLevel level) {
		if (cachedDisplay != null && cachedLevel == level) {
			return cachedDisplay;
		}

		try {
			cachedDisplay = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
			cachedLevel = level;
			return cachedDisplay;
		} catch (Exception | LinkageError | AssertionError throwable) {
			if (!displayCreationWarned) {
				displayCreationWarned = true;
				LOGGER.warn(
					() -> "unable to create virtual block display; stage=display-creation; class="
						+ Display.BlockDisplay.class.getName(),
					throwable);
			}

			cachedDisplay = null;
			cachedLevel = null;
			return null;
		}
	}

	/**
	 * Drops the cached virtual display; called when leaving a server so a
	 * stale level identity can never be reused.
	 */
	public void clear() {
		cachedDisplay = null;
		cachedLevel = null;
	}

	/**
	 * Records a fail-soft model-outline attempt failure: one aggregate count
	 * for the frame's debug log, and a single session-level warn per block
	 * registry id (the id keys the warn-once bookkeeping only). The lazily
	 * preformatted message includes the complete target, registry, position,
	 * source/block class, failure stage ({@code render}, {@code geometry-runner},
	 * or {@code flush}), and
	 * exception class; the original throwable is attached so its complete
	 * causal/suppressed stack is retained. The session-level warning still
	 * prevents per-frame warning spam.
	 */
	private void recordFailure(
		String registryId,
		BlockPos position,
		BlockState blockState,
		TargetKey targetKey,
		Object sourceTarget,
		FailureRoute route,
		FailureStage stage,
		Throwable throwable
	) {
		frameFailures.merge(registryId, 1, Integer::sum);

		if (warnOnceRegistryIds.add(registryId)) {
			LOGGER.warn(
				() -> failureMessage(
					registryId, position, blockState, targetKey, sourceTarget,
					route, stage, throwable),
				throwable);
		}
	}

	private static String failureMessage(
		String registryId,
		BlockPos position,
		BlockState blockState,
		TargetKey targetKey,
		Object sourceTarget,
		FailureRoute route,
		FailureStage stage,
		Throwable throwable
	) {
		String blockClass = blockState == null || blockState.getBlock() == null
			? "<null>" : blockState.getBlock().getClass().getName();
		String sourceClass = sourceTarget == null ? "<null>"
			: sourceTarget instanceof Class<?> type ? type.getName() : sourceTarget.getClass().getName();
		String target = targetDescription(targetKey);
		String positionText = position == null
			? "<null>"
			: position.getX() + "," + position.getY() + "," + position.getZ();
		String exceptionClass = throwable == null ? "<null>" : throwable.getClass().getName();
		return "block outline model pass failed; route=" + route.label
			+ "; stage=" + stage.label
			+ "; target=" + target
			+ "; registry=" + registryId
			+ "; position=" + positionText
			+ "; class=" + sourceClass
			+ "; blockClass=" + blockClass
			+ "; exceptionClass=" + exceptionClass
			+ "; voxelFallback=applied";
	}

	private static String targetDescription(TargetKey targetKey) {
		if (targetKey == null) {
			return "<unknown>";
		}

		return switch (targetKey) {
			case TargetKey.BlockKey block ->
				"dimension=" + block.dimensionId()
					+ "; position=" + block.x() + "," + block.y() + "," + block.z()
					+ "; blockRegistryId=" + block.blockRegistryId();
			case TargetKey.ExternalBlockKey external ->
				"dimension=" + external.dimensionId()
					+ "; provider=" + external.providerId()
					+ "; stableTargetId=" + external.stableTargetId()
					+ "; expectedBlockRegistryId=" + external.expectedBlockRegistryId();
			case TargetKey.EntityKey entity ->
				"dimension=" + entity.dimensionId() + "; entity=" + entity.locator();
			case TargetKey.LocationKey location ->
				"dimension=" + location.dimensionId()
					+ "; position=" + location.x() + "," + location.y() + "," + location.z();
		};
	}

	private static String failureRegistryId(EntityBlockGeometryContext context) {
		if (context.blockState() == null) {
			return "<unknown>";
		}

		var registryKey = BuiltInRegistries.BLOCK.getKey(context.blockState().getBlock());
		return registryKey == null ? "<unknown>" : registryKey.toString();
	}
}
