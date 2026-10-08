package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import static org.junit.jupiter.api.Assertions.*;

class WheelMouseCaptureLifecycleTest {
	/**
	 * Recording cursor seam. Grab transitions stay in {@code events} (the
	 * pre-existing assertions) while explicit cursor-mode writes are recorded
	 * separately in {@code modeEvents}, so release/grab recording is independent
	 * from the cursor-visibility state under test. {@code grab()} mirrors the
	 * production {@code MouseHandler#grabMouse()} window-active guard.
	 */
	private static final class Mouse implements WheelMouseCapture.MouseAccess {
		record ModeChange(long window, int mode) {}
		boolean screen, grabbed = true, windowActive = true;
		long window = 1L;
		int mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		final List<String> events = new ArrayList<>();
		final List<ModeChange> modeEvents = new ArrayList<>();
		WheelMouseCapture owner;
		Runnable released = () -> {};
		public boolean screenOpen() { return screen; }
		public boolean grabbed() { return grabbed; }
		public long window() { return window; }
		public int cursorMode() { return mode; }
		public void setCursorMode(int next) { modeEvents.add(new ModeChange(window, next)); mode = next; }
		public void release() {
			assertTrue(owner.isTransitioning()); events.add("release"); grabbed = false;
			mode = WheelMouseCapture.CURSOR_MODE_NORMAL; released.run();
		}
		public void grab() {
			assertTrue(owner.isTransitioning()); events.add("grab");
			if (windowActive) { grabbed = true; mode = WheelMouseCapture.CURSOR_MODE_DISABLED; }
		}
	}
	@Test void normalCloseReclaimsExactlyItsReleaseAndRepeatedCloseIsInert() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		capture.sync(PingInteractionPhase.IDLE, mouse); capture.close(mouse, false); capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release", "grab"), mouse.events);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
		assertFalse(capture.isTransitioning());
	}
	@Test void incomingScreenDisposesBeforeAssignmentAndCannotStealBackCursorLater() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertFalse(mouse.screen, "the setScreen HEAD still sees the old null screen");
		capture.close(mouse, true); mouse.screen = true; capture.sync(PingInteractionPhase.IDLE, mouse);
		mouse.screen = false; capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release"), mouse.events);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
	}
	@Test void screenOwnedCursorIsNeverHiddenEvenWhenTheMouseIsFree() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.screen = true;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertTrue(mouse.events.isEmpty()); assertTrue(mouse.modeEvents.isEmpty());
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		mouse.grabbed = false; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertTrue(mouse.modeEvents.isEmpty()); assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
	}
	@Test void screenTakeoverRestoresAndVanillaRegrabHidesAgainMidHold() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		capture.close(mouse, true);
		assertEquals(List.of("release"), mouse.events);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
		mouse.screen = true; capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(2, mouse.modeEvents.size(), "a screen-open hold never hides over the screen");
		// Vanilla re-grabs after the screen closes; the still-open wheel releases
		// and hides again on the next sync.
		mouse.screen = false; mouse.grabbed = true; mouse.mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(List.of("release", "release"), mouse.events);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents);
	}
	@Test void windowReplacementDropsHideOwnershipAndNewWindowIsNeverTouched() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents);
		mouse.window = 2L; mouse.grabbed = false; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents,
			"a replaced window's cursor mode is never touched");
		// A fresh grabbed mouse on the replacement window is released and hidden on its own.
		mouse.grabbed = true; mouse.mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(2L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents);
		capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(2L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(2L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
	}
	@Test void focusLossDisposalRestoresVisibleCursorWhileInactiveRegrabStaysInert() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		mouse.windowActive = false;
		capture.close(mouse, false);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		assertFalse(mouse.grabbed);
		assertEquals(List.of("release", "grab"), mouse.events, "the regrab is requested but vanilla's inactive-window guard makes it inert");
	}
	@Test void newerOwnerCursorModeIsNeverOverriddenAndSuppressesRehide() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		// A newer owner changes the mode first; the still-open wheel must neither
		// restore over it nor hide over it again.
		mouse.mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents);
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		mouse.windowActive = false;
		capture.close(mouse, false);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents);
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		// The suppression ends with the hold: the next hold hides its own free pointer again.
		mouse.windowActive = true; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(2, mouse.modeEvents.size());
		assertEquals(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN), mouse.modeEvents.getLast());
	}
	@Test void freshOwnedReleaseClearsSuppressionAndHidesAgain() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN)), mouse.modeEvents,
			"a newer visible mode is not hidden over");
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		// The mouse is grabbed again; the wheel's own release re-establishes the hide.
		mouse.grabbed = true; mouse.mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(2, mouse.modeEvents.size());
		assertEquals(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN), mouse.modeEvents.getLast());
	}
	@Test void unownedFreeCursorIsNeverGrabbedByIdleDispose() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.grabbed = false; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.close(mouse, false);
		assertTrue(mouse.events.isEmpty()); assertFalse(mouse.grabbed);
		// The open selector owns the free pointer, so it hides the OS cursor and
		// restores it on disposal without claiming a release it never made.
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
	}
	@Test void disposalReenteredFromReleaseDoesNotReacquireStaleOwnership() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.released = () -> capture.close(mouse, false);
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release", "grab"), mouse.events);
		assertTrue(mouse.modeEvents.isEmpty(), "a reentrant dispose already consumed the release before any hide");
		assertFalse(capture.isTransitioning());
	}
}
