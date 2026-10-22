package nx.pingwheel.neoforge.integration.create.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BoundedCreateSummaryTest {
	@Test
	void identicalRegistryIdsAggregateWithoutRetainingVariantOrSlot() {
		BoundedCreateSummary summary = new BoundedCreateSummary(4, 2, 4, 64);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", 17);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", 12);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:dirt", 3);
		var snapshot = summary.snapshot();
		assertEquals(Map.of("minecraft:stone", 29L, "minecraft:dirt", 3L), snapshot.counts());
		assertEquals(3, snapshot.scanned());
		assertTrue(snapshot.available());
		assertFalse(snapshot.partial());
		assertEquals("minecraft:dirt", snapshot.counts().keySet().iterator().next());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().put("test:other", 3L));
	}

	@Test
	void emptySlotsStillConsumeScanAndWorkLimits() {
		BoundedCreateSummary summary = new BoundedCreateSummary(8, 4, 2, 64);
		assertTrue(summary.beginEntry());
		assertTrue(summary.beginEntry());
		assertFalse(summary.beginEntry());
		assertTrue(summary.snapshot().partial());
		assertEquals(2, summary.snapshot().scanned());
		assertTrue(summary.snapshot().counts().isEmpty());
	}

	@Test
	void outputIdsAndBytesHaveIndependentLimits() {
		BoundedCreateSummary summary = new BoundedCreateSummary(8, 2, 8, 32);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:a", 1);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:b", 1); // two entries do not fit 32 bytes
		assertTrue(summary.beginEntry());
		summary.add("minecraft:a", 2); // same ID can still accumulate
		assertEquals(Map.of("minecraft:a", 3L), summary.snapshot().counts());
		assertTrue(summary.snapshot().partial());
	}

	@Test
	void cardinalityBoundDoesNotEndScanningButMarksOmission() {
		BoundedCreateSummary summary = new BoundedCreateSummary(5, 1, 5, 64);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", 1);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:dirt", 1);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", 4);
		assertEquals(Map.of("minecraft:stone", 5L), summary.snapshot().counts());
		assertEquals(3, summary.snapshot().scanned());
		assertTrue(summary.snapshot().partial());
	}

	@Test
	void invalidAmountsAndIdsMarkPartialRatherThanClaimingEmpty() {
		BoundedCreateSummary summary = new BoundedCreateSummary(5, 3, 5, 64);
		assertTrue(summary.beginEntry());
		summary.add("Minecraft:bad", 3);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", -2);
		assertTrue(summary.snapshot().partial());
		assertTrue(summary.snapshot().counts().isEmpty());
		assertTrue(summary.snapshot().available());
		assertFalse(BoundedCreateSummary.Summary.unavailable().available());
		assertFalse(BoundedCreateSummary.Summary.incomplete().available());
		assertTrue(BoundedCreateSummary.Summary.incomplete().partial());
	}

	@Test
	void overflowOmitsUnsafeAmountAndSnapshotCopiesSourceMap() {
		BoundedCreateSummary summary = new BoundedCreateSummary(3, 2, 3, 64);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", Long.MAX_VALUE);
		assertTrue(summary.beginEntry());
		summary.add("minecraft:stone", 1);
		assertTrue(summary.snapshot().counts().isEmpty());
		assertTrue(summary.snapshot().partial());
		Map<String, Long> mutable = new HashMap<>();
		mutable.put("minecraft:dirt", 5L);
		var detached = new BoundedCreateSummary.Summary(mutable, 1, false, true);
		mutable.clear();
		assertEquals(Map.of("minecraft:dirt", 5L), detached.counts());
	}

	@Test
	void rejectsInvalidBudgets() {
		assertThrows(IllegalArgumentException.class, () -> new BoundedCreateSummary(0, 1, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new BoundedCreateSummary(1, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new BoundedCreateSummary(1, 1, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new BoundedCreateSummary(1, 1, 1, 0));
	}
}
