package nx.pingwheel.common.interaction.candidate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Finite native-box intersection: no segment-length-scaled containment probe. */
public final class ExactNativeShapeClip {
	private ExactNativeShapeClip() {}

	public static BlockHitResult clip(VoxelShape shape, Vec3 start, Vec3 end, BlockPos pos,
		CandidateWorkBudget budget) {
		if (shape.isEmpty() || start.equals(end)) return null;
		Best best = new Best();
		shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
			if (!budget.visitShapeBox()) throw new Incomplete();
			BoxHit hit = intersect(start, end, new AABB(minX + pos.getX(), minY + pos.getY(), minZ + pos.getZ(),
				maxX + pos.getX(), maxY + pos.getY(), maxZ + pos.getZ()));
			if (hit != null && (best.hit == null || hit.t < best.hit.t
				|| hit.t == best.hit.t && hit.inside && !best.hit.inside)) best.hit = hit;
		});
		if (best.hit == null) return null;
		if (best.hit.containment) {
			// Origin containment or a parallel boundary contact establishes no observed
			// surface face. Preserve point/target compatibility but do not authorize a
			// sided inventory from the backwards ray.
			return new UnobservedFaceBlockHitResult(start, best.hit.face, pos, best.hit.inside);
		}
		return new BlockHitResult(start.add(end.subtract(start).scale(best.hit.t)), best.hit.face, pos, best.hit.inside);
	}

	private static BoxHit intersect(Vec3 start, Vec3 end, AABB box) {
		double[] origins = {start.x, start.y, start.z};
		double[] deltas = {end.x - start.x, end.y - start.y, end.z - start.z};
		double[] minima = {box.minX, box.minY, box.minZ};
		double[] maxima = {box.maxX, box.maxY, box.maxZ};
		Direction[] negativeFaces = {Direction.WEST, Direction.DOWN, Direction.NORTH};
		Direction[] positiveFaces = {Direction.EAST, Direction.UP, Direction.SOUTH};
		double entry = Double.NEGATIVE_INFINITY;
		double exit = Double.POSITIVE_INFINITY;
		Direction face = null;
		boolean inside = true;
		for (int axis = 0; axis < 3; axis++) {
			double origin = origins[axis], delta = deltas[axis];
			if (!Double.isFinite(minima[axis]) || !Double.isFinite(maxima[axis])) throw new Incomplete();
			inside &= origin > minima[axis] && origin < maxima[axis];
			if (delta == 0) {
				if (origin < minima[axis] || origin > maxima[axis]) return null;
				continue;
			}
			double first = (minima[axis] - origin) / delta;
			double second = (maxima[axis] - origin) / delta;
			double near = Math.min(first, second);
			// Strict improvement retains native X, then Y, then Z corner-face ties.
			if (near > entry) {
				entry = near;
				face = delta > 0 ? negativeFaces[axis] : positiveFaces[axis];
			}
			exit = Math.min(exit, Math.max(first, second));
		}
		if (entry > exit || exit < 0) return null;
		double t = Math.max(0, entry);
		if (t >= 1 || t == 0 && !inside && exit <= 0) return null;
		// A negative entry means the origin already lies within every moving slab, so the
		// clamped t=0 point is containment or a parallel boundary contact, not a positive
		// crossing. Only a non-negative entry observes a surface face; the backwards ray
		// is an API placeholder, never a fabricated surface face.
		if (entry < 0) {
			return new BoxHit(0, Direction.getNearest(-deltas[0], -deltas[1], -deltas[2]), inside, true);
		}
		return new BoxHit(t, face, inside, false);
	}

	public static final class Incomplete extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
	private record BoxHit(double t, Direction face, boolean inside, boolean containment) {}
	private static final class Best { private BoxHit hit; }
}
