package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Exact native-shape scanner for blocks owned by an external entity.
 *
 * <p>The caller supplies a stable local view and every candidate local block
 * position. This scanner deliberately performs no unit-block broad rejection:
 * a queried shape may protrude beyond its nominal position. It prunes only by
 * that shape's own native bounds, then intersects every native shape box with
 * the finite segment using exact slab math.</p>
 */
public final class NativeLocalShapeRaycaster {

	private NativeLocalShapeRaycaster() {}

	/**
	 * Scans all supplied local positions and returns the closest native block or
	 * fluid shape hit. A true result from {@code hiddenPredicate} skips that
	 * local position. Block shapes use the policy's OUTLINE/VISUAL mode and
	 * fluids are queried only when the policy permits ANY fluids.
	 *
	 * <p>Malformed non-finite segments return no hit. Game/view failures are
	 * intentionally not contained here so an owning registry callback can
	 * classify the entire attempted scan as {@code FAILED}.</p>
	 */
	public static Optional<LocalGeometryHit> trace(
		Vec3 localStart,
		Vec3 localEnd,
		RaycastPolicy policy,
		BlockGetter view,
		Iterable<BlockPos> localPositions,
		Predicate<BlockPos> hiddenPredicate,
		CollisionContext localCollisionContext
	) {
		Objects.requireNonNull(localStart, "localStart");
		Objects.requireNonNull(localEnd, "localEnd");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(localPositions, "localPositions");
		Objects.requireNonNull(hiddenPredicate, "hiddenPredicate");
		Objects.requireNonNull(localCollisionContext, "localCollisionContext");

		if (!isFinite(localStart) || !isFinite(localEnd) || localStart.distanceToSqr(localEnd) == 0.0) {
			return Optional.empty();
		}

		ClipContext context = new ClipContext(
			localStart,
			localEnd,
			policy.blockMode() == RaycastPolicy.BlockMode.OUTLINE
				? ClipContext.Block.OUTLINE
				: ClipContext.Block.VISUAL,
			policy.fluidMode() == RaycastPolicy.FluidMode.ANY
				? ClipContext.Fluid.ANY
				: ClipContext.Fluid.NONE,
			localCollisionContext);
		Candidate best = null;

		for (BlockPos suppliedPos : localPositions) {
			Objects.requireNonNull(suppliedPos, "localPositions contains null");
			BlockPos localPos = suppliedPos.immutable();

			if (hiddenPredicate.test(localPos)) {
				continue;
			}

			BlockState state = view.getBlockState(localPos);
			best = chooseBetter(best, traceNativeShape(
				context.getBlockShape(state, view, localPos),
				localStart,
				localEnd,
				localPos,
				LocalGeometryKind.BLOCK,
				state,
				blockRegistryId(state),
				Optional.empty()));

			if (policy.fluidMode() == RaycastPolicy.FluidMode.ANY) {
				FluidState fluidState = view.getFluidState(localPos);

				if (!fluidState.isEmpty()) {
					best = chooseBetter(best, traceNativeShape(
						context.getFluidShape(fluidState, view, localPos),
						localStart,
						localEnd,
						localPos,
						LocalGeometryKind.FLUID,
						state,
						blockRegistryId(state),
						fluidRegistryId(fluidState)));
				}
			}
		}

		return best == null ? Optional.empty() : Optional.of(best.hit());
	}

