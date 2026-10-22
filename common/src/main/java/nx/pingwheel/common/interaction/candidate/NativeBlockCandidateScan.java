package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded native selectable-shape cells discovered by Minecraft's DDA.
 * This is not render-model discovery, multipart owner resolution or a promise to
 * search arbitrary distant model extents. Shape strategy stays owned by ClipContext.
 */
public final class NativeBlockCandidateScan {
	private NativeBlockCandidateScan() {}

	/** True means the requested native scan completed; callers also check guarded-view/provider completion. */
	public static boolean scan(BlockGetter world, Predicate<BlockPos> loaded, ClipContext context,
		CandidateWorkBudget budget, Consumer<BlockHitResult> hitConsumer) {
		return scan(world, loaded, context, context.getFrom(), context.getTo(), budget, hitConsumer);
	}

	/** Traversal can be clipped to a provider plot; shape intersection still uses the original ray. */
	public static boolean scan(BlockGetter world, Predicate<BlockPos> loaded, ClipContext context,
		Vec3 traversalStart, Vec3 traversalEnd, CandidateWorkBudget budget, Consumer<BlockHitResult> hitConsumer) {
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(loaded, "loaded");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(budget, "budget");
		Objects.requireNonNull(hitConsumer, "hitConsumer");
		boolean[] complete = {true};
		BlockGetter.traverseBlocks(traversalStart, traversalEnd, context, (ray, mutablePos) -> {
			if (!budget.visitBlock()) {
				complete[0] = false;
				return Boolean.FALSE;
			}
			BlockPos pos = mutablePos.immutable();
			if (!loaded.test(pos)) return null;
			try {
				var block = world.getBlockState(pos);
				var fluid = world.getFluidState(pos);
				var blockShape = ray.getBlockShape(block, world, pos);
				var fluidShape = ray.getFluidShape(fluid, world, pos);
				var blockHit = ExactNativeShapeClip.clip(blockShape, ray.getFrom(), ray.getTo(), pos, budget);
				if (blockHit != null) {
					var interactionShape = block.getInteractionShape(world, pos);
					var interactionHit = ExactNativeShapeClip.clip(interactionShape, ray.getFrom(), ray.getTo(), pos, budget);
					if (interactionHit != null && distance(ray, interactionHit) < distance(ray, blockHit)) {
						blockHit = blockHit.withDirection(interactionHit.getDirection());
					}
				}
				var fluidHit = ExactNativeShapeClip.clip(fluidShape, ray.getFrom(), ray.getTo(), pos, budget);
				BlockHitResult hit = distance(ray, blockHit) <= distance(ray, fluidHit) ? blockHit : fluidHit;
				if (hit != null) hitConsumer.accept(hit);
			} catch (ExactNativeShapeClip.Incomplete exhausted) {
				complete[0] = false;
				return Boolean.FALSE;
			}
			return null;
		}, ignored -> Boolean.TRUE);
		return complete[0];
	}

	private static double distance(ClipContext ray, BlockHitResult hit) {
		return hit == null ? Double.POSITIVE_INFINITY : ray.getFrom().distanceToSqr(hit.getLocation());
	}
}
