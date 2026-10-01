package nx.pingwheel.common.presentation.inventory;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InventoryScannerTest {
	private static final InventoryScanner.Key A = new InventoryScanner.Key("minecraft:stone", "a");
	private static final InventoryScanner.Key B = new InventoryScanner.Key("minecraft:stone", "b");
	private static final InventoryScanner.Key MISSING = new InventoryScanner.Key("minecraft:dirt", "");

	private static final class Source implements InventoryScanner.Source {
		final InventoryScanner.Stack[] slots;
		boolean cursor = true;
		boolean atomic;
		boolean fail;
		int reads;
		Source(InventoryScanner.Stack... slots) { this.slots = slots; }
		public int slots() { return slots.length; }
		public boolean stableCursor() { return cursor; }
		public boolean stableSnapshot() { return atomic; }
		public InventoryScanner.Stack read(int slot) {
			reads++;
			if (fail) throw new IllegalStateException("source disappeared");
			return slots[slot];
		}
	}

	private InventoryScanner scanner(Source source, Set<InventoryScanner.Key> selected) {
		return new InventoryScanner(source, selected, Set.of(), false, 16);
	}

	@Test void partitionsKeepExactCountsAndOnlyCompletedAbsenceBecomesZero() {
		var source = new Source(new InventoryScanner.Stack(A, 3), null, new InventoryScanner.Stack(A, 7));
		var scanner = scanner(source, Set.of(A, MISSING));
		var first = scanner.step(1);
		assertEquals(Map.of(A, 3L), first.counts());
		assertFalse(first.complete());
		assertEquals(1, source.reads);
		var complete = scanner.step(2);
		assertEquals(Map.of(A, 10L, MISSING, 0L), complete.counts());
		assertTrue(complete.complete());
		assertTrue(complete.uncertain());
		assertEquals(Map.of(A, 3L), first.counts());
		scanner.step(100);
		assertEquals(3, source.reads);
	}

	@Test void noBudgetDoesNotReadOrInvalidate() {
		var source = new Source(new InventoryScanner.Stack(A, 1));
		var scanner = scanner(source, Set.of(A));
		assertEquals(InventoryScanner.State.SCANNING, scanner.step(0).state());
		assertEquals(0, source.reads);
		assertTrue(scanner.step(1).complete());
	}

	@Test void unstableCursorEndsIncompleteInsteadOfResumingAChangedIterator() {
		var source = new Source(new InventoryScanner.Stack(A, 2), new InventoryScanner.Stack(A, 5));
		source.cursor = false;
		var scanner = scanner(source, Set.of(A, MISSING));
		var result = scanner.step(1);
		assertEquals(InventoryScanner.State.INCOMPLETE, result.state());
		assertFalse(result.counts().containsKey(MISSING));
		scanner.step(10);
		assertEquals(1, source.reads);
	}

	@Test void cursorlessSourceCanCompleteWithinOneAllowance() {
		var source = new Source(new InventoryScanner.Stack(A, 9));
		source.cursor = false;
		source.atomic = true;
		var result = scanner(source, Set.of(A)).step(1);
		assertTrue(result.complete());
		assertFalse(result.uncertain());
		assertEquals(9L, result.counts().get(A));
	}

	@Test void unavailableIsNotEmptyOrAnAbsentSelectedItem() {
		var source = new Source(new InventoryScanner.Stack(A, 3));
		source.fail = true;
		var result = scanner(source, Set.of(MISSING)).step(1);
		assertEquals(InventoryScanner.State.UNAVAILABLE, result.state());
		assertFalse(result.complete());
		assertTrue(result.counts().isEmpty());
	}

	@Test void aggregateIncludesVariantsThatWereNotSelected() {
		var source = new Source(new InventoryScanner.Stack(A, 2), new InventoryScanner.Stack(B, 8));
		var result = new InventoryScanner(source, Set.of(A), Set.of(A.itemId()), false, 16).step(2);
		assertEquals(Map.of(A, 2L), result.counts());
		assertEquals(Map.of(A.itemId(), 10L), result.itemTotals());
	}

	@Test void previewDiscoversDistinctVariantsAndPreservesLongCounts() {
		var source = new Source(new InventoryScanner.Stack(A, 9_007_199_254_740_993L), new InventoryScanner.Stack(B, 4));
		var result = new InventoryScanner(source, Set.of(), Set.of(), true, 16).step(2);
		assertEquals(9_007_199_254_740_993L, result.counts().get(A));
		assertEquals(4L, result.counts().get(B));
		assertThrows(UnsupportedOperationException.class, () -> result.counts().clear());
	}

	@Test void overflowAndEntryBoundsCannotMasqueradeAsCompleteTotals() {
		var source = new Source(new InventoryScanner.Stack(A, Long.MAX_VALUE), new InventoryScanner.Stack(A, 1));
		assertEquals(InventoryScanner.State.INCOMPLETE, scanner(source, Set.of(A)).step(2).state());
		var two = new Source(new InventoryScanner.Stack(A, 1), new InventoryScanner.Stack(B, 1));
		var bounded = new InventoryScanner(two, Set.of(), Set.of(), true, 1).step(2);
		assertEquals(InventoryScanner.State.INCOMPLETE, bounded.state());
		assertEquals(Map.of(A, 1L), bounded.counts());
	}

	@Test void emptySweepAndCancelledSweepAreDifferent() {
		var scanner = scanner(new Source(), Set.of(MISSING));
		assertEquals(Map.of(MISSING, 0L), scanner.step(1).counts());
		scanner.close();
		assertEquals(InventoryScanner.State.CANCELLED, scanner.step(1).state());
		assertTrue(scanner.snapshot().counts().isEmpty());
	}

	@Test void completingPreviewCannotOverflowItsBoundWithAbsentSelectedKeys() {
		var source = new Source(new InventoryScanner.Stack(A, 2));
		var result = new InventoryScanner(source, Set.of(B, MISSING), Set.of(), true, 2).step(1);
		assertEquals(InventoryScanner.State.INCOMPLETE, result.state());
		assertEquals(Map.of(A, 2L), result.counts());
		var fits = new InventoryScanner(source, Set.of(B, MISSING), Set.of(), true, 3).step(1);
		assertTrue(fits.complete());
		assertEquals(Map.of(A, 2L, B, 0L, MISSING, 0L), fits.counts());
	}
}
