package nx.pingwheel.common.presentation.inventory;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryCreationPublicationTest {
	@Test void initialStagingSharesTrackingCapacityAndReleasesMemoryWithoutPreviewWireSpend() {
		var memory = new RetainedMemoryLedger(16_000_000);
		var settings = InventorySettings.serverDefaults();
		try (var publisher = new InventorySyncPublisher(memory, new InventorySyncPublisherTest.Transport())) {
			publisher.advance(0, settings);
			long capacity = settings.trackingRollingQueueBytes();
			try (var staging = publisher.reserveInitial(capacity, 4096).orElseThrow()) {
				assertEquals(capacity + 4096, memory.reserved());
				assertTrue(publisher.reserveInitial(1, 0).isEmpty());
			}
			assertEquals(0, memory.reserved());
			memory.setCap(4095);
			assertTrue(publisher.reserveInitial(1, 4096).isEmpty());
			assertEquals(0, memory.reserved());
			memory.setCap(16_000_000);
			try (var staging = publisher.reserveInitial(capacity, 4096).orElseThrow()) {
				assertEquals(capacity + 4096, memory.reserved());
			}
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	@Test void completionProofIsForTheCurrentFullyHandedCumulativePreviewCutOnly() {
		var memory = new RetainedMemoryLedger(16_000_000);
		var transport = new InventorySyncPublisherTest.Transport();
		var settings = InventorySettings.serverDefaults();
		try (var publisher = new InventorySyncPublisher(memory, transport)) {
			publisher.advance(0, settings);
			var context = InventorySyncPublisherTest.context("preview/complete", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 1, 0));
			assertTrue(publisher.accepted(context, 1));
			assertFalse(publisher.previewCutComplete(context, 1));
			publisher.drain(); assertTrue(publisher.previewCutComplete(context, 1));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(2, 1, 0));
			assertFalse(publisher.previewCutComplete(context, 1));
			assertFalse(publisher.previewCutComplete(context, 2));
			publisher.drain(); assertTrue(publisher.previewCutComplete(context, 2));
			publisher.retireGroup(context, "minecraft:stone");
			assertFalse(publisher.previewCutComplete(context, 2));
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void exactSettlementReleasesTheUpperBoundDifferenceAndKeepsOnlyActualWireCapacity() {
		var memory = new RetainedMemoryLedger(16_000_000); var settings = InventorySettings.serverDefaults();
		try (var publisher = new InventorySyncPublisher(memory, new InventorySyncPublisherTest.Transport())) {
			publisher.advance(0, settings);
			try (var staging = publisher.reserveInitial(1000, 10000).orElseThrow()) {
				staging.settle(100, 2000);
				assertEquals(0, memory.reserved()); assertEquals(2100, memory.retained());
				try (var remainder = publisher.reserveInitial(settings.trackingRollingQueueBytes() - 100, 0).orElseThrow()) {
					assertTrue(publisher.reserveInitial(1, 0).isEmpty());
				}
			}
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
}
