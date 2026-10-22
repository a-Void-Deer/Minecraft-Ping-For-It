package nx.pingwheel.common.interaction.candidate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A concrete position-only hit (optional terrain or precise origin containment). The inherited
 * direction is an API compatibility placeholder, never permission to read a sided inventory.
 */
public final class UnobservedFaceBlockHitResult extends BlockHitResult {
	public UnobservedFaceBlockHitResult(Vec3 point, Direction placeholder, BlockPos pos, boolean inside) {
		super(point, placeholder, pos, inside);
	}

	@Override public BlockHitResult withDirection(Direction direction) {
		return new UnobservedFaceBlockHitResult(getLocation(), direction, getBlockPos(), isInside());
	}

	@Override public BlockHitResult withPosition(BlockPos pos) {
		return new UnobservedFaceBlockHitResult(getLocation(), getDirection(), pos, isInside());
	}
}
