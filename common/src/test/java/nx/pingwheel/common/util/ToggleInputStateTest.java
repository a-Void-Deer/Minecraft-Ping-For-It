package nx.pingwheel.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

class ToggleInputStateTest {

	@Test
	void guiPressAndReleaseConsumedBeforeMappingsCannotTurnAnInGameRepeatIntoAPress() {
		ToggleInputState state = new ToggleInputState();

		state.beginCallback(GLFW_PRESS, true, true); // screen consumes it; no mapping callback
		state.beginCallback(GLFW_REPEAT, true, false); // screen closed before the next repeat
		assertFalse(state.claimMappingClick(false));
		state.beginCallback(GLFW_REPEAT, true, false);
		assertFalse(state.claimMappingClick(false));

		state.beginCallback(GLFW_RELEASE, true, true); // release can also be consumed by a screen
		state.beginCallback(GLFW_PRESS, true, false);
		assertTrue(state.claimMappingClick(false));
		assertFalse(state.claimMappingClick(false), "one physical press authorizes only one mapping click");
	}

	@Test
	void aScreenAtEitherBoundaryOfTheSamePressPreventsAToggle() {
		ToggleInputState state = new ToggleInputState();

		state.beginCallback(GLFW_PRESS, true, true);
		assertFalse(state.claimMappingClick(false), "a non-consuming GUI handler may still close its screen");
		state.beginCallback(GLFW_PRESS, true, false);
		assertFalse(state.claimMappingClick(true), "a vanilla action may open a screen before posting the click");
	}

	@Test
	void losingFocusDoesNotTurnARepeatIntoAPressAndAFreshFocusedPressStillWorks() {
		ToggleInputState state = new ToggleInputState();

		state.beginCallback(GLFW_PRESS, true, false);
		assertTrue(state.claimMappingClick(false));
		state.beginCallback(GLFW_RELEASE, false, false); // GLFW can synthesize release on blur
		state.beginCallback(GLFW_REPEAT, true, false); // whatever the binding now, no new press
		assertFalse(state.claimMappingClick(false));

		state.beginCallback(GLFW_PRESS, false, false);
		assertFalse(state.claimMappingClick(false));
		state.beginCallback(GLFW_PRESS, true, false);
		assertTrue(state.claimMappingClick(false));
	}
}