	/**
	 * Intersects one native local shape with the original finite segment.
	 *
	 * <p>This package-visible production seam is shared by the full scanner and
	 * focused math tests. It deliberately accepts a shape directly so tests can
	 * cover protruding and thin native boxes that ordinary vanilla blocks need
	 * not expose.</p>
	 */
	static Optional<LocalGeometryHit> traceNativeShape(
		VoxelShape shape,
		Vec3 start,
		Vec3 end,
		BlockPos localPos,
		LocalGeometryKind kind,
		BlockState state,
		String blockRegistryId,
		Optional<String> fluidRegistryId
	) {
		if (shape == null || shape.isEmpty() || blockRegistryId == null || fluidRegistryId == null
			|| !isFinite(start) || !isFinite(end) || start.distanceToSqr(end) == 0.0) {
			return Optional.empty();
		}

		if (kind == LocalGeometryKind.FLUID && fluidRegistryId.isEmpty()) {
			return Optional.empty();
		}

		AABB nativeBounds = shape.bounds().move(localPos.getX(), localPos.getY(), localPos.getZ());

		if (intersectSegment(start, end, nativeBounds).isEmpty()) {
			return Optional.empty();
		}

		BestBox bestBox = new BestBox();
		shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
			OptionalDouble t = intersectSegment(start, end, new AABB(
				minX + localPos.getX(), minY + localPos.getY(), minZ + localPos.getZ(),
				maxX + localPos.getX(), maxY + localPos.getY(), maxZ + localPos.getZ()));

			if (t.isPresent() && (bestBox.t.isEmpty() || t.getAsDouble() < bestBox.t.getAsDouble())) {
				bestBox.t = t;
			}
		});

		if (bestBox.t.isEmpty()) {
			return Optional.empty();
		}

		double t = bestBox.t.getAsDouble();
		Vec3 localPoint = pointAt(start, end, t);
		return Optional.of(new LocalGeometryHit(
			t, localPos, kind, state, blockRegistryId, fluidRegistryId, localPoint));
	}

	private static Candidate chooseBetter(Candidate current, Optional<LocalGeometryHit> proposed) {
		if (proposed.isEmpty()) {
			return current;
		}

		Candidate proposedCandidate = new Candidate(proposed.orElseThrow());

		if (current == null || compareHits(proposedCandidate.hit(), current.hit()) < 0) {
			return proposedCandidate;
		}

		return current;
	}

	/** Package-private production comparator seam for deterministic hit-tie tests. */
	static int compareHits(LocalGeometryHit first, LocalGeometryHit second) {
		int byT = Double.compare(first.t(), second.t());

		if (byT != 0) {
			return byT;
		}

		// Native block geometry wins an exact block/fluid tie before position
		// ordering. This prevents a fluid overlay from replacing a coincident
		// concrete local block surface.
		int byKind = Integer.compare(kindOrder(first.kind()), kindOrder(second.kind()));

		if (byKind != 0) {
			return byKind;
		}

		return comparePositions(first.localPos(), second.localPos());
	}

	private static int kindOrder(LocalGeometryKind kind) {
		return kind == LocalGeometryKind.BLOCK ? 0 : 1;
	}

	private static int comparePositions(BlockPos first, BlockPos second) {
		int byX = Integer.compare(first.getX(), second.getX());

		if (byX != 0) {
			return byX;
		}

		int byY = Integer.compare(first.getY(), second.getY());
		return byY != 0 ? byY : Integer.compare(first.getZ(), second.getZ());
	}

	/**
	 * Exact finite-segment intersection against one native shape box.
	 *
	 * <p>Unlike {@link VoxelShape#clip(Vec3, Vec3, BlockPos)}, this never shifts
	 * the start point or shortens the segment. A strict interior origin is a
	 * {@code t=0} hit; an origin on a face is a hit only when the remaining
	 * segment stays on or enters the box, so an outward-facing start contact is
	 * not revived as a false hit.</p>
	 */
	private static OptionalDouble intersectSegment(Vec3 start, Vec3 end, AABB box) {
		if (!isFinite(start) || !isFinite(end) || !isFinite(box)) {
			return OptionalDouble.empty();
		}

		double deltaX = end.x - start.x;
		double deltaY = end.y - start.y;
		double deltaZ = end.z - start.z;
		boolean strictlyInside = strictlyBetween(start.x, box.minX, box.maxX)
			&& strictlyBetween(start.y, box.minY, box.maxY)
			&& strictlyBetween(start.z, box.minZ, box.maxZ);

		double entry = Double.NEGATIVE_INFINITY;
		double exit = Double.POSITIVE_INFINITY;
		SlabIntersection x = intersectAxis(start.x, deltaX, box.minX, box.maxX);
		SlabIntersection y = intersectAxis(start.y, deltaY, box.minY, box.maxY);
		SlabIntersection z = intersectAxis(start.z, deltaZ, box.minZ, box.maxZ);

		if (!x.intersects() || !y.intersects() || !z.intersects()) {
			return OptionalDouble.empty();
		}

		entry = Math.max(entry, x.entry());
		exit = Math.min(exit, x.exit());
		entry = Math.max(entry, y.entry());
		exit = Math.min(exit, y.exit());
		entry = Math.max(entry, z.entry());
		exit = Math.min(exit, z.exit());

		if (entry > exit || exit < 0.0) {
			return OptionalDouble.empty();
		}

		double t = Math.max(entry, 0.0);

		if (!(t < 1.0)) {
			return OptionalDouble.empty();
		}

		// A non-interior t=0 contact must have a forward interval. This admits
		// an inward-facing start face while excluding an outward-only touch.
		if (t == 0.0 && !strictlyInside && !(exit > 0.0)) {
			return OptionalDouble.empty();
		}

		return OptionalDouble.of(t);
	}

	private static SlabIntersection intersectAxis(double start, double delta, double minimum, double maximum) {
		if (delta == 0.0) {
			return start < minimum || start > maximum
				? SlabIntersection.NONE
				: SlabIntersection.ALL;
		}

		double first = (minimum - start) / delta;
		double second = (maximum - start) / delta;
		return new SlabIntersection(true, Math.min(first, second), Math.max(first, second));
	}

	private static boolean strictlyBetween(double value, double minimum, double maximum) {
		return value > minimum && value < maximum;
	}

	private static Vec3 pointAt(Vec3 start, Vec3 end, double t) {
		return start.add(end.subtract(start).scale(t));
	}

	private static String blockRegistryId(BlockState state) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id == null ? null : id.toString();
	}

	private static Optional<String> fluidRegistryId(FluidState state) {
		ResourceLocation id = BuiltInRegistries.FLUID.getKey(state.getType());
		return id == null ? Optional.empty() : Optional.of(id.toString());
	}

	private static boolean isFinite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}

	private static boolean isFinite(AABB box) {
		return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
			&& Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
	}

	private record Candidate(LocalGeometryHit hit) {}

	private record SlabIntersection(boolean intersects, double entry, double exit) {
		private static final SlabIntersection NONE = new SlabIntersection(false, 0.0, 0.0);
		private static final SlabIntersection ALL = new SlabIntersection(true,
			Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
	}

	private static final class BestBox {
		private OptionalDouble t = OptionalDouble.empty();
	}
}
