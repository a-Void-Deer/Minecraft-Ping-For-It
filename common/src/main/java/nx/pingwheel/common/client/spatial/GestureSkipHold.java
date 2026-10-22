package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Passive pre-open raw input: net displacement qualifies, ordered samples preserve
 * the gesture. Excess input discards this hold's shortcut, not its ordinary press.
 */
public final class GestureSkipHold {
	/** Engineering work/storage bound, not a capture deadline or a user preference. */
	private static final int MAX_PREOPEN_SAMPLES = 4096;

	public record Position(double x, double y) {}
	public record Sample(double deltaX, double deltaY, long timeMillis) {}
	public static final class Trajectory {
		private final long pressTimeMillis;
		private final List<Sample> samples;

		/** The helper relinquishes its only mutable reference immediately after this transfer. */
		private Trajectory(long pressTimeMillis, List<Sample> samples) {
			this.pressTimeMillis = pressTimeMillis;
			this.samples = samples == null ? List.of() : Collections.unmodifiableList(samples);
		}

		public long pressTimeMillis() { return pressTimeMillis; }
		public List<Sample> samples() { return samples; }
	}

	private boolean active, primed;
	private long pressTime;
	private int minimumMillis, deadzone;
	private NativeSelectorInput.Frame frame;
	private double lastX, lastY, netX, netY;
	private List<Sample> samples;

	public void begin(long pressTimeMillis, int minimumMillis, int deadzone) {
		reset();
		active = true;
		pressTime = pressTimeMillis;
		this.minimumMillis = minimumMillis;
		this.deadzone = deadzone;
	}

	/** Ownership and scale changes prime a new raw baseline, never an invented delta. */
	public void synchronize(NativeSelectorInput.Frame current) {
		Objects.requireNonNull(current);
		if (!current.equals(frame)) { primed = false; frame = current; }
		if (!current.focused()) primed = false;
	}

	public void rePrime() { primed = false; frame = null; }

	public void onMove(long window, double x, double y, NativeSelectorInput.Frame current, long nowMillis) {
		if (!active) return;
		synchronize(current);
		if (window != current.window() || !current.focused()) return;
		if (!Double.isFinite(x) || !Double.isFinite(y)) { primed = false; return; }
		if (!primed) { lastX = x; lastY = y; primed = true; return; }
		double dx = (x - lastX) * current.guiWidth() / current.windowWidth();
		double dy = (y - lastY) * current.guiHeight() / current.windowHeight();
		lastX = x; lastY = y;
		if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(netX + dx) || !Double.isFinite(netY + dy)) {
			rePrime(); return;
		}
		if (dx != 0 || dy != 0) {
			if (samples != null && samples.size() == MAX_PREOPEN_SAMPLES) {
				// Drop the entire pre-open gesture for this hold, before admitting
				// another sample. The runtime's ordinary press/capture/timer stays intact.
				reset();
				return;
			}
			if (samples == null) samples = new ArrayList<>(MAX_PREOPEN_SAMPLES);
			netX += dx; netY += dy;
			samples.add(new Sample(dx, dy, nowMillis));
		}
	}

	public boolean eligible(long nowMillis) {
		return active && nowMillis >= pressTime && nowMillis - pressTime >= minimumMillis
			&& Math.hypot(netX, netY) > deadzone;
	}

	/** Consumed before native ownership starts; no callback can replay a sample twice. */
	public Trajectory take() {
		var result = new Trajectory(pressTime, samples);
		reset();
		return result;
	}

	public boolean active() { return active; }
	public void reset() {
		active = false; rePrime(); netX = 0; netY = 0; samples = null;
	}
}
