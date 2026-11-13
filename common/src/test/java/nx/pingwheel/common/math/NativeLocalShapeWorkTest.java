package nx.pingwheel.common.math;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import nx.pingwheel.common.interaction.candidate.CandidateWorkBudget;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class NativeLocalShapeWorkTest {
	private static final Vec3 START = new Vec3(-1, .5, .5), END = new Vec3(5, .5, .5);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void shapeNeighborAndBlockEntityReadsAreChargedBeforeTheirSourceIsConsumed() {
		AtomicInteger reads = new AtomicInteger(); BlockGetter view = view(reads);
		var policy = RaycastPolicy.from(true, false, false);
		ClipContext context = new ClipContext(START, END, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()) {
			@Override public VoxelShape getBlockShape(BlockState state, BlockGetter local, BlockPos pos) {
				local.getBlockState(pos.above()); local.getBlockEntity(pos); return Shapes.block();
			}
		};
		var work = work(2, 10);
		assertThrows(EntityRaycastWork.Exhausted.class, () -> NativeLocalShapeRaycaster.traceWithContext(
			START, END, policy, view, List.of(BlockPos.ZERO), ignored -> false, context, work));
		assertEquals(2, reads.get(), "exhausted block-entity read must not reach source");
		assertFalse(work.complete());
		reads.set(0);
		assertTrue(NativeLocalShapeRaycaster.traceWithContext(START, END, policy, view,
			List.of(BlockPos.ZERO), ignored -> false, context, work(3, 1)).isPresent());
		assertEquals(3, reads.get());
	}

	@Test void everyBoxCostsWorkAndLaterReadFailureDiscardsAnEarlierProvisionalHit() {
		var policy = RaycastPolicy.from(true, false, false);
		VoxelShape separated = Shapes.or(Shapes.box(0, 0, 0, .25, 1, 1), Shapes.box(.75, 0, 0, 1, 1, 1));
		ClipContext context = new ClipContext(START, END, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()) {
			@Override public VoxelShape getBlockShape(BlockState state, BlockGetter local, BlockPos pos) { return separated; }
		};
		AtomicInteger reads = new AtomicInteger();
		var boxWork = work(1, 1);
		assertThrows(EntityRaycastWork.Exhausted.class, () -> NativeLocalShapeRaycaster.traceWithContext(START, END,
			policy, view(reads), List.of(BlockPos.ZERO), ignored -> false, context, boxWork));
		assertFalse(boxWork.complete(), "first native box hit cannot survive exhaustion on a later box");
		var readWork = work(1, 10);
		assertThrows(EntityRaycastWork.Exhausted.class, () -> NativeLocalShapeRaycaster.traceWithContext(START, END,
			policy, view(reads), List.of(BlockPos.ZERO, new BlockPos(2, 0, 0)), ignored -> false, context, readWork));
		assertFalse(readWork.complete());
	}

	@Test void boundedScannerPreservesVisualOutlineAndFluidPolicy() {
		BlockPos glass = new BlockPos(0, 0, 0), water = new BlockPos(2, 0, 0), stone = new BlockPos(4, 0, 0);
		BlockGetter local = new BlockGetter() {
			@Override public BlockState getBlockState(BlockPos pos) {
				return (pos.equals(glass) ? Blocks.GLASS : pos.equals(water) ? Blocks.WATER : pos.equals(stone) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
			}
			@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
			@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
			@Override public int getHeight() { return 256; }
			@Override public int getMinBuildHeight() { return 0; }
		};
		for (boolean transparent : List.of(false, true)) for (boolean fluids : List.of(false, true)) {
			var policy = RaycastPolicy.from(transparent, false, fluids);
			Optional<LocalGeometryHit> unlimited = NativeLocalShapeRaycaster.trace(START, END, policy, local,
				List.of(stone, water, glass), ignored -> false, CollisionContext.empty());
			var bounded = NativeLocalShapeRaycaster.trace(START, END, policy, local, List.of(stone, water, glass),
				ignored -> false, CollisionContext.empty(), work(100, 100));
			assertEquals(unlimited, bounded);
		}
	}
	private static EntityRaycastWork work(int reads, int boxes) {
		return EntityRaycastWork.bounded(new CandidateWorkBudget(new CandidateWorkLimits(reads, 0, 0, boxes)));
	}
	private static BlockGetter view(AtomicInteger reads) {
		return new BlockGetter() {
			@Override public BlockState getBlockState(BlockPos pos) { reads.incrementAndGet(); return Blocks.STONE.defaultBlockState(); }
			@Override public FluidState getFluidState(BlockPos pos) { reads.incrementAndGet(); return Blocks.WATER.defaultBlockState().getFluidState(); }
			@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { reads.incrementAndGet(); return null; }
			@Override public int getHeight() { return 256; }
			@Override public int getMinBuildHeight() { return 0; }
		};
	}
}
