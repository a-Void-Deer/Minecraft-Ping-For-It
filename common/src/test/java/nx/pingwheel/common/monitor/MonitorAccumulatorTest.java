package nx.pingwheel.common.monitor;

import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class MonitorAccumulatorTest {

	@Test
	void disabledIsNoClockNoSampleNoCountersAndReadOnlyConsumersCannotTriggerSampling() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
		core.recordDuration(Lane.PREVIEW, Phase.INGRESS, 100);
		core.recordCount(Lane.TRACKING, Counter.REQUESTS_RECEIVED, 7);
		core.setGauge(Lane.PREVIEW, Gauge.REQUESTS_OPEN, 9);
		core.setBudget(Lane.PREVIEW, Budget.WIRE_BYTES, BudgetUsage.unavailable());
		core.recordSharedCount(Counter.READ_ATTEMPTS, 5);
		core.setSharedMemory(MemoryUsage.accounted(5, 0, 10));
		assertNull(core.completeTick(0));
		assertNull(core.latest());
		assertNull(core.snapshot());
		assertEquals(0, clock.reads);
		core.configure(true, false);
		assertEquals(1, clock.reads, "only enabled collection start takes an origin");
		core.configure(true, false);
		clock.advance(10);
		var sample = Objects.requireNonNull(core.completeTick(1));
		assertNull(preview(sample).counter(Counter.REQUESTS_RECEIVED));
		assertNull(preview(sample).timing(Phase.INGRESS));
		assertNull(preview(sample).gauge(Gauge.REQUESTS_OPEN));
		assertNull(Objects.requireNonNull(sample.shared()).memory());
		for (int viewer = 0; viewer < 20; viewer++) {
			assertSame(sample, core.latest());
			assertSame(sample, core.snapshot());
		}
		assertNull(core.completeTick(1), "same authoritative tick cannot publish again");
		assertEquals(2, clock.reads);
		core.configure(false, false);
		core.resetGeneration(7);
		assertNull(core.completeTick(2));
		assertNull(core.latest());
		assertEquals(2, clock.reads, "all-off and disabled generation reset read no clock");
	}

	@Test
	void allFourEnableCombinationsHaveIndependentLanesAndOnlyOneSharedRegion() {
		for (int mask = 0; mask < 4; mask++) {
			var clock = new FakeClock();
			var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
			core.configure((mask & 1) != 0, (mask & 2) != 0);
			core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 2);
			core.recordCount(Lane.TRACKING, Counter.REQUESTS_RECEIVED, 3);
			core.recordSharedCount(Counter.READ_ATTEMPTS, 7);
			core.setSharedMemory(MemoryUsage.accounted(19, 3, 30));
			clock.advance(100);
			if (mask == 0) {
				assertNull(core.completeTick(0));
				assertFalse(core.sharedEnabled());
				continue;
			}
			var sample = Objects.requireNonNull(core.completeTick(0));
			assertEquals((mask & 1) != 0, sample.preview() != null);
			assertEquals((mask & 2) != 0, sample.tracking() != null);
			if ((mask & 1) != 0) assertEquals(2, Objects.requireNonNull(preview(sample).counter(Counter.REQUESTS_RECEIVED)).value());
			if ((mask & 2) != 0) assertEquals(3, Objects.requireNonNull(Objects.requireNonNull(sample.tracking()).counter(Counter.REQUESTS_RECEIVED)).value());
			var shared = Objects.requireNonNull(sample.shared());
			assertEquals(7, Objects.requireNonNull(shared.counter(Counter.READ_ATTEMPTS)).value());
			assertEquals(19, Objects.requireNonNull(shared.memory()).retained());
		}
	}

	@Test
	void periodicRollingWindowEvictsOldTimingsCountersAndScalarReadingsIncludingPeaks() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(2, 2), clock);
		core.configure(true, false);
		core.recordDuration(Lane.PREVIEW, Phase.TICK_WORK, 10);
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 2);
		core.setGauge(Lane.PREVIEW, Gauge.REQUESTS_OPEN, 4);
		clock.advance(100);
		assertNull(core.completeTick(0));
		assertNull(core.latest());
		core.recordDuration(Lane.PREVIEW, Phase.TICK_WORK, 30);
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 3);
		clock.advance(100);
		var first = Objects.requireNonNull(core.completeTick(1));
		assertEquals(new TimingStats(40, 2, 30), preview(first).timing(Phase.TICK_WORK));
		assertEquals(20.0, Objects.requireNonNull(preview(first).timing(Phase.TICK_WORK)).meanNs().orElseThrow());
		assertEquals(0, first.firstTick());
		assertEquals(200, first.elapsedNs());
		core.recordDuration(Lane.PREVIEW, Phase.TICK_WORK, 6);
		clock.advance(100);
		assertNull(core.completeTick(2));
		assertSame(first, core.latest(), "reading latest never flushes a partial bucket");
		clock.advance(100);
		var second = Objects.requireNonNull(core.completeTick(3));
		assertEquals(new TimingStats(46, 3, 30), preview(second).timing(Phase.TICK_WORK));
		assertEquals(5, Objects.requireNonNull(preview(second).counter(Counter.REQUESTS_RECEIVED)).value());
		assertEquals(4, Objects.requireNonNull(preview(second).gauge(Gauge.REQUESTS_OPEN)).value());
		clock.advance(100);
		assertNull(core.completeTick(4));
		clock.advance(100);
		var third = Objects.requireNonNull(core.completeTick(5));
		assertEquals(new TimingStats(6, 1, 6), preview(third).timing(Phase.TICK_WORK));
		assertNull(preview(third).counter(Counter.REQUESTS_RECEIVED));
		assertNull(preview(third).gauge(Gauge.REQUESTS_OPEN), "expired scalar cannot leak past its window");
		assertEquals(2, third.firstTick());
		assertEquals(400, third.elapsedNs());
		assertEquals(new TimingStats(40, 2, 30), preview(first).timing(Phase.TICK_WORK), "published snapshot stays detached");
	}

	@Test
	void latestGaugeBudgetAndExplicitInvalidationNeverFallBackToOlderWindowValues() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 4), clock);
		core.configure(true, false);
		core.setGauge(Lane.PREVIEW, Gauge.QUEUE_LOGICAL_COST, 15);
		core.setBudget(Lane.PREVIEW, Budget.WIRE_BYTES, new BudgetUsage(true, 2, 100, 10, 3, 87, 87));
		core.setSharedBudget(Budget.PHYSICAL_SLOTS, new BudgetUsage(true, 0, 32, 5, 0, 27, 27));
		core.setSharedGauge(Gauge.CONSUMERS, 3);
		core.setSharedMemory(MemoryUsage.accounted(50, 10, 40));
		clock.advance(10);
		var first = Objects.requireNonNull(core.completeTick(0));
		assertEquals(-20, Objects.requireNonNull(Objects.requireNonNull(first.shared()).memory()).remaining());
		core.setGauge(Lane.PREVIEW, Gauge.QUEUE_LOGICAL_COST, 2);
		core.setBudget(Lane.PREVIEW, Budget.WIRE_BYTES, new BudgetUsage(true, 3, 100, 1, 0, 99, 99));
		clock.advance(10);
		var second = Objects.requireNonNull(core.completeTick(1));
		assertEquals(2, Objects.requireNonNull(preview(second).gauge(Gauge.QUEUE_LOGICAL_COST)).value());
		assertEquals(1, Objects.requireNonNull(preview(second).budget(Budget.WIRE_BYTES)).used());
		core.invalidateGauge(Lane.PREVIEW, Gauge.QUEUE_LOGICAL_COST);
		core.invalidateBudget(Lane.PREVIEW, Budget.WIRE_BYTES);
		core.invalidateSharedBudget(Budget.PHYSICAL_SLOTS);
		core.invalidateSharedGauge(Gauge.CONSUMERS);
		core.setSharedMemory(null);
		clock.advance(10);
		var third = Objects.requireNonNull(core.completeTick(2));
		assertNull(preview(third).gauge(Gauge.QUEUE_LOGICAL_COST));
		assertNull(preview(third).budget(Budget.WIRE_BYTES));
		assertNull(Objects.requireNonNull(third.shared()).budget(Budget.PHYSICAL_SLOTS));
		assertNull(Objects.requireNonNull(third.shared()).gauge(Gauge.CONSUMERS));
		assertNull(Objects.requireNonNull(third.shared()).memory());
		clock.advance(10);
		var fourth = Objects.requireNonNull(core.completeTick(3));
		assertNull(preview(fourth).gauge(Gauge.QUEUE_LOGICAL_COST));
		assertEquals(15, Objects.requireNonNull(preview(first).gauge(Gauge.QUEUE_LOGICAL_COST)).value());
		assertThrows(UnsupportedOperationException.class, () -> preview(first).gauges().clear());
	}

	@Test
	void reconfigurationAndLifetimeResetFencePendingEventsWindowsAndLatest() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 3), clock);
		core.configure(true, true);
		long generation = core.collectionGeneration();
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 4);
		clock.advance(5);
		var first = Objects.requireNonNull(core.completeTick(0));
		core.configure(true, true);
		assertEquals(generation, core.collectionGeneration());
		assertSame(first, core.latest());
		core.recordCount(Lane.TRACKING, Counter.REQUESTS_RECEIVED, 9);
		core.configure(false, true);
		assertTrue(core.collectionGeneration() > generation);
		assertNull(core.latest());
		clock.advance(5);
		var next = Objects.requireNonNull(core.completeTick(1));
		assertNull(next.preview());
		assertNull(Objects.requireNonNull(next.tracking()).counter(Counter.REQUESTS_RECEIVED));
		assertEquals(1, next.sampleSequence());
		core.recordCount(Lane.TRACKING, Counter.REQUESTS_RECEIVED, 5);
		core.resetGeneration(12);
		assertTrue(core.enabled(Lane.TRACKING));
		assertNull(core.latest());
		clock.advance(5);
		var reset = Objects.requireNonNull(core.completeTick(0));
		assertEquals(12, reset.serverGeneration());
		assertTrue(reset.collectionGeneration() > next.collectionGeneration());
		assertNull(Objects.requireNonNull(reset.tracking()).counter(Counter.REQUESTS_RECEIVED));
		assertThrows(IllegalArgumentException.class, () -> core.resetGeneration(-1));
		assertSame(reset, core.latest());
	}

	@Test
	void allOffThenReenableCannotResurrectSharedMemoryOrOldDisabledLaneEvents() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
		core.configure(true, false);
		core.setSharedMemory(MemoryUsage.accounted(8, 1, 10));
		clock.advance(1);
		assertNotNull(core.completeTick(1));
		core.configure(false, false);
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 100);
		core.configure(true, false);
		clock.advance(1);
		var sample = Objects.requireNonNull(core.completeTick(2));
		assertNull(Objects.requireNonNull(sample.shared()).memory());
		assertNull(preview(sample).counter(Counter.REQUESTS_RECEIVED));
	}

	@Test
	void overflowSaturatesAcrossBucketsButEvictionRestoresExactSmallValues() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
		core.configure(true, false);
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, Long.MAX_VALUE);
		core.recordDuration(Lane.PREVIEW, Phase.TICK_WORK, Long.MAX_VALUE);
		clock.advance(1);
		var exact = Objects.requireNonNull(core.completeTick(0));
		assertFalse(Objects.requireNonNull(preview(exact).counter(Counter.REQUESTS_RECEIVED)).saturated());
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 4);
		core.recordDuration(Lane.PREVIEW, Phase.TICK_WORK, 5);
		clock.advance(1);
		var saturated = Objects.requireNonNull(core.completeTick(1));
		var count = Objects.requireNonNull(preview(saturated).counter(Counter.REQUESTS_RECEIVED));
		assertEquals(Long.MAX_VALUE, count.value());
		assertTrue(count.saturated());
		assertTrue(saturated.ratePerSecond(count).isEmpty());
		var timing = Objects.requireNonNull(preview(saturated).timing(Phase.TICK_WORK));
		assertEquals(Long.MAX_VALUE, timing.totalNs());
		assertEquals(2, timing.count());
		assertTrue(timing.meanNs().isEmpty());
		clock.advance(1);
		var recovered = Objects.requireNonNull(core.completeTick(2));
		assertEquals(4, Objects.requireNonNull(preview(recovered).counter(Counter.REQUESTS_RECEIVED)).value());
		assertFalse(Objects.requireNonNull(preview(recovered).counter(Counter.REQUESTS_RECEIVED)).saturated());
		assertEquals(new TimingStats(5, 1, 5), preview(recovered).timing(Phase.TICK_WORK));
	}

	@Test
	void sameTickSaturationIsVisibleAndCurrentUnavailableBudgetIsNotAnObservedZero() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
		core.configure(true, false);
		core.recordCount(Lane.PREVIEW, Counter.BUDGET_CHARGED_BYTES, Long.MAX_VALUE);
		core.recordCount(Lane.PREVIEW, Counter.BUDGET_CHARGED_BYTES, 1);
		core.recordSharedCount(Counter.READ_ATTEMPTS, Long.MAX_VALUE);
		core.recordSharedCount(Counter.READ_ATTEMPTS, 1);
		core.setBudget(Lane.PREVIEW, Budget.WIRE_BYTES, BudgetUsage.unavailable());
		clock.advance(1);
		var sample = Objects.requireNonNull(core.completeTick(0));
		assertTrue(Objects.requireNonNull(preview(sample).counter(Counter.BUDGET_CHARGED_BYTES)).saturated());
		assertTrue(Objects.requireNonNull(Objects.requireNonNull(sample.shared()).counter(Counter.READ_ATTEMPTS)).saturated());
		assertFalse(Objects.requireNonNull(preview(sample).budget(Budget.WIRE_BYTES)).available());
		assertNull(preview(sample).budget(Budget.LOGICAL_SLOTS));
	}

	@Test
	void elapsedWarmupDuplicateTicksLargeGapsAndClockWrapHaveExplicitBoundaries() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
		core.configure(true, false);
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 0);
		var warmup = Objects.requireNonNull(core.completeTick(0));
		assertEquals(0, warmup.elapsedNs());
		assertTrue(warmup.ratePerSecond(Objects.requireNonNull(preview(warmup).counter(Counter.REQUESTS_RECEIVED))).isEmpty());
		int reads = clock.reads;
		assertNull(core.completeTick(0));
		assertEquals(reads, clock.reads);
		assertThrows(IllegalArgumentException.class, () -> core.completeTick(-1));
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 2);
		clock.advance(10);
		var gap = Objects.requireNonNull(core.completeTick(Long.MAX_VALUE));
		assertEquals(Long.MAX_VALUE, gap.firstTick());
		assertEquals(2, Objects.requireNonNull(preview(gap).counter(Counter.REQUESTS_RECEIVED)).value());
		assertEquals(0, gap.elapsedNs(), "a full-window gap restarts rate warmup");
		clock.now = Long.MAX_VALUE - 5;
		core.resetGeneration(1);
		clock.advance(10);
		var wrapped = Objects.requireNonNull(core.completeTick(0));
		assertEquals(10, wrapped.elapsedNs(), "nanoTime wrap preserves short signed elapsed deltas");
	}

	@Test
	void backwardClockAndInvalidRecordingsRejectWithoutDrainingOrMutatingPublishedData() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), clock);
		core.configure(true, false);
		clock.advance(20);
		var first = Objects.requireNonNull(core.completeTick(1));
		core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 3);
		assertThrows(IllegalArgumentException.class, () -> core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, -1));
		assertThrows(IllegalArgumentException.class, () -> core.recordDuration(Lane.PREVIEW, Phase.INGRESS, -1));
		assertThrows(IllegalArgumentException.class, () -> core.recordDuration(Lane.PREVIEW, Phase.DRAW, 1));
		assertThrows(IllegalArgumentException.class, () -> core.setGauge(Lane.PREVIEW, Gauge.CHANNELS, 1));
		assertThrows(IllegalArgumentException.class, () -> core.setBudget(Lane.PREVIEW, Budget.PROBES, BudgetUsage.unavailable()));
		clock.now = 19;
		assertThrows(IllegalArgumentException.class, () -> core.completeTick(2));
		assertSame(first, core.latest());
		clock.now = 30;
		var recovered = Objects.requireNonNull(core.completeTick(2));
		assertEquals(3, Objects.requireNonNull(preview(recovered).counter(Counter.REQUESTS_RECEIVED)).value());
		assertThrows(IllegalArgumentException.class, () -> core.completeTick(1));
		assertSame(recovered, core.latest());
	}

	@Test
	void localApplyPrepareDrawAndBufferEstimatesStayLocalWithoutServerOrTransportMetrics() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.LOCAL, new MonitorTuning(1, 2), clock);
		core.configure(true, true);
		core.recordDuration(Lane.PREVIEW, Phase.APPLY, 5);
		core.recordDuration(Lane.PREVIEW, Phase.PREPARE, 7);
		core.recordDuration(Lane.TRACKING, Phase.DRAW, 9);
		core.recordCount(Lane.PREVIEW, Counter.ACCEPTED, 2);
		core.recordCount(Lane.PREVIEW, Counter.REJECTED, 1);
		core.recordCount(Lane.TRACKING, Counter.REPAIR_REQUESTS, 3);
		core.setGauge(Lane.PREVIEW, Gauge.BUFFER_ESTIMATED_BYTES, 40);
		core.setGauge(Lane.PREVIEW, Gauge.CHANNELS, 2);
		core.recordSharedCount(Counter.READ_ATTEMPTS, 100);
		clock.advance(20);
		var sample = Objects.requireNonNull(core.completeTick(0));
		assertEquals(Source.LOCAL, preview(sample).source());
		assertNull(sample.shared());
		assertEquals(new TimingStats(5, 1, 5), preview(sample).timing(Phase.APPLY));
		assertEquals(new TimingStats(7, 1, 7), preview(sample).timing(Phase.PREPARE));
		assertEquals(new TimingStats(9, 1, 9), Objects.requireNonNull(sample.tracking()).timing(Phase.DRAW));
		assertEquals(40, Objects.requireNonNull(preview(sample).gauge(Gauge.BUFFER_ESTIMATED_BYTES)).value());
		assertEquals(2, Objects.requireNonNull(preview(sample).counter(Counter.ACCEPTED)).value());
		assertThrows(IllegalArgumentException.class, () -> core.recordCount(Lane.PREVIEW, Counter.HANDOFF_BYTES, 40));
		assertThrows(IllegalArgumentException.class, () -> new MonitorAccumulator(Source.SHARED));
	}

	@Test
	void tuningRejectsUnboundedWindowsWithoutLockingAnImplementationDefault() {
		assertThrows(IllegalArgumentException.class, () -> new MonitorTuning(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new MonitorTuning(1, 0));
		assertThrows(IllegalArgumentException.class, () -> new MonitorTuning(MonitorTuning.MAX_TICKS_PER_BUCKET + 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new MonitorTuning(1, MonitorTuning.MAX_BUCKET_COUNT + 1));
		assertEquals(6, new MonitorTuning(2, 3).windowTicks());
	}

	@Test
	void rollingTotalsMatchIndependentTickHistoryAcrossRepeatedWrapsAndIdleGaps() {
		var clock = new FakeClock();
		var core = new MonitorAccumulator(Source.SERVER, new MonitorTuning(2, 3), clock);
		core.configure(true, false);
		var history = new ArrayDeque<long[]>();
		long sequence = 0;
		for (long tick = 0; tick < 120; tick++) {
			clock.advance(10);
			if (tick % 11 == 4 || tick % 11 == 5) continue;
			long delta = tick % 7;
			history.add(new long[] {tick, delta});
			core.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, delta);
			@Nullable MonitorSample sample = core.completeTick(tick);
			if (sample == null) continue;
			while (history.getFirst()[0] / 2 < tick / 2 - 2) history.removeFirst();
			long expected = 0;
			for (long[] event : history) expected += event[1];
			assertEquals(expected, Objects.requireNonNull(preview(sample).counter(Counter.REQUESTS_RECEIVED)).value(),
				"rolling window at tick " + tick);
			assertEquals(++sequence, sample.sampleSequence());
		}
		assertTrue(sequence > 30, "exercise repeated publication and ring wrap");
	}

	private static LaneSample preview(MonitorSample sample) { return Objects.requireNonNull(sample.preview()); }

	@NotNullByDefault
	private static final class FakeClock implements LongSupplier {
		long now;
		int reads;
		void advance(long elapsed) { now += elapsed; }
		@Override public long getAsLong() { reads++; return now; }
	}
}
