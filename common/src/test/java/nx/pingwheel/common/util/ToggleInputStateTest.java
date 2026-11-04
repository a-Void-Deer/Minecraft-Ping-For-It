package nx.pingwheel.common.util;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class ToggleInputStateTest {

	@Test
	void repeatedPressesDoNotToggleUntilThePhysicalKeyIsReleased() {
		ToggleInputState<String> state = new ToggleInputState<>();

		assertTrue(state.claimPress("shift"));
		assertFalse(state.claimPress("shift"));
		state.release("shift");
		assertTrue(state.claimPress("shift"));
	}

	@Test
	void suppressedPressBlocksRepeatsUntilReleaseThenAllowsTheNextValidPress() {
		ToggleInputState<String> state = new ToggleInputState<>();

		assertTrue(state.suppressPress("shift"));
		assertFalse(state.suppressPress("shift"));
		assertFalse(state.claimPress("shift"));
		state.release("shift");
		assertTrue(state.claimPress("shift"));
	}

	@Test
	void resetRearmsEveryPhysicalKeyWithoutChangingAnySetting() {
		ToggleInputState<String> state = new ToggleInputState<>();

		assertTrue(state.claimPress("shift"));
		assertTrue(state.claimPress("ctrl"));
		state.reset();
		assertTrue(state.claimPress("shift"));
		assertTrue(state.claimPress("ctrl"));
	}
}
