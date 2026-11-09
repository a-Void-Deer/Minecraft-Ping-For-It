package nx.pingwheel.common.monitor.server;

import nx.pingwheel.common.monitor.MonitorAccumulator;
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
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator.CollectionState;
import nx.pingwheel.common.monitor.server.ServerMonitorCoordinator.Limits;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ServerMonitorCoordinatorTest {
	private static final UUID FIRST = new UUID(1, 1), SECOND = new UUID(2, 2);
	private static InventoryMonitorSubscribeC2SPacket demand(long revision, String... ids) {
		return InventoryMonitorSubscribeC2SPacket.watch(revision,
			java.util.Arrays.stream(ids).map(id -> Objects.requireNonNull(Metric.byId(id))).toList());
	}
	private static InventoryMonitorSubscribeC2SPacket requests(long revision) {
		return demand(revision, "server.preview.requests.received");
	}

	@Test void unauthorizedWatcherGetsOnlyPublicStatusWithoutReadingOrSerializingNumbers() {
		var f = new Fixture();
		f.authorized = false;
		assertTrue(f.coordinator.subscribe(FIRST, requests(1)));
		assertEquals(1, f.authorityReads, "subscription must consult trusted authority");
		f.coordinator.tick(0);
		assertEquals(1, f.statuses.size());
		assertFalse(f.statuses.getFirst().authorized());
		assertTrue(f.statuses.getFirst().previewEnabled());
		assertEquals(0, f.latestReads); assertTrue(f.sent.isEmpty());
	}

	@Test void authorityIsRecheckedImmediatelyBeforeSampleHandoff() {
		var f = new Fixture();
		f.coordinator.subscribe(FIRST, requests(1));
		f.revokeOnNextLatestRead = true;
		f.coordinator.tick(0);
		assertTrue(f.sent.isEmpty(), "permission held at subscribe/tick is not a send grant");
		f.coordinator.tick(1);
		assertFalse(f.statuses.getLast().authorized());
		assertEquals(2, f.statuses.getLast().permissionGeneration());
		assertTrue(f.authorityReads >= 4);
	}

	@Test void revocationAndRegrantAdvancePermissionFenceAndNeverReplayOldLatest() {
		var f = new Fixture();
		f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.tick(0);
		assertEquals(1, f.sent.size());
		f.authorized = false; f.latest = sample(2, 1); f.coordinator.tick(1);
		assertEquals(1, f.sent.size());
		assertFalse(f.statuses.getLast().authorized()); assertEquals(2, f.statuses.getLast().permissionGeneration());
		f.authorized = true; f.coordinator.tick(2);
		assertTrue(f.statuses.getLast().authorized()); assertEquals(3, f.statuses.getLast().permissionGeneration());
		assertEquals(1, f.sent.size(), "regrant waits beyond the existing latest snapshot");
		f.coordinator.tick(3); assertEquals(1, f.sent.size());
		f.latest = sample(3, 1); f.coordinator.tick(4);
		assertEquals(2, f.sent.size()); assertEquals(3, f.sent.getLast().permissionGeneration());
		assertEquals(3, Objects.requireNonNull(f.sent.getLast().sample()).sampleSequence());
	}

	@Test void stoppedRegrantKeepsFreshnessAcrossUnobservedCollectionChangeWithDefaults() {
		assertDefaultCollectorRegrantFence(false, 1);
	}

	@Test void stoppedRegrantResetsOldSequenceFloorWithoutDiscardingFreshnessWaitWithDefaults() {
		assertDefaultCollectorRegrantFence(false, 3);
	}

	@Test void stoppedRegrantKeepsFreshnessAcrossSimultaneousServerAndCollectionChangeWithDefaults() {
		assertDefaultCollectorRegrantFence(true, 3);
	}

	private static void assertDefaultCollectorRegrantFence(boolean changeServer, int oldPublications) {
		long[] clock = {0};
		var accumulator = new MonitorAccumulator(Source.SERVER, MonitorTuning.DEFAULT, () -> ++clock[0]);
		accumulator.resetGeneration(1); accumulator.configure(true, false);
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 1);
		int period = MonitorTuning.DEFAULT.ticksPerBucket();
		int initialTick = oldPublications * period - 1;
		for (int tick = 0; tick <= initialTick; tick++) accumulator.completeTick(tick);
		var f = new Fixture();
		var coordinator = defaultCoordinator(f, accumulator);
		assertTrue(coordinator.subscribe(FIRST, requests(1)));
		coordinator.tick(initialTick);
		assertEquals(1, f.sent.size(), "initial authorized subscription may use the collector's current baseline");
		assertEquals(oldPublications, Objects.requireNonNull(f.sent.getFirst().sample()).sampleSequence());
		long previousCollection = accumulator.collectionGeneration();
		f.authorized = false;
		assertTrue(coordinator.subscribe(FIRST, InventoryMonitorSubscribeC2SPacket.stop(2)));
		coordinator.tick(initialTick + 1L);
		assertEquals(0, coordinator.watchingConnections());
		if (changeServer) accumulator.resetGeneration(2);
		accumulator.configure(true, true);
		assertTrue(accumulator.collectionGeneration() > previousCollection);
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 73);
		int collectionStart = initialTick + 2;
		int cachedTick = collectionStart + period - 1;
		for (int tick = collectionStart; tick <= cachedTick; tick++) {
			accumulator.completeTick(tick); coordinator.tick(tick);
		}
		MonitorSample beforeGrant = Objects.requireNonNull(accumulator.latest());
		assertEquals(1, beforeGrant.sampleSequence(), "new collection's sequence space starts independently");
		assertEquals(73, Objects.requireNonNull(Objects.requireNonNull(beforeGrant.preview()).counter(Counter.REQUESTS_RECEIVED)).value());
		assertEquals(1, f.sent.size(), "no numeric handoff while stopped and unauthorized");
		f.authorized = true;
		assertTrue(coordinator.subscribe(FIRST, requests(3)));
		int grantTick = cachedTick + 1;
		assertNull(accumulator.completeTick(grantTick), "grant tick has no new default-window publication");
		coordinator.tick(grantTick);
		assertEquals(1, f.sent.size(), "collection observation cannot stamp pre-grant cached values with fresh permission");
		InventoryMonitorStatusS2CPacket status = f.statuses.getLast();
		assertTrue(status.authorized()); assertEquals(3, status.demandRevision());
		assertEquals(accumulator.serverGeneration(), status.serverGeneration());
		assertEquals(accumulator.collectionGeneration(), status.collectionGeneration());
		assertEquals(changeServer ? 1 : 3, status.permissionGeneration(), "server lifecycle may restart permission IDs, not freshness waiting");
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 2);
		for (int tick = grantTick + 1; tick < cachedTick + period; tick++) {
			assertNull(accumulator.completeTick(tick)); coordinator.tick(tick);
			assertEquals(1, f.sent.size(), "the same baseline must stay blocked until publication advances");
		}
		int freshTick = cachedTick + period;
		MonitorSample fresh = Objects.requireNonNull(accumulator.completeTick(freshTick));
		coordinator.tick(freshTick);
		assertEquals(2, f.sent.size(), "new sequence after regrant is delivered without waiting for the old collection's sequence floor");
		InventoryMonitorSampleS2CPacket delivered = f.sent.getLast();
		assertEquals(status.permissionGeneration(), delivered.permissionGeneration());
		MonitorSample projected = Objects.requireNonNull(delivered.sample());
		assertEquals(fresh.sampleSequence(), projected.sampleSequence());
		assertEquals(2, projected.sampleSequence());
		assertEquals(fresh.lastTick(), projected.lastTick());
		assertEquals(75, Objects.requireNonNull(Objects.requireNonNull(projected.preview()).counter(Counter.REQUESTS_RECEIVED)).value());
	}

	@Test void initialAuthorizedWatchAfterIdleCollectionChangeDoesNotAcquireRegrantWaitWithDefaults() {
		long[] clock = {0};
		var accumulator = new MonitorAccumulator(Source.SERVER, MonitorTuning.DEFAULT, () -> ++clock[0]);
		accumulator.resetGeneration(1); accumulator.configure(true, false);
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 8);
		var f = new Fixture();
		var coordinator = defaultCoordinator(f, accumulator);
		int period = MonitorTuning.DEFAULT.ticksPerBucket();
		for (int tick = 0; tick < period; tick++) { accumulator.completeTick(tick); coordinator.tick(tick); }
		assertTrue(f.sent.isEmpty());
		assertTrue(coordinator.subscribe(FIRST, requests(1))); coordinator.tick(period);
		assertEquals(1, f.sent.size(), "first authorized watch may use latest even when collection is first observed then");
		assertTrue(coordinator.subscribe(FIRST, InventoryMonitorSubscribeC2SPacket.stop(2)));
		coordinator.tick(period + 1L);
		accumulator.configure(true, true);
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 9);
		int start = period + 2;
		for (int tick = start; tick < start + period; tick++) { accumulator.completeTick(tick); coordinator.tick(tick); }
		MonitorSample latest = Objects.requireNonNull(accumulator.latest());
		assertTrue(coordinator.subscribe(FIRST, requests(3))); coordinator.tick(start + period);
		assertEquals(2, f.sent.size(), "same-authority resubscription is not a permission regrant");
		assertEquals(latest.sampleSequence(), Objects.requireNonNull(f.sent.getLast().sample()).sampleSequence());
	}

	private static ServerMonitorCoordinator defaultCoordinator(Fixture fixture, MonitorAccumulator accumulator) {
		return new ServerMonitorCoordinator(key -> fixture.authorized, fixture, fixture, accumulator::latest,
			() -> new CollectionState(accumulator.serverGeneration(), accumulator.collectionGeneration(),
				accumulator.enabled(Lane.PREVIEW), accumulator.enabled(Lane.TRACKING)));
	}

	@Test void offStatusDoesNotDependOnCollectorOrNumericDemand() {
		var f = new Fixture();
		f.state = new CollectionState(1, 2, false, false); f.latest = null;
		f.coordinator.subscribe(FIRST, demand(1)); f.coordinator.tick(0);
		var status = f.statuses.getFirst();
		assertFalse(status.previewEnabled()); assertFalse(status.trackingEnabled());
		assertEquals(2, status.collectionGeneration()); assertEquals(0, f.latestReads);
		f.coordinator.tick(10);
		assertEquals(2, f.statuses.size(), "bounded STATUS refresh works without SAMPLE");
	}

	@Test void capabilityUnknownUnavailableAndMissingStatusTransportNeverBecomeOff() {
		var f = new Fixture(); f.capability = SampleCapability.UNKNOWN;
		f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.tick(0);
		assertEquals(SampleCapability.UNKNOWN, f.statuses.getLast().sampleCapability());
		assertTrue(f.statuses.getLast().previewEnabled()); assertEquals(0, f.latestReads);
		f.capability = SampleCapability.UNAVAILABLE; f.coordinator.tick(1);
		assertEquals(SampleCapability.UNAVAILABLE, f.statuses.getLast().sampleCapability());
		assertTrue(f.statuses.getLast().previewEnabled()); assertTrue(f.sent.isEmpty());
		f.capability = SampleCapability.AVAILABLE; f.statusAvailable = false; f.coordinator.tick(2);
		assertTrue(f.sent.isEmpty(), "SAMPLE cannot grant the capability/authority STATUS never handed off");
		f.statusAvailable = true; f.coordinator.tick(3);
		assertEquals(SampleCapability.AVAILABLE, f.statuses.getLast().sampleCapability());
		assertEquals(1, f.sent.size());
	}

	@Test void zeroDemandWatchesStatusStopReleasesWatchAndOlderRevisionCannotReviveIt() {
		var f = new Fixture();
		f.coordinator.subscribe(FIRST, demand(1)); f.coordinator.tick(0);
		assertEquals(1, f.statuses.size()); assertEquals(0, f.latestReads);
		assertTrue(f.coordinator.subscribe(FIRST, InventoryMonitorSubscribeC2SPacket.stop(9)));
		assertEquals(0, f.coordinator.watchingConnections());
		assertFalse(f.coordinator.subscribe(FIRST, requests(8)));
		assertFalse(f.coordinator.subscribe(FIRST, requests(9)));
		f.coordinator.tick(20); assertEquals(1, f.statuses.size()); assertTrue(f.sent.isEmpty());
		assertTrue(f.coordinator.subscribe(FIRST, requests(14))); f.coordinator.tick(21);
		assertEquals(14, f.sent.getFirst().demandRevision());
		f.coordinator.disconnect(FIRST); assertEquals(0, f.coordinator.retainedConnections());
		assertTrue(f.coordinator.subscribe(FIRST, requests(1)), "new connection scope starts a new revision space");
	}

	@Test void projectionContainsOnlySelectedObservedFieldsAndKeepsSharedExactlyOnce() {
		var f = new Fixture();
		f.latest = new MonitorSample(1, 1, 1, 0, 0, 1,
			new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(),
				List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 7, false), new CounterReading(Counter.REQUESTS_ADMITTED, 3, false)),
				List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 2)),
				List.of(new BudgetReading(Budget.WIRE_BYTES, BudgetUsage.unavailable()))), null,
			new SharedSample(List.of(new CounterReading(Counter.READ_ATTEMPTS, 8, false)), List.of(), List.of(), MemoryUsage.accounted(80, 20, 50)));
		f.coordinator.subscribe(FIRST, demand(1, "server.preview.requests.received", "server.preview.budget.wire_bytes", "shared.memory"));
		f.coordinator.tick(0);
		MonitorSample projected = Objects.requireNonNull(f.sent.getFirst().sample());
		LaneSample lane = Objects.requireNonNull(projected.preview());
		assertEquals(List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 7, false)), lane.counters());
		assertTrue(lane.gauges().isEmpty()); assertTrue(lane.budgets().isEmpty());
		assertNull(projected.tracking());
		SharedSample shared = Objects.requireNonNull(projected.shared());
		assertTrue(shared.counters().isEmpty()); assertEquals(-50, Objects.requireNonNull(shared.memory()).remaining());
		f.coordinator.subscribe(FIRST, demand(2, "server.preview.queue.cap")); f.coordinator.tick(1);
		assertEquals(1, f.sent.size(), "unobserved selected values are not synthesized as zero or empty numeric samples");
	}

	@Test void sameSampleIsNotSentTwiceButNewRevisionMayProjectItAgain() {
		var f = new Fixture(); f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.tick(0);
		f.coordinator.tick(1); f.coordinator.tick(2); assertEquals(1, f.sent.size());
		f.coordinator.subscribe(FIRST, requests(3)); f.coordinator.tick(3);
		assertEquals(2, f.sent.size()); assertEquals(3, f.sent.getLast().demandRevision());
		f.latest = sample(2, 1); f.coordinator.tick(4); assertEquals(3, f.sent.size());
		f.latest = sample(1, 1); f.coordinator.tick(5); assertEquals(3, f.sent.size(), "collector sequence regression cannot replay values");
	}

	@Test void collectionChangesFenceOffAndOldSampleBeforeStatusAndNewSample() {
		var f = new Fixture(); f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.tick(0);
		f.state = new CollectionState(1, 2, false, false); f.coordinator.tick(1);
		assertFalse(f.statuses.getLast().previewEnabled()); assertEquals(2, f.statuses.getLast().collectionGeneration());
		assertEquals(1, f.sent.size());
		f.state = new CollectionState(1, 3, true, false); f.coordinator.tick(2);
		assertEquals(1, f.sent.size(), "prior collection's immutable latest is stale");
		f.latest = sample(1, 3); f.coordinator.tick(3);
		assertEquals(2, f.sent.size()); assertEquals(3, Objects.requireNonNull(f.sent.getLast().sample()).collectionGeneration());
		assertEquals(List.of("status:1", "sample:1", "status:1", "status:1", "sample:1"), f.order);
	}

	@Test void boundedRateCoalescesDemandAndSamplesWithoutPendingQueuesOrImmediateRetry() {
		var f = new Fixture(new Limits(5, 20, 2));
		f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.tick(0);
		for (int tick = 1; tick < 5; tick++) {
			f.coordinator.subscribe(FIRST, requests(tick + 1)); f.latest = sample(tick + 1, 1); f.coordinator.tick(tick);
		}
		assertEquals(1, f.sent.size()); assertEquals(1, f.statuses.size()); assertEquals(1, f.latestReads);
		f.coordinator.tick(5);
		assertEquals(2, f.sent.size()); assertEquals(5, f.sent.getLast().demandRevision());
		assertEquals(5, Objects.requireNonNull(f.sent.getLast().sample()).sampleSequence());
		f.sampleHandoff = false; f.latest = sample(6, 1); f.coordinator.tick(10);
		assertEquals(3, f.sent.size());
		f.sampleHandoff = true; f.coordinator.tick(15); assertEquals(3, f.sent.size(), "failed sample is not retried endlessly");
		f.coordinator.subscribe(SECOND, demand(1));
		assertFalse(f.coordinator.subscribe(new UUID(3, 3), demand(1)), "finite connection-state guard includes tombstones");
	}

	@Test void failedStatusHandoffUsesBoundedRefreshAndCannotAuthorizeSample() {
		var f = new Fixture(new Limits(2, 10, 4)); f.statusHandoff = false;
		f.coordinator.subscribe(FIRST, requests(1));
		for (int tick = 0; tick < 10; tick++) f.coordinator.tick(tick);
		assertEquals(1, f.statuses.size()); assertTrue(f.sent.isEmpty()); assertEquals(0, f.latestReads);
		f.statusHandoff = true; f.coordinator.tick(10);
		assertEquals(2, f.statuses.size()); assertEquals(1, f.sent.size());
	}

	@Test void noSubscriberDoesNotReadCollectorOrInvokeSenderAndManyViewersShareOneLatestRead() {
		var f = new Fixture(); f.coordinator.tick(0);
		assertEquals(0, f.stateReads); assertEquals(0, f.latestReads); assertTrue(f.order.isEmpty());
		f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.subscribe(SECOND, requests(1));
		f.coordinator.tick(1);
		assertEquals(1, f.latestReads); assertEquals(2, f.sent.size());
		assertEquals(f.sent.getFirst().sample(), f.sent.getLast().sample());
		f.coordinator.disconnect(FIRST); f.coordinator.disconnect(SECOND); f.coordinator.tick(2);
		assertEquals(1, f.latestReads);
	}

	@Test void actualAccumulatorIsReadOnlyAndCollectionIsIndependentOfViewers() {
		int[] clocks = {0};
		var accumulator = new MonitorAccumulator(Source.SERVER, new MonitorTuning(1, 2), () -> ++clocks[0]);
		accumulator.resetGeneration(1); accumulator.configure(true, false);
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 9); accumulator.completeTick(0);
		var f = new Fixture();
		var coordinator = new ServerMonitorCoordinator(key -> true, f, f, accumulator::latest,
			() -> new CollectionState(accumulator.serverGeneration(), accumulator.collectionGeneration(), accumulator.enabled(Lane.PREVIEW), false),
			new Limits(1, 10, 10));
		int before = clocks[0]; MonitorSample latest = Objects.requireNonNull(accumulator.latest());
		coordinator.tick(0); coordinator.subscribe(FIRST, requests(1)); coordinator.subscribe(SECOND, requests(1)); coordinator.tick(1);
		assertSame(latest, accumulator.latest()); assertEquals(before, clocks[0], "fanout cannot trigger collection or a collector clock read");
		assertEquals(2, f.sent.size());
		coordinator.disconnect(FIRST); coordinator.disconnect(SECOND);
		accumulator.recordCount(Lane.PREVIEW, Counter.REQUESTS_RECEIVED, 1); accumulator.completeTick(1);
		assertEquals(2, Objects.requireNonNull(accumulator.latest()).sampleSequence(), "collector still publishes with no viewers");
	}

	@Test void lifecycleResetAndDisconnectRemoveFencesAndCloseReleasesAllState() {
		var f = new Fixture(); f.coordinator.subscribe(FIRST, requests(5)); f.coordinator.tick(7);
		f.coordinator.reset(2); assertEquals(0, f.coordinator.retainedConnections());
		f.state = new CollectionState(2, 1, true, false);
		f.latest = sample(2, 1); // still server 1 and must be rejected
		f.coordinator.subscribe(FIRST, requests(1)); f.coordinator.tick(0); assertEquals(1, f.sent.size());
		f.latest = new MonitorSample(2, 1, 1, 0, 0, 1, Objects.requireNonNull(sample(1, 1).preview()), null, null);
		f.coordinator.tick(1); assertEquals(2, f.sent.size());
		assertThrows(IllegalArgumentException.class, () -> f.coordinator.reset(2));
		assertThrows(IllegalArgumentException.class, () -> f.coordinator.tick(0));
		f.coordinator.close(); assertEquals(0, f.coordinator.retainedConnections());
		assertFalse(f.coordinator.subscribe(SECOND, requests(1)));
	}

	private static MonitorSample sample(long sequence, long collection) {
		return new MonitorSample(1, collection, sequence, 0, sequence, 1,
			new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.REQUESTS_RECEIVED, sequence, false)), List.of(), List.of()), null, null);
	}
	@NotNullByDefault
	private static final class Fixture implements ServerMonitorCoordinator.Capabilities, ServerMonitorCoordinator.Sender {
		final ServerMonitorCoordinator coordinator;
		final List<InventoryMonitorStatusS2CPacket> statuses = new ArrayList<>();
		final List<InventoryMonitorSampleS2CPacket> sent = new ArrayList<>();
		final List<String> order = new ArrayList<>();
		boolean authorized = true, revokeOnNextLatestRead, statusAvailable = true, statusHandoff = true, sampleHandoff = true;
		int authorityReads, latestReads, stateReads;
		SampleCapability capability = SampleCapability.AVAILABLE;
		CollectionState state = new CollectionState(1, 1, true, false);
		@Nullable MonitorSample latest = sample(1, 1);
		Fixture() { this(new Limits(1, 10, 20)); }
		Fixture(Limits limits) {
			coordinator = new ServerMonitorCoordinator(key -> { authorityReads++; return authorized; }, this, this,
				() -> { latestReads++; if (revokeOnNextLatestRead) { authorized = false; revokeOnNextLatestRead = false; } return latest; },
				() -> { stateReads++; return state; }, limits);
		}
		@Override public boolean statusAvailable(UUID key) { return statusAvailable; }
		@Override public SampleCapability sampleCapability(UUID key) { return capability; }
		@Override public boolean sendStatus(UUID key, InventoryMonitorStatusS2CPacket packet) {
			statuses.add(packet); order.add("status:" + packet.demandRevision()); return statusHandoff;
		}
		@Override public boolean sendSample(UUID key, InventoryMonitorSampleS2CPacket packet) {
			sent.add(packet); order.add("sample:" + packet.demandRevision()); return sampleHandoff;
		}
	}
}
