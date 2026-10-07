package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventorySnapshotLayoutTest {

	@Test
	void positionsListsAndNestedMappingsAreDetached() {
		BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(3, 4, 5);
		BlockPos.MutableBlockPos controller = new BlockPos.MutableBlockPos(7, 8, 9);
		List<Integer> mapping = new ArrayList<>(List.of(2, 0));
		List<InventorySnapshotLayout.Segment> segments = new ArrayList<>(List.of(segment(3, mapping)));
		List<InventorySnapshotLayout.Member> members = new ArrayList<>(List.of(member(position, segments)));
		var layout = new InventorySnapshotLayout("test:layout", "face=north", controller, members);

		position.set(20, 30, 40);
		controller.set(70, 80, 90);
		mapping.clear();
		segments.clear();
		members.clear();
		assertEquals(new BlockPos(3, 4, 5), layout.members().getFirst().position());
		assertEquals(new BlockPos(7, 8, 9), layout.controller());
		assertEquals(List.of(2, 0), layout.members().getFirst().segments().getFirst().visibleSlots());
		assertThrows(UnsupportedOperationException.class, () -> layout.members().clear());
		assertThrows(UnsupportedOperationException.class, () -> layout.members().getFirst().segments().clear());
		assertThrows(UnsupportedOperationException.class, () -> layout.members().getFirst().segments().getFirst().visibleSlots().clear());
	}

	@Test
	void structuralMembersAndNullableControllerDoNotInventSlots() {
		var layout = new InventorySnapshotLayout("test:layout", "", null,
			List.of(member(BlockPos.ZERO, List.of()), member(new BlockPos(1, 0, 0), List.of(segment(3, List.of())))));
		assertNull(layout.controller());
		assertTrue(layout.members().getFirst().segments().isEmpty());
		assertTrue(layout.members().get(1).segments().getFirst().visibleSlots().isEmpty());
	}

	@Test
	void duplicatePositionsAndInvalidPerSegmentMappingsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new InventorySnapshotLayout("test:layout", "", null,
			List.of(member(BlockPos.ZERO, List.of()), member(BlockPos.ZERO, List.of()))));
		assertThrows(IllegalArgumentException.class, () -> segment(3, List.of(0, 0)));
		assertThrows(IllegalArgumentException.class, () -> segment(3, List.of(-1)));
		assertThrows(IllegalArgumentException.class, () -> segment(3, List.of(3)));
		assertThrows(IllegalArgumentException.class, () -> segment(-1, List.of()));
	}

	@Test
	void totalVisibleBoundSpansAllMembersRatherThanEachMemberAlone() {
		var full = segment(4096, IntStream.range(0, 4096).boxed().toList());
		assertDoesNotThrow(() -> new InventorySnapshotLayout("test:layout", "", null, List.of(member(BlockPos.ZERO, List.of(full)))));
		assertThrows(IllegalArgumentException.class, () -> new InventorySnapshotLayout("test:layout", "", null,
			List.of(member(BlockPos.ZERO, List.of(full)), member(new BlockPos(1, 0, 0), List.of(segment(1, List.of(0)))))));
	}

	private static InventorySnapshotLayout.Segment segment(int slots, List<Integer> mapping) {
		return new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, slots, mapping);
	}

	private static InventorySnapshotLayout.Member member(BlockPos position, List<InventorySnapshotLayout.Segment> segments) {
		return new InventorySnapshotLayout.Member(position, "minecraft:chest", "minecraft:chest", "member", segments);
	}
}
