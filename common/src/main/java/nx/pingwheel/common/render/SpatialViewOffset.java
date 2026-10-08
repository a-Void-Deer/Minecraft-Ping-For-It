package nx.pingwheel.common.render;

/**
 * One rigid, controller-space translation for the whole selector overlay.
 *
 * <p>While a menu is active the overlay is projected so that menu's origin
 * lands on the GUI center; this class owns that single translation
 * ({@code -origin}) with one finite, caller-timed smoothstep interval. Because
 * every layer, chrome element and inventory anchor shares the sampled value, no
 * per-node transition can shear the pattern. The first aim and reduced motion
 * snap immediately, an unchanged origin never restarts the interval, and a
 * rewound caller clock cannot rewind the displayed translation. Callers sample
 * only while a menu is active and read {@link #x()}/{@link #y()} otherwise, so a
 * normal exit keeps the last displayed translation instead of snapping back to
 * the center. {@link #clear()} is hard disposal for a screen, world or
 * connection discontinuity.
 *
 * <p>Main-thread confined. Owns no clock, renderer, configuration, input or
 * target state.
 */
public final class SpatialViewOffset {

	private final long durationNanos;
	private boolean hasValue;
	private double fromX;
	private double fromY;
	private double toX;
	private double toY;
	private long beganNanos;
	private long lastNanos;
	private boolean hasTime;
	private double currentX;
	private double currentY;

	public SpatialViewOffset(long durationNanos) {
		if (durationNanos <= 0L) {
			throw new IllegalArgumentException("durationNanos must be positive");
		}
		this.durationNanos = durationNanos;
	}

	/**
	 * Aims so the given controller-space origin projects onto the GUI center.
	 * The first aim and reduced motion snap; any later aim starts from the
	 * currently displayed translation.
	 */
	public void centerOn(double originX, double originY, long nowNanos, boolean reduceMotion) {
		if (!Double.isFinite(originX) || !Double.isFinite(originY)) {
			throw new IllegalArgumentException("origin must be finite");
		}
		long now = monotonic(nowNanos);
		double targetX = negated(originX);
		double targetY = negated(originY);
		if (!hasValue || reduceMotion) {
			fromX = targetX;
			fromY = targetY;
			toX = targetX;
			toY = targetY;
			beganNanos = now;
			hasValue = true;
			currentX = targetX;
			currentY = targetY;
			return;
		}
		if (targetX == toX && targetY == toY) {
			return; // an unchanged origin never restarts the interval
		}
		fromX = currentX;
		fromY = currentY;
		toX = targetX;
		toY = targetY;
		beganNanos = now;
		currentX = fromX;
		currentY = fromY;
	}

	/**
	 * Samples the displayed translation at now and retains it as the last
	 * displayed value. A caller that stops sampling (an inactive exit frame)
	 * therefore keeps this translation instead of advancing it.
	 */
	public void sample(long nowNanos) {
		long now = monotonic(nowNanos);
		currentX = displayedX(now);
		currentY = displayedY(now);
	}

	/** Last sampled translation; reading never advances it. */
	public double x() {
		return currentX;
	}

	/** Last sampled translation; reading never advances it. */
	public double y() {
		return currentY;
	}

	/** Hard disposal (disconnect/screen/world change); the next aim snaps. */
	public void clear() {
		hasValue = false;
		hasTime = false;
		lastNanos = 0L;
		beganNanos = 0L;
		fromX = 0.0;
		fromY = 0.0;
		toX = 0.0;
		toY = 0.0;
		currentX = 0.0;
		currentY = 0.0;
	}

	private long monotonic(long nowNanos) {
		long now = hasTime ? Math.max(lastNanos, nowNanos) : nowNanos;
		hasTime = true;
		lastNanos = now;
		return now;
	}

	private double displayedX(long now) {
		return hasValue ? lerp(fromX, toX, easedFraction(now)) : 0.0;
	}

	private double displayedY(long now) {
		return hasValue ? lerp(fromY, toY, easedFraction(now)) : 0.0;
	}

	private double easedFraction(long now) {
		double fraction = Math.min(1.0, elapsed(now, beganNanos) / durationNanos);
		return fraction * fraction * (3.0 - 2.0 * fraction);
	}

	private static double elapsed(long now, long began) {
		if (now <= began) {
			return 0.0;
		}
		long difference = now - began;
		return difference < 0L ? Double.MAX_VALUE : difference;
	}

	private static double lerp(double from, double to, double fraction) {
		return from + (to - from) * fraction;
	}

	/** Avoids a negative zero translation for the root's zero origin. */
	private static double negated(double value) {
		return value == 0.0 ? 0.0 : -value;
	}
}
