package nx.pingwheel.common.monitor;

import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetReading;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.GaugeReading;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.Timing;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Owner-thread-only, fixed tick-bucket rolling aggregates and one immutable latest
 * snapshot. There is no subscription, world read, logging, background task or
 * transport here. Construct once; record operations use preallocated primitive
 * storage. Collection start/reset establishes one monotonic origin; completeTick
 * reads that clock once per new enabled tick. Only publication allocates samples.
 *
 * <p>Record all work for a tick before completeTick(tick). Those pending events
 * belong to that completing tick, including after skipped ticks. Buckets use
 * floor(tick / ticksPerBucket); at most bucketCount consecutive buckets survive.
 * Skipped ticks are idle, never synthetic events. Current gauges/budgets are the
 * latest observation within that bounded window; explicit invalidation prevents
 * fallback to an older value. Missing fields remain unavailable.
 *
 * <p>Callers must gate their own time measurement with enabled(lane), then pass
 * the measured duration; this core does not allocate per-operation spans. Both
 * disabled lanes make all recording and completion calls no-ops without clock
 * reads or snapshot allocation. latest/snapshot are strictly read-only.
 */
@NotNullByDefault
public final class MonitorAccumulator {

	private static final Phase @Unmodifiable [] PHASES = Phase.values();
	private static final Counter @Unmodifiable [] COUNTERS = Counter.values();
	private static final Gauge @Unmodifiable [] GAUGES = Gauge.values();
	private static final Budget @Unmodifiable [] BUDGETS = Budget.values();

	private final Source source;
	private final MonitorTuning tuning;
	private final LongSupplier clock;
	private final Bucket @Unmodifiable [] buckets;
	private final ScopeTotals @Unmodifiable [] pending = {new ScopeTotals(), new ScopeTotals(), new ScopeTotals()};
	private final ScopeTotals @Unmodifiable [] aggregate = {new ScopeTotals(), new ScopeTotals(), new ScopeTotals()};
	private boolean previewEnabled;
	private boolean trackingEnabled;
	private long serverGeneration;
	private long collectionGeneration;
	private long sampleSequence;
	private boolean hasTick;
	private long lastTick;
	private long lastNs;
	private long originNs;
	private long firstCompletedTick;
	private boolean published;
	private long lastPublicationTick;
	private @Nullable MonitorSample latest;

	public MonitorAccumulator(Source source) { this(source, MonitorTuning.DEFAULT, System::nanoTime); }

	/** Source is LOCAL or SERVER; only SERVER permits the single shared region. */
	public MonitorAccumulator(Source source, MonitorTuning tuning, LongSupplier clock) {
		this.source = Objects.requireNonNull(source, "source");
		if (source == Source.SHARED) throw new IllegalArgumentException("shared is not an endpoint");
		this.tuning = Objects.requireNonNull(tuning, "tuning");
		this.clock = Objects.requireNonNull(clock, "clock");
		buckets = new Bucket[tuning.bucketCount()];
		Arrays.setAll(buckets, ignored -> new Bucket());
	}

	public Source source() { return source; }
	public MonitorTuning tuning() { return tuning; }
	public long serverGeneration() { return serverGeneration; }
	public long collectionGeneration() { return collectionGeneration; }
	public boolean enabled(Lane lane) { return Objects.requireNonNull(lane, "lane") == Lane.PREVIEW ? previewEnabled : trackingEnabled; }
	public boolean enabled() { return previewEnabled || trackingEnabled; }
	public boolean sharedEnabled() { return source == Source.SERVER && enabled(); }

	/**
	 * Any actual enable-mask transition advances collection generation and clears
	 * all pending/window/latest values, including shared. This deliberately avoids
	 * mixed-enable windows and old values on re-enable. A repeated mask is a no-op.
	 * An enabled new collection reads one origin; fully disabled never reads the
	 * clock. Collection does not depend on viewers.
	 */
	public void configure(boolean preview, boolean tracking) {
		if (previewEnabled == preview && trackingEnabled == tracking) return;
		long nextGeneration = Math.incrementExact(collectionGeneration);
		previewEnabled = preview;
		trackingEnabled = tracking;
		collectionGeneration = nextGeneration;
		clear();
		if (enabled()) originNs = clock.getAsLong();
	}

	/** Lifecycle reset preserves the configured mask; caller owns subsequent configuration. */
	public void resetGeneration(long generation) {
		if (generation < 0) throw new IllegalArgumentException("server generation");
		long nextGeneration = Math.incrementExact(collectionGeneration);
		serverGeneration = generation;
		collectionGeneration = nextGeneration;
		clear();
		if (enabled()) originNs = clock.getAsLong();
	}

