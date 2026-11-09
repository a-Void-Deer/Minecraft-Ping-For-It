package nx.pingwheel.common.monitor.server;

import nx.pingwheel.common.monitor.MonitorSchema.BudgetReading;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
import nx.pingwheel.common.monitor.MonitorSchema.GaugeReading;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Timing;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket.SampleCapability;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-owner-thread latest-only telemetry fanout. Ports resolve authenticated
 * connection keys and current inherent permission level 3; none is a client hint.
 * No collector mutation, collection trigger, world/global access, timer, retry
 * queue or packet encoding exists here. Sender ports report actual handoff and
 * isolate transport failures. One latest() read serves every eligible viewer in
 * a tick. Call tick after collector completion/configuration on that same thread.
 */
@NotNullByDefault
public final class ServerMonitorCoordinator implements AutoCloseable {
	@NotNullByDefault
	@FunctionalInterface
	public interface Authority { boolean authorized(UUID connection); }
	@NotNullByDefault
	public interface Capabilities {
		boolean statusAvailable(UUID connection);
		SampleCapability sampleCapability(UUID connection);
	}
	@NotNullByDefault
	public interface Sender {
		boolean sendStatus(UUID connection, InventoryMonitorStatusS2CPacket packet);
		boolean sendSample(UUID connection, InventoryMonitorSampleS2CPacket packet);
	}
	@NotNullByDefault
	@FunctionalInterface
	public interface LatestSample { @Nullable MonitorSample latest(); }
	@NotNullByDefault
	@FunctionalInterface
	public interface EnabledState { CollectionState current(); }
	@NotNullByDefault
	public record CollectionState(long serverGeneration, long collectionGeneration,
		boolean previewEnabled, boolean trackingEnabled) {
		public CollectionState {
			if (serverGeneration <= 0 || collectionGeneration <= 0) throw new IllegalArgumentException("monitor generations must be positive");
		}
		public boolean enabled() { return previewEnabled || trackingEnabled; }
	}
	/** Internal finite scheduling guards, not business budgets or measured defaults. */
	@NotNullByDefault
	public record Limits(int minSendIntervalTicks, int statusRefreshTicks, int maxConnections) {
		public static final Limits DEFAULT = new Limits(5, 100, 4096);
		public Limits {
			if (minSendIntervalTicks < 1 || statusRefreshTicks < minSendIntervalTicks || maxConnections < 1)
				throw new IllegalArgumentException("monitor delivery bounds");
		}
	}

	private final Authority authority;
	private final Capabilities capabilities;
	private final Sender sender;
	private final LatestSample samples;
	private final EnabledState enabledState;
	private final Limits limits;
	private final Map<UUID, Connection> connections = new HashMap<>();
	private long serverGeneration;
	private long collectionGeneration;
	private long highestSequence;
	private long currentTick;
	private boolean ticked;
	private boolean closed;

	public ServerMonitorCoordinator(Authority authority, Capabilities capabilities, Sender sender,
		LatestSample samples, EnabledState enabledState) {
		this(authority, capabilities, sender, samples, enabledState, Limits.DEFAULT);
	}
	public ServerMonitorCoordinator(Authority authority, Capabilities capabilities, Sender sender,
		LatestSample samples, EnabledState enabledState, Limits limits) {
		this.authority = Objects.requireNonNull(authority);
		this.capabilities = Objects.requireNonNull(capabilities);
		this.sender = Objects.requireNonNull(sender);
		this.samples = Objects.requireNonNull(samples);
		this.enabledState = Objects.requireNonNull(enabledState);
		this.limits = Objects.requireNonNull(limits);
	}

	/** Gaps in positive revisions are valid; repeats/backward revisions never replace demand. */
	public boolean subscribe(UUID key, InventoryMonitorSubscribeC2SPacket demand) {
		Objects.requireNonNull(key); Objects.requireNonNull(demand);
		if (closed || demand.isCorrupt()) return false;
		@Nullable Connection connection = connections.get(key);
		if (connection != null && demand.demandRevision() <= connection.revision) return false;
		boolean authorized = authority.authorized(key);
		if (connection == null) {
			if (connections.size() >= limits.maxConnections()) return false;
			connection = new Connection(authorized);
			connections.put(key, connection);
		} else updateAuthority(connection, authorized);
		connection.revision = demand.demandRevision();
		connection.watching = demand.watching();
		connection.metrics = demand.metricWireIds().stream().map(id -> Objects.requireNonNull(Metric.byWireId(id))).toList();
		connection.clearProjection();
		connection.status = null;
		connection.pendingStatus = demand.watching();
		if (!demand.watching()) connection.clearDelivery();
		return true;
	}

