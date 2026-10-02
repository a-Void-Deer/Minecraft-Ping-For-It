package nx.pingwheel.common.integration.sable.client;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import nx.pingwheel.common.integration.sable.server.SableExternalBlockLocator;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.candidate.*;
import nx.pingwheel.common.math.RaycastPolicy;
import nx.pingwheel.common.resolve.BlockEntityClassification;

/** Provider-specific synchronous transform/scan core. No provider objects survive capture. */
final class SableSupplementalRaycaster {
	private SableSupplementalRaycaster() {}

	/** Raw random-access loaded list, never a filtered iterator whose hasNext scans hidden entries. */
	interface Source {
		int size();
		SubLevel at(int index, CandidateWorkBudget budget);
	}

	/** Press-only positively resolved plot view. Bounds are local block/shape bounds, not render bounds. */
	record SubLevel(UUID id, Pose3dc logicalPose, AABB localBounds, BlockGetter world,
		Predicate<BlockPos> contains, Predicate<BlockPos> loaded) {
		SubLevel {
			Objects.requireNonNull(id); Objects.requireNonNull(logicalPose); Objects.requireNonNull(localBounds);
			Objects.requireNonNull(world); Objects.requireNonNull(contains); Objects.requireNonNull(loaded);
		}
	}

	static boolean scan(Source source, String dimensionId, Vec3 start, Vec3 end, RaycastPolicy policy,
		CollisionContext context, Vec3 cameraFeet, CandidateWorkBudget budget, CandidateCollector collector) {
		int size = source.size();
		if (size < 0) return false;
		if (start.equals(end)) return true;
		for (int index = 0; index < size; index++) {
			if (!budget.callProvider()) return false;
			SubLevel subLevel = source.at(index, budget);
			if (subLevel == null) continue; // positively removed/empty, not an unresolved provider
			if (!scanSubLevel(subLevel, dimensionId, start, end, policy, context, cameraFeet, budget, collector)) return false;
		}
		return source.size() == size;
	}

