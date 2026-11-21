package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
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
			ClipContext.Fluid.NONE, hits));
		assertEquals(List.of(new BlockPos(2, 0, 0), new BlockPos(5, 0, 0)), hits.stream().map(BlockHitResult::getBlockPos).toList());
		assertEquals(List.of(2.0, 5.0), hits.stream().map(hit -> hit.getLocation().x).toList());
		assertTrue(hits.stream().allMatch(hit -> hit.getDirection() == Direction.WEST));
		hits.clear();
		assertTrue(scan(world, new Vec3(8, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, hits));
		assertEquals(List.of(6.0, 3.0), hits.stream().map(hit -> hit.getLocation().x).toList());
		assertTrue(hits.stream().allMatch(hit -> hit.getDirection() == Direction.EAST));
	}

	@Test
	void finiteSegmentDoesNotSeeBlockBeyondEnd() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(5, 0, 0), Blocks.STONE.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(scan(world, new Vec3(0.5, 0.5, 0.5), new Vec3(4, 0.5, 0.5), ClipContext.Block.OUTLINE,
			ClipContext.Fluid.NONE, hits));
		assertTrue(hits.isEmpty());
	}

	@Test
	void subnormalDeltaWithinOneCellDoesNotHideLaterHitOnAnotherAxis() {
		MemoryBlocks world = new MemoryBlocks();
		BlockPos late = new BlockPos(2, 0, 0);
		world.blocks.put(late, Blocks.STONE.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		// Both Z endpoints remain strictly inside the same cell; an infinite reciprocal
		// on that axis does not prevent vanilla from crossing the two X boundaries.
		assertTrue(scan(world, new Vec3(.5, .5, 2 * Double.MIN_VALUE), new Vec3(2.5, .5, Double.MIN_VALUE),
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, hits));
		assertEquals(List.of(late), hits.stream().map(BlockHitResult::getBlockPos).toList());
		assertEquals(2.0, hits.getFirst().getLocation().x, 1.0E-12);
		assertEquals(Direction.WEST, hits.getFirst().getDirection());
	}

	@Test
	void coordinatesOutsideTheIntegerGridAreIncompleteBeforeWorldAccess() {
		MemoryBlocks world = new MemoryBlocks();
		Vec3 start = new Vec3(Integer.MAX_VALUE - .5, .5, .5);
		Vec3 end = new Vec3(Integer.MAX_VALUE + 1.5, .5, .5);
		assertFalse(NativeBlockCandidateScan.scan(world, ignored -> fail("invalid traversal must not query a cell"),
			context(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE),
			ignored -> fail("invalid traversal must not publish a hit")));
		assertTrue(world.readPositions.isEmpty());
	}

	@Test
	void loadedScanFindsLateSupportedStoneAfterManyNeighborReads() {
		MemoryBlocks world = new MemoryBlocks();
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockPos late = new BlockPos(1_800, 0, 0);
		for (int x = 0; x <= late.getX(); x++) world.blocks.put(new BlockPos(x, 0, 0), stone);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) world.blocks.put(late.offset(dx, 1, dz), stone);
		}
		var start = new Vec3(.5, .5, .5);
		var end = new Vec3(late.getX() + .5, .5, .5);
		// Each shape checks its nine loaded neighbors, exceeding the former shared
		// cell/read quota within the normal Ping range; only the final cell has full support.
		var context = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()) {
			@Override public VoxelShape getBlockShape(BlockState state, BlockGetter view, BlockPos pos) {
				boolean supported = state.is(Blocks.STONE);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) supported &= view.getBlockState(pos.offset(dx, 1, dz)).is(Blocks.STONE);
				}
				return supported ? super.getBlockShape(state, view, pos) : Shapes.empty();
			}
		};
		var view = new LoadedCandidateBlockView(world, ignored -> true);
		List<BlockHitResult> hits = new ArrayList<>();
		assertTrue(NativeBlockCandidateScan.scan(view, ignored -> true, context, hits::add));
		assertTrue(view.complete(), "all shape inputs were available in the loaded view");
		assertEquals(List.of(late), hits.stream().map(BlockHitResult::getBlockPos).toList());
		assertEquals(late.getX(), hits.getFirst().getLocation().x, 1.0E-12);
	}

	@Test
	void unloadedCellsDoNotReadTheirStateOrInventTargets() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(4, 0, 0), Blocks.STONE.defaultBlockState());
		List<BlockHitResult> hits = new ArrayList<>();
		var context = context(new Vec3(0.5, 0.5, 0.5), new Vec3(8, 0.5, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE);
		assertTrue(NativeBlockCandidateScan.scan(world, pos -> pos.getX() != 4, context, hits::add));
		assertFalse(world.readPositions.contains(new BlockPos(4, 0, 0)));
		assertTrue(hits.isEmpty());
	}

	@Test
	void outlineVisualAndFluidModesUseRealNativeShapeStrategies() {
		MemoryBlocks world = new MemoryBlocks();
		world.blocks.put(new BlockPos(2, 0, 0), Blocks.TORCH.defaultBlockState());
		List<BlockHitResult> outline = new ArrayList<>();
		List<BlockHitResult> visual = new ArrayList<>();
		assertTrue(scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, outline));
		assertTrue(scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, visual));
		assertEquals(1, outline.size());
		assertTrue(visual.isEmpty());
		world.blocks.clear();
		world.fluids.put(new BlockPos(3, 0, 0), Fluids.WATER.defaultFluidState());
		List<BlockHitResult> none = new ArrayList<>();
		List<BlockHitResult> any = new ArrayList<>();
		assertTrue(scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, none));
		assertTrue(scan(world, new Vec3(0.5, 0.25, 0.5), new Vec3(8, 0.25, 0.5), ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, any));
		assertTrue(none.isEmpty());
		assertEquals(new BlockPos(3, 0, 0), any.getFirst().getBlockPos());
		assertEquals(Direction.WEST, any.getFirst().getDirection());
	}

	private static boolean scan(MemoryBlocks world, Vec3 start, Vec3 end, ClipContext.Block block,
		ClipContext.Fluid fluid, List<BlockHitResult> hits) {
		return NativeBlockCandidateScan.scan(world, ignored -> true, context(start, end, block, fluid), hits::add);
	}
	private static ClipContext context(Vec3 start, Vec3 end, ClipContext.Block block, ClipContext.Fluid fluid) {
		return new ClipContext(start, end, block, fluid, CollisionContext.empty());
	}
	private static final class MemoryBlocks implements BlockGetter {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Map<BlockPos, FluidState> fluids = new HashMap<>();
		final List<BlockPos> readPositions = new ArrayList<>();
		@Override public BlockState getBlockState(BlockPos pos) {
			readPositions.add(pos.immutable());
			return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}
		@Override public FluidState getFluidState(BlockPos pos) {
			readPositions.add(pos.immutable());
			return fluids.getOrDefault(pos, Fluids.EMPTY.defaultFluidState());
		}
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return fail("this fixture has no block entities"); }
		@Override public int getHeight() { return 384; }
		@Override public int getMinBuildHeight() { return -64; }
	}
}
