package nx.pingwheel.common.monitor;

import org.jetbrains.annotations.NotNullByDefault;

/** Internal bounded aggregation tuning, not confirmed user-facing settings or measured overhead. */
@NotNullByDefault
public record MonitorTuning(int ticksPerBucket, int bucketCount) {

	public static final int MAX_TICKS_PER_BUCKET = 100;
	public static final int MAX_BUCKET_COUNT = 128;
	public static final MonitorTuning DEFAULT = new MonitorTuning(5, 20);

	public MonitorTuning {
		if (ticksPerBucket < 1 || ticksPerBucket > MAX_TICKS_PER_BUCKET
			|| bucketCount < 1 || bucketCount > MAX_BUCKET_COUNT) throw new IllegalArgumentException("monitor window bounds");
	}

	public int windowTicks() { return ticksPerBucket * bucketCount; }
}
