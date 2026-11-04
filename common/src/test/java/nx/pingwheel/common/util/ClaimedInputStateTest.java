package nx.pingwheel.common.util;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class ClaimedInputStateTest {

	@Test
	void unrelatedAndPressTransitionsDoNotReleaseClaim() {
		ClaimedInputState<String> state = new ClaimedInputState<>();
		state.arm("old");

		assertFalse(state.observe("other", false));
		assertFalse(state.observe("old", true));
		assertTrue(state.isArmed());
	}

	@Test
	void releaseUsesTheRawKeyCapturedBeforeRebinding() {
		ClaimedInputState<String> state = new ClaimedInputState<>();
		state.arm("old");

		assertFalse(state.observe("new", false));
		assertTrue(state.observe("old", false));
		assertFalse(state.isArmed());
	}

	@Test
	void resetDisarmsWithoutEmittingARelease() {
		ClaimedInputState<String> state = new ClaimedInputState<>();
		state.arm("old");
		state.reset();

		assertFalse(state.observe("old", false));
		assertFalse(state.isArmed());
	}
}
