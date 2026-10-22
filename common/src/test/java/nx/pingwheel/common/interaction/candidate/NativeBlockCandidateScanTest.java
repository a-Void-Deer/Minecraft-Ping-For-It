package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeBlockCandidateScanTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test
	void nativeDdaContinuesBehindBlockersAndRetainsEachActualSurfaceFace() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
		world.blocks.put(new BlockPos(5, 0, 0), Blocks.STONE.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(scan(world, new Vec3(0.5, 0.5, 0.5), new Vec3(8, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, 1000, hits));
		assertEquals(List.of(new BlockPos(2, 0, 0), new BlockPos(5, 0, 0)), hits.stream().map(BlockHitResult::getBlockPos).toList());
		assertEquals(List.of(2.0, 5.0), hits.stream().map(hit -> hit.getLocation().x).toList());
		assertTrue(hits.stream().allMatch(hit -> hit.getDirection() == Direction.WEST));
		hits.clear();
		assertTrue(scan(world, new Vec3(8, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, 1000, hits));
		assertEquals(List.of(6.0, 3.0), hits.stream().map(hit -> hit.getLocation().x).toList());
		assertTrue(hits.stream().allMatch(hit -> hit.getDirection() == Direction.EAST));
	}

	@Test
	void finiteSegmentDoesNotSeeBlockBeyondEndAndZeroBudgetReadsNothing() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(5, 0, 0), Blocks.STONE.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(scan(world, new Vec3(0.5, 0.5, 0.5), new Vec3(4, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, 1000, hits));
		assertTrue(hits.isEmpty());
		world.reads.set(0);
		assertFalse(scan(world, new Vec3(0.5, 0.5, 0.5), new Vec3(8, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, 0, hits));
		assertEquals(0, world.reads.get());
	}

	@Test
	void exhaustionMarksEvenHitBearingPrefixIncomplete() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		world.blocks.put(new BlockPos(5, 0, 0), Blocks.CHEST.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		assertFalse(scan(world, new Vec3(0.5, 0.5, 0.5), new Vec3(8, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, 3, hits));
		assertEquals(List.of(new BlockPos(1, 0, 0)), hits.stream().map(BlockHitResult::getBlockPos).toList());
	}

	@Test
	void unloadedCellsDoNotReadTheirStateOrInventTargets() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(4, 0, 0), Blocks.STONE.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		var context = context(new Vec3(0.5, 0.5, 0.5), new Vec3(8, 0.5, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE);
		assertTrue(NativeBlockCandidateScan.scan(world, pos -> pos.getX() != 4, context,
			new CandidateWorkBudget(new CandidateWorkLimits(1000, 0, 0)), hits::add));
		assertFalse(world.readPositions.contains(new BlockPos(4, 0, 0)));
		assertTrue(hits.isEmpty());
	}

	@Test
	void outlineVisualAndFluidModesUseRealNativeShapeStrategies() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(2, 0, 0), Blocks.TORCH.defaultBlockState());
		List<BlockHitResult> outline = new ArrayList<>();
		List<BlockHitResult> visual = new ArrayList<>();
		scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, 1000, outline);
		scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, 1000, visual);
		assertEquals(1, outline.size());
		assertTrue(visual.isEmpty());
		world.blocks.clear();
		world.fluids.put(new BlockPos(3, 0, 0), Fluids.WATER.defaultFluidState());
		List<BlockHitResult> none = new ArrayList<>();
		List<BlockHitResult> any = new ArrayList<>();
		scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, 1000, none);
		scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, 1000, any);
		assertTrue(none.isEmpty());
		assertEquals(new BlockPos(3, 0, 0), any.getFirst().getBlockPos());
		assertEquals(Direction.WEST, any.getFirst().getDirection());
	}

	private static boolean scan(MemoryBlocks world, Vec3 start, Vec3 end, ClipContext.Block block,
		ClipContext.Fluid fluid, int cells, List<BlockHitResult> hits) {
		return NativeBlockCandidateScan.scan(world, ignored -> true, context(start, end, block, fluid),
			new CandidateWorkBudget(new CandidateWorkLimits(cells, 0, 0)), hits::add);
	}
	private static ClipContext context(Vec3 start, Vec3 end, ClipContext.Block block, ClipContext.Fluid fluid) {
		return new ClipContext(start, end, block, fluid, CollisionContext.empty());
	}
	private static final class MemoryBlocks implements BlockGetter {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, FluidState> fluids = new HashMap<>();
		final AtomicInteger reads = new AtomicInteger();
		final List<BlockPos> readPositions = new ArrayList<>();
		@Override public BlockState getBlockState(BlockPos pos) {
			reads.incrementAndGet(); readPositions.add(pos.immutable());
			return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}
		@Override public FluidState getFluidState(BlockPos pos) {
			reads.incrementAndGet(); readPositions.add(pos.immutable());
			return fluids.getOrDefault(pos, Fluids.EMPTY.defaultFluidState());
		}
		@Override public BlockEntity getBlockEntity(BlockPos pos) { fail("ray must never request block entities"); return null; }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}
}
