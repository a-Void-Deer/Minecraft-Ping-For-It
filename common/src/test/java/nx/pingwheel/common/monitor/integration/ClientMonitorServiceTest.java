package nx.pingwheel.common.monitor.integration;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.RouteSupport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientMonitorServiceTest {
	@Test void finalDebugHudGateSendsOneWatchAndOneStopWithoutChangingPreferences() {
		var f = new Fixture();
		f.service.configure(f.config); assertTrue(f.network.sent.isEmpty());
		f.config.setLoggingProfile(LoggingProfile.DEBUG); f.config.setMonitorHudEnabled(true);
		f.service.configure(f.config);
		assertEquals(1, f.network.sent.size()); assertTrue(f.network.sent.getFirst().watching());
		assertFalse(f.network.sent.getFirst().metricWireIds().isEmpty());
		assertEquals(ClientMonitorState.Status.UNKNOWN, f.state.view().previewStatus(), "C2S availability cannot prove S2C support");
		f.service.configure(f.config); assertEquals(1, f.network.sent.size());
		var selected = f.config.getMonitorMetrics().snapshot();
		f.config.setMonitorHudEnabled(false); f.service.configure(f.config);
		assertEquals(2, f.network.sent.size()); assertFalse(f.network.sent.getLast().watching());
		assertTrue(f.network.sent.getLast().demandRevision() > f.network.sent.getFirst().demandRevision());
		assertEquals(selected, f.config.getMonitorMetrics().snapshot());
		f.service.configure(f.config); assertEquals(2, f.network.sent.size());
	}

	@Test void localOnlyMetricsNeverRequestNumbersAndTransitionStopsAnExistingWatch() {
		var f = new Fixture();
		f.config.setLoggingProfile(LoggingProfile.DEBUG); f.config.setMonitorHudEnabled(true);
		for (Metric metric : Metric.all()) f.config.getMonitorMetrics().setSelected(metric.id(), false);
		f.config.getMonitorMetrics().setSelected("local.preview.timing.apply", true);
		f.service.configure(f.config);
		assertTrue(f.state.enabled()); assertFalse(f.state.watching()); assertTrue(f.network.sent.isEmpty());
		f.config.getMonitorMetrics().setSelected(Metric.SUMMARY_PROCESSING.id(), true);
		f.service.configure(f.config); assertTrue(f.network.sent.getFirst().watching());
		f.config.getMonitorMetrics().setSelected(Metric.SUMMARY_PROCESSING.id(), false);
		f.service.configure(f.config); assertFalse(f.network.sent.getLast().watching());
		assertTrue(f.state.enabled());
	}

	@Test void explicitMissingSubscribeIsUnsupportedButPendingNegotiationRemainsUnknownAndRetryable() {
		var f = new Fixture();
		f.config.setLoggingProfile(LoggingProfile.DEBUG); f.config.setMonitorHudEnabled(true);
		f.network.support = RouteSupport.UNKNOWN; f.service.configure(f.config);
		assertTrue(f.network.sent.isEmpty()); assertEquals(ClientMonitorState.Status.UNKNOWN, f.state.view().previewStatus());
		f.network.support = RouteSupport.UNAVAILABLE; f.service.configure(f.config);
		assertTrue(f.network.sent.isEmpty()); assertEquals(ClientMonitorState.Status.UNSUPPORTED, f.state.view().previewStatus());
		f.network.support = RouteSupport.AVAILABLE; f.service.configure(f.config);
		assertEquals(1, f.network.sent.size()); assertEquals(ClientMonitorState.Status.UNKNOWN, f.state.view().previewStatus());
	}

	@Test void connectionResetRotatesTokenAndReannouncesCurrentWatchWithoutOppositeSideState() {
		var f = new Fixture();
		f.config.setLoggingProfile(LoggingProfile.DEBUG); f.config.setMonitorHudEnabled(true);
		f.service.configure(f.config); long token = f.state.connectionGeneration();
		long revision = f.state.demandRevision();
		f.service.resetConnection(); f.service.configure(f.config);
		assertNotEquals(token, f.state.connectionGeneration()); assertTrue(f.state.demandRevision() > revision);
		assertEquals(2, f.network.sent.size()); assertTrue(f.network.sent.getLast().watching());
	}

	@Test void explicitRouteLossClearsViewAndReappearanceReannouncesWithoutAssumingResponseSupport() {
		var f = new Fixture();
		f.config.setLoggingProfile(LoggingProfile.DEBUG); f.config.setMonitorHudEnabled(true);
		f.service.configure(f.config); assertEquals(1, f.network.sent.size());
		f.network.support = RouteSupport.UNAVAILABLE; f.service.configure(f.config);
		assertEquals(ClientMonitorState.Status.UNSUPPORTED, f.state.view().previewStatus());
		assertEquals(1, f.network.sent.size());
		f.network.support = RouteSupport.AVAILABLE; f.service.configure(f.config);
		assertEquals(2, f.network.sent.size()); assertEquals(ClientMonitorState.Status.UNKNOWN, f.state.view().previewStatus());
		f.service.configure(f.config); assertEquals(2, f.network.sent.size());
	}

	@NotNullByDefault
	private static final class Fixture {
		final ClientConfig config = new ClientConfig();
		final ClientMonitorState state = new ClientMonitorState();
		final Network network = new Network();
		final ClientMonitorService service = new ClientMonitorService(state, network);
		Fixture() { service.resetConnection(); }
	}
	@NotNullByDefault
	private static final class Network implements IPlatformNetworkService {
		final List<InventoryMonitorSubscribeC2SPacket> sent = new ArrayList<>();
		RouteSupport support = RouteSupport.AVAILABLE;
		@Override public void sendToServer(IPacket packet) { sent.add(assertInstanceOf(InventoryMonitorSubscribeC2SPacket.class, packet)); }
		@Override public void sendToClient(IPacket packet, ServerPlayer player) { fail("client never sends S2C"); }
		@Override public RouteSupport serverboundSupport(ResourceLocation route) {
			assertEquals(InventoryMonitorSubscribeC2SPacket.PACKET_ID, route); return support;
		}
	}
}
