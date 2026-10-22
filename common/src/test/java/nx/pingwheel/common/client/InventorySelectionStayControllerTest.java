package nx.pingwheel.common.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySelectionStayControllerTest {

	@Test void firstReleaseEntersStayThenOneShortTapCommits() {
		InventorySelectionStayController stay = new InventorySelectionStayController();
		stay.begin(250);
		assertTrue(stay.press(1000));
		assertEquals(InventorySelectionStayController.ReleaseDecision.COMMIT, stay.release(1100));
		assertFalse(stay.active());
		assertFalse(stay.consuming());
	}

	@Test void thresholdReleaseWithoutAFrameCancelsInsteadOfSubmitting() {
		InventorySelectionStayController stay = new InventorySelectionStayController();
		stay.begin(250);
		stay.press(1000);
		assertEquals(InventorySelectionStayController.ReleaseDecision.CANCEL, stay.release(1250));
		assertFalse(stay.active());
	}

	@Test void frameCanCancelAStillHeldTapAndThenSwallowsItsRelease() {
		InventorySelectionStayController stay = new InventorySelectionStayController();
		stay.begin(250);
		stay.press(1000);
		assertTrue(stay.cancelOnFrame(1250));
		assertFalse(stay.active());
		assertTrue(stay.consuming());
		assertTrue(stay.press(1260), "a repeated press of the same physical hold stays consumed");
		assertEquals(InventorySelectionStayController.ReleaseDecision.NONE, stay.release(1270));
		assertFalse(stay.consuming());
	}

	@Test void pathExitWhileHeldSwallowsTheRemainingEdges() {
		InventorySelectionStayController stay = new InventorySelectionStayController();
		stay.begin(250);
		stay.press(1000);
		stay.closeForPathExit();
		assertFalse(stay.active());
		assertTrue(stay.consuming());
		assertEquals(InventorySelectionStayController.ReleaseDecision.NONE, stay.release(1010));
		assertFalse(stay.consuming());
	}

	@Test void lifecycleResetForgetsEverything() {
		InventorySelectionStayController stay = new InventorySelectionStayController();
		stay.begin(250);
		stay.press(1000);
		stay.reset();
		assertFalse(stay.active());
		assertFalse(stay.consuming());
		assertEquals(InventorySelectionStayController.ReleaseDecision.NONE, stay.release(2000));
	}
}
