package nx.pingwheel.common.client.outline;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.mixin.DisplayBlockDisplayAccessor;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.warnException;

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
 *       {@code BlockEntityType#isValid(currentState)} holds. The renderer is
 *       invoked directly inside a try/catch (never through the dispatcher's
 *       crash-report path), with an identity {@link PoseStack} translated by
 *       {@code blockPos - cameraPos}.</li>
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
 * </ul>
 *
 * <p>Every attempt writes exclusively through an {@link OutlineOnlyBufferSource}
 * adapter (never the normal world buffer and never any shared vanilla
 * buffer): each attempt creates its own transient {@link ByteBufferBuilder}
 * and its own {@code MultiBufferSource.immediate(...)} source around it, so a
 * failure that left an incomplete vertex is discarded together with the
 * attempt-local buffer and can never corrupt a batch that vanilla (or any
 * other code path) will later flush. The adapter applies the winning ping
 * type's opaque marker color to every vertex itself. A render-type switch
 * inside one attempt can make the local immediate source synchronously draw
 * an earlier local batch before the explicit final flush; if a later
 * operation in that same attempt then fails, the VoxelShape fallback may
 * overlap that already-drawn partial model for one frame only — the outline
 * target clears next frame and the vanilla buffer remains untouched. All
 * local-buffer draws, flushes, and closes run synchronously on the render
 * thread: every vertex written is submitted or discarded before the attempt
 * returns, so attempt-local state can never outlive the attempt.
 *
 * <p>Attempts that throw or emit zero vertices are fail-soft: the key is
 * not recorded and the late VoxelShape fallback covers it. Only
 * {@link Exception}s, {@link LinkageError}s, and {@link AssertionError}s are
 * caught — application, linkage, and assertion failures are quarantined —
 * while resource and JVM errors ({@code OutOfMemoryError},
 * {@code StackOverflowError}, {@code InternalError}/{@code VirtualMachineError},
 * {@code ThreadDeath}) propagate. Logging is aggregated per frame (debug)
 * and at most once per
 * block registry id (warn); never per-frame per-block spam. Warn messages
 * report only the fixed route/stage context plus the bounded safe exception
 * report — never the throwable message, block registry id, position, or name,
 * consistent with the client logging policy. No world, team, scoreboard, or
 * global glowing state is ever
 * mutated; optional-mod block entity classes are never referenced directly.
 */
public final class VirtualBlockDisplayRenderer {
	private enum FailureRoute {
		BLOCK_ENTITY("block entity"),
		BLOCK_DISPLAY("block display");

		private final String label;

		FailureRoute(String label) {
			this.label = label;
		}
	}

	private enum FailureStage {
		RENDER("render"),
		FLUSH("flush");

		private final String label;

		FailureStage(String label) {
			this.label = label;
		}
	}

	public static final VirtualBlockDisplayRenderer INSTANCE = new VirtualBlockDisplayRenderer();

	private static final BlockDisplayWhitelist WHITELIST = BlockDisplayWhitelist.builtIn();

	/** Registry ids already warned about this session; bounded by distinct failing blocks. */
	private final Set<String> warnOnceRegistryIds = new HashSet<>();

	/** Per-frame aggregate failure counts by block registry id (debug logged only). */
	private final Map<String, Integer> frameFailures = new LinkedHashMap<>();

	private boolean displayCreationWarned;
	private ClientLevel cachedLevel;
	private Display.BlockDisplay cachedDisplay;

	private VirtualBlockDisplayRenderer() {}

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
		float partialTick,
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

			// The ordinary-block whitelist is evaluated lazily and only for
			// the generic `block` target type; `entity_block` never consults it.
			boolean whitelistMatches = false;

			if (BlockModelOutlineRoute.TARGET_TYPE_BLOCK.equals(spec.targetTypeId())) {
				whitelistMatches = WHITELIST.matches(blockState);
			}

			boolean success = switch (BlockModelOutlineRoute.route(spec.targetTypeId(), whitelistMatches)) {
				case ENTITY_BLOCK -> renderBlockEntity(
					level, pos, blockState, spec, blockEntityDispatcher,
					cameraPosition, partialTick);
				case BLOCK_DISPLAY -> renderBlockDisplay(
					level, pos, blockState, spec, entityDispatcher,
					cameraPosition, partialTick);
				case VOXEL -> false;
			};

			if (success) {
				frameState.addSuccess(blockKey);
			}
		}

		if (!frameFailures.isEmpty()) {
			int attempts = frameFailures.values().stream().mapToInt(Integer::intValue).sum();
			LOGGER.debug(
				"block outline model pass: {} attempts failed across {} block types; voxel fallback applied",
				attempts, frameFailures.size());
		}
	}

	/**
	 * Renders the actual {@link BlockEntity} at {@code pos} through its real
	 * renderer into an attempt-local transient buffer that is flushed exactly
	 * once on success. Returns whether at least one outline vertex was
	 * emitted.
	 */
	private boolean renderBlockEntity(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		BlockOutlineSpec spec,
		BlockEntityRenderDispatcher dispatcher,
		Vec3 cameraPosition,
		float partialTick
	) {
		BlockEntity blockEntity = level.getBlockEntity(pos);

		if (blockEntity == null || !blockEntity.getType().isValid(blockState)) {
			return false;
		}

		BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);

		if (renderer == null) {
			return false;
		}

		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(
			pos.getX() - cameraPosition.x,
			pos.getY() - cameraPosition.y,
			pos.getZ() - cameraPosition.z);

		FailureStage stage = FailureStage.RENDER;

		// The whole attempt lives on its own transient buffer: a failure at
		// any point — even after an incomplete vertex — is discarded together
		// with the builder instead of corrupting a shared vanilla buffer that
		// some other code path will later flush.
		try (ByteBufferBuilder builder = new ByteBufferBuilder(RenderType.TRANSIENT_BUFFER_SIZE)) {
			MultiBufferSource.BufferSource localSource = MultiBufferSource.immediate(builder);
			OutlineOnlyBufferSource buffer = new OutlineOnlyBufferSource(localSource, spec.argbColor());

			renderer.render(
				blockEntity, partialTick, poseStack, buffer,
				LevelRenderer.getLightColor(level, pos), OverlayTexture.NO_OVERLAY);

			if (buffer.vertexCount() == 0) {
				return false;
			}

			stage = FailureStage.FLUSH;
			localSource.endBatch();
			return true;
		} catch (Exception | LinkageError | AssertionError throwable) {
			recordFailure(spec, FailureRoute.BLOCK_ENTITY, stage, throwable);
			return false;
		} finally {
			poseStack.popPose();
		}
	}

	/**
	 * Renders the cached virtual {@code BlockDisplay} at the block MIN corner
	 * through the vanilla {@code BlockDisplayRenderer} into an attempt-local
	 * transient buffer that is flushed exactly once on success. Returns
	 * whether at least one outline vertex was emitted.
	 */
	private boolean renderBlockDisplay(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		BlockOutlineSpec spec,
		EntityRenderDispatcher dispatcher,
		Vec3 cameraPosition,
		float partialTick
	) {
		Display.BlockDisplay display = displayFor(level);

		if (display == null) {
			return false;
		}

		FailureStage stage = FailureStage.RENDER;

		// The whole attempt lives on its own transient buffer: a failure at
		// any point — even after an incomplete vertex — is discarded together
		// with the builder instead of corrupting a shared vanilla buffer that
		// some other code path will later flush.
		try (ByteBufferBuilder builder = new ByteBufferBuilder(RenderType.TRANSIENT_BUFFER_SIZE)) {
			MultiBufferSource.BufferSource localSource = MultiBufferSource.immediate(builder);
			OutlineOnlyBufferSource buffer = new OutlineOnlyBufferSource(localSource, spec.argbColor());

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
			Vec3 renderPosition = BlockDisplayPlacement.cameraRelative(
				pos, cameraPosition, blockState.getOffset(level, pos));

			// The model render seed follows the live state and actual block
			// position (BlockState#getSeed), exactly as the vanilla chunk
			// renderer derives it, so weighted/rotated models (turtle eggs,
			// sea pickles, ...) resolve the same variant the world shows.
			// The seed is scoped only around the vanilla render dispatch:
			// the ModelBlockRenderer mixin substitutes it for the vanilla
			// seed inside the scope, and resolves to the vanilla seed outside
			// it. The scope is restored before any failure reaches the
			// fail-soft catch below.
			long modelSeed = blockState.getSeed(pos);

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
				BlockModelRenderSeed.runWithSeed(modelSeed, () -> dispatcher.render(
					display,
					renderPosition.x,
					renderPosition.y,
					renderPosition.z,
					0.0F,
					partialTick,
					new PoseStack(),
					buffer,
					LevelRenderer.getLightColor(level, pos)));
			} finally {
				if (shouldRenderHitBoxes) {
					dispatcher.setRenderHitBoxes(true);
				}
			}

			if (buffer.vertexCount() == 0) {
				return false;
			}

			stage = FailureStage.FLUSH;
			localSource.endBatch();
			return true;
		} catch (Exception | LinkageError | AssertionError throwable) {
			// Setup and render share one fail-soft path. A partially mutated
			// virtual display must never be reused: drop the cache so the next
			// attempt rebuilds it from scratch.
			cachedDisplay = null;
			cachedLevel = null;
			recordFailure(spec, FailureRoute.BLOCK_DISPLAY, stage, throwable);
			return false;
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
		} catch (Throwable throwable) {
			if (!displayCreationWarned) {
				displayCreationWarned = true;
				LOGGER.warn("unable to create virtual block display; block model glow disabled");
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
	 * registry id (the id keys the warn-once bookkeeping only). The warn
 * message reports only the route, the failure stage ({@code render} or
 * {@code flush}), and a bounded, message-free, sanitized report of the
 * throwable's causal and suppressed stacks: exception class names,
 * relationships, and selected source-frame fields. It never includes
 * throwable messages or payload data, the block registry id, position, or
 * name — consistent with the client logging policy, so no per-frame warning
 * spam occurs.
	 */
	private void recordFailure(
		BlockOutlineSpec spec, FailureRoute route, FailureStage stage, Throwable throwable
	) {
		frameFailures.merge(spec.blockKey().blockRegistryId(), 1, Integer::sum);

		if (warnOnceRegistryIds.add(spec.blockKey().blockRegistryId())) {
			warnException(
				"block outline model pass failed; route=" + route.label
					+ "; stage=" + stage.label + "; voxel fallback applied",
				throwable);
		}
	}
}
