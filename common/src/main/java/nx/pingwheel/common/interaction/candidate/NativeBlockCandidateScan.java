package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Native selectable-shape cells along the finite segment discovered by Minecraft's DDA.
 * This is not render-model discovery, multipart owner resolution or a promise to
 * search arbitrary distant model extents. Shape strategy stays owned by ClipContext.
 */
public final class NativeBlockCandidateScan {
	private static final double VANILLA_TRAVERSAL_PADDING = -1.0E-7;

	private NativeBlockCandidateScan() {}

	/** True means the requested native scan completed; callers also check guarded-view/provider completion. */
	public static boolean scan(BlockGetter world, Predicate<BlockPos> loaded, ClipContext context,
		Consumer<BlockHitResult> hitConsumer) {
		return scan(world, loaded, context, context.getFrom(), context.getTo(), hitConsumer);
	}

	/** Traversal can be clipped to a provider plot; shape intersection still uses the original ray. */
	public static boolean scan(BlockGetter world, Predicate<BlockPos> loaded, ClipContext context,
		Vec3 traversalStart, Vec3 traversalEnd, Consumer<BlockHitResult> hitConsumer) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(loaded, "loaded");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(hitConsumer, "hitConsumer");
		if (!canTraverseAxis(traversalStart.x, traversalEnd.x)
			|| !canTraverseAxis(traversalStart.y, traversalEnd.y)
			|| !canTraverseAxis(traversalStart.z, traversalEnd.z)) return false;
		@Nullable BlockPos[] previousCell = {null};
		return BlockGetter.traverseBlocks(traversalStart, traversalEnd, context, (ray, mutablePos) -> {
			BlockPos pos = mutablePos.immutable();
			@Nullable BlockPos previous = previousCell[0];
			// A native step is at most one cell, including stationary repeats. Reject int wrap before reading that cell.
			if (previous != null && (Math.abs((long) pos.getX() - previous.getX()) > 1
				|| Math.abs((long) pos.getY() - previous.getY()) > 1
				|| Math.abs((long) pos.getZ() - previous.getZ()) > 1)) return Boolean.FALSE;
			previousCell[0] = pos;
			if (!loaded.test(pos)) return null;
			try {
				var block = world.getBlockState(pos);
				var fluid = world.getFluidState(pos);
				var blockShape = ray.getBlockShape(block, world, pos);
				var fluidShape = ray.getFluidShape(fluid, world, pos);
				@Nullable BlockHitResult blockHit = ExactNativeShapeClip.clip(blockShape, ray.getFrom(), ray.getTo(), pos);
				if (blockHit != null) {
					var interactionShape = block.getInteractionShape(world, pos);
					@Nullable BlockHitResult interactionHit = ExactNativeShapeClip.clip(interactionShape, ray.getFrom(), ray.getTo(), pos);
					if (interactionHit != null && distance(ray, interactionHit) < distance(ray, blockHit)) {
						blockHit = blockHit.withDirection(interactionHit.getDirection());
					}
				}
				@Nullable BlockHitResult fluidHit = ExactNativeShapeClip.clip(fluidShape, ray.getFrom(), ray.getTo(), pos);
				@Nullable BlockHitResult hit = distance(ray, blockHit) <= distance(ray, fluidHit) ? blockHit : fluidHit;
				if (hit != null) hitConsumer.accept(hit);
			} catch (ExactNativeShapeClip.Incomplete incomplete) {
				return Boolean.FALSE;
			}
			return null;
		}, ignored -> Boolean.TRUE);
	}

	private static boolean canTraverseAxis(double start, double end) {
		// Match the arithmetic used by BlockGetter.traverseBlocks before it floors to int coordinates.
		double paddedStart = Mth.lerp(VANILLA_TRAVERSAL_PADDING, start, end);
		double paddedEnd = Mth.lerp(VANILLA_TRAVERSAL_PADDING, end, start);
		if (!hasRepresentableCell(paddedStart) || !hasRepresentableCell(paddedEnd)) return false;
		double delta = paddedEnd - paddedStart;
		if (delta == 0.0) return true;
		// A negative step at the minimum integer plane would enter the cell below it.
		if (delta < 0.0 && paddedEnd == Integer.MIN_VALUE) return false;
		double fraction = Mth.frac(paddedStart);
		double firstCrossing = (Mth.sign(delta) / delta) * (delta > 0.0 ? 1.0 - fraction : fraction);
		// Positive infinity means this axis never crosses within the segment. NaN breaks vanilla's axis selection.
		return !Double.isNaN(firstCrossing);
	}

	private static boolean hasRepresentableCell(double coordinate) {
		return coordinate >= Integer.MIN_VALUE && coordinate < (double) Integer.MAX_VALUE + 1.0;
	}

	private static double distance(ClipContext ray, @Nullable BlockHitResult hit) {
		return hit == null ? Double.POSITIVE_INFINITY : ray.getFrom().distanceToSqr(hit.getLocation());
	}
}