	public void recordDuration(Lane lane, Phase phase, long elapsedNs) {
		if (!enabled(lane)) return;
		if (!phase.supports(source, lane)) throw new IllegalArgumentException("timing scope");
		requireNonnegative(elapsedNs);
		pending[laneIndex(lane)].duration(phase, elapsedNs);
	}

	/** A measured zero marks the counter available; omission never invents a zero. */
	public void recordCount(Lane lane, Counter counter, long delta) {
		if (!enabled(lane)) return;
		if (!counter.supports(source, lane)) throw new IllegalArgumentException("counter scope");
		requireNonnegative(delta);
		pending[laneIndex(lane)].count(counter, delta);
	}

	public void setGauge(Lane lane, Gauge gauge, long value) {
		if (!enabled(lane)) return;
		if (!gauge.supports(source, lane)) throw new IllegalArgumentException("gauge scope");
		requireNonnegative(value);
		pending[laneIndex(lane)].gauge(gauge, value, true);
	}

	public void invalidateGauge(Lane lane, Gauge gauge) {
		if (!enabled(lane)) return;
		if (!gauge.supports(source, lane)) throw new IllegalArgumentException("gauge scope");
		pending[laneIndex(lane)].gauge(gauge, 0, false);
	}

	public void setBudget(Lane lane, Budget budget, BudgetUsage usage) {
		if (!enabled(lane)) return;
		if (!budget.supports(source, lane)) throw new IllegalArgumentException("budget scope");
		pending[laneIndex(lane)].budget(budget, Objects.requireNonNull(usage, "usage"));
	}

	public void invalidateBudget(Lane lane, Budget budget) {
		if (!enabled(lane)) return;
		if (!budget.supports(source, lane)) throw new IllegalArgumentException("budget scope");
		pending[laneIndex(lane)].budget(budget, null);
	}

	public void recordSharedCount(Counter counter, long delta) {
		if (!sharedEnabled()) return;
		if (!counter.supports(Source.SHARED, null)) throw new IllegalArgumentException("shared counter scope");
		requireNonnegative(delta);
		pending[2].count(counter, delta);
	}

	public void setSharedGauge(Gauge gauge, long value) {
		if (!sharedEnabled()) return;
		if (!gauge.supports(Source.SHARED, null)) throw new IllegalArgumentException("shared gauge scope");
		requireNonnegative(value);
		pending[2].gauge(gauge, value, true);
	}

	public void invalidateSharedGauge(Gauge gauge) {
		if (!sharedEnabled()) return;
		if (!gauge.supports(Source.SHARED, null)) throw new IllegalArgumentException("shared gauge scope");
		pending[2].gauge(gauge, 0, false);
	}

	public void setSharedBudget(Budget budget, BudgetUsage usage) {
		if (!sharedEnabled()) return;
		if (!budget.supports(Source.SHARED, null)) throw new IllegalArgumentException("shared budget scope");
		pending[2].budget(budget, Objects.requireNonNull(usage, "usage"));
	}

	public void invalidateSharedBudget(Budget budget) {
		if (!sharedEnabled()) return;
		if (!budget.supports(Source.SHARED, null)) throw new IllegalArgumentException("shared budget scope");
		pending[2].budget(budget, null);
	}

	/** Null explicitly invalidates the unique accounted-memory reading. */
	public void setSharedMemory(@Nullable MemoryUsage memory) {
		if (!sharedEnabled()) return;
		pending[2].memoryTouched = true;
		pending[2].memory = memory;
	}

