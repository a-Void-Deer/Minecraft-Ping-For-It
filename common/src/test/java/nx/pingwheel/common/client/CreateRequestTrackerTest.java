package nx.pingwheel.common.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the single-slot latest-create-request tracker: it decides
 * which authoritative TARGET_GONE rejection may surface the local error.
 */
final class CreateRequestTrackerTest {

	@Test
	void emptyTrackerNeverMatches() {
		CreateRequestTracker tracker = new CreateRequestTracker();

		assertTrue(tracker.isEmpty());
		assertFalse(tracker.isLatest(0L));
		assertFalse(tracker.isLatest(-1L));
		assertFalse(tracker.isLatest(Long.MAX_VALUE));
	}

	@Test
	void latestCreateMatchesExactly() {
		CreateRequestTracker tracker = new CreateRequestTracker();

		tracker.onCreateDispatched(7L);

		assertFalse(tracker.isEmpty());
		assertEquals(7L, tracker.latestRequestId());
		assertTrue(tracker.isLatest(7L));
		assertFalse(tracker.isLatest(6L));
		assertFalse(tracker.isLatest(8L));
	}

	@Test
	void newestCreateSupersedesOlderOne() {
		CreateRequestTracker tracker = new CreateRequestTracker();

		tracker.onCreateDispatched(7L);
		tracker.onCreateDispatched(9L);

		assertTrue(tracker.isLatest(9L));
		assertFalse(tracker.isLatest(7L));
		assertEquals(9L, tracker.latestRequestId());
	}

	@Test
	void reDispatchSameIdStillLatest() {
		CreateRequestTracker tracker = new CreateRequestTracker();

		tracker.onCreateDispatched(5L);
		tracker.onCreateDispatched(5L);

		assertTrue(tracker.isLatest(5L));
	}
}
