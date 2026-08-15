package nx.pingwheel.common.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pure tests for the {@link WheelMouseCapture} transition policy. Only the
 * static {@link WheelMouseCapture#nextAction(boolean, boolean, boolean, boolean)}
 * function is exercised here; the Minecraft {@code MouseHandler} glue in
 * {@code sync}/{@code close} is compile coverage only and cannot run without a
 * game client.
 *
 * <p>The policy is stateless over one sync snapshot: the same
 * (isOpen, releasedByWheel, screenOpen, mouseGrabbed) tuple always yields the
 * same action, whether the wheel just opened or stayed open across a screen
 * closing mid-hold.
 */
class WheelMouseCaptureTest {

	@Test
	void enteringWheelWithGrabbedMouseReleases() {
		assertSame(WheelMouseCapture.Action.RELEASE, WheelMouseCapture.nextAction(true, false, false, true));
	}

	@Test
	void stayingOpenAfterScreenCloseReleasesRegrabbedMouse() {
		// Screen closed mid-hold: vanilla re-grabbed the cursor while the
		// wheel stayed open and this controller never claimed a release, so
		// the grabbed cursor must be released and claimed now, even though
		// there was no closed->open wheel transition.
		assertSame(WheelMouseCapture.Action.RELEASE, WheelMouseCapture.nextAction(true, false, false, true));
	}

	@Test
	void openWheelWithClaimedReleaseDoesNotReleaseAgain() {
		// No double release: a claimed release stays claimed.
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, true, false, true));
	}

	@Test
	void openWheelWithScreenOpenDoesNotRelease() {
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, true, true));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, true, false));
	}

	@Test
	void openWheelWithFreeCursorDoesNothing() {
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, false, false));
	}

	@Test
	void leavingWheelWithoutOwnedReleaseDoesNothing() {
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(false, false, false, false));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(false, false, false, true));
	}

	@Test
	void leavingWheelWithOwnedReleaseRegrabsWithoutScreen() {
		assertSame(WheelMouseCapture.Action.GRAB, WheelMouseCapture.nextAction(false, true, false, false));
		// The 1.21.1 grab is idempotent, so an already grabbed cursor is fine
		// too: the claim is dropped and grabMouse() is a no-op.
		assertSame(WheelMouseCapture.Action.GRAB, WheelMouseCapture.nextAction(false, true, false, true));
	}

	@Test
	void leavingWheelWithOpenScreenDefersRegrab() {
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(false, true, true, false));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(false, true, true, true));
	}

	@Test
	void pendingRegrabKeepsWaitingWhileScreenOpen() {
		// After the wheel closed with a screen open, later idle ticks must keep
		// deferring until the screen is gone, then regrab.
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(false, true, true, false));
		assertSame(WheelMouseCapture.Action.GRAB, WheelMouseCapture.nextAction(false, true, false, false));
	}
}