	/**
	 * Completes one authoritative tick. Returns only a newly published sample,
	 * otherwise null; read latest() for the last completed publication. Duplicate
	 * tick calls do nothing and read no clock. Backward ticks/clocks reject before
	 * draining pending events. Clock wrap is handled by signed elapsed subtraction
	 * (successive observations must be less than 2^63 ns apart).
	 *
	 * <p>Elapsed spans this endpoint's collection origin or retained bucket start.
	 * A zero-elapsed warm-up sample has unavailable rates. No absolute clock stamp
	 * escapes, and clocks from different endpoints must never be compared.
	 */
	public @Nullable MonitorSample completeTick(long tick) {
		if (!enabled()) return null;
		if (tick < 0 || (hasTick && tick < lastTick)) throw new IllegalArgumentException("monitor tick must advance");
		if (hasTick && tick == lastTick) return null;
		long now = clock.getAsLong();
		if (now - (hasTick ? lastNs : originNs) < 0) throw new IllegalArgumentException("monitor clock moved backward");
		long bucketId = tick / tuning.ticksPerBucket();
		for (Bucket bucket : buckets) if (bucket.id >= 0 && bucketId - bucket.id >= buckets.length) bucket.clear();
		Bucket current = buckets[(int) (bucketId % buckets.length)];
		if (current.id != bucketId) {
			current.clear();
			current.id = bucketId;
			current.firstTick = tick;
			current.startNs = !hasTick ? originNs : tick - lastTick == 1 ? lastNs : now;
		}
		for (int index = 0; index < pending.length; index++) {
			current.scopes[index].merge(pending[index]);
			pending[index].clear();
		}
		if (!hasTick) firstCompletedTick = tick;
		hasTick = true;
		lastTick = tick;
		lastNs = now;
		boolean due = published ? tick - lastPublicationTick >= tuning.ticksPerBucket()
			: tick - firstCompletedTick >= tuning.ticksPerBucket() - 1L;
		if (!due) return null;
		for (ScopeTotals totals : aggregate) totals.clear();
		long firstTick = tick;
		long startNs = now;
		for (int age = buckets.length - 1; age >= 0; age--) {
			if (age > bucketId) continue;
			long id = bucketId - age;
			Bucket bucket = buckets[(int) (id % buckets.length)];
			if (bucket.id != id) continue;
			if (bucket.firstTick <= firstTick) { firstTick = bucket.firstTick; startNs = bucket.startNs; }
			for (int index = 0; index < aggregate.length; index++) aggregate[index].merge(bucket.scopes[index]);
		}
		long elapsed = now - startNs;
		if (elapsed < 0) throw new IllegalArgumentException("monitor elapsed interval overflows");
		long sequence = Math.incrementExact(sampleSequence);
		latest = new MonitorSample(serverGeneration, collectionGeneration, sequence, firstTick, tick, elapsed,
			previewEnabled ? laneSample(Lane.PREVIEW, aggregate[0]) : null,
			trackingEnabled ? laneSample(Lane.TRACKING, aggregate[1]) : null,
			sharedEnabled() ? sharedSample(aggregate[2]) : null);
		sampleSequence = sequence;
		published = true;
		lastPublicationTick = tick;
		return latest;
	}

	public @Nullable MonitorSample latest() { return latest; }
	public @Nullable MonitorSample snapshot() { return latest; }

	private LaneSample laneSample(Lane lane, ScopeTotals totals) {
		List<Timing> timings = new ArrayList<>();
		for (Phase phase : PHASES) if ((totals.timingSeen & phase.mask()) != 0) {
			int index = phase.wireId() - 1;
			timings.add(new Timing(phase, new TimingStats(totals.durationTotal[index], totals.durationCount[index], totals.durationMax[index])));
		}
		return new LaneSample(source, lane, timings, counters(totals), gauges(totals), budgets(totals));
	}

	private static SharedSample sharedSample(ScopeTotals totals) {
		return new SharedSample(counters(totals), gauges(totals), budgets(totals), totals.memory);
	}

	private static List<CounterReading> counters(ScopeTotals totals) {
		List<CounterReading> readings = new ArrayList<>();
		for (Counter counter : COUNTERS) if ((totals.counterSeen & counter.mask()) != 0) readings.add(new CounterReading(
			counter, totals.counts[counter.wireId() - 1], (totals.counterSaturated & counter.mask()) != 0));
		return readings;
	}

	private static List<GaugeReading> gauges(ScopeTotals totals) {
		List<GaugeReading> readings = new ArrayList<>();
		for (Gauge gauge : GAUGES) if ((totals.gaugeValid & gauge.mask()) != 0)
			readings.add(new GaugeReading(gauge, totals.gauges[gauge.wireId() - 1]));
		return readings;
	}

	private static List<BudgetReading> budgets(ScopeTotals totals) {
		List<BudgetReading> readings = new ArrayList<>();
		for (Budget budget : BUDGETS) {
			@Nullable BudgetUsage usage = totals.budgets[budget.wireId() - 1];
			if (usage != null) readings.add(new BudgetReading(budget, usage));
		}
		return readings;
	}

	private void clear() {
		for (Bucket bucket : buckets) bucket.clear();
		for (ScopeTotals totals : pending) totals.clear();
		for (ScopeTotals totals : aggregate) totals.clear();
		latest = null;
		hasTick = false;
		published = false;
		sampleSequence = 0;
		lastTick = 0;
		lastNs = 0;
		originNs = 0;
		firstCompletedTick = 0;
		lastPublicationTick = 0;
	}

