package nx.pingwheel.common.presentation.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerIdSource;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.ServerMarkerStore;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationPropertySelection;
import nx.pingwheel.common.presentation.PresentationRegistry;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationServerFairRotationTest {
	static final UUID OWNER = new UUID(0, 1), VIEWER = new UUID(0, 2);
	static final String OPTIONAL = "test:optional", FIELD = "test:value";
	static class Adapter implements PresentationAdapter {
		final String id;
		final int interval;
		final Map<Integer, List<Long>> reads = new HashMap<>();
		long tick;
		boolean fail;
		Adapter(String id, int interval) { this.id = id; this.interval = interval; }
		public String adapterId() { return id; }
		public String modId() { return "test"; }
		public int schema() { return 1; }
		public int minUpdateIntervalTicks() { return interval; }
		public List<PresentationField> fields() {
			return id.equals(PresentationBasic.ID) ? PresentationBasic.fields()
				: List.of(new PresentationField(FIELD, PresentationField.Kind.FLAG, true, 0, "Value"));
		}
		public PresentationSection collect(DetachedTarget target, Set<String> demand, CaptureBudget budget) {
			assertTrue(budget.scan());
			reads.computeIfAbsent(target.x(), ignored -> new ArrayList<>()).add(tick);
			return fail ? null : value(id);
		}
	}
	static PresentationSection value(String id) {
		return new PresentationSection(id, 1, id.equals(PresentationBasic.ID)
			? Map.of(PresentationBasic.NAME, new PresentationValue.Text("name"))
			: Map.of(FIELD, new PresentationValue.Flag(true)), false);
	}
	static Set<String> demand(PresentationAdapter adapter) {
		return Set.of(adapter.adapterId().equals(PresentationBasic.ID) ? PresentationBasic.NAME : FIELD);
	}
	static ServerMarker create(ServerMarkerStore store, int position, long arrival, long expiry) {
		return create(store, position, arrival, expiry, List.of());
	}
	static ServerMarker create(ServerMarkerStore store, int position, long arrival, long expiry,
		List<PresentationPropertySelection> selections) {
		var type = TargetTypeCatalog.builtIn().findById("block").orElseThrow();
		return store.create(OWNER, new Target.BlockTarget("minecraft:overworld", position, 1, 2, "minecraft:stone"),
			type, type.defaultPingType(), new MarkerAnchor(position, 1, 2), arrival, expiry, List.of(VIEWER), selections).marker();
	}
	static class Sink implements BudgetDelaySink {
		final List<DelayEvent> events = new ArrayList<>();
		public void observe(DelayEvent event) { events.add(event); }
		public void state(Module module, long cycle, long now, DelayState state) { fail("sampler is not cycle-end owner"); }
		public void endCycle(Module module, long cycle, long now) { fail("sampler is not cycle-end owner"); }
	}

	@Test void entireOverflowPopulationRepeatedlyGetsBasicAndOptionalTurnsUnderCreatesAndMetadataChurn() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		int population = 1107;
		for (int id = 0; id < population; id++) create(store, id, 0, 100000);
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var adapters = new PresentationRegistry(); var basic = new Adapter(PresentationBasic.ID, 7); var optional = new Adapter(OPTIONAL, 13);
		adapters.register(basic); adapters.register(optional);
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1); policy.setScanBudget(4);
		var sampler = new PresentationFairSampler();
		Map<Integer, List<Long>> basicReads = new HashMap<>();
		for (long tick = 20; tick < 540; tick++) {
			optional.tick = tick;
			long now = tick;
			if (tick % 7 == 0) create(store, 2000 + (int) tick, tick, 100000);
			// Metadata churn updates the same canonical identity; it never restarts a sweep.
			if (!cache.isEmpty()) cache.values().iterator().next().ownerName = "owner-" + tick;
			var work = new PresentationAdapter.CaptureBudget(4096);
			var result = sampler.tick(store, cache, adapters, policy, tick, work, 32,
				(marker, adapter) -> demand(adapter), (marker, fields) -> {
					int position = ((Target.BlockTarget) marker.target()).x();
					basicReads.computeIfAbsent(position, ignored -> new ArrayList<>()).add(now); return value(PresentationBasic.ID);
				}, marker -> "Owner", lease -> {}, BudgetDelaySink.NONE, tick);
			assertTrue(result.captures() <= 32);
			assertTrue(cache.size() <= 1024);
			int optionalCaptures = optional.reads.values().stream().mapToInt(reads ->
				(int) reads.stream().filter(time -> time == now).count()).sum();
			assertEquals(result.captures() + optionalCaptures, 4096 - work.remaining(), "measured scans plus each adapter overhead");
		}
		for (int id = 0; id < population; id++) {
			assertTrue(basicReads.getOrDefault(id, List.of()).size() >= 3, "Basic repeatedly reaches marker " + id);
			assertTrue(optional.reads.getOrDefault(id, List.of()).size() >= 3, "optional repeatedly reaches marker " + id);
			assertSpacing(basicReads.get(id), 7);
			assertSpacing(optional.reads.get(id), 13);
		}
	}

	@Test void blockedOptionalResumesWithoutRepeatingBasicAndFailedPaidTurnAdvances() {
		var store = new ServerMarkerStore(new MarkerIdSource()); create(store, 0, 0, 10000); create(store, 1, 0, 10000);
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 1));
		var optional = new Adapter(OPTIONAL, 1); optional.fail = true; adapters.register(optional);
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1);
		var sampler = new PresentationFairSampler(); var sink = new Sink(); List<Integer> basics = new ArrayList<>();
		var first = sampler.tick(store, cache, adapters, policy, 10, new PresentationAdapter.CaptureBudget(1), 32,
			(marker, adapter) -> demand(adapter), (marker, fields) -> { basics.add(((Target.BlockTarget) marker.target()).x()); return value(PresentationBasic.ID); },
			marker -> "Owner", lease -> {}, sink, 10);
		assertEquals(List.of(0), basics); assertEquals(1, first.captures()); assertEquals(DelayState.DELAYED, first.state());
		assertEquals(Reason.SOURCE_WORK.bit(), sink.events.getFirst().newReasonBits());
		assertTrue(sink.events.getFirst().item().identity().endsWith(OPTIONAL));
		assertEquals(Unit.MARKER_ADAPTER, sink.events.getFirst().quantity().unit());
		optional.tick = 11;
		var second = sampler.tick(store, cache, adapters, policy, 11, new PresentationAdapter.CaptureBudget(20), 32,
			(marker, adapter) -> demand(adapter), (marker, fields) -> { basics.add(((Target.BlockTarget) marker.target()).x()); return value(PresentationBasic.ID); },
			marker -> "Owner", lease -> {}, sink, 11);
		assertEquals(List.of(0, 1), basics, "Basic id 0 is not rerun when optional resumes a later tick");
		assertEquals(1, optional.reads.get(0).size()); assertEquals(1, optional.reads.get(1).size());
		assertEquals(3, second.captures()); assertEquals(DelayState.HEALTHY, second.state());
		assertEquals(1, sink.events.size(), "paid failures are service turns, not resource refusals");
	}

	@Test void ageSafeFullCacheWaitPinsOneMissAndRemovalFreesCapacityWithoutMutatingPriorValues() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var blocked = create(store, 0, 0, 10000);
		for (int i = 1; i <= 1024; i++) {
			var marker = create(store, i, 0, 10000); var lease = new PresentationServer.Lease(marker, "Owner");
			var source = new PresentationServer.Source(PresentationBasic.ID, 1); source.value = value(PresentationBasic.ID);
			source.observation = PresentationServer.Source.Observation.SUCCESS; source.nextSample = 100;
			lease.sources.put(PresentationBasic.ID, source); assertTrue(PresentationServer.rememberLease(cache, lease));
		}
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 1));
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1);
		var sampler = new PresentationFairSampler(); var sink = new Sink(); List<Long> observed = new ArrayList<>();
		var result = sampler.tick(store, cache, adapters, policy, 10, new PresentationAdapter.CaptureBudget(40), 32,
			(marker, adapter) -> demand(adapter), (marker, fields) -> { observed.add(marker.id().value()); return value(PresentationBasic.ID); },
			marker -> "Owner", lease -> {}, sink, 10);
		assertEquals(0, result.captures()); assertEquals(DelayState.DELAYED, result.state()); assertTrue(sampler.hasParkedMiss(cache));
		assertEquals(Reason.CACHE_CAPACITY.bit(), sink.events.getFirst().newReasonBits());
		assertTrue(cache.values().stream().allMatch(lease -> !lease.sources.get(PresentationBasic.ID).value.stale()));
		store.removeByServer(new MarkerId(1), MarkerRemovalReason.CANCELLED);
		cache.remove(1L); // a vacancy opened between ordinary presentation ticks
		var newcomer = create(store, 2000, 11, 10000); var initial = new PresentationServer.Lease(newcomer, "New owner");
		var paid = new PresentationServer.Source(PresentationBasic.ID, 1); paid.value = value(PresentationBasic.ID);
		paid.observation = PresentationServer.Source.Observation.SUCCESS; paid.nextSample = 12;
		initial.sources.put(PresentationBasic.ID, paid);
		assertFalse(sampler.rememberInitial(cache, initial), "new creates cannot steal a parked canonical miss's vacancy");
		sampler.tick(store, cache, adapters, policy, 11, new PresentationAdapter.CaptureBudget(40), 32,
			(marker, adapter) -> demand(adapter), (marker, fields) -> { observed.add(marker.id().value()); return value(PresentationBasic.ID); },
			marker -> "Owner", lease -> {}, sink, 11);
		assertEquals(List.of(blocked.id().value()), observed); assertTrue(cache.containsKey(blocked.id().value()));
		assertEquals(1024, cache.size());
	}

	@Test void creationRetainsOnlyPaidInitialAttemptsAndNeverEvictsAnOlderEntry() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var sampler = new PresentationFairSampler();
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var unserviced = new PresentationServer.Lease(create(store, 0, 0, 10000), "Owner");
		unserviced.sources.put(PresentationBasic.ID, new PresentationServer.Source(PresentationBasic.ID, 1));
		assertFalse(sampler.rememberInitial(cache, unserviced)); assertTrue(cache.isEmpty());
		var failed = new PresentationServer.Lease(create(store, 1, 0, 10000), "Owner");
		var source = new PresentationServer.Source(PresentationBasic.ID, 1); source.observation = PresentationServer.Source.Observation.FAILED;
		source.nextSample = 100; failed.sources.put(PresentationBasic.ID, source);
		assertTrue(sampler.rememberInitial(cache, failed)); assertSame(failed, cache.get(failed.marker.id().value()));
		for (int i = 2; cache.size() < 1024; i++) {
			var lease = new PresentationServer.Lease(create(store, i, 0, 10000), "Owner");
			var seed = new PresentationServer.Source(PresentationBasic.ID, 1); seed.observation = PresentationServer.Source.Observation.SUCCESS;
			seed.value = value(PresentationBasic.ID); seed.nextSample = 100; lease.sources.put(PresentationBasic.ID, seed);
			assertTrue(sampler.rememberInitial(cache, lease));
		}
		assertFalse(sampler.rememberInitial(cache, failed));
		assertSame(failed, cache.values().iterator().next(), "initial paid work owns no cache eviction or insertion-order rotation");
	}

	@Test void seededMissFloorsAndLatchedLiveIntervalsSurviveEvictionWithoutExtraSweepCooldown() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		var selected = PresentationPropertySelection.of(PresentationPropertyRef.root(OPTIONAL, FIELD), "attention");
		var marker = create(store, 0, 20, 10000, List.of(selected));
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 5));
		var optional = new Adapter(OPTIONAL, 11); adapters.register(optional);
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1);
		var sampler = new PresentationFairSampler(); List<Long> basicReads = new ArrayList<>();
		for (long tick = 20; tick <= 32; tick++) {
			long now = tick; optional.tick = tick;
			sampler.tick(store, cache, adapters, policy, tick, new PresentationAdapter.CaptureBudget(10), 32,
				(m, a) -> demand(a), (m, fields) -> { basicReads.add(now); return value(PresentationBasic.ID); },
				m -> "Owner", lease -> {}, BudgetDelaySink.NONE, tick);
		}
		assertEquals(List.of(25L, 30L), basicReads); assertEquals(List.of(31L), optional.reads.get(0));
		long latched = cache.get(marker.id().value()).sources.get(OPTIONAL).nextSample;
		policy.setUpdateIntervals(Map.of(OPTIONAL, 90));
		assertEquals(42, latched); // new configuration cannot reinterpret already paid observation deadlines
		for (long tick = 33; tick <= 42; tick++) {
			long now = tick; optional.tick = tick;
			sampler.tick(store, cache, adapters, policy, tick, new PresentationAdapter.CaptureBudget(10), 32,
				(m, a) -> demand(a), (m, fields) -> { basicReads.add(now); return value(PresentationBasic.ID); },
				m -> "Owner", lease -> {}, BudgetDelaySink.NONE, tick);
		}
		assertEquals(List.of(31L, 42L), optional.reads.get(0));
		assertEquals(132, cache.get(marker.id().value()).sources.get(OPTIONAL).nextSample);
		assertEquals(Long.MAX_VALUE, PresentationServer.addTick(Long.MAX_VALUE - 1, 5));
	}

	@Test void insufficientThenRestoredAllowanceProgressesAndDiscoveryOrCadenceIsNotOverload() {
		var store = new ServerMarkerStore(new MarkerIdSource());
		for (int i = 0; i < 601; i++) create(store, i, 0, 10000);
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 1));
		var optional = new Adapter(OPTIONAL, 1); adapters.register(optional);
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1); policy.setScanBudget(0);
		var sampler = new PresentationFairSampler(); var sink = new Sink();
		assertEquals(DelayState.DELAYED, sampler.tick(store, cache, adapters, policy, 10,
			new PresentationAdapter.CaptureBudget(10), 32, (m, a) -> demand(a), (m, f) -> fail("capture disabled"), m -> "Owner", l -> {}, sink, 10).state());
		assertTrue(cache.isEmpty()); assertEquals(Reason.CAPTURE_DISABLED.bit(), sink.events.getFirst().newReasonBits());
		policy.setScanBudget(1); optional.tick = 11;
		assertEquals(32, sampler.tick(store, cache, adapters, policy, 11,
			new PresentationAdapter.CaptureBudget(4096), 32, (m, a) -> demand(a), (m, f) -> value(PresentationBasic.ID), m -> "Owner", l -> {}, sink, 11).captures());
		assertEquals(16, optional.reads.size());
		// No-demand discovery spans several pages and cannot establish a healthy or idle global sweep early.
		var quiet = new PresentationFairSampler(); var quietSink = new Sink();
		assertEquals(DelayState.NEUTRAL, quiet.tick(store, new LinkedHashMap<>(), adapters, policy, 0,
			new PresentationAdapter.CaptureBudget(0), 0, (m, a) -> Set.of(), (m, f) -> fail("no demand"), m -> "Owner", l -> {}, quietSink, 0).state());
		assertTrue(quietSink.events.isEmpty());
		var cadenceStore = new ServerMarkerStore(new MarkerIdSource()); create(cadenceStore, 0, 10, 10000);
		var cadence = new PresentationFairSampler(); var cadenceSink = new Sink();
		assertEquals(DelayState.NEUTRAL, cadence.tick(cadenceStore, new LinkedHashMap<>(), adapters, policy, 10,
			new PresentationAdapter.CaptureBudget(0), 0, (m, a) -> a.adapterId().equals(PresentationBasic.ID) ? demand(a) : Set.of(),
			(m, f) -> fail("arrival floor"), m -> "Owner", l -> {}, cadenceSink, 10).state());
		assertTrue(cadenceSink.events.isEmpty());
	}

	@Test void ageSafeEvictionAndReentryNeverRecapturePaidInitialSeedsBeforeTheirHeterogeneousDeadlines() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var cache = new LinkedHashMap<Long, PresentationServer.Lease>();
		var first = create(store, 0, 0, 10000);
		for (int id = 1; id <= 1024; id++) {
			var marker = create(store, id, 0, 10000); var lease = new PresentationServer.Lease(marker, "Owner");
			var basicSeed = new PresentationServer.Source(PresentationBasic.ID, 1);
			basicSeed.value = value(PresentationBasic.ID); basicSeed.observation = PresentationServer.Source.Observation.SUCCESS; basicSeed.nextSample = 5;
			var optionalSeed = new PresentationServer.Source(OPTIONAL, 1);
			optionalSeed.value = value(OPTIONAL); optionalSeed.observation = PresentationServer.Source.Observation.SUCCESS; optionalSeed.nextSample = 18;
			lease.sources.put(PresentationBasic.ID, basicSeed); lease.sources.put(OPTIONAL, optionalSeed);
			assertTrue(PresentationServer.rememberLease(cache, lease));
		}
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 5));
		var optional = new Adapter(OPTIONAL, 18); adapters.register(optional);
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1);
		var sampler = new PresentationFairSampler(); var sink = new Sink(); Map<Integer, List<Long>> basicReads = new HashMap<>();
		for (long tick = 5; tick <= 200; tick++) {
			optional.tick = tick; long now = tick;
			sampler.tick(store, cache, adapters, policy, tick, new PresentationAdapter.CaptureBudget(4096), 32,
				(m, a) -> demand(a), (m, fields) -> { basicReads.computeIfAbsent(((Target.BlockTarget) m.target()).x(), ignored -> new ArrayList<>()).add(now); return value(PresentationBasic.ID); },
				m -> "Owner", l -> {}, sink, tick);
			if (tick < 18) {
				assertFalse(cache.containsKey(first.id().value()), "an elapsed Basic floor cannot erase a retained optional seed deadline");
				assertTrue(basicReads.isEmpty()); assertTrue(optional.reads.isEmpty());
			}
		}
		assertTrue(basicReads.get(0).size() >= 2); assertTrue(optional.reads.get(0).size() >= 2);
		for (int id = 1; id <= 1024; id++) {
			assertTrue(basicReads.containsKey(id)); assertTrue(optional.reads.containsKey(id));
			List<Long> basicsWithSeed = new ArrayList<>(); basicsWithSeed.add(0L); basicsWithSeed.addAll(basicReads.get(id));
			List<Long> optionalWithSeed = new ArrayList<>(); optionalWithSeed.add(0L); optionalWithSeed.addAll(optional.reads.get(id));
			assertSpacing(basicsWithSeed, 5); assertSpacing(optionalWithSeed, 18);
		}
	}

	@Test void parkedRemovedExpiredAndNoLongerDemandedJobsReleaseTheCanonicalTurn() {
		for (String exit : List.of("removed", "expired", "no demand")) {
			var store = new ServerMarkerStore(new MarkerIdSource()); var first = create(store, 0, 0, 12); create(store, 1, 0, 10000);
			var cache = new LinkedHashMap<Long, PresentationServer.Lease>(); var adapters = new PresentationRegistry();
			adapters.register(new Adapter(PresentationBasic.ID, 1)); var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1);
			var sampler = new PresentationFairSampler(); var sink = new Sink(); List<Long> reads = new ArrayList<>();
			assertEquals(DelayState.DELAYED, sampler.tick(store, cache, adapters, policy, 10,
				new PresentationAdapter.CaptureBudget(0), 0, (m, a) -> demand(a), (m, f) -> fail("blocked"), m -> "Owner", l -> {}, sink, 10).state());
			if (exit.equals("removed")) store.removeByServer(first.id(), MarkerRemovalReason.CANCELLED);
			long tick = exit.equals("expired") ? 12 : 11;
			sampler.tick(store, cache, adapters, policy, tick, new PresentationAdapter.CaptureBudget(10), 32,
				(m, a) -> exit.equals("no demand") && m.id().equals(first.id()) ? Set.of() : demand(a),
				(m, f) -> { reads.add(m.id().value()); return value(PresentationBasic.ID); }, m -> "Owner", l -> {}, sink, tick);
			assertEquals(List.of(1L), reads, exit + " current item cannot keep a later continuously eligible marker parked");
		}
	}

	@Test void paidInitialCaptureSeedsExactCadenceAndAnUnretainedSeedStillUsesArrivalFloor() {
		var store = new ServerMarkerStore(new MarkerIdSource()); var marker = create(store, 0, 50, 10000);
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 7));
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1);
		var seed = new PresentationServer.Lease(marker, "Owner"); List<Long> reads = new ArrayList<>();
		assertEquals(1, PresentationServer.captureSources(seed, adapters, policy, 50,
			new PresentationAdapter.CaptureBudget(1), 1, true, PresentationServerFairRotationTest::demand,
			fields -> { reads.add(50L); return value(PresentationBasic.ID); }));
		assertEquals(57, seed.sources.get(PresentationBasic.ID).nextSample);
		var cache = new LinkedHashMap<Long, PresentationServer.Lease>(); var sampler = new PresentationFairSampler();
		assertTrue(sampler.rememberInitial(cache, seed));
		for (long tick = 51; tick <= 57; tick++) {
			long now = tick;
			sampler.tick(store, cache, adapters, policy, tick, new PresentationAdapter.CaptureBudget(10), 32,
				(m, a) -> demand(a), (m, f) -> { reads.add(now); return value(PresentationBasic.ID); }, m -> "Owner", l -> {}, BudgetDelaySink.NONE, tick);
		}
		assertEquals(List.of(50L, 57L), reads);
		// A paid initial delivered outside the heavy cache cannot be read again at arrival+1.
		var miss = new PresentationFairSampler(); var emptyCache = new LinkedHashMap<Long, PresentationServer.Lease>(); List<Long> reentry = new ArrayList<>();
		for (long tick = 51; tick <= 57; tick++) {
			long now = tick;
			miss.tick(store, emptyCache, adapters, policy, tick, new PresentationAdapter.CaptureBudget(10), 32,
				(m, a) -> demand(a), (m, f) -> { reentry.add(now); return value(PresentationBasic.ID); }, m -> "Owner", l -> {}, BudgetDelaySink.NONE, tick);
		}
		assertEquals(List.of(57L), reentry);
	}

	@Test void currentAdapterReasonsDeduplicateWithinCycleAndMonitoringFailureCannotBlockRestoredWork() {
		var store = new ServerMarkerStore(new MarkerIdSource()); create(store, 0, 0, 10000);
		var adapters = new PresentationRegistry(); adapters.register(new Adapter(PresentationBasic.ID, 1));
		var policy = PresentationSettings.serverDefaults(); policy.setMinUpdateIntervalTicks(1); policy.setScanBudget(0);
		var sampler = new PresentationFairSampler(); var cache = new LinkedHashMap<Long, PresentationServer.Lease>(); var sink = new Sink();
		for (int repeat = 0; repeat < 2; repeat++) sampler.tick(store, cache, adapters, policy, 10,
			new PresentationAdapter.CaptureBudget(10), 32, (m, a) -> demand(a), (m, f) -> fail("disabled"), m -> "Owner", l -> {}, sink, repeat);
		assertEquals(1, sink.events.size());
		sampler.tick(store, cache, adapters, policy, 10, new PresentationAdapter.CaptureBudget(0), 0,
			(m, a) -> demand(a), (m, f) -> fail("disabled"), m -> "Owner", l -> {}, sink, 2);
		assertEquals(2, sink.events.size()); assertFalse(sink.events.getLast().firstItemObservation());
		assertEquals(Reason.CAPTURE_COUNT.bit() | Reason.SOURCE_WORK.bit(), sink.events.getLast().newReasonBits());
		var throwing = new BudgetDelaySink() {
			public void observe(DelayEvent event) { throw new LinkageError("diagnostics missing"); }
			public void state(Module module, long cycle, long now, DelayState state) {}
			public void endCycle(Module module, long cycle, long now) {}
		};
		assertDoesNotThrow(() -> sampler.tick(store, cache, adapters, policy, 11, new PresentationAdapter.CaptureBudget(0), 0,
			(m, a) -> demand(a), (m, f) -> fail("disabled"), m -> "Owner", l -> {}, throwing, 3));
		policy.setScanBudget(1);
		var result = sampler.tick(store, cache, adapters, policy, 12, new PresentationAdapter.CaptureBudget(10), 32,
			(m, a) -> demand(a), (m, f) -> value(PresentationBasic.ID), m -> "Owner", l -> {}, throwing, 4);
		assertEquals(1, result.captures()); assertEquals(DelayState.HEALTHY, result.state());
	}

	static void assertSpacing(List<Long> observations, int interval) {
		for (int i = 1; i < observations.size(); i++) assertTrue(observations.get(i) - observations.get(i - 1) >= interval);
	}
}
