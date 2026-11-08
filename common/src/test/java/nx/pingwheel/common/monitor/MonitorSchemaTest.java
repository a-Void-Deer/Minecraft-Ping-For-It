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
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.Timing;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class MonitorSchemaTest {

	@Test
	void stableSelectorsHaveClosedLegalScopesAndExplicitWireIdentities() {
		assertEquals(List.of("summary.processing", "summary.requests_queue", "summary.budget",
			"summary.memory", "summary.sync_rate"), Metric.defaultIds());
		var ids = new HashSet<String>();
		var wireIds = new HashSet<Integer>();
		for (Metric metric : Metric.all()) {
			assertTrue(ids.add(metric.id()), "duplicate persisted selector");
			assertTrue(wireIds.add(metric.wireId()), "duplicate wire identity");
			assertSame(metric, Metric.byId(metric.id()));
			assertSame(metric, Metric.byWireId(metric.wireId()));
			if (metric.kind() == Metric.Kind.SUMMARY) assertNull(metric.scope());
			else assertNotNull(metric.scope());
		}
		assertSame(Metric.SUMMARY_PROCESSING, Metric.byWireId(1));
		assertEquals(1302, Objects.requireNonNull(Metric.byId("server.preview.timing.ingress")).wireId());
		assertNotNull(Metric.byId("local.tracking.timing.draw.max"));
		assertNotNull(Metric.byId("shared.budget.physical_slots"));
		assertNotNull(Metric.byId("shared.memory"));
		assertNull(Metric.byId("server.preview.memory"));
		assertNull(Metric.byId("shared.preview.memory"));
		assertNull(Metric.byId("local.preview.timing.capture"));
		assertNull(Metric.byId("server.preview.timing.draw"));
		assertNull(Metric.byId("server.preview.sync.frames_stream"));
		assertNull(Metric.byId("Server.Preview.Timing.Ingress"));
		assertNull(Metric.byWireId(0));
		assertNull(Metric.byWireId(Integer.MAX_VALUE));
		assertThrows(UnsupportedOperationException.class, () -> Metric.all().clear());
		assertThrows(UnsupportedOperationException.class, () -> Metric.defaultIds().add("invented"));
	}

	@Test
	void summaryExpansionSelectsRawFieldsWithoutDoubleCountingInclusivePhasesOrSharedMemory() {
		var processing = Metric.SUMMARY_PROCESSING.expanded();
		assertTrue(processing.contains(Metric.byId("server.preview.timing.tick_work")));
		assertTrue(processing.contains(Metric.byId("server.preview.timing.ingress")));
		assertTrue(processing.contains(Metric.byId("local.preview.timing.apply")));
		assertFalse(processing.contains(Metric.byId("server.preview.timing.capture")), "inclusive child is not an extra total");
		assertTrue(processing.stream().allMatch(metric -> metric.kind() == Metric.Kind.TIMING));
		Metric detail = Objects.requireNonNull(Metric.byId("server.preview.timing.tick_work"));
		assertEquals(processing, Metric.expand(List.of(Metric.SUMMARY_PROCESSING, detail, detail)));
		var memory = Metric.SUMMARY_MEMORY.expanded();
		assertEquals(1, memory.stream().filter(metric -> metric.kind() == Metric.Kind.MEMORY).count());
		assertTrue(memory.contains(Metric.byId("local.tracking.buffer.estimated_bytes")));
		assertThrows(UnsupportedOperationException.class, () -> processing.clear());
		assertThrows(UnsupportedOperationException.class, () -> detail.expanded().clear());
	}

	@Test
	void enumDecodersAndMasksDoNotAcceptUnknownScopeOrUseSharedLaneAliases() {
		assertSame(Lane.PREVIEW, Lane.byWireId(1));
		assertSame(Lane.TRACKING, Lane.byWireId(2));
		assertEquals(3, Lane.PREVIEW.mask() | Lane.TRACKING.mask());
		assertSame(Scope.SHARED, Scope.byWireId(5));
		assertSame(Source.SHARED, Source.byWireId(3));
		assertNull(Lane.byWireId(0));
		assertNull(Source.byWireId(4));
		assertNull(Scope.byWireId(6));
		assertNull(Phase.byWireId(0));
		assertNull(Counter.byWireId(255));
		assertNull(Gauge.byWireId(-1));
		assertNull(Budget.byWireId(0));
		assertThrows(IllegalArgumentException.class, () -> new Scope(Source.LOCAL, null));
		assertThrows(IllegalArgumentException.class, () -> new Scope(Source.SHARED, Lane.PREVIEW));
		assertFalse(Budget.PHYSICAL_SLOTS.supports(Scope.SERVER_PREVIEW));
		assertFalse(Gauge.BUFFER_ESTIMATED_BYTES.supports(Scope.SERVER_TRACKING));
		assertFalse(Counter.HANDOFF_BYTES.supports(Scope.LOCAL_TRACKING));
	}

	@Test
	void laneAndSharedReadingsAreDefensivelyCopiedAndMissingFieldsStayUnknown() {
		var timings = new ArrayList<>(List.of(new Timing(Phase.TICK_WORK, new TimingStats(8, 1, 8)),
			new Timing(Phase.INGRESS, new TimingStats(0, 1, 0))));
		var counters = new ArrayList<>(List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 0, false)));
		var gauges = new ArrayList<>(List.of(new GaugeReading(Gauge.QUEUE_LOGICAL_COST, 12)));
		var budgets = new ArrayList<>(List.of(new BudgetReading(Budget.WIRE_BYTES, BudgetUsage.unavailable())));
		var lane = new LaneSample(Source.SERVER, Lane.PREVIEW, timings, counters, gauges, budgets);
		timings.clear(); counters.clear(); gauges.clear(); budgets.clear();
		assertEquals(2, lane.timings().size());
		assertEquals(Phase.INGRESS, lane.timings().getFirst().phase());
		assertEquals(0, Objects.requireNonNull(lane.counter(Counter.REQUESTS_RECEIVED)).value());
		assertNull(lane.counter(Counter.REQUESTS_ADMITTED));
		assertNull(lane.timing(Phase.CAPTURE));
		assertNull(lane.gauge(Gauge.PENDING_ENTRIES));
		assertFalse(Objects.requireNonNull(lane.budget(Budget.WIRE_BYTES)).available());
		assertNull(lane.budget(Budget.LOGICAL_SLOTS));
		assertThrows(UnsupportedOperationException.class, () -> lane.timings().clear());
		assertThrows(UnsupportedOperationException.class, () -> lane.counters().clear());
		assertThrows(UnsupportedOperationException.class, () -> lane.gauges().clear());
		assertThrows(UnsupportedOperationException.class, () -> lane.budgets().clear());
		var sharedCounters = new ArrayList<>(List.of(new CounterReading(Counter.READ_ATTEMPTS, 1, false)));
		var shared = new SharedSample(sharedCounters, List.of(), List.of(), null);
		sharedCounters.clear();
		assertEquals(1, Objects.requireNonNull(shared.counter(Counter.READ_ATTEMPTS)).value());
		assertNull(shared.memory());
		assertThrows(UnsupportedOperationException.class, () -> shared.counters().clear());
	}

	@Test
	void samplesRejectDuplicatesAndMetricsThatWouldMisattributePhysicalWorkOrLocalBytes() {
		var tick = new Timing(Phase.TICK_WORK, new TimingStats(10, 1, 10));
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(tick, tick), List.of(), List.of(), List.of()));
		var request = new CounterReading(Counter.REQUESTS_RECEIVED, 1, false);
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(), List.of(request, request), List.of(), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(), List.of(), List.of(new GaugeReading(Gauge.BUFFER_ESTIMATED_BYTES, 3)), List.of()));
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(), List.of(), List.of(), List.of(new BudgetReading(Budget.PHYSICAL_SLOTS, BudgetUsage.unavailable()))));
		assertThrows(IllegalArgumentException.class, () -> new SharedSample(List.of(request), List.of(), List.of(), null));
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.LOCAL, Lane.PREVIEW,
			List.of(tick), List.of(), List.of(), List.of()));
		var gauge = new GaugeReading(Gauge.REQUESTS_OPEN, 1);
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(), List.of(), List.of(gauge, gauge), List.of()));
		var budget = new BudgetReading(Budget.WIRE_BYTES, BudgetUsage.unavailable());
		assertThrows(IllegalArgumentException.class, () -> new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(), List.of(), List.of(), List.of(budget, budget)));
	}

	@Test
	void memoryReductionAndSmoothingBudgetRemainDistinctFromPretendUtilization() {
		var memory = MemoryUsage.accounted(80, 20, 50);
		assertEquals(-50, memory.remaining(), "cap reduction cannot erase live accounting");
		assertEquals(0, memory.admissibleRemaining());
		assertThrows(IllegalArgumentException.class, () -> new MemoryUsage(80, 20, 50, 0));
		assertThrows(IllegalArgumentException.class, () -> MemoryUsage.accounted(Long.MAX_VALUE, Long.MAX_VALUE, 0));
		var smoothed = new BudgetUsage(true, 3, 100, 140, 0, 60, 40);
		assertEquals(140, smoothed.used());
		assertEquals(60, smoothed.remaining(), "rolling allowance is not inferred as limit minus used");
		assertEquals(40, smoothed.admissibleRemaining());
		assertEquals(-3, new BudgetUsage(true, 4, 10, 13, 0, -3, 0).remaining());
		assertThrows(IllegalArgumentException.class, () -> new BudgetUsage(false, 3, 0, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new BudgetUsage(true, 0, 1, 0, 0, 1, 2));
		assertThrows(IllegalArgumentException.class, () -> new GaugeReading(Gauge.CHANNELS, -1));
		assertThrows(IllegalArgumentException.class, () -> new CounterReading(Counter.ACCEPTED, 1, true));
	}

	@Test
	void meansAndRatesRespectWarmupSaturationAndEndpointSeparation() {
		assertTrue(new TimingStats(0, 0, 0).meanNs().isEmpty());
		assertEquals(0.0, new TimingStats(0, 1, 0).meanNs().orElseThrow());
		assertEquals(4.0, new TimingStats(12, 3, 8).meanNs().orElseThrow());
		assertTrue(new TimingStats(Long.MAX_VALUE, 3, Long.MAX_VALUE).meanNs().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new TimingStats(1, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new TimingStats(2, 1, 3));
		assertThrows(IllegalArgumentException.class, () -> new TimingStats(10, 1, 5));
		var local = new LaneSample(Source.LOCAL, Lane.PREVIEW, List.of(), List.of(), List.of(), List.of());
		var server = new LaneSample(Source.SERVER, Lane.TRACKING, List.of(), List.of(), List.of(), List.of());
		var reading = new CounterReading(Counter.ACCEPTED, 4, false);
		var warmup = new MonitorSample(1, 1, 1, 0, 0, 0, local, null, null);
		assertTrue(warmup.ratePerSecond(reading).isEmpty());
		var sample = new MonitorSample(1, 1, 2, 0, 4, 2_000_000_000L, local, null, null);
		assertEquals(2.0, sample.ratePerSecond(reading).orElseThrow());
		assertTrue(sample.ratePerSecond(new CounterReading(Counter.ACCEPTED, Long.MAX_VALUE, true)).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new MonitorSample(1, 1, 1, 0, 1, 1, local, server, null));
		assertThrows(IllegalArgumentException.class, () -> new MonitorSample(1, 1, 1, 0, 1, 1, local, null,
			new SharedSample(List.of(), List.of(), List.of(), null)));
		assertThrows(IllegalArgumentException.class, () -> new MonitorSample(1, 1, 1, 0, 0, 0, null, null, null));
		var shared = new SharedSample(List.of(), List.of(), List.of(), MemoryUsage.accounted(1, 0, 2));
		assertSame(shared, new MonitorSample(1, 1, 1, 0, 0, 0, null, null, shared).shared(),
			"shared-only transmission demand needs no fabricated lane");
	}
}