	/** At most one STATUS and one SAMPLE attempt per viewer per eligible tick. No ack/retry transaction. */
	public void tick(long tick) {
		if (closed) return;
		if (tick < 0 || (ticked && tick < currentTick)) throw new IllegalArgumentException("monitor tick moved backward");
		if (ticked && tick == currentTick) return;
		ticked = true;
		currentTick = tick;
		if (watchingConnections() == 0) return;
		CollectionState state = Objects.requireNonNull(enabledState.current());
		observeCollection(state);
		boolean latestRead = false;
		@Nullable MonitorSample latest = null;
		for (Map.Entry<UUID, Connection> entry : connections.entrySet()) {
			UUID key = entry.getKey();
			Connection connection = entry.getValue();
			if (!connection.watching) continue;
			updateAuthority(connection, authority.authorized(key));
			SampleCapability capability = Objects.requireNonNull(capabilities.sampleCapability(key));
			refreshStatus(connection, state, capability);
			flushStatus(key, connection, tick);
			if (!connection.authorized || capability != SampleCapability.AVAILABLE || !state.enabled()
				|| connection.metrics.isEmpty() || !connection.deliveredCurrentStatus()
				|| !due(tick, connection.lastSampleAttempt, limits.minSendIntervalTicks())) continue;
			if (!latestRead) {
				latest = samples.latest();
				latestRead = true;
				if (latest != null && (latest.serverGeneration() != serverGeneration
					|| latest.collectionGeneration() != collectionGeneration || latest.sampleSequence() <= 0
					|| latest.sampleSequence() < highestSequence)) latest = null;
				if (latest != null) highestSequence = latest.sampleSequence();
			}
			if (latest == null) continue;
			if (connection.awaitFreshSample) {
				connection.minimumSequence = Math.max(connection.minimumSequence, latest.sampleSequence());
				connection.awaitFreshSample = false;
				continue;
			}
			if (latest.sampleSequence() <= connection.minimumSequence || latest.sampleSequence() == connection.projectedSequence) continue;
			connection.projectedSequence = latest.sampleSequence();
			connection.projection = project(latest, state, connection.metrics);
			if (connection.projection == null) continue;
			// The second trusted check is immediately before numeric handoff, not a cached grant.
			if (!authority.authorized(key)) {
				updateAuthority(connection, false);
				refreshStatus(connection, state, capability);
				flushStatus(key, connection, tick);
				continue;
			}
			connection.lastSampleAttempt = tick;
			sender.sendSample(key, new InventoryMonitorSampleS2CPacket(connection.revision,
				connection.permissionGeneration, connection.projection));
		}
	}
	public void flush(long tick) { tick(tick); }
	public void disconnect(UUID key) { connections.remove(Objects.requireNonNull(key)); }
	public int watchingConnections() {
		int count = 0;
		for (Connection connection : connections.values()) if (connection.watching) count++;
		return count;
	}
	public int retainedConnections() { return connections.size(); }

	/** New server lifecycle: clear all watches/revision tombstones before accepting fresh demands. */
	public void reset(long generation) {
		if (generation <= 0 || (serverGeneration != 0 && generation <= serverGeneration))
			throw new IllegalArgumentException("server generation must advance");
		connections.clear();
		serverGeneration = generation;
		collectionGeneration = 0;
		highestSequence = 0;
		currentTick = 0;
		ticked = false;
	}
	@Override public void close() { connections.clear(); closed = true; }

	private void observeCollection(CollectionState state) {
		if (state.serverGeneration() < serverGeneration
			|| (state.serverGeneration() == serverGeneration && state.collectionGeneration() < collectionGeneration))
			throw new IllegalArgumentException("monitor collection generation moved backward");
		if (state.serverGeneration() == serverGeneration && state.collectionGeneration() == collectionGeneration) return;
		boolean lifecycleChange = state.serverGeneration() != serverGeneration;
		serverGeneration = state.serverGeneration();
		collectionGeneration = state.collectionGeneration();
		highestSequence = 0;
		for (Connection connection : connections.values()) {
			connection.clearProjection();
			connection.minimumSequence = 0;
			// Sequence floors belong to this collection, but a pending regrant wait
			// survives either generation change. Its first valid latest establishes
			// the new floor; only a subsequent publication may carry numeric values.
			if (lifecycleChange) connection.permissionGeneration = 1;
		}
	}
	private void updateAuthority(Connection connection, boolean authorized) {
		if (connection.authorized == authorized) return;
		connection.permissionGeneration = Math.incrementExact(connection.permissionGeneration);
		connection.authorized = authorized;
		connection.minimumSequence = Math.max(highestSequence, connection.projectedSequence);
		connection.awaitFreshSample = authorized;
		connection.clearProjection();
		connection.pendingStatus = connection.watching;
	}
	private void refreshStatus(Connection connection, CollectionState state, SampleCapability capability) {
		@Nullable InventoryMonitorStatusS2CPacket old = connection.status;
		if (old == null || old.demandRevision() != connection.revision || old.serverGeneration() != state.serverGeneration()
			|| old.collectionGeneration() != state.collectionGeneration() || old.permissionGeneration() != connection.permissionGeneration
			|| old.authorized() != connection.authorized || old.sampleCapability() != capability
			|| old.previewEnabled() != state.previewEnabled() || old.trackingEnabled() != state.trackingEnabled()) {
			connection.status = new InventoryMonitorStatusS2CPacket(connection.revision, state.serverGeneration(),
				state.collectionGeneration(), connection.permissionGeneration, connection.authorized, capability,
				state.previewEnabled(), state.trackingEnabled());
			connection.pendingStatus = true;
		} else if (due(currentTick, connection.lastStatusAttempt, limits.statusRefreshTicks())) connection.pendingStatus = true;
	}
	private void flushStatus(UUID key, Connection connection, long tick) {
		if (!connection.pendingStatus || !due(tick, connection.lastStatusAttempt, limits.minSendIntervalTicks())
			|| !capabilities.statusAvailable(key)) return;
		InventoryMonitorStatusS2CPacket status = Objects.requireNonNull(connection.status);
		connection.pendingStatus = false;
		connection.lastStatusAttempt = tick;
		if (sender.sendStatus(key, status)) {
			connection.deliveredStatus = status;
		}
	}
	private static boolean due(long tick, long last, int interval) { return last < 0 || tick - last >= interval; }

