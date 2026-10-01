package nx.pingwheel.common.presentation.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral tests for the rolling-excess wire window. Expected capacities are
 * computed by hand from the period contract (peak {@code (n + 1) * B}, previous
 * {@code n - 1} excesses deducted), never from the class under test.
 */
class InventoryWireWindowTest {

	@Test
	void constructorAcceptsTheSupportedWindowRangeAndRejectsOthers() {
		assertEquals(200L, new InventoryWireWindow(100L, 1).remaining());
		assertEquals(3300L, new InventoryWireWindow(100L, 32).remaining());

		assertThrows(IllegalArgumentException.class, () -> new InventoryWireWindow(0L, 1));
		assertThrows(IllegalArgumentException.class, () -> new InventoryWireWindow(-5L, 1));
		assertThrows(IllegalArgumentException.class, () -> new InventoryWireWindow(100L, 0));
		assertThrows(IllegalArgumentException.class, () -> new InventoryWireWindow(100L, 33));
	}

	@Test
	void firstPeriodAllowsTheDocumentedSinglePeriodPeak() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 1);
		assertEquals(0L, window.spent());
		assertEquals(200L, window.remaining());
		assertTrue(window.trySpend(200L));
		assertEquals(200L, window.spent());
		assertEquals(0L, window.remaining());
		assertFalse(window.trySpend(1L));
	}

	@Test
	void singlePeriodWindowOffersThePeakEveryPeriod() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 1);
		for (long period = 0L; period < 5L; period++) {
			assertEquals(200L, window.remaining());
			assertTrue(window.trySpend(200L));
			assertFalse(window.trySpend(1L));
			window.advance(period + 1L);
		}
	}

	@Test
	void scriptedPeriodsMatchTheHandComputedRollingExcessOracle() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 3);

		assertEquals(400L, window.remaining());
		assertTrue(window.trySpend(350L));      // excess 250
		assertEquals(50L, window.remaining());

		window.advance(1L);
		assertEquals(0L, window.spent());
		assertEquals(150L, window.remaining()); // 400 - 250
		assertTrue(window.trySpend(150L));      // excess 50
		assertEquals(0L, window.remaining());

		window.advance(2L);
		assertEquals(100L, window.remaining()); // 400 - (250 + 50)
		assertTrue(window.trySpend(60L));       // excess 0
		assertEquals(40L, window.remaining());

		window.advance(3L);
		assertEquals(350L, window.remaining()); // 400 - (50 + 0)
		assertTrue(window.trySpend(290L));      // excess 190
		assertEquals(60L, window.remaining());

		window.advance(4L);
		assertEquals(210L, window.remaining()); // 400 - (0 + 190)
	}

	@Test
	void unusedBaseIsNotCarriedIntoLaterPeriods() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 3);
		assertEquals(400L, window.remaining());

		window.advance(1L);
		assertEquals(400L, window.remaining());
		window.advance(2L);
		assertEquals(400L, window.remaining());

		assertTrue(window.trySpend(400L));
		assertEquals(0L, window.remaining());

		window.advance(3L);
		assertEquals(100L, window.remaining()); // 400 - 300 excess
	}

	@Test
	void rejectedSpendLeavesTheWindowUnchanged() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 2);
		assertTrue(window.trySpend(300L));
		assertFalse(window.trySpend(1L));
		assertEquals(300L, window.spent());
		assertEquals(0L, window.remaining());

		assertThrows(IllegalArgumentException.class, () -> window.trySpend(-1L));
		assertEquals(300L, window.spent());
		assertEquals(0L, window.remaining());
	}

	@Test
	void advanceRejectsBackwardAndRepeatedPeriods() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 2);
		assertThrows(IllegalArgumentException.class, () -> window.advance(0L));
		assertThrows(IllegalArgumentException.class, () -> window.advance(-1L));

		window.advance(2L);
		assertThrows(IllegalArgumentException.class, () -> window.advance(2L));
		assertThrows(IllegalArgumentException.class, () -> window.advance(1L));
	}

	@Test
	void idleGapAtTheWindowEdgeKeepsOrDropsTheExcess() {
		InventoryWireWindow kept = new InventoryWireWindow(100L, 3);
		assertTrue(kept.trySpend(400L)); // excess 300
		kept.advance(2L);                // gap 2 = n - 1, still in the window
		assertEquals(100L, kept.remaining());

		InventoryWireWindow dropped = new InventoryWireWindow(100L, 3);
		assertTrue(dropped.trySpend(400L)); // excess 300
		dropped.advance(3L);                // gap 3 = n, outside the window
		assertEquals(400L, dropped.remaining());
	}

	@Test
	void hugeIdleSkipClearsAllRetainedExcess() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 32);
		assertTrue(window.trySpend(3300L));
		assertEquals(0L, window.remaining());

		window.advance(1_000_000L);
		assertEquals(0L, window.spent());
		assertEquals(3300L, window.remaining());

		window.advance(Long.MAX_VALUE);
		assertEquals(3300L, window.remaining());
	}

	@Test
	void ringWrapMatchesTheHandComputedThreePeriodCycle() {
		InventoryWireWindow window = new InventoryWireWindow(100L, 3);
		for (long period = 0L; period < 30L; period++) {
			long expected = period % 3L == 0L ? 400L : 100L;
			assertEquals(expected, window.remaining(), "period " + period);
			assertTrue(window.trySpend(expected), "period " + period);
			assertEquals(0L, window.remaining(), "period " + period);
			window.advance(period + 1L);
		}
	}

	@Test
	void constructorRejectsConfigurationWhosePeakOverflows() {
		InventoryWireWindow largest = new InventoryWireWindow(Long.MAX_VALUE / 33L, 32);
		long peak = largest.remaining();
		assertTrue(peak > 0L);
		assertTrue(largest.trySpend(peak));

		assertThrows(IllegalArgumentException.class, () -> new InventoryWireWindow(Long.MAX_VALUE, 1));
		assertThrows(IllegalArgumentException.class, () -> new InventoryWireWindow(Long.MAX_VALUE / 2L, 3));
	}
}
