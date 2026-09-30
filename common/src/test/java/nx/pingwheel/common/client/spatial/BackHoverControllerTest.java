package nx.pingwheel.common.client.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure, deterministic tests for {@link BackHoverController}. Every case drives
 * the controller with explicit timestamps and relative to the duration it was
 * started with, so the assertions describe behavior rather than mirror the
 * prototype's literal defaults. No game client, renderer, or wall clock is
 * involved.
 */
class BackHoverControllerTest {

	private static final double DELTA = 1.0e-9;

	@Test
	void progressBeforeThresholdDoesNotTrigger() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 1000L);

		BackHoverController.Result first = controller.update("root", true, 10_000L);
		assertEquals(0.0, first.progress(), DELTA);
		assertFalse(first.triggerPop());

		BackHoverController.Result quarter = controller.update("root", true, 10_250L);
		assertEquals(0.25, quarter.progress(), DELTA);
		assertFalse(quarter.triggerPop());

		BackHoverController.Result almost = controller.update("root", true, 10_999L);
		assertEquals(0.999, almost.progress(), DELTA);
		assertFalse(almost.triggerPop());
	}

	@Test
	void thresholdTriggersExactlyOnceAndNeverRepeatsOnHeldFocus() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 800L);
		controller.update("root", true, 0L);

		BackHoverController.Result atThreshold = controller.update("root", true, 800L);
		assertEquals(1.0, atThreshold.progress(), DELTA);
		assertTrue(atThreshold.triggerPop());

		// The caller pops exactly one level; the pointer is still in the Back
		// direction, so a held focus must never arm a second pop.
		BackHoverController.Result held = controller.update("root", true, 5_000L);
		assertEquals(0.0, held.progress(), DELTA);
		assertFalse(held.triggerPop());
	}

	@Test
	void leaveAndReenterRestartsHoldClock() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 500L);

		controller.update("root", true, 1_000L);
		assertFalse(controller.update("root", true, 1_400L).triggerPop());

		BackHoverController.Result left = controller.update("root", false, 1_450L);
		assertEquals(0.0, left.progress(), DELTA);

		BackHoverController.Result reentered = controller.update("root", true, 2_000L);
		assertEquals(0.0, reentered.progress(), DELTA);
		assertFalse(reentered.triggerPop());

		BackHoverController.Result before = controller.update("root", true, 2_499L);
		assertEquals(0.998, before.progress(), DELTA);
		assertFalse(before.triggerPop());

		assertTrue(controller.update("root", true, 2_500L).triggerPop());
	}

	@Test
	void parentBackStaysBlockedWhileHeldAndRearmsAfterFocusLoss() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 300L);
		controller.update("child", true, 0L);
		assertTrue(controller.update("child", true, 300L).triggerPop());

		// After the pop the cursor already sits in the parent's Back direction,
		// so focus is held across the menu change; it must not cascade.
		BackHoverController.Result parentHeld = controller.update("parent", true, 350L);
		assertEquals(0.0, parentHeld.progress(), DELTA);
		assertFalse(parentHeld.triggerPop());

		BackHoverController.Result stillHeld = controller.update("parent", true, 100_000L);
		assertEquals(0.0, stillHeld.progress(), DELTA);
		assertFalse(stillHeld.triggerPop());

		// Leaving the Back direction releases the block; a deliberate re-entry
		// arms a new one-level return.
		assertFalse(controller.update("parent", false, 100_100L).triggerPop());
		controller.update("parent", true, 100_200L);
		assertFalse(controller.update("parent", true, 100_499L).triggerPop());
		assertTrue(controller.update("parent", true, 100_500L).triggerPop());
	}

	@Test
	void switchingFocusedMenuRestartsHoldClock() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 600L);

		controller.update("first", true, 0L);
		assertFalse(controller.update("first", true, 500L).triggerPop());

		BackHoverController.Result switched = controller.update("second", true, 500L);
		assertEquals(0.0, switched.progress(), DELTA);
		assertFalse(switched.triggerPop());

		assertFalse(controller.update("second", true, 1_099L).triggerPop());
		assertTrue(controller.update("second", true, 1_100L).triggerPop());
	}

	@Test
	void rewoundClockIsClampedInsteadOfResettingBaseline() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 400L);
		controller.update("root", true, 1_000L);

		BackHoverController.Result rewound = controller.update("root", true, 250L);
		assertEquals(0.0, rewound.progress(), DELTA);
		assertFalse(rewound.triggerPop());

		BackHoverController.Result forward = controller.update("root", true, 1_399L);
		assertEquals(0.9975, forward.progress(), DELTA);
		assertFalse(forward.triggerPop());

		assertTrue(controller.update("root", true, 1_400L).triggerPop());
	}

	@Test
	void endClearsTimerAndBlockedStateAndIsIdempotent() {
		BackHoverController controller = new BackHoverController();
		controller.start(true, 500L);
		controller.update("root", true, 0L);
		controller.update("root", true, 250L);

		controller.end();
		controller.end();
		assertFalse(controller.isActive());

		BackHoverController.Result afterEnd = controller.update("root", true, 250L);
		assertEquals(0.0, afterEnd.progress(), DELTA);
		assertFalse(afterEnd.triggerPop());

		// A fresh session after an end() is not blocked by the old pop.
		controller.start(true, 200L);
		controller.update("root", true, 10L);
		assertTrue(controller.update("root", true, 210L).triggerPop());

		// end() must release the block armed by that trigger too.
		controller.end();
		controller.start(true, 200L);
		controller.update("root", true, 0L);
		assertFalse(controller.update("root", true, 199L).triggerPop());
		assertTrue(controller.update("root", true, 200L).triggerPop());
	}

	@Test
	void disabledSessionNeverArmsOrTriggers() {
		BackHoverController controller = new BackHoverController();
		controller.start(false, 100L);
		assertTrue(controller.isActive());

		for (long now : new long[] {0L, 50L, 100L, 10_000L}) {
			BackHoverController.Result result = controller.update("root", true, now);
			assertEquals(0.0, result.progress(), DELTA);
			assertFalse(result.triggerPop());
		}
	}

	@Test
	void updateBeforeStartIsInert() {
		BackHoverController controller = new BackHoverController();
		assertFalse(controller.isActive());

		BackHoverController.Result result = controller.update("root", true, 42L);
		assertEquals(0.0, result.progress(), DELTA);
		assertFalse(result.triggerPop());
	}

	@Test
	void parametersAreFrozenAtStart() {
		BackHoverController controller = new BackHoverController();
		long configuredDuration = 900L;
		controller.start(true, configuredDuration);

		// Changing the caller's variable cannot alter the running session.
		configuredDuration = 1L;

		controller.update("root", true, 0L);
		assertFalse(controller.update("root", true, 500L).triggerPop());
		assertTrue(controller.update("root", true, 900L).triggerPop());
	}
}
