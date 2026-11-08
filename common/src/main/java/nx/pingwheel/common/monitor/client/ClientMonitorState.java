package nx.pingwheel.common.monitor.client;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import nx.pingwheel.common.monitor.MonitorDemand;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.platform.RouteSupport;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Client-thread-owned mirror, not a protocol or transport. The integration
 * adapter passes decoded STATUS primitives and the existing detached schema.
 * Every queued callback must carry the connectionGeneration captured before
 * enqueueing; a sample cannot establish capability or numeric permission.
 */
@NotNullByDefault
public final class ClientMonitorState {

	/** Internal expiry bound, not a user option or a network sampling promise. */
	public static final long SAMPLE_TTL_NS = 5_000_000_000L;
	/** Only client callback identity, never a server generation or cross-side state. */
	private static final AtomicLong CONNECTION_TOKENS = new AtomicLong();

	@NotNullByDefault
	public enum Status { UNKNOWN, UNSUPPORTED, UNAUTHORIZED, OFF, WAITING, ACTIVE, STALE, DEPENDENCY_OFF }

	@NotNullByDefault
	public record View(boolean enabled, @Unmodifiable List<Metric> selected, Status previewStatus,
		Status trackingStatus, Status sharedStatus, @Nullable MonitorSample local, @Nullable MonitorSample remote) {
		public View { selected = List.copyOf(selected); }
	}

	private final ClientMonitorCollector local;
	private final LongSupplier clock;
	private boolean enabled;
	private @Unmodifiable List<Metric> selected = Metric.defaults();
	private MonitorDemand demand = MonitorDemand.NONE;
	private long connectionGeneration = nextConnectionToken();
	private long demandRevision;
	private RouteSupport routeSupport = RouteSupport.UNKNOWN;
	private boolean known;
	private boolean hasStatusFence;
	private long serverGeneration, collectionGeneration, permissionGeneration;
	private boolean authorized, previewEnabled, trackingEnabled;
	private RouteSupport sampleSupport = RouteSupport.UNKNOWN;
	private @Nullable MonitorSample remote;
	private long lastSequence = -1;
	private long receivedNs;
	private boolean stale;
	private View view = new View(false, selected, Status.UNKNOWN, Status.UNKNOWN, Status.UNKNOWN, null, null);

