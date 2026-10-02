package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeSelectorInputTest {
	private static final class Sink implements NativeSelectorInput.Sink {
		final List<SpatialController.Point> moves = new ArrayList<>();
		final List<Double> rows = new ArrayList<>();
		public void moveGui(double x, double y, long now) { moves.add(new SpatialController.Point(x, y)); }
		public void scrollRows(double delta, long now) { rows.add(delta); }
	}
	private static NativeSelectorInput.Frame frame(int width, int guiWidth, boolean focused, boolean grabbed) {
		return new NativeSelectorInput.Frame(7, width, 800, guiWidth, 400, focused, grabbed);
	}

	@Test
	void onlyAcceptedSuccessiveWindowPositionsBecomeGuiTravel() {
		Sink sink = new Sink();
		NativeSelectorInput input = new NativeSelectorInput(sink);
		var frame = frame(1200, 600, true, false);
		assertFalse(input.onMove(7, 400, 300, frame, 0));
		input.open();
		assertFalse(input.onMove(8, 900, 900, frame, 1));
		assertTrue(input.onMove(7, 400, 300, frame, 2));
		assertTrue(sink.moves.isEmpty(), "opening/cursor warp primes without travel");
		input.onMove(8, 10000, 10000, frame, 3);
		input.onMove(7, 420, 310, frame, 4);
		assertEquals(List.of(new SpatialController.Point(10, 5)), sink.moves);
	}

	@Test
	void resizeScaleCaptureFocusAndReopenReprimeWithoutPhantomTravel() {
		Sink sink = new Sink();
		NativeSelectorInput input = new NativeSelectorInput(sink);
		input.open();
		input.onMove(7, 100, 100, frame(1000, 500, true, false), 0);
		input.onMove(7, 900, 900, frame(1000, 250, true, false), 1);
		input.onMove(7, 700, 700, frame(1400, 250, true, false), 2);
		input.synchronize(frame(1400, 250, true, true));
		assertFalse(input.onMove(7, 10000, 10000, frame(1400, 250, true, true), 3));
		input.onMove(7, 500, 500, frame(1400, 250, true, false), 4);
		input.synchronize(frame(1400, 250, false, false));
		input.onMove(7, 800, 800, frame(1400, 250, true, false), 5);
		input.release(); input.open();
		input.onMove(7, 900, 900, frame(1400, 250, true, false), 6);
		input.rePrime();
		input.onMove(7, 100, 100, frame(1400, 250, true, false), 7);
		assertTrue(sink.moves.isEmpty());
		input.onMove(7, 114, 108, frame(1400, 250, true, false), 8);
		assertEquals(new SpatialController.Point(2.5, 4), sink.moves.getFirst());
	}

	@Test
	void scrollNormalizationOccursOnceOnlyWhenOwnedAndDoesNotMovePointer() {
		Sink sink = new Sink();
		NativeSelectorInput input = new NativeSelectorInput(sink);
		var frame = frame(1200, 600, true, false);
		assertFalse(input.onScroll(7, 0, 1, frame, 0));
		input.open();
		input.onMove(7, 20, 20, frame, 0);
		assertFalse(input.onScroll(8, 0, 3, frame, 1));
		assertTrue(input.onScroll(7, 4, 0.5, frame, 2));
		assertTrue(input.onScroll(7, 4, -2, frame, 3));
		input.onMove(7, 20, 20, frame, 4);
		assertEquals(List.of(-0.5, 2.0), sink.rows);
		assertTrue(sink.moves.isEmpty());
		input.release();
		assertFalse(input.onScroll(7, 0, 1, frame, 5));
	}
}
