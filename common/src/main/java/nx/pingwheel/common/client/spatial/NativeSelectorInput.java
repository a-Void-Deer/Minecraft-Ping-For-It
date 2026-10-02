package nx.pingwheel.common.client.spatial;

import java.util.Objects;

/** Absolute window callbacks to owned GUI deltas; no Minecraft or native callback dependency. */
public final class NativeSelectorInput {

	public interface Sink {
		void moveGui(double deltaX, double deltaY, long nowMillis);
		void scrollRows(double rows, long nowMillis);
	}

	/** A changed window, scale, focus or capture state invalidates the previous sample. */
	public record Frame(long window, int windowWidth, int windowHeight, int guiWidth, int guiHeight,
		boolean focused, boolean grabbed) {
		public Frame {
			if (windowWidth <= 0 || windowHeight <= 0 || guiWidth <= 0 || guiHeight <= 0)
				throw new IllegalArgumentException("window and GUI dimensions must be positive");
		}
	}

	private final Sink sink;
	private boolean owned;
	private Frame frame;
	private boolean primed;
	private double lastX;
	private double lastY;

	public NativeSelectorInput(Sink sink) { this.sink = Objects.requireNonNull(sink); }

	/** Call after open or any ownership/cursor warp; the first accepted position has zero travel. */
	public void open() { owned = true; rePrime(); }
	public void release() { owned = false; rePrime(); }
	public void rePrime() { primed = false; frame = null; }
	public boolean ownsInput() { return owned; }

	/** Also call on focus/capture/resize changes when no mouse callback is delivered. */
	public void synchronize(Frame current) {
		Objects.requireNonNull(current);
		if (!current.equals(frame)) { primed = false; frame = current; }
		if (!current.focused() || current.grabbed()) primed = false;
	}

	/** Return true only for the current window while input is actually owned. */
	public boolean onMove(long window, double x, double y, Frame current, long nowMillis) {
		synchronize(current);
		if (!owned || window != current.window() || !current.focused() || current.grabbed()) return false;
		if (!Double.isFinite(x) || !Double.isFinite(y)) { primed = false; return true; }
		if (!primed) { lastX = x; lastY = y; primed = true; return true; }
		double dx = (x - lastX) * current.guiWidth() / current.windowWidth();
		double dy = (y - lastY) * current.guiHeight() / current.windowHeight();
		lastX = x; lastY = y;
		if (dx != 0.0 || dy != 0.0) sink.moveGui(dx, dy, nowMillis);
		return true;
	}

	/** Native positive-up vertical units become positive-down rows exactly once, only when owned. */
	public boolean onScroll(long window, double horizontal, double vertical, Frame current, long nowMillis) {
		synchronize(current);
		if (!owned || window != current.window() || !current.focused() || current.grabbed()) return false;
		if (Double.isFinite(vertical) && vertical != 0.0) sink.scrollRows(-vertical, nowMillis);
		return true;
	}
}
