package nx.pingwheel.common.monitor.client;

import java.util.Objects;
import java.util.function.LongSupplier;
import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Client-thread-confined, local-clock-only instrumentation. Nested inclusive
 * intervals of the same lane/phase collapse to their outermost interval: the
 * runtime, packet projection and numeric store must not triple-charge APPLY.
 * No span object, content traversal, transport or server state is involved.
 */
@NotNullByDefault
public final class ClientMonitorCollector {

	private final LongSupplier clock;
	private final MonitorAccumulator accumulator;
	private final int[] depths = new int[6];
	private final long[] starts = new long[6];

	public ClientMonitorCollector() { this(MonitorTuning.DEFAULT, System::nanoTime); }

	public ClientMonitorCollector(MonitorTuning tuning, LongSupplier clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
		accumulator = new MonitorAccumulator(Source.LOCAL, tuning, clock);
	}

	public boolean enabled() { return accumulator.enabled(); }

	/** The caller supplies the final client DEBUG/HUD gate, never a server switch. */
	public void configure(boolean enabled) {
		if (enabled() == enabled) return;
		java.util.Arrays.fill(depths, 0);
		accumulator.configure(enabled, enabled);
	}

	public void resetConnection(long generation) {
		java.util.Arrays.fill(depths, 0);
		accumulator.resetGeneration(generation);
	}

	/** Callers gate before even obtaining a clock or allocating a measurement value. */
	public void begin(Lane lane, Phase phase) {
		if (!enabled()) return;
		int index = index(lane, phase);
		if (depths[index]++ == 0) starts[index] = clock.getAsLong();
	}

	public void end(Lane lane, Phase phase) {
		if (!enabled()) return;
		int index = index(lane, phase);
		// A reentrant HUD disable/reset invalidates all outstanding spans.
		if (depths[index] == 0 || --depths[index] != 0) return;
		long elapsed = clock.getAsLong() - starts[index];
		if (elapsed >= 0) accumulator.recordDuration(lane, phase, elapsed);
	}

	public void count(Lane lane, Counter counter, long delta) {
		if (enabled()) accumulator.recordCount(lane, counter, delta);
	}

	public void gauge(Lane lane, Gauge gauge, long value) {
		if (enabled()) accumulator.setGauge(lane, gauge, value);
	}

	public @Nullable MonitorSample completeTick(long tick) { return accumulator.completeTick(tick); }
	public @Nullable MonitorSample latest() { return accumulator.latest(); }

	private static int index(Lane lane, Phase phase) {
		int phaseIndex = switch (phase) {
			case APPLY -> 0;
			case PREPARE -> 1;
			case DRAW -> 2;
			default -> throw new IllegalArgumentException("not a local phase");
		};
		return (lane == Lane.PREVIEW ? 0 : 3) + phaseIndex;
	}
}
