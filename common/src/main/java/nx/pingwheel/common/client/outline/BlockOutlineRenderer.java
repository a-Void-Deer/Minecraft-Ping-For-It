package nx.pingwheel.common.client.outline;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Main-thread block outline render pass.
 *
 * <p>Draws the current native {@link VoxelShape} wireframe of every block in
 * the prepared {@link BlockOutlineState} snapshot into the given line buffer.
 * The pass is deliberately conservative and never mutates the level:
 * <ul>
 *   <li>specs whose dimension differs from the level's are skipped;</li>
 *   <li>unloaded blocks are skipped;</li>
 *   <li>the current block state's registry id is compared exactly to the
	 *       spec's frozen id, so a replaced block type drops the outline;</li>
	 *   <li>committed deferred source lines are always emitted into this late
	 *       line batch, including for keys recorded in {@code modelOutlineKeys};
	 *       those keys suppress only the ordinary VoxelShape fallback, so a
	 *       successful {@code entity_block} or whitelisted {@code block} glow
	 *       never doubles the shape outline while deferred modded geometry is
	 *       not lost;</li>
 *   <li>a same-type {@code BlockState} change re-reads the current shape
 *       every frame instead of caching anything;</li>
 *   <li>null or empty shapes are skipped; no full-cube fallback exists.</li>
 * </ul>
 *
 * <p>The caller (the {@code LevelRendererMixin} anchor) already has the
 * camera-relative model-view matrix applied, so this pass builds camera-
 * relative vertices with its own identity {@link PoseStack} and writes them
 * into the caller-provided custom block outline buffer ({@link
 * BlockOutlineRenderType#BLOCK_OUTLINE}). Nothing is flushed here: the
 * caller flushes exactly that custom batch after the pass returns, and the
 * vanilla {@code lines()} batch is never touched.
 */
public final class BlockOutlineRenderer {

	private BlockOutlineRenderer() {}

	/**
	 * Renders the ordered block outline specs of {@code state} into
	 * {@code lines} for the given camera. Runs on the client main thread
	 * only, once per world render frame, after {@link BlockOutlineState#prepare}.
	 *
	 * @param modelOutlineKeys the per-frame set of keys whose model-outline
	 *                         pass succeeded; those blocks are skipped here
	 */
	public static void render(
		ClientLevel level,
		Camera camera,
		VertexConsumer lines,
		BlockOutlineState state,
		Set<TargetKey.BlockKey> modelOutlineKeys
	) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(lines, "lines");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(modelOutlineKeys, "modelOutlineKeys");

		String dimensionId = level.dimension().location().toString();
		Entity cameraEntity = camera.getEntity();
		CollisionContext collisionContext =
			cameraEntity == null ? CollisionContext.empty() : CollisionContext.of(cameraEntity);
		Vec3 cameraPosition = camera.getPosition();
		PoseStack poseStack = new PoseStack();

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

			// Optional deferred sources publish complete camera-relative batches
			// before this late pass. They are intentionally emitted even when a
			// model-outline key suppresses the ordinary VoxelShape fallback.
			for (EntityBlockGeometryLine line :
				DeferredEntityBlockGeometryState.INSTANCE.linesFor(blockKey)) {
				VoxelShapeRenderUtil.renderLine(poseStack, lines, line, spec.argbColor());
			}

			// Native/model success suppresses only VoxelShape generation. Deferred
			// modded lines above have already been committed in source order and
			// must remain in the production line consumer for this target.
			if (modelOutlineKeys.contains(blockKey)) {
				continue;
			}

			VoxelShape shape = blockState.getShape(level, pos, collisionContext);

			if (shape == null || shape.isEmpty()) {
				continue;
			}

			poseStack.pushPose();
			poseStack.translate(
				pos.getX() - cameraPosition.x,
				pos.getY() - cameraPosition.y,
				pos.getZ() - cameraPosition.z);

			VoxelShapeRenderUtil.renderEdges(poseStack, lines, shape, 0.0, 0.0, 0.0, spec.argbColor());

			poseStack.popPose();
		}
	}
}
