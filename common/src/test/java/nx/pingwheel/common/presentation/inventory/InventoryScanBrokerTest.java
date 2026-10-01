package nx.pingwheel.common.presentation.inventory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nx.pingwheel.common.config.InventoryLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryScanBrokerTest {

	@Test
	void twoConsumersShareOnePhysicalReadPerSlot() {
		var source = new FakeSource(new ArrayList<>(Arrays.asList(
			stack("test:a", 2), null, stack("test:b", 5))), true, true);
		var broker = new InventoryScanBroker(source, 16);

		broker.physicalStep(16);

		assertEquals(InventoryScanner.State.COMPLETE, broker.state());
		assertTrue(broker.complete());
		assertEquals(3, broker.physicalReads());

		var first = new InventoryScanner(broker.capturedSource(), Set.of(), Set.of("test:b"), true, 16);
		var second = new InventoryScanner(broker.capturedSource(), Set.of(key("test:b")), Set.of(), false, 16);

		InventoryScanner.Result firstResult = null;
		InventoryScanner.Result secondResult = null;
		for (int step = 0; step < 3; step++) {
			firstResult = first.step(1);
			secondResult = second.step(1);
		}

		assertEquals(3, source.reads, "three slots are physically read once for both consumers");
		assertEquals(InventoryScanner.State.COMPLETE, firstResult.state());
		assertEquals(2L, firstResult.counts().get(key("test:a")));
		assertEquals(5L, firstResult.counts().get(key("test:b")));
		assertEquals(5L, firstResult.itemTotals().get("test:b"));
		assertEquals(InventoryScanner.State.COMPLETE, secondResult.state());
		assertEquals(5L, secondResult.counts().get(key("test:b")));
		assertFalse(secondResult.counts().containsKey(key("test:a")),
			"a selected consumer never gains an unselected entry from the shared cache");
	}

	@Test
	void constructorRejectsAnOversizedCacheBeforeReading() {
		var source = new FakeSource(new ArrayList<>(Arrays.asList(stack("test:a", 1))), true, true);

		assertThrows(IllegalArgumentException.class,
			() -> new InventoryScanBroker(source, InventoryLimits.INTERNAL_COUNT_LIMIT_GUARD + 1));
		assertThrows(IllegalArgumentException.class,
			() -> new InventoryScanBroker(source, Integer.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> new InventoryScanBroker(source, -1));

		assertEquals(0, source.reads, "an invalid cache bound is rejected before any physical read");
		assertEquals(0, source.sizeQueries, "an invalid cache bound is rejected before any source probe");
	}

	@Test
	void capturedSnapshotQualityIsFrozenAtPhysicalCapture() {
		var stableAtCapture = new FakeSource(new ArrayList<>(Arrays.asList(stack("test:a", 1))), true, true);
		var stableBroker = new InventoryScanBroker(stableAtCapture, 4);
		stableBroker.physicalStep(1);
		stableAtCapture.setStableSnapshot(false);

		assertFalse(stableBroker.uncertain(), "the broker keeps the quality observed at capture");
		assertTrue(stableBroker.capturedSource().stableSnapshot(),
			"the wrapper returns the frozen quality, not the live source answer");

		var uncertainAtCapture = new FakeSource(new ArrayList<>(Arrays.asList(stack("test:b", 1))), true, false);
		var uncertainBroker = new InventoryScanBroker(uncertainAtCapture, 4);
		uncertainBroker.physicalStep(1);
		uncertainAtCapture.setStableSnapshot(true);

		assertTrue(uncertainBroker.uncertain());
		assertFalse(uncertainBroker.capturedSource().stableSnapshot(),
			"a later live answer never upgrades a frozen uncertain observation");
	}

	@Test
	void aConsumerReadingPastTheCapturedPrefixIsUnavailableNotComplete() {
		var source = new FakeSource(new ArrayList<>(Arrays.asList(
			stack("test:a", 1), stack("test:b", 2), stack("test:c", 3))), true, true);
		var broker = new InventoryScanBroker(source, 8);
		broker.physicalStep(1);

		var scanner = new InventoryScanner(broker.capturedSource(), Set.of(), Set.of(), true, 16);
		var result = scanner.step(3);

		assertEquals(InventoryScanner.State.UNAVAILABLE, result.state(),
			"an unclamped consumer step is safely unavailable, never a false completion");
		assertTrue(result.counts().isEmpty(), "a rejected prefix read never keeps partial counts");
		assertTrue(result.itemTotals().isEmpty());
		assertEquals(1, source.reads, "the wrapper never falls through to a physical read");
		assertFalse(broker.complete());
		assertEquals(InventoryScanner.State.SCANNING, broker.state());
	}

	@Test
	void aZeroBudgetPerformsNoPhysicalWork() {
		var source = new FakeSource(new ArrayList<>(List.of(stack("test:a", 1))), true, true);
		var broker = new InventoryScanBroker(source, 4);

		broker.physicalStep(0);

		assertEquals(0, broker.physicalReads());
		assertEquals(0, broker.scanned());
		assertEquals(0, source.reads);
		assertEquals(0, source.sizeQueries, "a zero budget does not even probe the source size");
		assertEquals(InventoryScanner.State.SCANNING, broker.state());
		assertThrows(IllegalStateException.class, broker::capturedSource);
	}

	@Test
	void aCachedNullIsAValidEmptyObservation() {
		var source = new FakeSource(new ArrayList<>(Arrays.asList(stack("test:a", 3), null)), true, true);
		var broker = new InventoryScanBroker(source, 4);

		broker.physicalStep(4);

		var shared = broker.capturedSource();
		assertNull(shared.read(1), "an empty slot is a captured observation, not a cache miss");

		var scanner = new InventoryScanner(shared, Set.of(key("test:missing")), Set.of("test:missing"), true, 16);
		var result = scanner.step(2);

		assertEquals(InventoryScanner.State.COMPLETE, result.state());
		assertEquals(0L, result.counts().get(key("test:missing")), "a complete scan proves the selected item missing");
		assertEquals(0L, result.itemTotals().get("test:missing"));
		assertEquals(3L, result.counts().get(key("test:a")));
	}

	@Test
	void anOversizedSourceCapturesABoundedPrefixAndNeverCompletes() {
		var source = new FakeSource(new ArrayList<>(List.of(
			stack("test:a", 1), stack("test:b", 1), stack("test:c", 1),
			stack("test:d", 1), stack("test:e", 1))), true, true);
		var broker = new InventoryScanBroker(source, 3);

		broker.physicalStep(10);

		assertEquals(3, broker.scanned());
		assertEquals(5, broker.total());
		assertEquals(3, broker.physicalReads());
		assertFalse(broker.complete());
		assertEquals(InventoryScanner.State.INCOMPLETE, broker.state());

		var shared = broker.capturedSource();
		assertEquals(5, shared.slots(), "the consumer still sees the full expected size");
		assertNotNull(shared.read(2));
		assertThrows(IllegalStateException.class, () -> shared.read(3), "reads never escape the captured prefix");

		broker.physicalStep(10);
		assertEquals(3, source.reads, "a terminal partial sweep performs no further physical reads");
	}

	@Test
	void anUnstableCursorSourceBecomesIncompleteWhenTheBudgetCannotFinish() {
		var source = new FakeSource(new ArrayList<>(List.of(
			stack("test:a", 1), stack("test:b", 1), stack("test:c", 1))), false, false);
		var broker = new InventoryScanBroker(source, 8);

		broker.physicalStep(2);

		assertEquals(2, broker.scanned());
		assertEquals(InventoryScanner.State.INCOMPLETE, broker.state());
		assertFalse(broker.complete());
		assertTrue(broker.uncertain(), "a non-atomic source keeps its uncertain quality");

		broker.physicalStep(8);
		assertEquals(2, source.reads, "a terminal incomplete sweep cannot resume");

		var shared = broker.capturedSource();
		assertEquals(3, shared.slots());
		assertTrue(shared.stableCursor(), "the captured prefix itself is stable");
		assertFalse(shared.stableSnapshot());
		assertNotNull(shared.read(1));
		assertThrows(IllegalStateException.class, () -> shared.read(2));

		var scanner = new InventoryScanner(shared, Set.of(), Set.of(), true, 16);
		var partial = scanner.step(2);
		assertEquals(2, partial.scanned());
		assertEquals(InventoryScanner.State.SCANNING, partial.state());
		assertTrue(partial.uncertain());

		var clamped = scanner.step(Math.max(0, broker.scanned() - partial.scanned()));
		assertEquals(2, clamped.scanned(), "the caller clamps to the captured prefix");
		assertFalse(broker.complete(), "a partial cache is never a complete scan");
	}

	@Test
	void aFailedPhysicalReadTerminalizesTheSharedSource() {
		var source = new FakeSource(new ArrayList<>(List.of(
			stack("test:a", 4), stack("test:b", 2))), true, true);
		source.failOn(1);
		var broker = new InventoryScanBroker(source, 4);

		broker.physicalStep(4);

		assertEquals(InventoryScanner.State.UNAVAILABLE, broker.state());
		assertEquals(1, broker.scanned());
		assertEquals(1, source.reads);
		assertEquals(1, broker.physicalReads());

		var scanner = new InventoryScanner(broker.capturedSource(), Set.of(), Set.of(), true, 16);
		var result = scanner.step(2);

		assertEquals(InventoryScanner.State.UNAVAILABLE, result.state());
		assertTrue(result.counts().isEmpty(), "an invalid source never publishes stale counts");
		assertTrue(result.itemTotals().isEmpty());

		broker.physicalStep(4);
		assertEquals(1, source.reads, "an unavailable source performs no further physical reads");
	}

	@Test
	void closeClearsTheBoundedCacheAndCancelsTheSource() {
		var source = new FakeSource(new ArrayList<>(List.of(
			stack("test:a", 1), stack("test:b", 1))), true, true);
		var broker = new InventoryScanBroker(source, 8);
		broker.physicalStep(8);
		var shared = broker.capturedSource();

		broker.close();

		assertEquals(InventoryScanner.State.CANCELLED, broker.state());
		assertThrows(IllegalStateException.class, () -> shared.read(0));

		broker.physicalStep(8);
		assertEquals(2, source.reads);
	}

	@Test
	void aStableCursorResumesAContinuedPrefixAcrossSteps() {
		var source = new FakeSource(new ArrayList<>(List.of(
			stack("test:a", 1), stack("test:b", 1), stack("test:c", 1), stack("test:d", 1))), true, true);
		var broker = new InventoryScanBroker(source, 8);

		broker.physicalStep(1);
		assertEquals(1, broker.scanned());
		assertEquals(InventoryScanner.State.SCANNING, broker.state());

		broker.physicalStep(3);
		assertEquals(4, broker.scanned());
		assertEquals(InventoryScanner.State.COMPLETE, broker.state());
		assertEquals(4, source.reads);
		assertEquals(4, broker.physicalReads());
	}

	private static InventoryScanner.Key key(String itemId) {
		return new InventoryScanner.Key(itemId, "components");
	}

	private static InventoryScanner.Stack stack(String itemId, long count) {
		return new InventoryScanner.Stack(key(itemId), count);
	}

	private static final class FakeSource implements InventoryScanner.Source {

		private final List<InventoryScanner.Stack> slots;
		private final boolean stableCursor;
		private boolean stableSnapshot;
		private final Set<Integer> failures = new HashSet<>();
		private int reads;
		private int sizeQueries;

		FakeSource(List<InventoryScanner.Stack> slots, boolean stableCursor, boolean stableSnapshot) {
			this.slots = slots;
			this.stableCursor = stableCursor;
			this.stableSnapshot = stableSnapshot;
		}

		void failOn(int slot) {
			failures.add(slot);
		}

		void setStableSnapshot(boolean stableSnapshot) {
			this.stableSnapshot = stableSnapshot;
		}

		@Override
		public int slots() {
			sizeQueries++;
			return slots.size();
		}

		@Override
		public boolean stableCursor() {
			return stableCursor;
		}

		@Override
		public boolean stableSnapshot() {
			return stableSnapshot;
		}

		@Override
		public InventoryScanner.Stack read(int slot) {
			if (slot < 0 || slot >= slots.size()) {
				throw new IllegalStateException("slot is out of range");
			}
			if (failures.contains(slot)) {
				throw new IllegalStateException("source became unavailable");
			}
			reads++;
			return slots.get(slot);
		}
	}
}
