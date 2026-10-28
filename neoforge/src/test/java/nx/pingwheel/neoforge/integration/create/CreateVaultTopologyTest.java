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
import nx.pingwheel.common.marker.TargetKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateVaultTopologyTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@NotNullByDefault
	static final class World implements CreateVaultTopology.World {
		final Map<BlockPos, CreateVaultTopology.Reading> members = new HashMap<>();
		final List<BlockPos> reads = new ArrayList<>();
		final BlockPos root;
		World(BlockPos root, Axis axis, int width, int length) {
			this.root = root;
			for (int x = 0; x < (axis == Axis.X ? length : width); x++) for (int y = 0; y < width; y++)
				for (int z = 0; z < (axis == Axis.Z ? length : width); z++) {
					var pos = root.offset(x, y, z);
					members.put(pos, new CreateVaultTopology.Reading(Blocks.STONE.defaultBlockState(), new Object(), root,
						pos.equals(root), pos.equals(root) ? width : 0, pos.equals(root) ? length : 0, axis));
				}
		}
		@Override public @Nullable CreateVaultTopology.Reading loaded(BlockPos pos) { reads.add(pos); return members.get(pos); }
	}

	@Test void allLegalExtentsAndEveryHitResolveToTheSameBoundedOrderedDetachedTopology() {
		for (Axis axis : List.of(Axis.X, Axis.Z)) for (int width = 1; width <= 3; width++)
			for (int length = 1; length <= width * 3; length++) for (BlockPos root : List.of(new BlockPos(-18, -5, -17), new BlockPos(15, 64, 15))) {
				var world = new World(root, axis, width, length);
				var expected = CreateVaultTopology.read(world, root).orElseThrow();
				assertEquals(width * width * length, expected.cells().size()); assertTrue(expected.cells().size() <= 81);
				assertEquals(root, expected.cells().getFirst().position());
				@Unmodifiable List<BlockPos> positions = expected.cells().stream().map(CreateVaultTopology.Cell::position).toList();
				assertEquals(positions.stream().sorted(java.util.Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ())).toList(), positions);
				assertThrows(UnsupportedOperationException.class, () -> expected.cells().clear());
				for (BlockPos hit : positions) {
					world.reads.clear(); var actual = CreateVaultTopology.read(world, hit).orElseThrow();
					assertEquals(expected, actual); assertEquals(2 + 2 * positions.size(), world.reads.size());
					assertTrue(positions.containsAll(world.reads));
					assertEquals(expected.key("minecraft:overworld"), actual.key("minecraft:overworld"));
				}
				assertNotEquals(expected.key("minecraft:overworld"), expected.key("minecraft:the_nether"));
				assertNotEquals(expected.key("minecraft:overworld"), new TargetKey.VaultKey("minecraft:overworld", root.getX() + 1, root.getY(), root.getZ(), "create:item_vault"));
			}
	}

	@Test void missingRootInteriorOrEndAndForeignSourcesNeverPublishAPrefix() {
		for (BlockPos missing : List.of(BlockPos.ZERO, new BlockPos(2, 1, 1), new BlockPos(4, 2, 2))) {
			var world = new World(BlockPos.ZERO, Axis.X, 3, 5); world.members.remove(missing);
			assertTrue(CreateVaultTopology.read(world, new BlockPos(1, 0, 0)).isEmpty());
		}
		var world = new World(BlockPos.ZERO, Axis.Z, 2, 3);
		var foreign = new BlockPos(-1, 0, 0); world.members.put(foreign, world.members.get(BlockPos.ZERO));
		assertTrue(CreateVaultTopology.read(world, foreign).isEmpty()); assertEquals(2, world.reads.size());
	}

	@Test void badControllerRolesAxesBoundsAndOverflowFailBeforePublishing() {
		for (int width : new int[] {0, 4}) {
			var w = new World(BlockPos.ZERO, Axis.X, 1, 1); var r = w.members.get(BlockPos.ZERO);
			w.members.put(BlockPos.ZERO, new CreateVaultTopology.Reading(r.state(), r.identity(), r.controller(), true, width, 1, Axis.X));
			assertTrue(CreateVaultTopology.read(w, BlockPos.ZERO).isEmpty()); assertEquals(2, w.reads.size());
		}
		for (int length : new int[] {0, 4}) {
			var w = new World(BlockPos.ZERO, Axis.X, 1, 1); var r = w.members.get(BlockPos.ZERO);
			w.members.put(BlockPos.ZERO, new CreateVaultTopology.Reading(r.state(), r.identity(), r.controller(), true, 1, length, Axis.X));
			assertTrue(CreateVaultTopology.read(w, BlockPos.ZERO).isEmpty());
		}
		for (int coordinate = 0; coordinate < 3; coordinate++) {
			var root = new BlockPos(coordinate == 0 ? Integer.MAX_VALUE : 0, coordinate == 1 ? Integer.MAX_VALUE : 0, coordinate == 2 ? Integer.MAX_VALUE : 0);
			var w = new World(root, Axis.X, 2, 3);
			assertTrue(CreateVaultTopology.read(w, root).isEmpty()); assertEquals(2, w.reads.size());
		}
		for (String broken : List.of("loop", "second-root", "axis", "root-role", "root-axis")) {
			var w = new World(BlockPos.ZERO, Axis.X, 2, 3); var p = broken.startsWith("root") ? BlockPos.ZERO : new BlockPos(1, 1, 1); var r = w.members.get(p);
			w.members.put(p, new CreateVaultTopology.Reading(r.state(), r.identity(), broken.equals("loop") ? p : r.controller(),
				broken.equals("second-root") || !broken.equals("root-role") && r.isController(), r.width(), r.length(), broken.contains("axis") ? Axis.Y : r.axis()));
			assertTrue(CreateVaultTopology.read(w, BlockPos.ZERO).isEmpty(), broken);
		}
	}

	@Test void replacementDuringMetadataCallbacksInvalidatesTheCompleteBatchAndNextOperationRecovers() {
		var w = new World(BlockPos.ZERO, Axis.X, 2, 3);
		var end = new BlockPos(2, 1, 1); var old = w.members.get(BlockPos.ZERO);
		assertTrue(CreateVaultTopology.read(pos -> {
			var value = w.loaded(pos);
			if (pos.equals(end)) w.members.put(BlockPos.ZERO, new CreateVaultTopology.Reading(old.state(), new Object(), old.controller(), true, 2, 3, Axis.X));
			return value;
		}, BlockPos.ZERO).isEmpty());
		assertEquals(12, CreateVaultTopology.read(w, end).orElseThrow().cells().size());
	}
}