	private static @Nullable MonitorSample project(MonitorSample sample, CollectionState state, List<Metric> metrics) {
		@Nullable LaneSample preview = state.previewEnabled() ? projectLane(sample.preview(), metrics) : null;
		@Nullable LaneSample tracking = state.trackingEnabled() ? projectLane(sample.tracking(), metrics) : null;
		@Nullable SharedSample shared = projectShared(sample.shared(), metrics);
		if (preview == null && tracking == null && shared == null) return null;
		return new MonitorSample(sample.serverGeneration(), sample.collectionGeneration(), sample.sampleSequence(),
			sample.firstTick(), sample.lastTick(), sample.elapsedNs(), preview, tracking, shared);
	}
	private static @Nullable LaneSample projectLane(@Nullable LaneSample lane, List<Metric> metrics) {
		if (lane == null || lane.source() != nx.pingwheel.common.monitor.MonitorSchema.Source.SERVER) return null;
		Scope scope = lane.scope();
		List<Timing> timings = lane.timings().stream().filter(reading -> metrics.stream()
			.anyMatch(metric -> scope.equals(metric.scope()) && reading.phase() == metric.phase())).toList();
		List<CounterReading> counters = lane.counters().stream().filter(reading -> metrics.stream()
			.anyMatch(metric -> scope.equals(metric.scope()) && reading.counter() == metric.counter())).toList();
		List<GaugeReading> gauges = lane.gauges().stream().filter(reading -> metrics.stream()
			.anyMatch(metric -> scope.equals(metric.scope()) && reading.gauge() == metric.gauge())).toList();
		List<BudgetReading> budgets = lane.budgets().stream().filter(reading -> reading.usage().available() && metrics.stream()
			.anyMatch(metric -> scope.equals(metric.scope()) && reading.budget() == metric.budget())).toList();
		return timings.isEmpty() && counters.isEmpty() && gauges.isEmpty() && budgets.isEmpty() ? null
			: new LaneSample(lane.source(), lane.lane(), timings, counters, gauges, budgets);
	}
	private static @Nullable SharedSample projectShared(@Nullable SharedSample shared, List<Metric> metrics) {
		if (shared == null) return null;
		List<CounterReading> counters = shared.counters().stream().filter(reading -> metrics.stream()
			.anyMatch(metric -> Scope.SHARED.equals(metric.scope()) && reading.counter() == metric.counter())).toList();
		List<GaugeReading> gauges = shared.gauges().stream().filter(reading -> metrics.stream()
			.anyMatch(metric -> Scope.SHARED.equals(metric.scope()) && reading.gauge() == metric.gauge())).toList();
		List<BudgetReading> budgets = shared.budgets().stream().filter(reading -> reading.usage().available() && metrics.stream()
			.anyMatch(metric -> Scope.SHARED.equals(metric.scope()) && reading.budget() == metric.budget())).toList();
		@Nullable MemoryUsage memory = metrics.stream().anyMatch(metric -> metric.kind() == Metric.Kind.MEMORY) ? shared.memory() : null;
		return counters.isEmpty() && gauges.isEmpty() && budgets.isEmpty() && memory == null ? null
			: new SharedSample(counters, gauges, budgets, memory);
	}

	@NotNullByDefault
	private static final class Connection {
		long revision;
		boolean watching;
		boolean authorized;
		long permissionGeneration = 1;
		@Unmodifiable List<Metric> metrics = List.of();
		@Nullable InventoryMonitorStatusS2CPacket status;
		boolean pendingStatus;
		@Nullable InventoryMonitorStatusS2CPacket deliveredStatus;
		long lastStatusAttempt = -1, lastSampleAttempt = -1;
		long minimumSequence, projectedSequence;
		boolean awaitFreshSample;
		@Nullable MonitorSample projection;
		Connection(boolean authorized) { this.authorized = authorized; }
		void clearProjection() { projection = null; projectedSequence = 0; }
		void clearDelivery() {
			status = null; pendingStatus = false;
			deliveredStatus = null;
		}
		boolean deliveredCurrentStatus() {
			return status != null && status.equals(deliveredStatus);
		}
	}
}
