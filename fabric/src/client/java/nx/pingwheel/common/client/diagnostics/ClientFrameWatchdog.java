package nx.pingwheel.common.client.diagnostics;

import nx.pingwheel.common.Global;

/**
 * Bounded, self-limiting frame-time watchdog used to attribute stutter reported
 * in play sessions (diagnostic only, no gameplay effect).
 *
 * <p>Every rendered frame is timed on the render thread. At the end of each
 * one-second window a single line is logged <em>only</em> when that window was
 * either slow (a frame above {@link #SLOW_FRAME_MILLIS}) or below
 * {@link #LOW_FPS}:
 *
 * <pre>[PingForIt] frame diag: fps=41 avg=24.1ms max=187ms slow=3 markers=6 wheel=closed heap=912/3072MB</pre>
 *
 * <p>A healthy session therefore logs nothing at all, and the total number of
 * lines is capped by {@link #MAX_LINES} so the diagnostic can never flood the
 * log. {@link #reset(String)} is called on join/leave and reports the aggregate
 * of the previous connection.
 *
 * <p>This class exists to answer "was the stutter the render thread, and did it
 * correlate with markers or with the wheel" without pulling in a profiler. It
 * is deliberately self-contained: no Minecraft types, no per-frame allocation
 * outside the (rare) log branch.
 *
 * <p>Client-only, render-thread-confined.
 */
public final class ClientFrameWatchdog {

	public static final ClientFrameWatchdog INSTANCE = new ClientFrameWatchdog();

	/** A frame slower than this is perceptible and counted as a hitch. */
	private static final long SLOW_FRAME_NANOS = 50_000_000L;
	private static final int SLOW_FRAME_MILLIS = 50;

	/** Below this frame rate the whole window is reported even without a hitch. */
	private static final int LOW_FPS = 40;

	/** Hard cap on emitted diagnostic lines for the whole session. */
	private static final int MAX_LINES = 200;

	private static final long ONE_SECOND_NANOS = 1_000_000_000L;

	private long lastFrameNanos;
	private long windowStartNanos;
	private long windowMaxNanos;
	private long windowTotalNanos;
	private int windowFrames;
	private int windowSlowFrames;
	private int lines;
	private boolean capAnnounced;
	private long totalFrames;
	private long totalSlowFrames;
	private long totalMaxNanos;

	private ClientFrameWatchdog() {}

	/**
	 * Times one rendered frame. Returns immediately after a couple of
	 * {@link System#nanoTime()} reads while the session is not in a world.
	 *
	 * @param inWorld   whether a level and player are present (world frames are
	 *                  the only ones worth reporting)
	 * @param markers   the marker views drawn last frame
	 * @param wheelOpen whether the ping wheel was open last frame
	 */
	public void onFrame(boolean inWorld, int markers, boolean wheelOpen) {
		long now = System.nanoTime();

		if (this.lastFrameNanos == 0L) {
			this.lastFrameNanos = now;
			this.windowStartNanos = now;
			return;
		}

		long delta = now - this.lastFrameNanos;
		this.lastFrameNanos = now;

		if (delta < 0L) {
			return;
		}

		this.windowFrames++;
		this.windowTotalNanos += delta;

		if (delta > this.windowMaxNanos) {
			this.windowMaxNanos = delta;
		}

		if (delta > SLOW_FRAME_NANOS) {
			this.windowSlowFrames++;
			this.totalSlowFrames++;
		}

		if (delta > this.totalMaxNanos) {
			this.totalMaxNanos = delta;
		}

		this.totalFrames++;

		if (now - this.windowStartNanos < ONE_SECOND_NANOS) {
			return;
		}

		boolean report = inWorld
			&& (this.windowSlowFrames > 0 || fps(this.windowFrames, now - this.windowStartNanos) < LOW_FPS);

		if (report) {
			report(inWorld, markers, wheelOpen, now);
		}

		this.windowStartNanos = now;
		this.windowFrames = 0;
		this.windowSlowFrames = 0;
		this.windowTotalNanos = 0L;
		this.windowMaxNanos = 0L;
	}

	private void report(boolean inWorld, int markers, boolean wheelOpen, long now) {
		if (this.lines >= MAX_LINES) {
			if (!this.capAnnounced) {
				this.capAnnounced = true;
				Global.LOGGER.info("frame diag: capped at " + MAX_LINES + " lines; further windows are not logged");
			}

			return;
		}

		this.lines++;

		long windowNanos = Math.max(1L, now - this.windowStartNanos);
		double averageMillis = this.windowFrames == 0 ? 0.0 : this.windowTotalNanos / 1_000_000.0 / this.windowFrames;
		Runtime runtime = Runtime.getRuntime();
		long usedMegabytes = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
		long maxMegabytes = runtime.maxMemory() / (1024L * 1024L);

		Global.LOGGER.info("frame diag: fps=" + fps(this.windowFrames, windowNanos)
			+ " avg=" + String.format("%.1f", averageMillis) + "ms"
			+ " max=" + (this.windowMaxNanos / 1_000_000L) + "ms"
			+ " slow=" + this.windowSlowFrames
			+ " markers=" + markers
			+ " wheel=" + (wheelOpen ? "open" : "closed")
			+ " heap=" + usedMegabytes + "/" + maxMegabytes + "MB");
	}

	private static int fps(int frames, long windowNanos) {
		if (windowNanos <= 0L) {
			return 0;
		}

		return (int)Math.round(frames * 1_000_000_000.0 / windowNanos);
	}

	/**
	 * Clears the window and reports the aggregate of the finished connection.
	 *
	 * @param reason short label of the lifecycle point, for the log line
	 */
	public void reset(String reason) {
		if (this.totalSlowFrames > 0 && this.totalFrames > 0) {
			Global.LOGGER.info("frame diag summary (" + reason + "): frames=" + this.totalFrames
				+ " slowFrames=" + this.totalSlowFrames
				+ " max=" + (this.totalMaxNanos / 1_000_000L) + "ms"
				+ " lines=" + this.lines);
		}

		this.lastFrameNanos = 0L;
		this.windowStartNanos = 0L;
		this.windowMaxNanos = 0L;
		this.windowTotalNanos = 0L;
		this.windowFrames = 0;
		this.windowSlowFrames = 0;
		this.lines = 0;
		this.capAnnounced = false;
		this.totalFrames = 0;
		this.totalSlowFrames = 0;
		this.totalMaxNanos = 0L;
	}
}
