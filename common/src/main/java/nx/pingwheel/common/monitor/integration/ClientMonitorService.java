package nx.pingwheel.common.monitor.integration;

import java.util.Objects;
import java.time.Duration;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.platform.RouteSupport;
import org.jetbrains.annotations.NotNullByDefault;

/** Client-thread configuration/demand adapter. It never completes the runtime's collector. */
@NotNullByDefault
public final class ClientMonitorService {
	private static final DiagnosticChannel DEMAND_FAILURES =
		DiagnosticChannel.interval("inventory-monitor/demand", Duration.ofSeconds(10));
	private final ClientMonitorState state;
	private final IPlatformNetworkService network;
	private long sentRevision = -1;
	private boolean watched;
	private RouteSupport previousSupport = RouteSupport.UNKNOWN;

	public ClientMonitorService(ClientMonitorState state, IPlatformNetworkService network) {
		this.state = Objects.requireNonNull(state);
		this.network = Objects.requireNonNull(network);
	}

	public void resetConnection() {
		state.resetConnection();
		sentRevision = -1;
		watched = false;
		previousSupport = RouteSupport.UNKNOWN;
	}

	/** Final loaded configuration only; neither a settings draft nor a server switch drives this gate. */
	public void configure(ClientConfig config) {
		boolean enabled = config.getLoggingProfile() == LoggingProfile.DEBUG && config.isMonitorHudEnabled();
		if (!enabled && !state.enabled() && !watched) return;
		state.configure(enabled, enabled ? Metric.all().stream()
			.filter(metric -> config.getMonitorMetrics().isSelected(metric.id())).toList() : state.selected());
		if (!state.watching() && !watched) { sentRevision = state.demandRevision(); return; }
		try {
			RouteSupport subscribe = network.serverboundSupport(InventoryMonitorSubscribeC2SPacket.PACKET_ID);
			// A missing subscribe route proves this exchange impossible. Availability
			// alone proves nothing about either S2C route; STATUS supplies those facts.
			state.routeSupport(state.connectionGeneration(), subscribe == RouteSupport.UNAVAILABLE
				? RouteSupport.UNAVAILABLE : RouteSupport.UNKNOWN);
			if (subscribe != previousSupport) {
				previousSupport = subscribe;
				if (subscribe == RouteSupport.AVAILABLE) sentRevision = -1;
			}
			if (subscribe != RouteSupport.AVAILABLE) return;
			if (sentRevision == state.demandRevision()) return;
			if (state.watching()) {
				network.sendToServer(InventoryMonitorSubscribeC2SPacket.watch(state.demandRevision(), state.selected()));
				watched = true;
			} else {
				network.sendToServer(InventoryMonitorSubscribeC2SPacket.stop(state.demandRevision()));
				watched = false;
			}
			sentRevision = state.demandRevision();
		} catch (RuntimeException | LinkageError failure) {
			Diagnostics.client().atDebug().rateLimited(DEMAND_FAILURES)
				.debug("inventory-monitor/demand", "handoff-unobserved", failure);
		}
	}
}
