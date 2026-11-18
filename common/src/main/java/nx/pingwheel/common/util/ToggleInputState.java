package nx.pingwheel.common.util;

import org.lwjgl.glfw.GLFW;

/** Carries the native callback's press eligibility to its mapping click, if any. */
public final class ToggleInputState {
	private boolean pendingPress;

	public void beginCallback(int action, boolean activeWindow, boolean screenOpen) {
		// Use the physical action, not a KeyMapping click (which keyboard repeat
		// can also produce). A screen, focus, or mapping reset cannot turn a
		// repeat into a press, even if the original edge never reached KeyMapping.
		pendingPress = action == GLFW.GLFW_PRESS && activeWindow && !screenOpen;
	}

	public boolean claimMappingClick(boolean screenOpen) {
		// Vanilla may open a screen during the press, so check the destination
		// as well as the origin. An edge may authorize at most one click.
		boolean claimed = pendingPress && !screenOpen;
		pendingPress = false;
		return claimed;
	}
}
