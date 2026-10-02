package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.math.RaycastSelection;

/** Press-only mapping port for optional block providers. Must use the supplied endpoints and budget. */
@FunctionalInterface
public interface CandidateBlockCapture {
	Result capture(Level level, BlockHitResult hit, Vec3 start, Vec3 end, CandidateWorkBudget budget);

	/** Independent provider acquisition: may find a surface behind a blocker or after native MISS. */
	default boolean collectSupplemental(Level level, Vec3 start, Vec3 end, RaycastPolicy policy,
		CollisionContext collisionContext, Vec3 cameraFeet, CandidateWorkBudget budget, CandidateCollector collector) {
		return true;
	}

	static CandidateBlockCapture nativeBlocks() {
		return new CandidateBlockCapture() {
			@Override public Result capture(Level level, BlockHitResult hit, Vec3 start, Vec3 end, CandidateWorkBudget budget) {
				return new Result(Optional.of(MinecraftTargetSnapshotFactory.fromCandidateSelection(level,
					RaycastSelection.withoutLocalGeometry(hit))), true);
			}
		};
	}

	record Result(Optional<TargetSnapshot> snapshot, boolean complete) {
		public Result { Objects.requireNonNull(snapshot, "snapshot"); }
		public static Result incomplete() { return new Result(Optional.empty(), false); }
	}
}
