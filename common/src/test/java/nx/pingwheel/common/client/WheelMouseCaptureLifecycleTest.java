package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

class WheelMouseCaptureLifecycleTest {
	/** Minecraft position-before-mode ordering and GLFW disabled virtual/physical separation; no native window. */
	private static final class Mouse implements WheelMouseCapture.MouseAccess {
		boolean screen, grabbed = true, focused = true;
		long window = 1;
		int mode = WheelMouseCapture.CURSOR_MODE_DISABLED;
		int width = 1200, height = 800, guiWidth = 600, guiHeight = 400;
		double physicalX = 600, physicalY = 400, restoreX = -40, restoreY = -20;
		double virtualX = 4000, virtualY = -3000, xpos = 4000, ypos = -3000;
		double accumulatedDX, accumulatedDY, cameraX, cameraY;
		boolean ignoreFirstMove, cursorTracked = true, pendingEntry, modePositionCallback = true;
		int vanillaScrolls, resets, positionCallbacks;
		final List<Boolean> edges = new ArrayList<>();
		final List<SpatialController.Point> moves = new ArrayList<>();
		final List<Double> rows = new ArrayList<>();
		final List<Integer> modes = new ArrayList<>();
		final List<String> events = new ArrayList<>();
		final NativeSelectorInput input = new NativeSelectorInput(new NativeSelectorInput.Sink() {
			public void moveGui(double x, double y, long now) { moves.add(new SpatialController.Point(x, y)); }
			public void scrollRows(double amount, long now) { rows.add(amount); }
		});
		final WheelMouseCapture owner = new WheelMouseCapture(PingInteractionLogger.noop());
		Runnable releaseHead = () -> {}, released = () -> {}, modeChanged = () -> {};
		Mouse() { input.open(); }
		public boolean screenOpen() { return screen; }
		public boolean grabbed() { return grabbed; }
		public boolean focused() { return focused; }
		public long window() { return window; }
		public int cursorMode() { return mode; }
		NativeSelectorInput.Frame frame() {
			return new NativeSelectorInput.Frame(window, width, height, guiWidth, guiHeight, focused, grabbed, owner.ownsInput(this));
		}
		public void beforeTransition() { accumulatedDX = accumulatedDY = 0; input.rePrime(); }
		public void afterTransition() { accumulatedDX = accumulatedDY = 0; input.prime(cursorX(), cursorY(), frame()); }
		double cursorX() { return mode == WheelMouseCapture.CURSOR_MODE_DISABLED ? virtualX : physicalX; }
		double cursorY() { return mode == WheelMouseCapture.CURSOR_MODE_DISABLED ? virtualY : physicalY; }
		public void setCursorMode(int next) {
			modes.add(next);
			if (next != mode) {
				int previous = mode; mode = next;
				virtualX = physicalX; virtualY = physicalY;
				if (previous == WheelMouseCapture.CURSOR_MODE_DISABLED) { physicalX = restoreX; physicalY = restoreY; }
				if (next == WheelMouseCapture.CURSOR_MODE_DISABLED && focused) {
					restoreX = physicalX; restoreY = physicalY;
					physicalX = width / 2.0; physicalY = height / 2.0;
					pendingEntry = !cursorTracked;
				}
				if (modePositionCallback) callback(cursorX(), cursorY());
			}
			modeChanged.run();
		}
		private void nativePosition(double x, double y) {
			resets++;
			if (mode == WheelMouseCapture.CURSOR_MODE_DISABLED) { virtualX = x; virtualY = y; }
			else { physicalX = x; physicalY = y; }
			callback(x, y);
		}
		public void applyCursorMode(int mode, double x, double y) { nativePosition(x, y); setCursorMode(mode); }
		public void release() {
			assertTrue(owner.isTransitioning()); WheelMouseCapture.beforeVanillaRelease();
			try {
				owner.vanillaCaptureChanged(false); events.add("release"); beforeTransition(); releaseHead.run();
				if (grabbed) {
					grabbed = false; xpos = width / 2.0; ypos = height / 2.0;
					WheelMouseCapture.releaseCursor(window, WheelMouseCapture.CURSOR_MODE_NORMAL, xpos, ypos);
				}
				released.run(); input.rePrime();
			} finally { WheelMouseCapture.afterVanillaRelease(); }
		}
		public void grab() {
			owner.vanillaCaptureChanged(true); events.add("grab"); beforeTransition();
			if (focused && !grabbed) {
				grabbed = true; xpos = width / 2.0; ypos = height / 2.0;
				applyCursorMode(WheelMouseCapture.CURSOR_MODE_DISABLED, xpos, ypos); ignoreFirstMove = true;
			}
			input.rePrime();
		}
		void externalGrab() { grab(); }
		void cursorEntered() {
			input.synchronize(frame()); // Runtime's cursorEntered HEAD delegate validates, without sampling coordinates.
			ignoreFirstMove = true; cursorTracked = true; pendingEntry = false; // Vanilla body stays intact.
		}
		void deferredEntry() { assertTrue(pendingEntry); cursorEntered(); }
		void callback(double x, double y) {
			positionCallbacks++;
			if (ignoreFirstMove) ignoreFirstMove = false;
			else if (focused) { accumulatedDX += x - xpos; accumulatedDY += y - ypos; }
			xpos = x; ypos = y; // The production hook is TAIL: preserve vanilla bookkeeping.
			input.onMove(window, x, y, frame(), 0);
		}
		void moveBy(double dx, double dy) {
			advancePosition(dx, dy);
			callback(cursorX(), cursorY());
		}
		void advancePosition(double dx, double dy) {
			if (mode == WheelMouseCapture.CURSOR_MODE_DISABLED) { virtualX += dx; virtualY += dy; }
			else { physicalX += dx; physicalY += dy; }
		}
		void handleMovement() {
			if (grabbed) { cameraX += accumulatedDX; cameraY += accumulatedDY; }
			accumulatedDX = accumulatedDY = 0;
		}
		boolean scroll(double horizontal, double vertical) {
			boolean consumed = input.onScroll(window, horizontal, vertical, frame(), 0);
			if (!consumed) vanillaScrolls++;
			return consumed;
		}
		void press(boolean down) {
			if (!screen && !grabbed && down && !WheelMouseCapture.preventVanillaGrab(PingInteractionPhase.WHEEL_OPEN, screen, focused)) externalGrab();
			edges.add(down);
		}
		void open() { owner.sync(PingInteractionPhase.WHEEL_OPEN, this); }
		void idle() { owner.sync(PingInteractionPhase.IDLE, this); }
	}
	private static Mouse initiallyOutsideFreeMouse() {
		var mouse = new Mouse(); mouse.grabbed = false; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		mouse.physicalX = -40; mouse.physicalY = -20; mouse.cursorTracked = false;
		// The recenter's zero-delta position callback is suppressed with raw input off,
		// and its WM_MOUSEMOVE position path is skipped with raw input on. Entry is separate.
		mouse.modePositionCallback = false;
		return mouse;
	}
	@Test void deferredCursorEntryWithoutPositionCallbackKeepsFirstAndNextRealGuiMovement() {
		var mouse = initiallyOutsideFreeMouse(); mouse.accumulatedDX = 100; mouse.accumulatedDY = -50;
		mouse.open(); assertTrue(mouse.owner.ownsInput(mouse)); assertFalse(mouse.grabbed);
		assertEquals(-40, mouse.virtualX); assertEquals(-20, mouse.virtualY);
		assertEquals(600, mouse.physicalX); assertEquals(400, mouse.physicalY);
		assertEquals(0, mouse.positionCallbacks); assertTrue(mouse.moves.isEmpty());
		mouse.deferredEntry(); assertTrue(mouse.ignoreFirstMove); assertEquals(0, mouse.positionCallbacks);
		mouse.moveBy(20, -10);
		assertEquals(List.of(new SpatialController.Point(10, -5)), mouse.moves, "entry must not discard the acquisition baseline");
		assertFalse(mouse.ignoreFirstMove); mouse.moveBy(6, 4);
		assertEquals(List.of(new SpatialController.Point(10, -5), new SpatialController.Point(3, 2)), mouse.moves);
		assertEquals(2, mouse.positionCallbacks);
		mouse.owner.close(mouse, false); mouse.handleMovement();
		assertFalse(mouse.grabbed); assertTrue(mouse.events.isEmpty());
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		assertEquals(0, mouse.accumulatedDX); assertEquals(0, mouse.accumulatedDY);
		assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY);
	}
	@Test void deferredCursorEntryAfterVirtualAdvanceDoesNotResampleAwayThePendingRealMovement() {
		var mouse = initiallyOutsideFreeMouse(); mouse.open();
		// GLFW has advanced its virtual coordinates before delivering entry followed by position.
		mouse.advancePosition(20, -10); mouse.deferredEntry();
		assertEquals(0, mouse.positionCallbacks); assertTrue(mouse.moves.isEmpty());
		mouse.callback(mouse.cursorX(), mouse.cursorY());
		assertEquals(List.of(new SpatialController.Point(10, -5)), mouse.moves, "entry must not sample already-advanced native coordinates");
		mouse.callback(mouse.cursorX(), mouse.cursorY()); mouse.moveBy(6, 4);
		assertEquals(List.of(new SpatialController.Point(10, -5), new SpatialController.Point(3, 2)), mouse.moves);
		mouse.owner.close(mouse, false); mouse.handleMovement();
		assertFalse(mouse.grabbed); assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY);
	}
	@ParameterizedTest @ValueSource(strings = {"window", "focus", "screen", "mode", "grab"})
	void cursorEntryAtInvalidOwnershipStillGatesOrReprimesBeforeMovement(String handoff) {
		var mouse = initiallyOutsideFreeMouse(); mouse.open();
		switch (handoff) {
			case "window" -> mouse.window = 2;
			case "focus" -> mouse.focused = false;
			case "screen" -> mouse.screen = true;
			case "mode" -> mouse.mode = GLFW.GLFW_CURSOR_HIDDEN;
			case "grab" -> mouse.grabbed = true;
		}
		mouse.deferredEntry(); assertTrue(mouse.moves.isEmpty());
		mouse.window = 1; mouse.focused = true; mouse.screen = false;
		mouse.mode = WheelMouseCapture.CURSOR_MODE_DISABLED; mouse.grabbed = false;
		mouse.moveBy(20, -10); assertTrue(mouse.moves.isEmpty(), "an invalid entry cannot retain the old baseline");
		mouse.moveBy(6, 4);
		if (handoff.equals("focus") || handoff.equals("screen")) {
			assertEquals(List.of(new SpatialController.Point(3, 2)), mouse.moves);
		} else {
			assertTrue(mouse.moves.isEmpty()); assertFalse(mouse.scroll(0, 1));
		}
	}

	@Test void gameplayReleaseKeepsDisabledPhysicalCaptureAndFirstActualMovementExactlyOnce() {
		var mouse = new Mouse(); mouse.accumulatedDX = 100; mouse.accumulatedDY = -50;
		mouse.open();
		assertFalse(mouse.grabbed); assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		assertTrue(mouse.owner.ownsInput(mouse)); assertTrue(mouse.moves.isEmpty());
		assertEquals(600, mouse.physicalX); assertEquals(400, mouse.physicalY);
		assertEquals(600, mouse.virtualX); assertEquals(400, mouse.virtualY);
		mouse.moveBy(20, 10); mouse.handleMovement();
		assertEquals(List.of(new SpatialController.Point(10, 5)), mouse.moves);
		assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY);
	}
	@Test void initiallyFreeAcquisitionPrimesNativeOnlyResetWithoutClaimingVanillaRegrab() {
		var mouse = new Mouse(); mouse.grabbed = false; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		mouse.physicalX = 74; mouse.physicalY = 91; mouse.xpos = 74; mouse.ypos = 91;
		mouse.open(); assertFalse(mouse.grabbed); assertTrue(mouse.moves.isEmpty());
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
		mouse.moveBy(20, -10); assertEquals(List.of(new SpatialController.Point(10, -5)), mouse.moves);
		mouse.owner.close(mouse, false);
		assertFalse(mouse.grabbed); assertTrue(mouse.events.isEmpty());
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		assertEquals(List.of(WheelMouseCapture.CURSOR_MODE_DISABLED, WheelMouseCapture.CURSOR_MODE_NORMAL), mouse.modes);
	}
	@Test void repeatedSyncNeverResetsModeOrDropsTheNextDelta() {
		var mouse = new Mouse(); mouse.open(); mouse.moveBy(20, 10);
		for (int i = 0; i < 10; i++) { mouse.open(); mouse.moveBy(2, -2); }
		assertEquals(1, mouse.resets); assertEquals(List.of(WheelMouseCapture.CURSOR_MODE_DISABLED), mouse.modes);
		assertEquals(11, mouse.moves.size()); assertEquals(new SpatialController.Point(1, -1), mouse.moves.getLast());
		assertEquals(List.of("release"), mouse.events);
	}
	@Test void unboundedCallbacksCrossAllFourEdgesWithExactGuiTravelAndNoPhysicalEscapeOrCameraLook() {
		var mouse = new Mouse(); mouse.open();
		for (var delta : List.of(new SpatialController.Point(2000, 0), new SpatialController.Point(-4000, 0),
			new SpatialController.Point(0, 2000), new SpatialController.Point(0, -4000))) {
			mouse.moveBy(delta.x(), delta.y());
			assertEquals(new SpatialController.Point(delta.x() / 2, delta.y() / 2), mouse.moves.getLast());
			assertEquals(600, mouse.physicalX); assertEquals(400, mouse.physicalY);
			assertFalse(mouse.grabbed); mouse.handleMovement();
			assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY);
		}
		assertEquals(-1400, mouse.virtualX); assertEquals(-1600, mouse.virtualY);
	}
	@Test void closeClearsUnprocessedLookReclaimsExactlyOnceAndKeepsRawButtonEdges() {
		var mouse = new Mouse(); mouse.open(); mouse.press(true); mouse.moveBy(2000, -2000); mouse.press(false);
		assertEquals(List.of(true, false), mouse.edges); assertFalse(mouse.grabbed);
		mouse.idle(); mouse.handleMovement(); mouse.owner.close(mouse, false); mouse.idle();
		assertEquals(List.of("release", "grab"), mouse.events); assertTrue(mouse.grabbed);
		assertEquals(0, mouse.cameraX); assertEquals(0, mouse.cameraY);
		assertFalse(mouse.owner.isTransitioning()); assertFalse(mouse.owner.ownsInput(mouse));
	}
	@Test void incomingScreenHeadHandoffNeverGrabsOverTheOldNullScreen() {
		var mouse = new Mouse(); mouse.open(); assertFalse(mouse.screen);
		mouse.owner.close(mouse, true); mouse.screen = true; mouse.idle(); mouse.screen = false; mouse.idle();
		assertEquals(List.of("release"), mouse.events); assertFalse(mouse.grabbed);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode); assertFalse(mouse.scroll(0, 1));
	}
	@Test void screenCursorIsNeverAcquiredAndFreshVanillaReleaseCanReestablishOwnership() {
		var mouse = new Mouse(); mouse.screen = true; mouse.open();
		assertTrue(mouse.events.isEmpty()); assertTrue(mouse.modes.isEmpty());
		mouse.screen = false; mouse.open(); mouse.screen = true; mouse.open();
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode); assertFalse(mouse.grabbed);
		mouse.screen = false; mouse.externalGrab(); int normalWrites = (int) mouse.modes.stream().filter(mode -> mode == WheelMouseCapture.CURSOR_MODE_NORMAL).count();
		mouse.open(); assertTrue(mouse.owner.ownsInput(mouse)); assertFalse(mouse.grabbed);
		assertEquals(normalWrites, mouse.modes.stream().filter(mode -> mode == WheelMouseCapture.CURSOR_MODE_NORMAL).count());
	}
	@Test void focusLossRestoresOwnedModeWithoutRequestingAnInactiveOrLaterGrab() {
		var mouse = new Mouse(); mouse.open(); mouse.focused = false; mouse.open(); mouse.open(); mouse.idle(); mouse.owner.close(mouse, false);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode); assertEquals(List.of("release"), mouse.events);
		assertFalse(mouse.scroll(0, 1)); mouse.focused = true; mouse.idle(); assertFalse(mouse.grabbed);
	}
	@Test void replacedWindowDropsClaimsWithoutChangingNewWindowAndGatesBothInputs() {
		var mouse = new Mouse(); mouse.open(); mouse.window = 2; mouse.mode = WheelMouseCapture.CURSOR_MODE_NORMAL;
		mouse.open(); mouse.moveBy(20, 10); assertFalse(mouse.scroll(0, 1));
		assertTrue(mouse.moves.isEmpty()); assertEquals(List.of(WheelMouseCapture.CURSOR_MODE_DISABLED), mouse.modes);
		mouse.idle(); assertEquals(List.of("release"), mouse.events);
		mouse.externalGrab(); mouse.open(); assertTrue(mouse.owner.ownsInput(mouse));
		mouse.moveBy(20, 10); assertEquals(List.of(new SpatialController.Point(10, 5)), mouse.moves);
	}
	@ParameterizedTest @ValueSource(ints = {GLFW.GLFW_CURSOR_NORMAL, GLFW.GLFW_CURSOR_HIDDEN})
	void differentNativeOwnerPassesMovementAndScrollThroughWithoutRestoreOrReacquisition(int next) {
		var mouse = new Mouse(); mouse.open(); mouse.setCursorMode(next); mouse.open(); mouse.moveBy(20, 10);
		assertFalse(mouse.scroll(0, 1)); mouse.idle(); mouse.owner.close(mouse, false);
		assertEquals(next, mouse.mode); assertEquals(List.of("release"), mouse.events); assertTrue(mouse.moves.isEmpty());
	}
	@Test void vanillaSameDisabledRegrabIsNeverRestoredToNormalOnClose() {
		var mouse = new Mouse(); mouse.open(); mouse.externalGrab(); int writes = mouse.modes.size();
		mouse.moveBy(20, 10); assertFalse(mouse.scroll(0, 1)); mouse.owner.close(mouse, false); mouse.idle();
		assertTrue(mouse.grabbed); assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode); assertEquals(writes, mouse.modes.size());
		assertTrue(mouse.moves.isEmpty()); assertFalse(mouse.owner.ownsInput(mouse));
	}
	@Test void deliberateOpenResyncReleasesNewVanillaGrabWithoutNormalModeCycle() {
		var mouse = new Mouse(); mouse.open(); mouse.moveBy(20, 10); mouse.externalGrab(); mouse.open();
		assertTrue(mouse.owner.ownsInput(mouse)); assertFalse(mouse.grabbed);
		assertTrue(mouse.modes.stream().allMatch(mode -> mode == WheelMouseCapture.CURSOR_MODE_DISABLED));
		mouse.moveBy(20, 10); assertEquals(List.of(new SpatialController.Point(10, 5), new SpatialController.Point(10, 5)), mouse.moves);
	}
	@Test void sameDisabledVanillaGrabDetectedEvenWhenNotificationWasNotObserved() {
		var mouse = new Mouse(); mouse.open(); mouse.grabbed = true; int writes = mouse.modes.size();
		mouse.owner.close(mouse, false); assertEquals(writes, mouse.modes.size()); assertTrue(mouse.grabbed);
	}
	@Test void everyOwnedScrollIsConsumedButTransitionCallbacksAreNotOwned() {
		var mouse = new Mouse(); mouse.modeChanged = () -> assertFalse(mouse.scroll(0, 1)); mouse.open(); mouse.modeChanged = () -> {};
		assertTrue(mouse.scroll(0, 1)); assertTrue(mouse.scroll(2, 0)); assertTrue(mouse.scroll(0, 0));
		assertEquals(List.of(-1.0), mouse.rows); assertTrue(mouse.moves.isEmpty());
	}
	@Test void scaleHandoffPrimesOnlyTheResetAndCountsFollowingMovement() {
		var mouse = new Mouse(); mouse.open(); mouse.moveBy(20, 10); mouse.guiWidth = 300;
		mouse.callback(mouse.virtualX, mouse.virtualY); mouse.open(); mouse.moveBy(20, 10);
		assertEquals(List.of(new SpatialController.Point(10, 5), new SpatialController.Point(5, 5)), mouse.moves);
	}
	@Test void disposalReenteredAfterReleaseCannotReacquireOrGrabTwice() {
		var mouse = new Mouse(); mouse.released = () -> mouse.owner.close(mouse, false); mouse.open(); mouse.idle();
		assertEquals(List.of("release", "grab"), mouse.events); assertTrue(mouse.grabbed);
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode); assertFalse(mouse.owner.ownsInput(mouse));
	}
	@Test void screenHandoffDuringReleaseModeWriteFencesStaleAcquisition() {
		var mouse = new Mouse(); mouse.modeChanged = () -> {
			if (mouse.mode == WheelMouseCapture.CURSOR_MODE_DISABLED) mouse.owner.close(mouse, true);
		};
		mouse.open(); mouse.open(); assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode);
		assertEquals(List.of("release"), mouse.events); assertTrue(mouse.moves.isEmpty()); assertFalse(mouse.scroll(0, 1));
	}
	@Test void newerOwnerDuringRestorePreventsStaleRegrab() {
		var mouse = new Mouse(); mouse.open(); mouse.modeChanged = () -> {
			if (mouse.mode == WheelMouseCapture.CURSOR_MODE_NORMAL) {
				mouse.modeChanged = () -> {}; mouse.owner.close(mouse, true); mouse.setCursorMode(GLFW.GLFW_CURSOR_HIDDEN);
			}
		};
		mouse.owner.close(mouse, false);
		assertEquals(GLFW.GLFW_CURSOR_HIDDEN, mouse.mode); assertEquals(List.of("release"), mouse.events);
	}
	@Test void screenHandoffBeforeRedirectDoesNotRunStaleNativeRelease() {
		var mouse = new Mouse(); mouse.releaseHead = () -> { mouse.owner.close(mouse, true); mouse.screen = true; };
		mouse.open(); assertTrue(mouse.modes.isEmpty()); assertEquals(0, mouse.resets); assertFalse(mouse.owner.ownsInput(mouse));
	}
	@Test void repeatedDisposeDuringRestoreCannotRegrabTwice() {
		var mouse = new Mouse(); mouse.open(); mouse.modeChanged = () -> {
			if (mouse.mode == WheelMouseCapture.CURSOR_MODE_NORMAL) mouse.owner.close(mouse, false);
		};
		mouse.owner.close(mouse, false); assertEquals(List.of("release", "grab"), mouse.events); assertTrue(mouse.grabbed);
	}
	@Test void callbackObservedNativeHandoffCannotRegainOwnershipByReturningToSameDisabledMode() {
		var mouse = new Mouse(); mouse.open(); mouse.setCursorMode(WheelMouseCapture.CURSOR_MODE_NORMAL);
		mouse.setCursorMode(WheelMouseCapture.CURSOR_MODE_DISABLED); int writes = mouse.modes.size();
		mouse.moveBy(20, 10); assertFalse(mouse.scroll(0, 1)); mouse.owner.close(mouse, false);
		assertEquals(writes, mouse.modes.size()); assertTrue(mouse.moves.isEmpty()); assertEquals(List.of("release"), mouse.events);
	}
	@Test void reentrantVanillaGrabAndReleaseDuringRestoreFencesOldReclaimEvenWithNormalModeAgain() {
		var mouse = new Mouse(); mouse.open(); mouse.modeChanged = () -> {
			if (mouse.mode == WheelMouseCapture.CURSOR_MODE_NORMAL) {
				mouse.modeChanged = () -> {}; mouse.externalGrab();
				mouse.owner.vanillaCaptureChanged(false); mouse.grabbed = false;
				mouse.applyCursorMode(WheelMouseCapture.CURSOR_MODE_NORMAL, 600, 400);
			}
		};
		mouse.owner.close(mouse, false);
		assertEquals(List.of("release", "grab"), mouse.events); assertFalse(mouse.grabbed);
		assertEquals(WheelMouseCapture.CURSOR_MODE_NORMAL, mouse.mode); assertFalse(mouse.owner.ownsInput(mouse));
	}
	@Test void vanillaRegrabReenteredDuringAcquisitionKeepsNewOwnerAndFencesOldClose() {
		var mouse = new Mouse(); mouse.modeChanged = () -> {
			mouse.modeChanged = () -> {}; mouse.externalGrab();
		};
		mouse.open(); int writes = mouse.modes.size(); mouse.owner.close(mouse, false);
		assertEquals(writes, mouse.modes.size()); assertTrue(mouse.grabbed);
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode); assertFalse(mouse.owner.ownsInput(mouse));
		assertEquals(List.of("release", "grab"), mouse.events); assertTrue(mouse.moves.isEmpty());
	}
	@Test void focusedCloseStillReclaimsBeforeIdlePhasePublication() {
		var mouse = new Mouse(); mouse.open(); mouse.owner.close(mouse, false);
		assertEquals(List.of("release", "grab"), mouse.events); assertTrue(mouse.grabbed);
		assertEquals(WheelMouseCapture.CURSOR_MODE_DISABLED, mouse.mode);
	}
}
