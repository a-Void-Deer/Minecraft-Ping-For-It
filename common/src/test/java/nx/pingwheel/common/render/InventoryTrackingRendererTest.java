package nx.pingwheel.common.render;

import java.util.List;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket.Status;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTrackingRendererTest {
	private static ClientInventory.EntryView entry(String label, long count, boolean fallback, Status quality) {
		return new ClientInventory.EntryView("opaque:" + label, "minecraft:stone", label, null, count, fallback, quality, "request");
	}
	@Test void onlyReceivedTrackingDataSuppliesBoundedItemLinesAndNeverSynthesizesZero() {
		assertTrue(InventoryTrackingRenderer.lines(ClientInventory.Tracking.empty(new MarkerId(1))).isEmpty());
		var projection = new ClientInventory.Tracking(new MarkerId(1), Status.READY, false, true, 1, 1, 1,
			List.of(entry(" ", 10, false, null), entry("A".repeat(100), -1, false, null), entry("B", 0, false, null),
				entry("C", 3, false, null), entry("D", 4, false, null)));
		var lines = InventoryTrackingRenderer.lines(projection);
		assertEquals(3, lines.size()); assertEquals(64, lines.getFirst().label().length()); assertNull(lines.getFirst().count());
		assertEquals(0L, lines.get(1).count()); assertEquals("request", lines.getFirst().pingType()); assertFalse(lines.getFirst().grey());
	}
	@Test void incompleteInvalidAndComponentFallbackKeepCountsAndExplicitGreyStatus() {
		for (Status status : List.of(Status.INCOMPLETE, Status.INVALID, Status.UNCERTAIN)) {
			var projection = new ClientInventory.Tracking(new MarkerId(1), status, true, false, 1, 2, 1,
				List.of(entry("Stone", 7, false, null), entry("Folded", 8, true, null)));
			var lines = InventoryTrackingRenderer.lines(projection);
			assertEquals(7L, lines.getFirst().count()); assertTrue(lines.getFirst().grey());
			assertEquals(SpatialInventoryView.Status.fromName(status.name()).translationKey(), lines.getFirst().statusKey());
			assertEquals(SpatialInventoryView.Status.COMPONENT_TOO_LONG.translationKey(), lines.get(1).statusKey()); assertTrue(lines.get(1).grey());
		}
	}
}
