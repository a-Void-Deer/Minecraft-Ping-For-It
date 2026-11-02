package nx.pingwheel.neoforge.integration.create;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import nx.pingwheel.common.marker.TargetKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateFluidTankTopologyTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@NotNullByDefault
	static final class World implements CreateFluidTankTopology.World {
		final Map<BlockPos, CreateFluidTankTopology.Reading> members = new HashMap<>();
		final List<BlockPos> reads = new ArrayList<>();
		int minimum = -64, height = 384;
		World(BlockPos root, int width, int height) {
			for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
				var position = root.offset(x, y, z);
				members.put(position, new CreateFluidTankTopology.Reading(Blocks.STONE.defaultBlockState(), new Object(), root,
					position.equals(root), position.equals(root) ? width : 0, position.equals(root) ? height : 0, Axis.Y));
			}
		}
		@Override public @Nullable CreateFluidTankTopology.Reading loaded(BlockPos position) { reads.add(position); return members.get(position); }
		@Override public int minBuildHeight() { return minimum; }
		@Override public int buildHeight() { return height; }
	}

	@Test void allWidthsAndTallStructuresResolveFromEveryHitToOneCanonicalDetachedTopology() {
		for (int width = 1; width <= 3; width++) for (int height : new int[] {1, 2, 9, 32, 40})
			for (BlockPos root : List.of(new BlockPos(-17, -5, -17), new BlockPos(15, 64, 15))) {
				var world = new World(root, width, height); var expected = CreateFluidTankTopology.read(world, root).orElseThrow();
				assertEquals(width * width * height, expected.cells().size());
				@Unmodifiable List<BlockPos> positions = expected.cells().stream().map(CreateFluidTankTopology.Cell::position).toList();
				assertEquals(positions.stream().sorted(java.util.Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ())).toList(), positions);
				assertThrows(UnsupportedOperationException.class, () -> expected.cells().clear());
				for (BlockPos hit : positions) {
					world.reads.clear(); var actual = CreateFluidTankTopology.read(world, hit).orElseThrow();
					assertEquals(expected, actual); assertEquals(2 + 2 * positions.size(), world.reads.size());
					assertTrue(positions.containsAll(world.reads)); assertEquals(expected.key("minecraft:overworld"), actual.key("minecraft:overworld"));
				}
				assertNotEquals(expected.key("minecraft:overworld"), expected.key("minecraft:the_nether"));
				assertNotEquals(expected.key("minecraft:overworld"), new TargetKey.CreateTankKey("minecraft:overworld", root.getX() + 1, root.getY(), root.getZ(), "create:fluid_tank"));
			}
	}

	@Test void extentDoesNotBelongToTheKeyAndDimensionBoundCanRepresentConfiguredHeights() {
		var small = CreateFluidTankTopology.read(new World(BlockPos.ZERO, 1, 1), BlockPos.ZERO).orElseThrow();
		var world = new World(BlockPos.ZERO, 3, 384); world.minimum = 0;
		var tall = CreateFluidTankTopology.read(world, new BlockPos(2, 383, 2)).orElseThrow();
		assertEquals(3456, tall.cells().size()); assertEquals(small.key("minecraft:overworld"), tall.key("minecraft:overworld"));
	}

	@Test void missingMemberForeignHitAndInconsistentControllerRoleOrAxisNeverPublishAPrefix() {
		for (BlockPos missing : List.of(BlockPos.ZERO, new BlockPos(1, 1, 1), new BlockPos(2, 4, 2))) {
			var world = new World(BlockPos.ZERO, 3, 5); world.members.remove(missing);
			assertTrue(CreateFluidTankTopology.read(world, BlockPos.ZERO.above()).isEmpty());
		}
		for (String failure : List.of("foreign-hit", "controller-loop", "second-controller", "root-role", "member-axis", "root-axis")) {
			var world = new World(BlockPos.ZERO, 2, 3); var position = failure.startsWith("root") ? BlockPos.ZERO : new BlockPos(1, 1, 1);
			var old = world.members.get(position);
			world.members.put(position, new CreateFluidTankTopology.Reading(old.state(), old.identity(), failure.equals("controller-loop") ? position : old.controller(),
				failure.equals("second-controller") || !failure.equals("root-role") && old.isController(), old.width(), old.height(), failure.endsWith("axis") ? Axis.X : Axis.Y));
			var hit = failure.equals("foreign-hit") ? new BlockPos(-1, 0, 0) : BlockPos.ZERO;
			if (failure.equals("foreign-hit")) world.members.put(hit, world.members.get(BlockPos.ZERO));
			assertTrue(CreateFluidTankTopology.read(world, hit).isEmpty(), failure);
		}
	}

	@Test void dimensionsBoundsAndOverflowAreRejectedBeforeAllocationOrPrismReads() {
		for (int width : new int[] {Integer.MIN_VALUE, 0, 4, Integer.MAX_VALUE}) rejectDimensions(width, 1);
		for (int height : new int[] {Integer.MIN_VALUE, 0, 385, Integer.MAX_VALUE}) rejectDimensions(1, height);
		for (int coordinate = 0; coordinate < 3; coordinate++) {
			var root = new BlockPos(coordinate == 0 ? Integer.MAX_VALUE : 0, coordinate == 1 ? Integer.MAX_VALUE : 0, coordinate == 2 ? Integer.MAX_VALUE : 0);
			var world = new World(root, 2, 2); assertTrue(CreateFluidTankTopology.read(world, root).isEmpty()); assertEquals(2, world.reads.size());
		}
		for (int minimum : new int[] {-65, 319}) {
			var root = new BlockPos(0, minimum, 0); var world = new World(root, 1, 2);
			assertTrue(CreateFluidTankTopology.read(world, root).isEmpty()); assertEquals(2, world.reads.size());
		}
		var world = new World(BlockPos.ZERO, 1, 1); world.height = DimensionType.Y_SIZE + 1;
		assertTrue(CreateFluidTankTopology.read(world, BlockPos.ZERO).isEmpty()); assertEquals(2, world.reads.size());
	}
	private static void rejectDimensions(int width, int height) {
		var world = new World(BlockPos.ZERO, 1, 1); var old = world.members.get(BlockPos.ZERO);
		world.members.put(BlockPos.ZERO, new CreateFluidTankTopology.Reading(old.state(), old.identity(), old.controller(), true, width, height, Axis.Y));
		assertTrue(CreateFluidTankTopology.read(world, BlockPos.ZERO).isEmpty()); assertEquals(2, world.reads.size());
	}

	@Test void replacementOrControllerChangeDuringMetadataCallbacksRejectsWholeBatchAndLaterReadRecovers() {
		var world = new World(BlockPos.ZERO, 2, 3); var root = world.members.get(BlockPos.ZERO); var end = new BlockPos(1, 2, 1);
		assertTrue(CreateFluidTankTopology.read(new CreateFluidTankTopology.World() {
			@Override public @Nullable CreateFluidTankTopology.Reading loaded(BlockPos position) {
				var current = world.loaded(position);
				if (position.equals(end)) world.members.put(BlockPos.ZERO, new CreateFluidTankTopology.Reading(root.state(), new Object(), root.controller(), true, 2, 3, Axis.Y));
				return current;
			}
			@Override public int minBuildHeight() { return world.minimum; }
			@Override public int buildHeight() { return world.height; }
		}, BlockPos.ZERO).isEmpty());
		assertEquals(12, CreateFluidTankTopology.read(world, end).orElseThrow().cells().size());
	}
}