	private static int laneIndex(Lane lane) { return lane == Lane.PREVIEW ? 0 : 1; }
	private static void requireNonnegative(long value) {
		if (value < 0) throw new IllegalArgumentException("monitor values must be nonnegative");
	}
	private static long add(long first, long second) {
		return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
	}

	@NotNullByDefault
	private static final class Bucket {
		long id = -1;
		long firstTick;
		long startNs;
		final ScopeTotals @Unmodifiable [] scopes = {new ScopeTotals(), new ScopeTotals(), new ScopeTotals()};

		void clear() {
			id = -1;
			firstTick = 0;
			startNs = 0;
			for (ScopeTotals totals : scopes) totals.clear();
		}
	}

	/** Fixed enum-indexed storage; these arrays are private mutable accumulator state. */
	@NotNullByDefault
	private static final class ScopeTotals {
		final long[] durationTotal = new long[PHASES.length];
		final long[] durationCount = new long[PHASES.length];
		final long[] durationMax = new long[PHASES.length];
		final long[] counts = new long[COUNTERS.length];
		final long[] gauges = new long[GAUGES.length];
		final @Nullable BudgetUsage[] budgets = new @Nullable BudgetUsage[BUDGETS.length];
		long timingSeen;
		long counterSeen;
		long counterSaturated;
		long gaugeSeen;
		long gaugeValid;
		long budgetSeen;
		boolean memoryTouched;
		@Nullable MemoryUsage memory;

		void duration(Phase phase, long elapsed) {
			int index = phase.wireId() - 1;
			timingSeen |= phase.mask();
			durationTotal[index] = add(durationTotal[index], elapsed);
			durationCount[index] = add(durationCount[index], 1);
			durationMax[index] = Math.max(durationMax[index], elapsed);
		}

		void count(Counter counter, long delta) {
			int index = counter.wireId() - 1;
			counterSeen |= counter.mask();
			if (delta > Long.MAX_VALUE - counts[index]) counterSaturated |= counter.mask();
			counts[index] = add(counts[index], delta);
		}

		void gauge(Gauge gauge, long value, boolean valid) {
			gaugeSeen |= gauge.mask();
			gaugeValid = valid ? gaugeValid | gauge.mask() : gaugeValid & ~gauge.mask();
			gauges[gauge.wireId() - 1] = value;
		}

		void budget(Budget budget, @Nullable BudgetUsage usage) {
			budgetSeen |= budget.mask();
			budgets[budget.wireId() - 1] = usage;
		}

		void merge(ScopeTotals other) {
			for (Phase phase : PHASES) if ((other.timingSeen & phase.mask()) != 0) {
				int index = phase.wireId() - 1;
				durationTotal[index] = add(durationTotal[index], other.durationTotal[index]);
				durationCount[index] = add(durationCount[index], other.durationCount[index]);
				durationMax[index] = Math.max(durationMax[index], other.durationMax[index]);
			}
			timingSeen |= other.timingSeen;
			for (Counter counter : COUNTERS) if ((other.counterSeen & counter.mask()) != 0) {
				int index = counter.wireId() - 1;
				if (other.counts[index] > Long.MAX_VALUE - counts[index]) counterSaturated |= counter.mask();
				counts[index] = add(counts[index], other.counts[index]);
			}
			counterSeen |= other.counterSeen;
			counterSaturated |= other.counterSaturated;
			for (Gauge gauge : GAUGES) if ((other.gaugeSeen & gauge.mask()) != 0)
				gauge(gauge, other.gauges[gauge.wireId() - 1], (other.gaugeValid & gauge.mask()) != 0);
			for (Budget budget : BUDGETS) if ((other.budgetSeen & budget.mask()) != 0)
				budget(budget, other.budgets[budget.wireId() - 1]);
			if (other.memoryTouched) { memoryTouched = true; memory = other.memory; }
		}

		void clear() {
			Arrays.fill(durationTotal, 0); Arrays.fill(durationCount, 0); Arrays.fill(durationMax, 0);
			Arrays.fill(counts, 0); Arrays.fill(gauges, 0); Arrays.fill(budgets, null);
			timingSeen = 0; counterSeen = 0; counterSaturated = 0; gaugeSeen = 0; gaugeValid = 0; budgetSeen = 0;
			memoryTouched = false;
			memory = null;
		}
	}
}
