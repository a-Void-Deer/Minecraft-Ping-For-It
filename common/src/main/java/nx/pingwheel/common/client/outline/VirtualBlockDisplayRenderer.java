package nx.pingwheel.common.client.outline;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.LevelRenderer;
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

/**
 * Main-thread model-outline pass: renders the vanilla model glow for winning
 * block markers into the entity-outline buffer, once per world render frame,
 * before the vanilla {@code OutlineBufferSource.endOutlineBatch()} call.
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
 *       an identity {@link PoseStack} — no +0.5 offset, no extra translation.</li>
 * </ul>
 *
 * <p>Every attempt writes exclusively through an {@link OutlineOnlyBufferSource}
 * adapter (never the normal world buffer), after
 * {@code OutlineBufferSource.setColor(winner RGB, 255)} — so the silhouette
 * color is the winning ping type's and no duplicate normal block rendering
 * happens. Attempts that throw or emit zero vertices are fail-soft: the key is
 * not recorded and the late VoxelShape fallback covers it. Logging is
 * aggregated per frame (debug) and at most once per block registry id (warn);
 * never per-frame per-block spam. Warn messages report only the route and the
 * throwable summary/class — never a block registry id, position, or name,
 * consistent with the client logging policy. No world, team, scoreboard, or
 * global glowing state is ever mutated; optional-mod block entity classes are
 * never referenced directly.
 */
public final class VirtualBlockDisplayRenderer {

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
		OutlineBufferSource outlineSource,
		float partialTick,
		BlockOutlineState state,
		BlockModelOutlineState frameState
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(outlineSource, "outlineSource");
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
					cameraPosition, outlineSource, partialTick);
				case BLOCK_DISPLAY -> renderBlockDisplay(
					level, pos, blockState, spec, entityDispatcher,
					cameraPosition, outlineSource, partialTick);
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
	 * renderer. Returns whether at least one outline vertex was emitted.
	 */
	private boolean renderBlockEntity(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		BlockOutlineSpec spec,
		BlockEntityRenderDispatcher dispatcher,
		Vec3 cameraPosition,
		OutlineBufferSource outlineSource,
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

		OutlineOnlyBufferSource buffer = new OutlineOnlyBufferSource(outlineSource, false);
		outlineSource.setColor(
			(spec.argbColor() >> 16) & 0xFF, (spec.argbColor() >> 8) & 0xFF, spec.argbColor() & 0xFF, 255);

		PoseStack poseStack = new PoseStack();
		poseStack.pushPose();
		poseStack.translate(
			pos.getX() - cameraPosition.x,
			pos.getY() - cameraPosition.y,
			pos.getZ() - cameraPosition.z);

		try {
			renderer.render(
				blockEntity, partialTick, poseStack, buffer,
				LevelRenderer.getLightColor(level, pos), OverlayTexture.NO_OVERLAY);
		} catch (Throwable throwable) {
			recordFailure(spec, "block entity", throwable);
			return false;
		} finally {
			poseStack.popPose();
		}

		return buffer.vertexCount() > 0;
	}

	/**
	 * Renders the cached virtual {@code BlockDisplay} at the block MIN corner
	 * through the vanilla {@code BlockDisplayRenderer}. Returns whether at
	 * least one outline vertex was emitted.
	 */
	private boolean renderBlockDisplay(
		ClientLevel level,
		BlockPos pos,
		BlockState blockState,
		BlockOutlineSpec spec,
		EntityRenderDispatcher dispatcher,
		Vec3 cameraPosition,
		OutlineBufferSource outlineSource,
		float partialTick
	) {
		Display.BlockDisplay display = displayFor(level);

		if (display == null) {
			return false;
		}

		OutlineOnlyBufferSource buffer = new OutlineOnlyBufferSource(outlineSource, true);
		outlineSource.setColor(
			(spec.argbColor() >> 16) & 0xFF, (spec.argbColor() >> 8) & 0xFF, spec.argbColor() & 0xFF, 255);

		try {
			display.setPos(pos.getX(), pos.getY(), pos.getZ());
			((DisplayBlockDisplayAccessor) display).pingForItSetBlockState(blockState);

			// One tick so the display's render state and block render state
			// exist; the vanilla BlockDisplayRenderer returns early without
			// them.
			display.tick();
			dispatcher.render(
				display,
				pos.getX() - cameraPosition.x,
				pos.getY() - cameraPosition.y,
				pos.getZ() - cameraPosition.z,
				0.0F,
				partialTick,
				new PoseStack(),
				buffer,
				LevelRenderer.getLightColor(level, pos));
		} catch (Throwable throwable) {
			// Setup and render share one fail-soft path. A partially mutated
			// virtual display must never be reused: drop the cache so the next
			// attempt rebuilds it from scratch.
			cachedDisplay = null;
			cachedLevel = null;
			recordFailure(spec, "block display", throwable);
			return false;
		}

		return buffer.vertexCount() > 0;
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
	 * message reports only the route and the throwable class name — never
	 * the throwable message, stack, block registry id, position, or name —
	 * consistent with the client logging policy, so no per-frame warning
	 * spam occurs.
	 */
	private void recordFailure(BlockOutlineSpec spec, String route, Throwable throwable) {
		frameFailures.merge(spec.blockKey().blockRegistryId(), 1, Integer::sum);

		if (warnOnceRegistryIds.add(spec.blockKey().blockRegistryId())) {
			LOGGER.warn(
				"block outline model pass failed ({} route); voxel fallback applied: {}",
				route, throwable.getClass().getName());
		}
	}
}
