package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finite, caller-timed semantics of the selector's single rigid view
 * translation: exact endpoints, continuous re-aiming, reduced-motion snapping,
 * an inert rewound clock and a hard reset. Oracles use caller-set durations and
 * endpoints rather than renderer tuning.
 */
class SpatialViewOffsetTest {

	private static final long DURATION = 1000L;

	private static SpatialViewOffset offset() {
		return new SpatialViewOffset(DURATION);
	}

	@Test
	void firstAimSnapsAndReaimStartsFromTheDisplayedTranslation() {
		var offset = offset();
		offset.centerOn(0.0, 0.0, 0L, false);
		assertEquals(0.0, offset.x());
		assertEquals(0.0, offset.y());

		offset.centerOn(30.0, -40.0, 100L, false);
		assertEquals(0.0, offset.x(), 1.0e-12, "a re-aim starts at the displayed translation");
		assertEquals(0.0, offset.y(), 1.0e-12);
		offset.sample(600L);
		assertEquals(-15.0, offset.x(), 1.0e-12);
		assertEquals(20.0, offset.y(), 1.0e-12);
		offset.sample(1100L);
		assertEquals(-30.0, offset.x());
		assertEquals(40.0, offset.y());
		offset.sample(9000L);
		assertEquals(-30.0, offset.x());
		assertEquals(40.0, offset.y());
	}

	@Test
	void unchangedOriginNeverRestartsTheRunningInterval() {
		var offset = offset();
		offset.centerOn(0.0, 0.0, 0L, false);
		offset.centerOn(60.0, 0.0, 0L, false);
		offset.sample(500L);
		double midpoint = offset.x();
		assertTrue(midpoint < 0.0 && midpoint > -60.0);

		offset.centerOn(60.0, 0.0, 500L, false);
		offset.sample(500L);
		assertEquals(midpoint, offset.x(), 1.0e-12, "an unchanged aim keeps the running interval");
		offset.sample(1000L);
		assertEquals(-60.0, offset.x(), 1.0e-12, "the original endpoint is still reached exactly");
	}

	@Test
	void reducedMotionSnapsImmediatelyToTheNewEndpoint() {
		var offset = offset();
		offset.centerOn(0.0, 0.0, 0L, false);
		offset.centerOn(60.0, 0.0, 0L, false);
		offset.sample(500L);
		assertTrue(offset.x() < 0.0 && offset.x() > -60.0);

		offset.centerOn(-25.0, 15.0, 500L, true);
		assertEquals(25.0, offset.x());
		assertEquals(-15.0, offset.y());
		offset.sample(900L);
		assertEquals(25.0, offset.x());
		assertEquals(-15.0, offset.y());
	}

	@Test
	void rewoundClockCannotRewindTheDisplayedTranslationAndALargeGapCompletes() {
		var offset = offset();
		offset.centerOn(0.0, 0.0, 0L, false);
		offset.centerOn(60.0, 0.0, 1000L, false);
		offset.sample(1500L);
		double midpoint = offset.x();
		assertTrue(midpoint < 0.0 && midpoint > -60.0);

		offset.sample(100L);
		assertEquals(midpoint, offset.x(), 1.0e-12, "a rewound clock is inert");
		offset.sample(1_000_000L);
		assertEquals(-60.0, offset.x());
	}

	@Test
	void clearDisposesPriorStateAndTheNextAimSnapsAgain() {
		var offset = offset();
		offset.centerOn(60.0, -20.0, 0L, false);
		offset.sample(500L);
		offset.clear();
		assertEquals(0.0, offset.x());
		assertEquals(0.0, offset.y());

		offset.centerOn(-25.0, 15.0, 0L, false);
		assertEquals(25.0, offset.x());
		assertEquals(-15.0, offset.y());
	}

	@Test
	void invalidBoundsAndNonFiniteOriginsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new SpatialViewOffset(0L));
		assertThrows(IllegalArgumentException.class, () -> new SpatialViewOffset(-1L));
		var offset = offset();
		assertThrows(IllegalArgumentException.class, () -> offset.centerOn(Double.NaN, 0.0, 0L, false));
		assertThrows(IllegalArgumentException.class, () -> offset.centerOn(0.0, Double.POSITIVE_INFINITY, 0L, false));
	}
}
