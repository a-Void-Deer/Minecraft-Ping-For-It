package nx.pingwheel.common.client.outline;

import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Pure camera-relative placement math for the virtual {@code BlockDisplay}
 * model-glow route.
 *
 * <p>The vanilla {@code BlockDisplayRenderer} centers the block model on the
 * entity position passed to {@code EntityRenderDispatcher.render(...)}, so
 * the glow must be placed at the block MIN corner minus the camera position,
 * plus the live {@code BlockState}'s vanilla model offset
 * ({@code BlockState#getOffset(BlockGetter, BlockPos)}) — the offset that
 * shifts short grass, flowers, bamboo and similar models away from the
 * integer corner in the world. The helper is pure so the exact application
 * can be regression-tested headless; it is used exactly once per block per
 * frame by {@link VirtualBlockDisplayRenderer}.
 */
final class BlockDisplayPlacement {

	private BlockDisplayPlacement() {}

	/**
	 * Camera-relative render coordinates for a block display glow, computed
	 * per axis as {@code (pos - cameraPosition) + modelOffset}.
	 *
	 * @param pos            the block MIN corner (integer coordinates)
	 * @param cameraPosition the camera position
	 * @param modelOffset    the block state's vanilla model offset
	 *                       ({@code BlockState#getOffset(BlockGetter, BlockPos)})
	 * @return the exact x/y/z to pass to {@code EntityRenderDispatcher.render}
	 */
	static Vec3 cameraRelative(BlockPos pos, Vec3 cameraPosition, Vec3 modelOffset) {
		Objects.requireNonNull(pos, "pos");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Objects.requireNonNull(modelOffset, "modelOffset");

		return new Vec3(
			pos.getX() - cameraPosition.x + modelOffset.x,
			pos.getY() - cameraPosition.y + modelOffset.y,
			pos.getZ() - cameraPosition.z + modelOffset.z);
	}
}
