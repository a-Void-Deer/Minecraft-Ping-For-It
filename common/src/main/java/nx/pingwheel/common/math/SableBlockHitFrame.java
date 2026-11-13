package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNullByDefault;

/** Detached evidence binding a native plot hit to the logical pose used by that clip. */
@NotNullByDefault
public record SableBlockHitFrame(UUID subLevelId, BlockPos localPos, Vec3 localHit, EntityParentFrame frame) {
	public SableBlockHitFrame {
		Objects.requireNonNull(subLevelId); Objects.requireNonNull(localPos);
		Objects.requireNonNull(localHit); Objects.requireNonNull(frame);
		localPos = localPos.immutable();
		frame.toWorld(localHit);
	}
	public boolean binds(BlockHitResult hit) {
		return hit.getType() == HitResult.Type.BLOCK && localPos.equals(hit.getBlockPos())
			&& localHit.equals(hit.getLocation());
	}
}
