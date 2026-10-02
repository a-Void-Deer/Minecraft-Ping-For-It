package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import static org.junit.jupiter.api.Assertions.*;

class WheelMouseCaptureLifecycleTest {
	private static final class Mouse implements WheelMouseCapture.MouseAccess {
		boolean screen, grabbed = true;
		final List<String> events = new ArrayList<>();
		WheelMouseCapture owner;
		Runnable released = () -> {};
		public boolean screenOpen() { return screen; }
		public boolean grabbed() { return grabbed; }
		public void release() { assertTrue(owner.isTransitioning()); events.add("release"); grabbed = false; released.run(); }
		public void grab() { assertTrue(owner.isTransitioning()); events.add("grab"); grabbed = true; }
	}
	@Test void normalCloseReclaimsExactlyItsReleaseAndRepeatedCloseIsInert() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		capture.sync(PingInteractionPhase.IDLE, mouse); capture.close(mouse, false); capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release", "grab"), mouse.events); assertFalse(capture.isTransitioning());
	}
	@Test void incomingScreenDisposesBeforeAssignmentAndCannotStealBackCursorLater() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse);
		assertFalse(mouse.screen, "the setScreen HEAD still sees the old null screen");
		capture.close(mouse, true); mouse.screen = true; capture.sync(PingInteractionPhase.IDLE, mouse);
		mouse.screen = false; capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release"), mouse.events);
	}
	@Test void unownedFreeCursorIsNeverGrabbedByIdleDispose() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture; mouse.grabbed = false;
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.close(mouse, false);
		assertTrue(mouse.events.isEmpty()); assertFalse(mouse.grabbed);
	}
	@Test void disposalReenteredFromReleaseDoesNotReacquireStaleOwnership() {
		var capture = new WheelMouseCapture(PingInteractionLogger.noop()); var mouse = new Mouse(); mouse.owner = capture;
		mouse.released = () -> capture.close(mouse, false);
		capture.sync(PingInteractionPhase.WHEEL_OPEN, mouse); capture.sync(PingInteractionPhase.IDLE, mouse);
		assertEquals(List.of("release", "grab"), mouse.events); assertFalse(capture.isTransitioning());
	}
}
