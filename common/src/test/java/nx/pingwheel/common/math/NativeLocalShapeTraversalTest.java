package nx.pingwheel.common.math;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class NativeLocalShapeTraversalTest {
	private static final Vec3 START = new Vec3(-1, .5, .5), END = new Vec3(5, .5, .5);
	private static final RaycastPolicy POLICY = RaycastPolicy.from(true, false, false);
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void laterShapeNeighborReadFailureCannotReturnAnEarlierLocalHit() {
		BlockPos later = new BlockPos(2, 0, 0);
		var failure = new IllegalStateException("later local shape's block entity read failed");
		BlockGetter view = new StoneView() {
			@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
				if (pos.equals(later.above())) throw failure;
				return super.getBlockEntity(pos);
			}
		};
		ClipContext context = new ClipContext(START, END, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()) {
			@Override public VoxelShape getBlockShape(BlockState state, BlockGetter local, BlockPos pos) {
				if (local.getBlockState(pos.above()).is(Blocks.STONE)) local.getBlockEntity(pos.above());
				return super.getBlockShape(state, local, pos);
			}
		};
		var first = NativeLocalShapeRaycaster.traceWithContext(START, END, POLICY, view,
			List.of(BlockPos.ZERO), ignored -> false, context).orElseThrow();
		assertEquals(0.0, first.localPoint().x, 1.0E-12, "the same first cell alone has a real surface hit");
		assertSame(failure, assertThrows(IllegalStateException.class,
			() -> NativeLocalShapeRaycaster.traceWithContext(START, END, POLICY, view,
				List.of(BlockPos.ZERO, later), ignored -> false, context)));
	}

	@Test void nearestLocalSurfaceCanComeFromTheLastOfManyDisjointNativeBoxes() {
		// The packed voxel grid avoids allocating a shape for every slab. Vanilla
		// emits them in ascending X, so a -X ray meets the last emitted slab first.
		// The fixture is large enough that the former box quota hid that surface.
		VoxelShape slabs = new SlabArrayShape(131_073);
		Vec3 start = new Vec3(2, .5, .5), end = new Vec3(-2, .5, .5);
		ClipContext context = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()) {
			@Override public VoxelShape getBlockShape(BlockState state, BlockGetter local, BlockPos pos) { return slabs; }
		};
		var hit = NativeLocalShapeRaycaster.traceWithContext(start, end, POLICY, new StoneView(),
			List.of(BlockPos.ZERO), ignored -> false, context).orElseThrow();
		assertEquals(new Vec3(1, .5, .5), hit.localPoint(), "the outer face of the last slab is nearest to x=2");
		assertEquals(.25, hit.t());
	}

	@NotNullByDefault
	private static final class SlabArrayShape extends ArrayVoxelShape {
		private static final double @Unmodifiable [] UNIT_INTERVAL = {0, 1};

		SlabArrayShape(int slabs) {
			super(occupancy(2 * slabs - 1), coordinates(2 * slabs - 1), UNIT_INTERVAL, UNIT_INTERVAL);
		}

		private static BitSetDiscreteVoxelShape occupancy(int xCells) {
			var grid = new BitSetDiscreteVoxelShape(xCells, 1, 1);
			for (int x = 0; x < xCells; x += 2) grid.fill(x, 0, 0);
			return grid;
		}

		private static double @Unmodifiable [] coordinates(int xCells) {
			double[] coordinates = new double[xCells + 1];
			for (int x = 0; x <= xCells; x++) coordinates[x] = (double) x / xCells;
			return coordinates;
		}
	}

	@NotNullByDefault
	private static class StoneView implements BlockGetter {
		@Override public BlockState getBlockState(BlockPos pos) { return Blocks.STONE.defaultBlockState(); }
		@Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
		@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
		@Override public int getHeight() { return 256; }
		@Override public int getMinBuildHeight() { return 0; }
	}
}
