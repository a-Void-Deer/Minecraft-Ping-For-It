package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import static org.junit.jupiter.api.Assertions.*;

class WheelMouseCaptureLifecycleTest {
	/**
	 * Minecraft 1.21.1 release/grab ordering plus GLFW 3.4 Win32 positions:
	 * a disabled-mode setCursorPos changes only virtual coordinates; leaving
	 * disabled mode restores the pre-grab physical position. Hidden mode hides
	 * only inside the content area. No native window or game is launched.
	 */
	private static final class Mouse implements WheelMouseCapture.MouseAccess {
		record ModeChange(long window, int mode) {}
		boolean screen, grabbed = true, windowActive = true;
		long window = 1L;
		int mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		double physicalX = 600, physicalY = 400, restoreX = -40, restoreY = -20;
		double virtualX = 4000, virtualY = -3000, xpos = 4000, ypos = -3000;
		double accumulatedDX, accumulatedDY, cameraX, cameraY;
		boolean ignoreFirstMove;
		final List<Boolean> rawButtonEdges = new ArrayList<>();
		final List<WheelMouseCapture.CursorPosition> guiMoves = new ArrayList<>();
		final List<String> nativeEvents = new ArrayList<>();
		final NativeSelectorInput input = new NativeSelectorInput(new NativeSelectorInput.Sink() {
			public void moveGui(double x, double y, long time) { guiMoves.add(new WheelMouseCapture.CursorPosition(x, y)); }
			public void scrollRows(double rows, long time) {}
		});
		final List<String> events = new ArrayList<>();
		final List<ModeChange> modeEvents = new ArrayList<>();
		WheelMouseCapture owner;
		Runnable released = () -> {};
		Runnable modeChanged = () -> {};
		public boolean screenOpen() { return screen; }
		public boolean grabbed() { return grabbed; }
		public boolean focused() { return windowActive; }
		public long window() { return window; }
		public int cursorMode() { return mode; }
		public void setCursorMode(int next) { modeEvents.add(new ModeChange(window, next)); nativeMode(next); modeChanged.run(); }
		public void setCursorPosition(WheelMouseCapture.CursorPosition position) { nativePosition(position.x(), position.y()); }
		private void nativePosition(double x, double y) {
			nativeEvents.add("position:" + x + ":" + y + ":" + mode);
			if (!windowActive) return;
			if (mode == WheelMouseCapture.CURSOR_MODE_DISABLED) { virtualX = x; virtualY = y; }
			else { physicalX = x; physicalY = y; }
		}
		private void nativeMode(int next) {
			nativeEvents.add("mode:" + next);
			if (next == mode) return;
			int previous = mode; mode = next;
			virtualX = physicalX; virtualY = physicalY;
			if (previous == WheelMouseCapture.CURSOR_MODE_DISABLED) { physicalX = restoreX; physicalY = restoreY; }
			if (next == WheelMouseCapture.CURSOR_MODE_DISABLED && windowActive) {
				restoreX = physicalX; restoreY = physicalY; physicalX = 600; physicalY = 400;
			}
		}
		boolean arrowVisible() {
			boolean inside = physicalX >= 0 && physicalX < 1200 && physicalY >= 0 && physicalY < 800;
			return mode == WheelMouseCapture.CURSOR_MODE_NORMAL || !inside;
		}
		private void beforeCapture() { accumulatedDX = accumulatedDY = 0; input.rePrime(); }
		private void vanillaModeAndWarp(int next, double x, double y) { nativePosition(x, y); nativeMode(next); }
		public void release() {
			assertTrue(owner.isTransitioning()); events.add("release"); beforeCapture();
			var position = new WheelMouseCapture.CursorPosition(physicalX, physicalY);
			owner.releaseWithPosition(this, position, () -> {
				if (grabbed) {
					grabbed = false; xpos = 600; ypos = 400;
					var bias = WheelMouseCapture.releaseCursor(window, WheelMouseCapture.CURSOR_MODE_NORMAL, xpos, ypos);
					if (bias != null) { xpos = bias.x(); ypos = bias.y(); }
				}
				released.run(); input.rePrime();
			});
		}
		public void grab() {
			assertTrue(owner.isTransitioning()); events.add("grab"); beforeCapture();
			if (windowActive && !grabbed) {
				grabbed = true; xpos = 600; ypos = 400;
				vanillaModeAndWarp(WheelMouseCapture.CURSOR_MODE_DISABLED, xpos, ypos);
				ignoreFirstMove = true;
			}
			input.rePrime();
		}
		void move(double x, double y) {
			physicalX = x; physicalY = y;
			if (ignoreFirstMove) ignoreFirstMove = false;
			else if (windowActive) { accumulatedDX += x - xpos; accumulatedDY += y - ypos; }
			xpos = x; ypos = y;
			input.onMove(window, x, y, new NativeSelectorInput.Frame(window, 1200, 800, 600, 400, windowActive, grabbed), 0);
		}
		void handleAccumulatedMovement() {
			if (grabbed) { cameraX += accumulatedDX; cameraY += accumulatedDY; }
			accumulatedDX = accumulatedDY = 0;
		}
		void press(boolean down, PingInteractionPhase phase) {
			// Vanilla onPress calls grabMouse before KeyMapping.set/click. Cancel
			// only that grab; the mouse-bound hold must still receive its release.
			if (!screen && !grabbed && down && !WheelMouseCapture.preventVanillaGrab(phase, screen, windowActive)) grab();
			rawButtonEdges.add(down);
		}
	}
	@Test void normalCloseReclaimsExactlyItsReleaseAndRepeatedCloseIsInert() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		capture.sync(PingInteractionPhase.IDLE, mouse); capture.close(mouse, false); capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release", "grab"), mouse.events);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
		assertTrue(mouse.grabbed); assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
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
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
		assertTrue(mouse.grabbed); assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		assertFalse(capture.isTransitioning());
	}
	@Test void vanillaReleaseThenHideRestoresAnOutsidePointerDespiteItsRecenterRequest() {
		var mouse = new Mouse(); mouse.grabbed = false; mouse.xpos = 600; mouse.ypos = 400;
		mouse.vanillaModeAndWarp(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.xpos, mouse.ypos);
		mouse.setCursorMode(WheelMouseCapture.CURSOR_MODE_HIDDEN);
		assertEquals(-40, mouse.physicalX); assertEquals(-20, mouse.physicalY);
		assertTrue(mouse.arrowVisible(), "GLFW hidden mode does not hide a cursor outside the content area");
	}
	@Test void ownedReleaseHidesBeforeLeavingDisabledAndKeepsPhysicalBiasNotVirtualPosition() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.physicalX = 614; mouse.physicalY = 408; mouse.input.open();
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertFalse(mouse.grabbed); assertFalse(mouse.arrowVisible());
		assertEquals(614, mouse.physicalX); assertEquals(408, mouse.physicalY);
		assertEquals(614, mouse.xpos); assertEquals(408, mouse.ypos);
		assertEquals(List.of("mode:" + WheelMouseCapture.CURSOR_MODE_HIDDEN,
			"position:614.0:408.0:" + WheelMouseCapture.CURSOR_MODE_HIDDEN), mouse.nativeEvents);
		mouse.move(614, 408); mouse.move(634, 418); mouse.handleAccumulatedMovement();
		assertEquals(List.of(new WheelMouseCapture.CursorPosition(10, 5)), mouse.guiMoves, "absolute input keeps GUI scaling and the virtual pointer");
		assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY, "mouseGrabbed stays false so vanilla cannot turn the camera");
		capture.sync(PingInteractionPhase.IDLE, mouse);
		mouse.handleAccumulatedMovement();
		assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY, "closing clears free-cursor accumulation before regrab");
	}
	@Test void modeWriteReenteredByScreenHandoffCannotWarpOrRehideAfterDisposal() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.modeChanged = () -> {
			if (mouse.mode == WheelMouseCapture.CURSOR_MODE_HIDDEN) capture.close(mouse, true);
		};
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		assertEquals(List.of(new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_HIDDEN),
			new Mouse.ModeChange(1L, WheelMouseCapture.CURSOR_MODE_NORMAL)), mouse.modeEvents);
		assertTrue(mouse.nativeEvents.stream().noneMatch(value -> value.startsWith("position:")), "handoff fences the captured release bias");
		assertEquals(List.of("release"), mouse.events);
	}
	@Test void focusLossDuringSyncRestoresOnceAndCannotHideOrGrabAgainWhileInactive() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); mouse.windowActive = false;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		capture.sync(PingInteractionPhase.IDLE, mouse); capture.close(mouse, false);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		assertEquals(List.of("release"), mouse.events);
		assertEquals(2, mouse.modeEvents.size());
	}
	@Test void newerVisibleOwnerIsNotRegrabbedEvenOnAFocusedClose() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		mouse.nativeMode(WheelMouseCapture.CURSOR_MODE_NORMAL);
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.IDLE, mouse); capture.close(mouse, false);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode); assertFalse(mouse.grabbed);
		assertEquals(List.of("release"), mouse.events);
	}
	@Test void rawMouseEdgesWhileOpenKeepFreeAbsoluteInputAndNeverEnableCameraLook() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.input.open(); capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		mouse.move(600, 400); mouse.press(true, PingInteractionPhase.WHEEL_OPEN);
		mouse.move(620, 410); mouse.press(false, PingInteractionPhase.WHEEL_OPEN); mouse.handleAccumulatedMovement();
		assertEquals(List.of(true, false), mouse.rawButtonEdges);
		assertEquals(List.of("release"), mouse.events); assertFalse(mouse.grabbed); assertFalse(mouse.arrowVisible());
		assertEquals(List.of(new WheelMouseCapture.CursorPosition(10, 5)), mouse.guiMoves);
		assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY);
	}
	@Test void normalCloseStillRegrabsWhenSelectorPhasePublicationLagsTheMouseSync() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		// Disposal may precede publishing IDLE. The guard belongs to onPress's
		// auto-grab invocation, not grabMouse itself or our close-time regrab.
		capture.close(mouse, false);
		assertTrue(mouse.grabbed); assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		assertEquals(List.of("release", "grab"), mouse.events);
	}
	@Test void cursorRestoreReenteredByRepeatedDisposeCannotRegrabTwice() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		mouse.modeChanged = () -> { if (mouse.mode == WheelMouseCapture.CURSOR_MODE_NORMAL) capture.close(mouse, false); };
		capture.close(mouse, false);
		assertEquals(List.of("release", "grab"), mouse.events);
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode); assertTrue(mouse.grabbed);
	}
}
