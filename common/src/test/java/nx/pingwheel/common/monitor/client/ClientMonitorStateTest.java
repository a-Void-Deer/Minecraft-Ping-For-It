package nx.pingwheel.common.monitor.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.platform.RouteSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static nx.pingwheel.common.monitor.client.ClientMonitorState.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientMonitorStateTest {

	private final AtomicLong clock = new AtomicLong();
	private final ClientMonitorCollector collector = new ClientMonitorCollector(new MonitorTuning(1, 2), clock::get);
	private final ClientMonitorState state = new ClientMonitorState(collector, clock::get);

	private void enable() { state.configure(true, Metric.defaults()); }
	private boolean status(long server, long collection, long permission, boolean authorized, boolean preview, boolean tracking) {
		return state.acceptStatus(state.connectionGeneration(), state.demandRevision(), server, collection, permission,
			authorized, preview, tracking, RouteSupport.AVAILABLE);
	}
	private MonitorSample sample(long server, long collection, long sequence) {
		return new MonitorSample(server, collection, sequence, 0, 1, 1_000_000_000,
			new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(), List.of(), List.of(), List.of()),
			new LaneSample(Source.SERVER, Lane.TRACKING, List.of(), List.of(), List.of(), List.of()),
			new SharedSample(List.of(), List.of(), List.of(), MemoryUsage.accounted(10, 5, 8)));
	}
	private boolean sample(long permission, MonitorSample sample) {
		return state.acceptSample(state.connectionGeneration(), state.demandRevision(), permission, sample);
	}

	@Test void samplesCannotEstablishAuthorizationAndAnActiveStatusWaitsForItsFirstSample() {
		enable();
		assertEquals(UNKNOWN, state.view().previewStatus());
		assertFalse(sample(1, sample(1, 1, 1)));
		assertTrue(status(1, 1, 1, true, true, true));
		assertEquals(WAITING, state.view().previewStatus());
		assertTrue(sample(1, sample(1, 1, 1)));
		assertEquals(ACTIVE, state.view().previewStatus());
		assertFalse(sample(1, sample(1, 1, 1)));
		assertFalse(sample(1, sample(1, 1, 0)));
	}

	@Test void expiryIsStaleAtTheExactBoundAndNeverOffOrUnsupported() {
		enable(); status(1, 1, 1, true, true, true); sample(1, sample(1, 1, 1));
		clock.set(ClientMonitorState.SAMPLE_TTL_NS - 1); state.tick(1);
		assertEquals(ACTIVE, state.view().trackingStatus());
		clock.incrementAndGet(); state.tick(2);
		assertEquals(STALE, state.view().trackingStatus()); assertNull(state.view().remote());
		assertFalse(sample(1, sample(1, 1, 1)), "expiry does not reset the monotonic sequence fence");
		assertTrue(sample(1, sample(1, 1, 2))); assertEquals(ACTIVE, state.view().sharedStatus());
	}

	@Test void permissionRevocationRegrantAndCollectionChangesClearSharedAndRejectOldCallbacks() {
		enable(); status(4, 3, 2, true, true, true); sample(2, sample(4, 3, 7));
		assertTrue(status(4, 3, 3, false, true, true));
		assertEquals(UNAUTHORIZED, state.view().previewStatus()); assertNull(state.view().remote());
		assertFalse(sample(2, sample(4, 3, 8)));
		assertFalse(status(4, 3, 2, true, true, true));
		assertTrue(status(4, 3, 4, true, true, true)); assertEquals(WAITING, state.view().previewStatus());
		assertTrue(sample(4, sample(4, 3, 1)));
		assertTrue(status(4, 4, 4, true, true, true)); assertNull(state.view().remote());
		assertFalse(sample(4, sample(4, 3, 9))); assertFalse(status(4, 3, 4, true, true, true));
		assertTrue(sample(4, sample(4, 4, 1)));
	}

	@Test void reconnectFencesBothStatusAndSampleEvenWhenTheNextServerReusesGenerations() {
		enable(); status(3, 2, 2, true, true, true); sample(2, sample(3, 2, 4));
		long connection = state.connectionGeneration(), revision = state.demandRevision();
		state.resetConnection();
		assertNull(state.view().remote()); assertEquals(UNKNOWN, state.view().previewStatus());
		assertFalse(state.acceptStatus(connection, revision, 9, 9, 9, true, true, true, RouteSupport.AVAILABLE));
		assertFalse(state.acceptSample(connection, revision, 2, sample(3, 2, 5)));
		assertTrue(status(1, 1, 1, true, true, true)); assertTrue(sample(1, sample(1, 1, 1)));
	}

	@Test void authoritativeOffIsPublicButCannotDisableLocalCollectionOrInventSharedZero() {
		enable(); assertTrue(status(1, 1, 1, false, false, false));
		assertEquals(OFF, state.view().previewStatus()); assertEquals(OFF, state.view().trackingStatus());
		assertEquals(DEPENDENCY_OFF, state.view().sharedStatus()); assertTrue(state.enabled()); assertTrue(collector.enabled());
		collector.begin(Lane.PREVIEW, Phase.APPLY); clock.set(10); collector.end(Lane.PREVIEW, Phase.APPLY); state.tick(1);
		assertNotNull(state.view().local()); assertNotNull(state.view().local().preview().timing(Phase.APPLY));
		assertNull(state.view().remote()); assertEquals(Metric.defaults(), state.selected());
	}

	@Test void missingCapabilityIsUnknownAndOnlyExplicitUnavailableIsUnsupported() {
		enable();
		state.routeSupport(state.connectionGeneration(), RouteSupport.AVAILABLE);
		assertEquals(UNKNOWN, state.view().previewStatus(), "C2S availability is no STATUS or sample proof");
		assertTrue(state.acceptStatus(state.connectionGeneration(), state.demandRevision(), 1, 1, 1, true, true, true, RouteSupport.UNKNOWN));
		assertEquals(UNKNOWN, state.view().previewStatus()); assertFalse(sample(1, sample(1, 1, 1)));
		assertTrue(state.acceptStatus(state.connectionGeneration(), state.demandRevision(), 1, 1, 1, true, true, true, RouteSupport.UNAVAILABLE));
		assertEquals(UNSUPPORTED, state.view().previewStatus());
		state.routeSupport(state.connectionGeneration(), RouteSupport.UNAVAILABLE); assertEquals(UNSUPPORTED, state.view().sharedStatus());
	}

	@Test void selectorsAreRetainedAcrossDisableAndLocalOnlySelectionHasNoRemoteDemand() {
		Metric detail = java.util.Objects.requireNonNull(Metric.byId("local.preview.timing.apply"));
		state.configure(true, List.of(detail)); assertFalse(state.watching()); assertFalse(state.demand().serverRequested());
		state.configure(false, List.of(detail)); assertEquals(List.of(detail), state.selected()); assertFalse(collector.enabled());
		state.resetConnection(); assertEquals(List.of(detail), state.selected());
		state.configure(true, state.selected()); assertTrue(collector.enabled()); assertFalse(state.watching());
	}

	@Test void aNewServerGenerationPermitsFreshCountersButRejectsOldServerStatusAndSamples() {
		enable(); status(4, 8, 9, true, true, true); sample(9, sample(4, 8, 100));
		assertTrue(status(5, 1, 1, true, true, true)); assertNull(state.view().remote());
		assertFalse(status(4, 10, 11, true, true, true)); assertFalse(sample(9, sample(4, 8, 101)));
		assertTrue(sample(1, sample(5, 1, 1)));
	}

	@Test void aSelectionRevisionRejectsOldResponsesAndStillRequiresItsOwnStatus() {
		enable(); status(1, 1, 1, true, true, true); sample(1, sample(1, 1, 1));
		long oldRevision = state.demandRevision();
		state.configure(true, List.of(Metric.SUMMARY_PROCESSING));
		assertEquals(UNKNOWN, state.view().previewStatus());
		assertFalse(state.acceptStatus(state.connectionGeneration(), oldRevision, 1, 1, 1, true, true, true, RouteSupport.AVAILABLE));
		assertFalse(state.acceptSample(state.connectionGeneration(), oldRevision, 1, sample(1, 1, 2)));
		assertFalse(sample(1, sample(1, 1, 2)), "a matching SAMPLE revision cannot create its STATUS");
		assertTrue(status(1, 1, 1, true, true, true));
		var laneOnly = new MonitorSample(1, 1, 2, 0, 1, 1, sample(1, 1, 2).preview(), sample(1, 1, 2).tracking(), null);
		assertTrue(sample(1, laneOnly));
	}

	@Test void capabilityLossCannotEraseGenerationAndSequenceFences() {
		enable(); status(2, 3, 4, true, true, true); sample(4, sample(2, 3, 9));
		assertTrue(state.acceptStatus(state.connectionGeneration(), state.demandRevision(), 2, 3, 4, true, true, true, RouteSupport.UNAVAILABLE));
		assertNull(state.view().remote());
		assertTrue(state.acceptStatus(state.connectionGeneration(), state.demandRevision(), 2, 3, 4, true, true, true, RouteSupport.AVAILABLE));
		assertFalse(sample(4, sample(2, 3, 9)));
		state.routeSupport(state.connectionGeneration(), RouteSupport.UNAVAILABLE); state.routeSupport(state.connectionGeneration(), RouteSupport.AVAILABLE);
		assertFalse(status(2, 3, 3, true, true, true));
		assertTrue(status(2, 3, 4, true, true, true)); assertFalse(sample(4, sample(2, 3, 8)));
		assertTrue(sample(4, sample(2, 3, 10)));
	}

	@Test void frozenPacketAdaptersRejectCorruptResponsesAndRetainThePreEnqueueConnectionToken() {
		enable();
		var status = new nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket(state.demandRevision(), 1, 1, 1, true,
			nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability.AVAILABLE, true, true);
		var sample = new nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket(state.demandRevision(), 1, sample(1, 1, 1));
		long connection = state.connectionGeneration();
		assertFalse(state.accept(connection, new nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket()));
		assertFalse(state.accept(connection, sample)); assertTrue(state.accept(connection, status)); assertTrue(state.accept(connection, sample));
		state.resetConnection(); assertFalse(state.accept(connection, status)); assertFalse(state.accept(connection, sample));
	}

	@Test void freshRuntimeInstancesCannotReuseTheOldConnectionsCallbackToken() {
		enable(); long oldConnection = state.connectionGeneration();
		var replacement = new ClientMonitorState(new ClientMonitorCollector(new MonitorTuning(1, 1), clock::get), clock::get);
		replacement.configure(true, Metric.defaults());
		assertNotEquals(oldConnection, replacement.connectionGeneration());
		assertFalse(replacement.acceptStatus(oldConnection, replacement.demandRevision(), 1, 1, 1, true, true, true, RouteSupport.AVAILABLE));
	}
}
