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
		// Screen closed after this controller released the cursor: vanilla
		// re-grabbed it while the wheel stayed open, so release it again.
		assertSame(WheelMouseCapture.Action.RELEASE, WheelMouseCapture.nextAction(true, true, false, true));
	}

	@Test
	void openWheelWithClaimedReleaseAndFreeCursorDoesNotReleaseAgain() {
		// No duplicate release while the cursor is already free.
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, true, false, false));
	}

	@Test
	void openWheelWithScreenOpenDoesNotRelease() {
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, true, true));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, true, true, true));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, true, false));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, true, true, false));
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

	@Test
	void screenCloseAfterInitialReleaseEventuallyReleasesAgainThenGrabsOnClose() {
		// The screen can open after the wheel has already claimed a release.
		assertSame(WheelMouseCapture.Action.RELEASE, WheelMouseCapture.nextAction(true, false, false, true));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, true, true, false));
		// Closing the screen lets vanilla grab the cursor; the open wheel must
		// reclaim it without losing ownership of the eventual re-grab.
		assertSame(WheelMouseCapture.Action.RELEASE, WheelMouseCapture.nextAction(true, true, false, true));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, true, false, false));
		assertSame(WheelMouseCapture.Action.GRAB, WheelMouseCapture.nextAction(false, true, false, false));
	}

	@Test
	void screenCloseBeforeInitialReleaseProtectsScreenThenReleasesAndGrabs() {
		// If a screen is open when the wheel first appears, it must not steal
		// the cursor. Once the screen closes, the open wheel can release it.
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, true, true));
		assertSame(WheelMouseCapture.Action.RELEASE, WheelMouseCapture.nextAction(true, false, false, true));
		assertSame(WheelMouseCapture.Action.NONE, WheelMouseCapture.nextAction(true, false, false, false));
		assertSame(WheelMouseCapture.Action.GRAB, WheelMouseCapture.nextAction(false, true, false, false));
	}
}