	private static boolean scanSubLevel(SubLevel subLevel, String dimensionId, Vec3 start, Vec3 end,
		RaycastPolicy policy, CollisionContext context, Vec3 cameraFeet, CandidateWorkBudget budget,
		CandidateCollector collector) {
		CandidateCollector provisional = new CandidateCollector();
		Pose3d pose = new Pose3d(subLevel.logicalPose());
		if (!finite(pose.position()) || !finite(pose.rotationPoint()) || !finite(pose.scale())
			|| pose.scale().x == 0 || pose.scale().y == 0 || pose.scale().z == 0
			|| !Double.isFinite(pose.orientation().lengthSquared()) || pose.orientation().lengthSquared() == 0) return false;
		Vec3 localStart = pose.transformPositionInverse(start);
		Vec3 localEnd = pose.transformPositionInverse(end);
		if (!finite(localStart) || !finite(localEnd)) return false;
		double[] interval = interval(localStart, localEnd, subLevel.localBounds().inflate(1));
		if (interval == null) return true;
		Vec3 delta = localEnd.subtract(localStart);
		Vec3 traversalStart = localStart.add(delta.scale(interval[0]));
		Vec3 traversalEnd = localStart.add(delta.scale(interval[1]));
		Vec3 localFeet = pose.transformPositionInverse(cameraFeet);
		if (!finite(localFeet)) return false;
		CollisionContext localContext = localContext(context, localFeet);
		var loadedView = new LoadedCandidateBlockView(subLevel.world(), subLevel.loaded(), budget);
		BlockGetter view = new BlockGetter() {
			@Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
				return subLevel.contains().test(pos) ? loadedView.getBlockState(pos)
					: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			}
			@Override public FluidState getFluidState(BlockPos pos) {
				return subLevel.contains().test(pos) ? loadedView.getFluidState(pos)
					: net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
			}
			@Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
				return subLevel.contains().test(pos) ? loadedView.getBlockEntity(pos) : null;
			}
			@Override public int getHeight() { return loadedView.getHeight(); }
			@Override public int getMinBuildHeight() { return loadedView.getMinBuildHeight(); }
		};
		ClipContext ray = new ClipContext(localStart, localEnd,
			policy.blockMode() == RaycastPolicy.BlockMode.OUTLINE ? ClipContext.Block.OUTLINE : ClipContext.Block.VISUAL,
			policy.fluidMode() == RaycastPolicy.FluidMode.ANY ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, localContext);
		boolean[] unloaded = {false};
		boolean complete = NativeBlockCandidateScan.scan(view, pos -> {
			if (!subLevel.contains().test(pos)) return false;
			boolean available = subLevel.loaded().test(pos);
			unloaded[0] |= !available;
			return available;
		},
			ray, traversalStart, traversalEnd, budget, hit -> {
			BlockPos pos = hit.getBlockPos();
			var state = view.getBlockState(pos);
			if (state.isAir()) return;
			var registryId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
			if (registryId == null) throw new ExactNativeShapeClip.Incomplete();
			Vec3 worldHit = pose.transformPosition(hit.getLocation());
			double worldDistance = start.distanceTo(worldHit);
			double t = worldHit.subtract(start).dot(end.subtract(start)) / start.distanceToSqr(end);
			if (!finite(worldHit) || !Double.isFinite(worldDistance) || !Double.isFinite(t)
				|| t < 0 || t >= 1 || worldDistance > start.distanceTo(end)) return;
			Vec3 projected = start.add(end.subtract(start).scale(t));
			if (projected.distanceToSqr(worldHit) > 1.0E-10) throw new ExactNativeShapeClip.Incomplete();
			String blockId = registryId.toString();
			var snapshot = TargetSnapshotFactory.externalBlockCandidate(dimensionId, SableClientProvider.PROVIDER_ID,
				blockId, new SableExternalBlockLocator(subLevel.id(), pos).encode(), BlockEntityClassification.hasBlockEntity(state))
				.withCandidateHit(new CandidateHit(new WorldVector(worldHit.x, worldHit.y, worldHit.z),
					SableCaptureEquivalence.fromResolved(dimensionId, subLevel.id(), pos.getX(), pos.getY(), pos.getZ(), blockId)));
			provisional.add(new CandidateEvidence(snapshot, worldDistance));
		});
		if (!complete || !loadedView.complete() || unloaded[0]) return false;
		provisional.evidence().forEach(collector::add);
		return true;
	}

	private static double[] interval(Vec3 start, Vec3 end, AABB box) {
		double entry = 0, exit = 1;
		double[] origins = {start.x, start.y, start.z}, deltas = {end.x - start.x, end.y - start.y, end.z - start.z};
		double[] minima = {box.minX, box.minY, box.minZ}, maxima = {box.maxX, box.maxY, box.maxZ};
		for (int axis = 0; axis < 3; axis++) {
			if (!Double.isFinite(minima[axis]) || !Double.isFinite(maxima[axis])) throw new ExactNativeShapeClip.Incomplete();
			if (deltas[axis] == 0) {
				if (origins[axis] < minima[axis] || origins[axis] > maxima[axis]) return null;
			} else {
				double first = (minima[axis] - origins[axis]) / deltas[axis], second = (maxima[axis] - origins[axis]) / deltas[axis];
				entry = Math.max(entry, Math.min(first, second)); exit = Math.min(exit, Math.max(first, second));
			}
		}
		return entry >= exit ? null : new double[] {entry, exit};
	}

	private static CollisionContext localContext(CollisionContext delegate, Vec3 feet) {
		return new CollisionContext() {
			@Override public boolean isDescending() { return delegate.isDescending(); }
			@Override public boolean isAbove(VoxelShape shape, BlockPos pos, boolean fallback) {
				return feet.y > pos.getY() + shape.max(Direction.Axis.Y) - 1.0E-5;
			}
			@Override public boolean isHoldingItem(Item item) { return delegate.isHoldingItem(item); }
			@Override public boolean canStandOnFluid(FluidState fluid, FluidState flowing) { return delegate.canStandOnFluid(fluid, flowing); }
		};
	}
	private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
	private static boolean finite(org.joml.Vector3dc point) { return Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z()); }
}