	public ClientMonitorState() { this(new ClientMonitorCollector(), System::nanoTime); }
	public ClientMonitorState(ClientMonitorCollector local, LongSupplier clock) {
		this.local = Objects.requireNonNull(local, "local");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public ClientMonitorCollector collector() { return local; }
	public boolean enabled() { return enabled; }
	public long connectionGeneration() { return connectionGeneration; }
	public long demandRevision() { return demandRevision; }
	public MonitorDemand demand() { return demand; }
	/** True means subscribe/watch; false means stop. Local-only choices require no numeric request. */
	public boolean watching() { return enabled && demand.serverRequested(); }
	public @Unmodifiable List<Metric> selected() { return selected; }
	public View view() { return view; }

	/** Adapter only; receive handlers still supply the token captured before enqueueing. */
	public boolean accept(long connection, @Nullable InventoryMonitorStatusS2CPacket packet) {
		if (packet == null || packet.isCorrupt()) return false;
		RouteSupport capability = switch (Objects.requireNonNull(packet.sampleCapability())) {
			case UNKNOWN -> RouteSupport.UNKNOWN;
			case AVAILABLE -> RouteSupport.AVAILABLE;
			case UNAVAILABLE -> RouteSupport.UNAVAILABLE;
		};
		return acceptStatus(connection, packet.demandRevision(), packet.serverGeneration(), packet.collectionGeneration(),
			packet.permissionGeneration(), packet.authorized(), packet.previewEnabled(), packet.trackingEnabled(), capability);
	}

	public boolean accept(long connection, @Nullable InventoryMonitorSampleS2CPacket packet) {
		return packet != null && !packet.isCorrupt() && acceptSample(connection, packet.demandRevision(),
			packet.permissionGeneration(), Objects.requireNonNull(packet.sample()));
	}

	/** Caller uses the final current-client {@code DEBUG && HUD} value; selection stays independent. */
	public void configure(boolean enabled, Collection<Metric> selected) {
		Objects.requireNonNull(selected, "selected");
		if (this.enabled == enabled && this.selected.size() == selected.size() && this.selected.containsAll(selected)) return;
		List<Metric> ordered = Metric.all().stream().filter(selected::contains).toList();
		if (this.enabled == enabled && this.selected.equals(ordered)) return;
		this.enabled = enabled;
		this.selected = ordered;
		demand = enabled ? MonitorDemand.fromMetrics(ordered) : MonitorDemand.NONE;
		demandRevision = Math.incrementExact(demandRevision);
		clearRemote();
		known = false; // New demand needs its own accepted, echoed STATUS before SAMPLE.
		local.configure(enabled);
		refreshView();
	}

	/** Disconnect/join fence. Configuration choices survive; old queued callbacks cannot. */
	public void resetConnection() {
		connectionGeneration = nextConnectionToken();
		demandRevision = Math.incrementExact(demandRevision);
		known = false;
		hasStatusFence = false;
		routeSupport = RouteSupport.UNKNOWN;
		serverGeneration = collectionGeneration = permissionGeneration = 0;
		clearRemote();
		local.resetConnection(connectionGeneration);
		refreshView();
	}

	/** Query the monitor response capability, not merely C2S subscribe support. */
	public void routeSupport(long connection, RouteSupport support) {
		if (connection != connectionGeneration) return;
		routeSupport = Objects.requireNonNull(support, "support");
		if (support == RouteSupport.UNAVAILABLE) { known = false; dropRemoteValues(); }
		refreshView();
	}

	/**
	 * Only the adapter for the independent STATUS message calls this method.
	 * Generations are positive, monotonic within a connection. A new server
	 * generation permits its collection/permission generations to start afresh.
	 */
	public boolean acceptStatus(long connection, long revision, long server, long collection, long permission,
		boolean authorized, boolean preview, boolean tracking, RouteSupport sampleSupport) {
		Objects.requireNonNull(sampleSupport, "sampleSupport");
		if (connection != connectionGeneration || revision != demandRevision || revision <= 0 || server <= 0 || collection <= 0 || permission <= 0
			|| routeSupport == RouteSupport.UNAVAILABLE) return false;
		if (hasStatusFence) {
			if (server < serverGeneration || server == serverGeneration
				&& (collection < collectionGeneration || permission < permissionGeneration)) return false;
			if (server == serverGeneration && collection == collectionGeneration && permission == permissionGeneration
				&& (this.authorized != authorized || previewEnabled != preview || trackingEnabled != tracking)) return false;
		}
		boolean changed = !hasStatusFence || server != serverGeneration || collection != collectionGeneration
			|| permission != permissionGeneration;
		if (changed) clearRemote();
		else if (!authorized || sampleSupport != RouteSupport.AVAILABLE || !preview && !tracking) dropRemoteValues();
		known = true;
		hasStatusFence = true;
		serverGeneration = server; collectionGeneration = collection; permissionGeneration = permission;
		this.authorized = authorized; previewEnabled = preview; trackingEnabled = tracking; this.sampleSupport = sampleSupport;
		refreshView();
		return true;
	}

	/** Permission is part of the message envelope, not inferred from a numeric sample. */
	public boolean acceptSample(long connection, long revision, long permission, MonitorSample sample) {
		if (!enabled || !known || !authorized || sampleSupport != RouteSupport.AVAILABLE || !demand.serverRequested()
			|| connection != connectionGeneration || revision != demandRevision || permission != permissionGeneration
			|| sample.serverGeneration() != serverGeneration || sample.collectionGeneration() != collectionGeneration
			|| sample.sampleSequence() <= 0 || sample.sampleSequence() <= lastSequence || !previewEnabled && !trackingEnabled
			|| sample.preview() != null && (!previewEnabled || !demand.includes(Lane.PREVIEW) || sample.preview().source() != Source.SERVER)
			|| sample.tracking() != null && (!trackingEnabled || !demand.includes(Lane.TRACKING) || sample.tracking().source() != Source.SERVER)
			|| sample.shared() != null && !demand.shared()) return false;
		remote = sample;
		lastSequence = sample.sampleSequence();
		receivedNs = clock.getAsLong(); // Local receipt clock; never compare with server elapsedNs.
		stale = false;
		refreshView();
		return true;
	}

	/** End of client tick, after local work. Paint never calls this method. */
	public void tick(long tick) {
		if (!enabled) return;
		local.completeTick(tick);
		if (remote != null && clock.getAsLong() - receivedNs >= SAMPLE_TTL_NS) { remote = null; stale = true; }
		refreshView();
	}

	/** Rebuild after a locale/view update without observing time or changing demand. */
	public void refreshView() {
		view = new View(enabled, selected, status(Lane.PREVIEW), status(Lane.TRACKING), sharedStatus(), local.latest(), remote);
	}

	private Status status(Lane lane) {
		if (routeSupport == RouteSupport.UNAVAILABLE) return Status.UNSUPPORTED;
		if (!known) return Status.UNKNOWN;
		if (!(lane == Lane.PREVIEW ? previewEnabled : trackingEnabled)) return Status.OFF;
		if (sampleSupport == RouteSupport.UNAVAILABLE) return Status.UNSUPPORTED;
		if (!authorized) return Status.UNAUTHORIZED;
		if (sampleSupport == RouteSupport.UNKNOWN) return Status.UNKNOWN;
		if (stale) return Status.STALE;
		if (remote == null || (lane == Lane.PREVIEW ? remote.preview() : remote.tracking()) == null) return Status.WAITING;
		return Status.ACTIVE;
	}

	private Status sharedStatus() {
		if (routeSupport == RouteSupport.UNAVAILABLE) return Status.UNSUPPORTED;
		if (!known) return Status.UNKNOWN;
		if (!previewEnabled && !trackingEnabled) return Status.DEPENDENCY_OFF;
		if (sampleSupport == RouteSupport.UNAVAILABLE) return Status.UNSUPPORTED;
		if (!authorized) return Status.UNAUTHORIZED;
		if (sampleSupport == RouteSupport.UNKNOWN) return Status.UNKNOWN;
		if (stale) return Status.STALE;
		return remote == null || remote.shared() == null ? Status.WAITING : Status.ACTIVE;
	}

	private void dropRemoteValues() { remote = null; stale = false; receivedNs = 0; }
	private void clearRemote() { dropRemoteValues(); lastSequence = -1; }
	private static long nextConnectionToken() { return CONNECTION_TOKENS.updateAndGet(Math::incrementExact); }
}
