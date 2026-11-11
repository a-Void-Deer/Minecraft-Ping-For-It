package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/** Actual bounded dedicated publication. Queued cuts remain immutable until fully transmitted. */
public final class InventorySyncPublisher implements SyncPublisher, AutoCloseable {
	public static final CostLedger.Counter PUBLICATIONS = new CostLedger.Counter("inventory/publication", CostLedger.Unit.WORK);
	public interface Transport {
		/** Last callback before accounting and irreversible host handoff; production must implement source authorization. */
		default SendDecision beforeSend(Context context, InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) { return SendDecision.ALLOW; }
		boolean authorized(Context context);
		default boolean authorizedControl(UUID recipient, InventoryS2CPacket packet) { return true; }
		default boolean admittedToSend(Context context) { return true; }
		/** Host measurement port kept for compatibility; admission uses the effective-budget overload. */
		int encodedBytes(InventoryS2CPacket packet);
		/**
		 * Exact encoded frame size under explicit effective server budgets. The
		 * default uses the packet's allocation-free exact measurement; an
		 * over-budget frame throws instead of being handed off. This is the
		 * admission measurement. Overrides must use the same bounded codec.
		 */
		default int encodedBytes(InventoryS2CPacket packet, int displayBytes, int frameBytes) {
			return packet.encodedSize(displayBytes, frameBytes);
		}
		void send(UUID recipient, InventoryS2CPacket packet);
		/** NOT_HANDED_OFF retries the same owner; legacy void sends return UNOBSERVED. */
		default TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) {
			send(recipient, packet); return TransportHandoff.UNOBSERVED;
		}
	}
	@NotNullByDefault
	public enum SendDecision { ALLOW, DEFER, DENY }
	/** Quantity and success certificates need a source grant; empty recovery/failure fences do not. */
	public static boolean requiresSource(InventoryS2CPacket packet) {
		if (!packet.entries().isEmpty()) return true;
		return switch (packet.kind()) {
			case HEARTBEAT -> true;
			case PREVIEW, SNAPSHOT, STREAM, STATUS -> packet.completeScan() || packet.status() != InventoryS2CPacket.Status.INVALID
				&& packet.status() != InventoryS2CPacket.Status.INCOMPLETE && (packet.watermark() > 0
					|| packet.status() == InventoryS2CPacket.Status.READY || packet.status() == InventoryS2CPacket.Status.UNCERTAIN);
			default -> false;
		};
	}
	/**
	 * Successful control-handoff callback. It is invoked once per control frame
	 * that the transport actually accepted, never for an enqueue, a budget
	 * defer or a memory admission. Returning true opens the recipient's data
	 * gate; a failed or throwing callback keeps it closed.
	 */
	@FunctionalInterface public interface ControlHandoff {
		boolean accepted(UUID recipient, InventoryS2CPacket packet);
	}
	public record Route(long epoch, long presentationEpoch, long view, long requestId, @Nullable MarkerId markerId,
		String targetQuota, @Nullable String itemPingType, InventoryResourceKind resourceKind) {
		public Route(long epoch, long presentationEpoch, long view, long requestId, @Nullable MarkerId markerId, String targetQuota, @Nullable String itemPingType) {
			this(epoch, presentationEpoch, view, requestId, markerId, targetQuota, itemPingType, InventoryResourceKind.ITEM);
		}
		public Route { Objects.requireNonNull(resourceKind); }
	}
	private static final long STATE_MEMORY = 4096;
	/**
	 * Each admitted backing-capacity growth owns a finite slab: per key, two
	 * retirement set nodes + bounded UTF-16 id, offered/delivered/pending nodes,
	 * overlapping identity-ticket nodes and backing/growth slots. Delivered and
	 * admitted keys can overlap a newer projection. One slab slot also pays its
	 * share of those old owners (each cut has at most MAX_KEYS entries).
	 * 3072 uses 40-byte objects / 8-byte references with alignment headroom.
	 * Retirement needs no later grant, even after the cap falls below live cost.
	 */
	private static final int MAX_KEYS = 1024, MAX_CONTEXTS = 2048;
	private static final long REGISTRATION_MEMORY = STATE_MEMORY;
	@NotNullByDefault
	private static final class RegistrationTicket implements RetainedMemoryLedger.Ticket {
		final RetainedMemoryLedger.Ticket base;
		final RetainedMemoryLedger.@Nullable Ticket backing;
		final int capacity;
		RegistrationTicket(RetainedMemoryLedger.Ticket base, RetainedMemoryLedger.@Nullable Ticket backing, int capacity) {
			this.base = base; this.backing = backing; this.capacity = capacity;
		}
		@Override public void commit(long actual) {
			if (actual != REGISTRATION_MEMORY + capacity * 3072L) throw new IllegalArgumentException("inventory registration settlement");
			base.commit(REGISTRATION_MEMORY); if (backing != null) backing.commit(capacity * 3072L);
		}
		@Override public void close() { base.close(); if (backing != null) backing.close(); }
	}
	/** Conservative fixed framing upper bounds for measured packet reservations; never a frame budget. */
	private static final int FRAME_FIXED_BYTES = 128, ENTRY_FIXED_BYTES = 64;
	private static final class State {
		final Module module;
		final InventoryBudgetDelayState publicationDelay, batchDelay, dataDelay, controlDelay, heartbeatDelay;
		boolean monitored = true;
		Context context;
		Route route;
		final RetainedMemoryLedger.@Nullable Ticket memory;
		boolean prepaidControl;
		int keyCapacity;
		RetainedMemoryLedger.@Nullable Ticket keyMemory;
		/** Lifetime identities pay their future retirement before entering any projection. */
		Set<String> admittedKeys = new LinkedHashSet<>();
		Set<String> admittedGroups = new LinkedHashSet<>();
		Map<String, InventoryS2CPacket.Entry> offered = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> delivered = new LinkedHashMap<>();
		Map<String, InventoryS2CPacket.Entry> pending = new LinkedHashMap<>();
		/** One ticket per immutable object, shared by offered/delivered/pending/admitted references. */
		final Map<InventoryS2CPacket.Entry, RetainedMemoryLedger.Ticket> entryMemory = new IdentityHashMap<>();
		/** Bounded by the lifetime projected-key allowance, never a per-frame history. */
		final Set<String> retiredGroups = new LinkedHashSet<>(), retiringGroups = new LinkedHashSet<>();
		boolean restageLatest, rebaselineRequired, retirementPending;
		@Unmodifiable List<InventoryS2CPacket> admitted = List.of();
		/** Frozen with the admitted packets and watermark; never the newer offered projection. */
		long admittedWatermark, admittedChecksum;
		@Nullable InventoryPublicationEvidence offeredEvidence, admittedEvidence, deliveredEvidence;
		boolean sourceDenied;
		RetainedMemoryLedger.@Nullable Ticket batchMemory;
		int part;
		/** Digest of the actual delivered map at deliveredWatermark; heartbeat uses only this. */
		long offeredWatermark, deliveredWatermark, deliveredChecksum, lastHeartbeat = -1;
		InventoryS2CPacket.Status status = InventoryS2CPacket.Status.UPDATING;
		boolean baseline = true, complete, control, deliveryIncomplete, fencePending;
		@Nullable InventoryFailureReason failureReason, deferredReason;
		long deliveredParts;
		/** Bootstrap is retained presentation, never completed source evidence. */
		boolean bootstrap, bootstrapClosed;
		/** A newly installed offer can finish without data, unlike an unrelated status control. */
		boolean offeredCutPending;
		long queueGeneration = -1, monitoredPending, monitoredAdmitted;
		State(Context context, Route route, RetainedMemoryLedger.@Nullable Ticket memory) {
			this.context = context; this.route = route; this.memory = memory; fencePending = route.markerId() != null;
			module = route.markerId() == null ? Module.INVENTORY_PREVIEW : Module.INVENTORY_TRACKING;
			publicationDelay = new InventoryBudgetDelayState(module); batchDelay = new InventoryBudgetDelayState(module);
			dataDelay = new InventoryBudgetDelayState(module); controlDelay = new InventoryBudgetDelayState(module);
			heartbeatDelay = new InventoryBudgetDelayState(module);
		}
		void cancelDelays() { publicationDelay.cancel(); batchDelay.cancel(); dataDelay.cancel(); controlDelay.cancel(); heartbeatDelay.cancel(); }
	}
	private final RetainedMemoryLedger memory;
	private final Transport transport;
	private final BudgetDelaySink sink;
	private final ServerInventoryMonitor monitor;
	private final @Nullable IntConsumer checksumSamples;
	private long tick = -1;
	private final Map<String, State> states = new LinkedHashMap<>();
	private record Control(State state, InventoryS2CPacket packet) {}
	private final Map<String, Control> controls = new LinkedHashMap<>();
	private final Map<String, Long> clientBytes = new LinkedHashMap<>(), streamBytes = new LinkedHashMap<>();
	private final Map<String, RetainedMemoryLedger.Ticket> wireSubjects = new LinkedHashMap<>();
	/** Recipients whose item-data handoff stays blocked until a successful control handoff releases them. */
	private final Set<UUID> dataGates = new LinkedHashSet<>();
	private @Nullable ControlHandoff controlHandoff;
	private java.util.function.LongPredicate backingWork = ignored -> true;
	/** Production debits allocation/copy work from Runtime's existing processing ledger. */
	void backingWork(java.util.function.LongPredicate admission) { backingWork = Objects.requireNonNull(admission); }
	private boolean drainingControls;
	private InventorySettings settings;
	private InventoryWireWindow window;
	private long trackingPeriod = -1, previewGlobal, previewQueued, trackingQueued;
	private long queueGeneration = -1, previewPending, previewAdmitted, trackingPending, trackingAdmitted;
	private boolean queueBaseline;
	private long windowBase;
	private int windowGrace;
	private int drainCursor;
	private int validationCursor;
	private int displayBytes = InventoryLimits.DISPLAY_BASE_BYTES, frameBytes = InventoryLimits.FRAME_BASE_BYTES;
	/** Package-private deterministic boundary fixture; production uses configured budgets. */
	private long fixturePreviewClientBytes = -1, fixtureSnapshotBytes = -1;
	private long fixturePreviewQueueBytes = -1, fixturePreviewGlobalBytes = -1;
	private int controlCapacity = 1024;
	void wireBudgetFixture(long previewClientBytes, long snapshotBytes) {
		if (previewClientBytes <= 0 || snapshotBytes <= 0) throw new IllegalArgumentException("fixture wire budgets");
		fixturePreviewClientBytes = previewClientBytes; fixtureSnapshotBytes = snapshotBytes;
	}
	void previewQueueFixture(long bytes) {
		if (bytes <= 0) throw new IllegalArgumentException("fixture queue budget");
		fixturePreviewQueueBytes = bytes;
	}
	void previewGlobalFixture(long bytes) {
		if (bytes <= 0) throw new IllegalArgumentException("fixture global budget");
		fixturePreviewGlobalBytes = bytes;
	}
	/** Smaller deterministic queue pressure without allocating hundreds of session slabs. */
	void controlCapacityFixture(int capacity) {
		if (capacity <= 0 || capacity > 1024) throw new IllegalArgumentException("fixture control capacity");
		controlCapacity = capacity;
	}
	private long previewQueueBytes() { return fixturePreviewQueueBytes < 0 ? settings.previewQueueBytes() : fixturePreviewQueueBytes; }
	private long previewGlobalBytes() { return fixturePreviewGlobalBytes < 0 ? settings.previewGlobalPeriodBytes() : fixturePreviewGlobalBytes; }
	private long previewClientBytes() { return fixturePreviewClientBytes < 0 ? settings.previewClientPeriodBytes() : fixturePreviewClientBytes; }
	private long snapshotClientBytes() { return fixtureSnapshotBytes < 0 ? settings.trackingSnapshotClientPeriodBytes() : fixtureSnapshotBytes; }
	private long snapshotFragmentBytes() { return fixtureSnapshotBytes < 0 ? settings.trackingSnapshotFragmentBytes() : fixtureSnapshotBytes; }
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock(), trackingClock = new InventoryPeriodClock();

	public InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport) { this(memory, transport, BudgetDelaySink.NONE); }
	public InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport, BudgetDelaySink sink) {
		this(memory, transport, sink, null, new ServerInventoryMonitor());
	}
	public InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport, BudgetDelaySink sink, ServerInventoryMonitor monitor) {
		this(memory, transport, sink, null, monitor);
	}
	/** Package-visible traversal accounting for focused publisher tests; production passes null. */
	InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport, BudgetDelaySink sink,
		@Nullable IntConsumer checksumSamples) {
		this(memory, transport, sink, checksumSamples, new ServerInventoryMonitor());
	}
	private InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport, BudgetDelaySink sink,
		@Nullable IntConsumer checksumSamples, ServerInventoryMonitor monitor) {
		this.memory = memory; this.transport = transport; this.sink = sink == null ? BudgetDelaySink.NONE : sink;
		this.checksumSamples = checksumSamples;
		this.monitor = Objects.requireNonNull(monitor, "monitor");
	}
	/** Existing logical queue cost, not estimated unsent wire bytes; includes existing initial staging. */
	void sampleMonitor() {
		if (!monitor.enabled() || settings == null) return;
		prepareMonitorQueues();
		if (monitor.enabled(Lane.PREVIEW)) {
			monitor.count(Lane.PREVIEW, Counter.PUBLICATIONS_INSTALLED, 0); monitor.count(Lane.PREVIEW, Counter.CUTS_COMPLETED, 0);
			monitor.gauge(Lane.PREVIEW, Gauge.QUEUE_LOGICAL_COST, previewQueued);
			monitor.gauge(Lane.PREVIEW, Gauge.QUEUE_CAP, previewQueueBytes());
			sampleMonitorQueues(Lane.PREVIEW, previewPending, previewAdmitted);
			monitor.budget(Lane.PREVIEW, Budget.WIRE_BYTES, previewClock.period(), previewGlobalBytes(),
				previewGlobal, previewGlobalBytes() - previewGlobal);
		}
		if (monitor.enabled(Lane.TRACKING)) {
			monitor.count(Lane.TRACKING, Counter.PUBLICATIONS_INSTALLED, 0); monitor.count(Lane.TRACKING, Counter.CUTS_COMPLETED, 0);
			monitor.gauge(Lane.TRACKING, Gauge.QUEUE_LOGICAL_COST, trackingQueued);
			monitor.gauge(Lane.TRACKING, Gauge.QUEUE_CAP, settings.trackingRollingQueueBytes());
			sampleMonitorQueues(Lane.TRACKING, trackingPending, trackingAdmitted);
			if (window != null) monitor.budget(Lane.TRACKING, Budget.WIRE_BYTES, trackingClock.period(),
				windowBase * (windowGrace + 1L), window.spent(), window.remaining());
		}
	}
	/** No scan on collection changes: the next existing complete tick loop establishes the baseline. */
	private boolean prepareMonitorQueues() {
		if (!monitor.enabled()) return false;
		long generation = monitor.collectionGeneration();
		if (queueGeneration != generation) {
			queueGeneration = generation; queueBaseline = false;
			previewPending = previewAdmitted = trackingPending = trackingAdmitted = 0;
		}
		if (states.isEmpty()) {
			previewPending = previewAdmitted = trackingPending = trackingAdmitted = 0;
			queueBaseline = true;
		}
		return true;
	}
	/** Actual map/frame sizes, not contexts, payload size or a conversion from logical queue cost. */
	private void observeMonitorQueue(State state) {
		if (!monitor.enabled() || states.get(key(state.context)) != state) return;
		// A reentrant withdraw/rebase can retire the cut before the old send returns
		// and advances its legacy cursor. No admitted cut owns that stale cursor.
		updateMonitorQueue(state, state.pending.size(), state.admitted.isEmpty() ? 0 : state.admitted.size() - state.part);
	}
	private void updateMonitorQueue(State state, long pending, long admitted) {
		if (!prepareMonitorQueues()) return;
		Lane lane = state.route.markerId() == null ? Lane.PREVIEW : Lane.TRACKING;
		if (!monitor.enabled(lane)) return;
		long pendingDelta = pending, admittedDelta = admitted;
		if (state.queueGeneration == queueGeneration) {
			pendingDelta -= state.monitoredPending; admittedDelta -= state.monitoredAdmitted;
		}
		if (lane == Lane.PREVIEW) { previewPending += pendingDelta; previewAdmitted += admittedDelta; }
		else { trackingPending += pendingDelta; trackingAdmitted += admittedDelta; }
		state.queueGeneration = queueGeneration; state.monitoredPending = pending; state.monitoredAdmitted = admitted;
	}
	private void sampleMonitorQueues(Lane lane, long pending, long admitted) {
		if (queueBaseline) {
			monitor.gauge(lane, Gauge.PENDING_ENTRIES, pending); monitor.gauge(lane, Gauge.ADMITTED_FRAMES, admitted);
		} else {
			monitor.invalidateGauge(lane, Gauge.PENDING_ENTRIES); monitor.invalidateGauge(lane, Gauge.ADMITTED_FRAMES);
		}
	}
	private long cycle(State state) { return periodId(state.module); }
	long periodId(Module module) { return module == Module.INVENTORY_PREVIEW ? previewClock.period() : trackingClock.period(); }
	private void delay(State state, InventoryBudgetDelayState stage, int reasons, String name, Unit unit, long amount) {
		for (Reason reason : Reason.values()) if ((reasons & reason.bit()) != 0) {
			InventoryFailureReason failure = switch (reason) {
				case MEMORY -> InventoryFailureReason.MEMORY_LIMIT;
				case QUEUE_CAPACITY -> InventoryFailureReason.QUEUE_LIMIT;
				case PUBLICATION_WORK -> InventoryFailureReason.ITEM_WORK_LIMIT;
				case SUBJECT_CAPACITY -> InventoryFailureReason.PUBLISHER_CAPACITY;
				case FRAME_OR_FRAGMENT_UNSERVICEABLE -> InventoryFailureReason.FRAME_LIMIT;
				case WIRE_CLIENT_OR_STREAM, WIRE_GLOBAL -> InventoryFailureReason.WIRE_LIMIT;
				default -> InventoryFailureReason.PROVIDER_WORK_LIMIT;
			};
			state.deferredReason = failure;
			report(state, failure, InventoryOutcome.DEFERRED, true, name, amount, -1, -1, null);
		}
		if (!state.monitored) return; // generic negotiation/result controls have no module work identity
		stage.observe(sink, cycle(state), tick, reasons,
			() -> new WorkItem("recipient/" + state.context.recipient() + "/" + state.context.baseline() + "/" + name,
				"Inventory " + (state.route.markerId() == null ? "request " + state.route.requestId() : "marker " + state.route.markerId().value()) + " " + name),
			() -> unit == Unit.UNKNOWN ? Quantity.unknown() : Quantity.of(unit, amount));
	}
	boolean delayed(Module module) {
		for (State state : states.values()) if (state.module == module && (state.publicationDelay.outstanding()
			|| state.batchDelay.outstanding() || state.dataDelay.outstanding() || state.controlDelay.outstanding() || state.heartbeatDelay.outstanding())) return true;
		return false;
	}
	boolean eligible(Module module) {
		for (State state : states.values()) if (state.module == module && (state.publicationDelay.eligible()
			|| state.batchDelay.eligible() || state.dataDelay.eligible() || state.controlDelay.eligible() || state.heartbeatDelay.eligible())) return true;
		return false;
	}
	/** Negotiation and correlated results are bounded control traffic, not a free transport side path. */
	public boolean control(UUID recipient, InventoryS2CPacket packet) {
		return control(recipient, packet, false);
	}
	/** Session-owned mailbox slots prepay their packet, queue wrapper and encoding scratch. */
	public boolean controlPrepaid(UUID recipient, InventoryS2CPacket packet) {
		return control(recipient, packet, true);
	}
	private boolean control(UUID recipient, InventoryS2CPacket packet, boolean prepaid) {
		if (packet.isCorrupt() || packet.kind() != InventoryS2CPacket.Kind.OFFER && packet.kind() != InventoryS2CPacket.Kind.POLICY
			&& packet.kind() != InventoryS2CPacket.Kind.SELECTED && packet.kind() != InventoryS2CPacket.Kind.REJECT
			&& packet.kind() != InventoryS2CPacket.Kind.OPENED && packet.kind() != InventoryS2CPacket.Kind.OPEN_REJECTED
			&& packet.kind() != InventoryS2CPacket.Kind.PREVIEW_STATE) return false;
		String key = controlKey(recipient, packet);
		Control previous = controls.get(key);
		if (previous != null) { controls.remove(key); releaseControl(previous); }
		if (controls.size() >= controlCapacity || controls.values().stream().filter(c -> c.state.context.recipient().equals(recipient)).count() >= 64) {
			InventoryDiagnostics.report(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, InventoryDiagnosticSite.PUBLICATION,
				InventoryOperation.PUBLISH, InventoryFailureReason.CONTROL_CAPACITY, InventoryImpact.DEGRADED, InventoryOutcome.DEFERRED, true,
				() -> new InventoryDiagnosticDetail("control", packet.requestId(), packet.commitId(), packet.resourceKind().name(), 1, controls.size(), controlCapacity), null);
			return false;
		}
		RetainedMemoryLedger.@Nullable Ticket held = prepaid ? null : memory.tryReserve(STATE_MEMORY).orElse(null);
		if (!prepaid && held == null) {
			InventoryDiagnostics.report(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, InventoryDiagnosticSite.PUBLICATION,
				InventoryOperation.PUBLISH, InventoryFailureReason.MEMORY_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.DEFERRED, true,
				() -> new InventoryDiagnosticDetail("control-admission", packet.requestId(), packet.commitId(), packet.resourceKind().name(), STATE_MEMORY,
					memory.retained(), memory.cap()), null);
			return false;
		}
		if (held != null) held.commit(STATE_MEMORY);
		Context context = new Context("control", recipient, packet.presentationEpoch() + "/" + packet.view(), 1, 1);
		State state = new State(context, new Route(packet.epoch(), packet.presentationEpoch(), packet.view(), packet.requestId(), null, "control", null, packet.resourceKind()), held);
		state.monitored = false; state.prepaidControl = prepaid;
		controls.put(key, new Control(state, packet)); return true;
	}
	/** Exact queued owner only: admission is not a successful transport handoff. */
	public boolean controlQueued(UUID recipient, InventoryS2CPacket packet) {
		@Nullable Control control = controls.get(controlKey(recipient, packet));
		return control != null && control.packet == packet;
	}
	public void drainControls() {
		if (drainingControls) return;
		drainingControls = true;
		try {
		// At most 1024 controls. New/replaced work belongs to the next drain;
		// cancellation/close may remove snapshot members before their turn.
		for (var entry : new LinkedHashMap<>(controls).entrySet()) {
			Control control = entry.getValue();
			if (controls.get(entry.getKey()) != control) continue;
			if (!transport.authorizedControl(control.state.context.recipient(), control.packet)) {
				if (controls.remove(entry.getKey(), control)) releaseControl(control);
				continue;
			}
			if (send(control.state, control.packet, true)) {
				// Remove/refund accepted work BEFORE calling external code, even
				// when that code cancels, closes, replaces or throws.
				if (controls.remove(entry.getKey(), control)) releaseControl(control);
				@Nullable ControlHandoff handoff = controlHandoff;
				if (handoff != null) {
					boolean accepted;
					try { accepted = handoff.accepted(control.state.context.recipient(), control.packet); }
					catch (RuntimeException | LinkageError callbackFailure) { warn("control-handoff", control.state, control.packet, callbackFailure); accepted = false; }
					if (accepted) dataGates.remove(control.state.context.recipient());
				}
			}
		}
		} finally { drainingControls = false; }
	}
	public void cancelControls(UUID recipient) {
		var iterator = controls.values().iterator();
		while (iterator.hasNext()) { var control = iterator.next(); if (control.state.context.recipient().equals(recipient)) { releaseControl(control); iterator.remove(); } }
	}
	/** Retiring a bounded result owner also retires only its exact queued send. */
	public void cancelControl(UUID recipient, InventoryS2CPacket packet) {
		String key = controlKey(recipient, packet);
		Control control = controls.get(key);
		if (control != null && control.packet == packet && controls.remove(key, control)) releaseControl(control);
	}
	private static void releaseControl(Control control) { if (control.state.memory != null) control.state.memory.close(); }
	/**
	 * Blocks every item-data handoff for one recipient until a successful
	 * control handoff (see {@link #setControlHandoff}) authorizes release. A
	 * compatibility caller that never gates keeps the old behavior.
	 */
	public void gateData(UUID recipient) { dataGates.add(Objects.requireNonNull(recipient, "recipient")); }
	public void releaseData(UUID recipient) { dataGates.remove(recipient); }
	public boolean dataGated(UUID recipient) { return dataGates.contains(recipient); }
	public void setControlHandoff(@Nullable ControlHandoff handoff) { this.controlHandoff = handoff; }
	/** The exact effective display byte budget currently enforced by this publisher. */
	public int effectiveDisplayBytes() { return displayBytes; }
	/** The exact effective frame byte budget currently enforced by this publisher. */
	public int effectiveFrameBytes() { return frameBytes; }
	/** An OFFER must advertise the pair this publisher actually writes under. */
	public InventoryS2CPacket.Offer offer(int previewPeriodTicks, int trackingPeriodTicks, int resyncMinPeriods, int heartbeatPeriods) {
		return new InventoryS2CPacket.Offer(previewPeriodTicks, trackingPeriodTicks, resyncMinPeriods, heartbeatPeriods, displayBytes, frameBytes);
	}
	public boolean register(Context context, Route route) {
		var ticket = reserveRegistration(route.markerId() == null ? 0 : 2);
		if (ticket.isEmpty()) return false;
		return register(context, route, ticket.get());
	}
	public Optional<RetainedMemoryLedger.Ticket> reserveRegistration() {
		return reserveRegistration(2); // one selected key and its exact-to-aggregate overlap
	}
	private Optional<RetainedMemoryLedger.Ticket> reserveRegistration(int keys) {
		RetainedMemoryLedger.@Nullable Ticket base = states.size() >= MAX_CONTEXTS ? null : memory.tryReserve(REGISTRATION_MEMORY).orElse(null);
		RetainedMemoryLedger.@Nullable Ticket backing = base == null || keys == 0 ? null : memory.tryReserve(keys * 3072L).orElse(null);
		Optional<RetainedMemoryLedger.Ticket> held;
		if (base == null || keys > 0 && backing == null) {
			if (base != null) base.close();
			held = Optional.empty();
		} else held = Optional.of(new RegistrationTicket(base, backing, keys));
		if (held.isEmpty()) InventoryDiagnostics.report(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER, InventoryDiagnosticSite.PUBLICATION,
			InventoryOperation.PUBLISH, states.size() >= MAX_CONTEXTS ? InventoryFailureReason.PUBLISHER_CAPACITY : InventoryFailureReason.MEMORY_LIMIT,
			InventoryImpact.DEGRADED, InventoryOutcome.DEFERRED, true,
			() -> new InventoryDiagnosticDetail("registration", -1, -1, null, REGISTRATION_MEMORY, states.size(), MAX_CONTEXTS), null);
		return held;
	}
	public int remainingRegistrations() { return MAX_CONTEXTS - states.size(); }
	public boolean register(Context context, Route route, RetainedMemoryLedger.Ticket ticket) {
		String key = key(context);
		if (states.size() >= MAX_CONTEXTS || states.containsKey(key)) {
			if (states.size() >= MAX_CONTEXTS) InventoryDiagnostics.report(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				InventoryDiagnosticSite.PUBLICATION, InventoryOperation.PUBLISH, InventoryFailureReason.PUBLISHER_CAPACITY,
				InventoryImpact.DEGRADED, InventoryOutcome.DEFERRED, true,
				() -> new InventoryDiagnosticDetail("registration-install", route.requestId(), -1, route.resourceKind().name(), 1, states.size(), MAX_CONTEXTS), null);
			ticket.close(); return false;
		}
		int capacity = route.markerId() == null ? 0 : 2;
		if (ticket instanceof RegistrationTicket registration) capacity = registration.capacity;
		ticket.commit(REGISTRATION_MEMORY + capacity * 3072L);
		try {
			State state = new State(context, route, ticket instanceof RegistrationTicket registration ? registration.base : ticket);
			state.keyCapacity = capacity;
			if (ticket instanceof RegistrationTicket registration) state.keyMemory = registration.backing;
			states.put(key, state); return true;
		}
		catch (RuntimeException | Error failure) { ticket.close(); throw failure; }
	}
	public void advance(long now, InventorySettings settings) {
		this.settings = settings;
		var sizePair = InventorySizeScale.reconcile(settings.getDisplayByteMultiplier(), settings.getFrameByteMultiplier());
		displayBytes = (int) InventorySizeScale.DISPLAY.bytes(sizePair.displayMultiplier());
		frameBytes = (int) InventorySizeScale.FRAME.bytes(sizePair.frameMultiplier());
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) {
			previewGlobal = 0; clientBytes.keySet().removeIf(k -> k.startsWith("preview/")); releaseWireSubjects("preview/");
		}
		if (trackingClock.advance(now, settings.getTracking().getPeriodTicks())) {
			trackingPeriod = trackingClock.period(); clientBytes.keySet().removeIf(k -> k.startsWith("snapshot/")); streamBytes.clear();
			releaseWireSubjects("snapshot/"); releaseWireSubjects("stream/");
			if (window != null && trackingPeriod > 0) window.advance(trackingPeriod);
		}
		if (window == null || windowBase != settings.trackingGlobalPeriodBytes() || windowGrace != settings.getTracking().getGracePeriods()) {
			windowBase = settings.trackingGlobalPeriodBytes(); windowGrace = settings.getTracking().getGracePeriods();
			if (window == null) window = new InventoryWireWindow(windowBase, windowGrace);
			else window.reconfigure(windowBase, windowGrace);
		}
		if (tick != now) {
			tick = now;
			boolean monitorQueues = prepareMonitorQueues();
			for (State state : states.values()) {
				state.publicationDelay.beginTick(cycle(state)); state.batchDelay.beginTick(cycle(state));
				state.dataDelay.beginTick(cycle(state)); state.controlDelay.beginTick(cycle(state));
				state.heartbeatDelay.beginTick(cycle(state));
				if (monitorQueues) observeMonitorQueue(state);
			}
			if (monitorQueues) queueBaseline = true;
		}
		if (settings.getTracking().getHeartbeatPeriods() == 0) for (State state : states.values()) state.heartbeatDelay.cancel();
	}
	/** Read-only acceptance, scoped to the current context and its retirement/rebaseline fences. */
	boolean accepted(Context context, long watermark) {
		State state = states.get(key(context));
		return state != null && state.context.equals(context) && state.offeredWatermark != 0
			&& watermark <= state.offeredWatermark && !state.retirementPending && !state.rebaselineRequired;
	}
	/** Exact current cumulative Preview handoff; never an acknowledgement or an offered-cut shortcut. */
	public boolean previewCutComplete(Context context, long revision) {
		@Nullable State state = states.get(key(context));
		return state != null && state.context.equals(context) && state.route.markerId() == null
			&& revision > 0 && state.offeredWatermark == revision && state.deliveredWatermark == revision
			&& state.complete && !state.deliveryIncomplete && !state.retirementPending && !state.rebaselineRequired
			&& !state.fencePending && !state.control && !state.restageLatest && !state.sourceDenied
			&& state.pending.isEmpty() && state.admitted.isEmpty();
	}
	/** Temporary atomic-initial staging shares the existing Tracking queue capacity, without its wire clock. */
	public Optional<InitialStaging> reserveInitial(long wireBytes, long encodingWorkspace) {
		return Optional.ofNullable(reserveInitialDecision(wireBytes, encodingWorkspace).value());
	}
	public InventoryDecision<InitialStaging> reserveInitialDecision(long wireBytes, long encodingWorkspace) {
		if (wireBytes < 0 || encodingWorkspace < 0) throw new IllegalArgumentException("initial staging bound");
		if (settings == null || wireBytes > settings.trackingRollingQueueBytes() - trackingQueued)
			return InventoryDecision.defer(InventoryFailureReason.QUEUE_LIMIT, InventoryImpact.DEGRADED);
		var held = memory.tryReserve(checkedAdd(wireBytes, encodingWorkspace));
		if (held.isEmpty()) return InventoryDecision.defer(InventoryFailureReason.MEMORY_LIMIT, InventoryImpact.DEGRADED);
		trackingQueued += wireBytes;
		return InventoryDecision.accept(new InitialStaging(wireBytes, encodingWorkspace, held.get()));
	}
	@NotNullByDefault
	public final class InitialStaging implements AutoCloseable {
		private long wireBytes;
		private final RetainedMemoryLedger.Ticket memory;
		private final long upper;
		private boolean settled;
		private boolean closed;
		private InitialStaging(long wireBytes, long workspace, RetainedMemoryLedger.Ticket memory) {
			this.wireBytes = wireBytes; this.memory = memory; this.upper = checkedAdd(wireBytes, workspace);
		}
		public void settle(long actualWire, long actualWorkspace) {
			if (closed || settled || actualWire < 0 || actualWire > wireBytes || actualWorkspace < 0
				|| checkedAdd(actualWire, actualWorkspace) > upper) throw new IllegalArgumentException("initial staging settlement");
			memory.commit(checkedAdd(actualWire, actualWorkspace));
			trackingQueued -= wireBytes - actualWire; wireBytes = actualWire; settled = true;
		}
		@Override public void close() {
			if (!closed) { closed = true; trackingQueued -= wireBytes; memory.close(); }
		}
	}
	@Override public Outcome publish(CaptureResult result, AuthorizedProjection projection, Context context, CostLedger ledger) {
		return publish(result, InventoryStep.SweepEvidence.NONE, projection, context, ledger);
	}
	/** Inventory evidence belongs to this observation, never to the route or a preceding publication. */
	public Outcome publish(CaptureResult result, InventoryStep.SweepEvidence evidence, AuthorizedProjection projection, Context context, CostLedger ledger) {
		return publish(result, evidence, projection, context, ledger, null);
	}
	public Outcome publish(CaptureResult result, InventoryStep.SweepEvidence evidence, AuthorizedProjection projection, Context context, CostLedger ledger,
		@Nullable InventoryPublicationEvidence sourceEvidence) {
		@Nullable State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || !transport.authorized(context)) return new Outcome.Rejected();
		if (result.availability() != CaptureResult.Availability.READABLE) {
			withdraw(state);
			state.cancelDelays();
			state.retirementPending = false; state.rebaselineRequired = false;
			state.bootstrapClosed = true;
			state.complete = false; state.status = InventoryS2CPacket.Status.INVALID; state.control = true; return new Outcome.Accepted();
		}
		if (state.route.markerId() != null && result.completeness() != CaptureResult.Completeness.COMPLETE) return rejectPublication(state);
		if (!(result.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment payload)) return rejectPublication(state);
		boolean complete = result.completeness() == CaptureResult.Completeness.COMPLETE;
		var quality = quantityStatus(result, evidence);
		var lifecycle = complete ? quality : result.completeness() == CaptureResult.Completeness.INCOMPLETE
			? InventoryS2CPacket.Status.INCOMPLETE : InventoryS2CPacket.Status.UPDATING;
		return publishPayload(state, payload, result.coverage().watermark(), complete, lifecycle, quality, false, projection, context, ledger, sourceEvidence);
	}
	/**
	 * Publishes one server-retained selected preview value to a marker recipient.
	 * This narrow path takes no capture consistency or sweep evidence: every part
	 * is quantity-bearing but completeScan=false. Repair/reprojection may replay it
	 * with a newer revision before the first completed publication; invalidation
	 * permanently withdraws bootstrap eligibility for this registration, even over rebase.
	 */
	public Outcome publishBootstrap(CaptureResult.OpaqueKeyedFragment payload, long revision, InventoryS2CPacket.Status lifecycle,
		AuthorizedProjection projection, Context context, CostLedger ledger) {
		return publishBootstrap(payload, revision, lifecycle, projection, context, ledger, null);
	}
	public Outcome publishBootstrap(CaptureResult.OpaqueKeyedFragment payload, long revision, InventoryS2CPacket.Status lifecycle,
		AuthorizedProjection projection, Context context, CostLedger ledger, @Nullable InventoryPublicationEvidence sourceEvidence) {
		@Nullable State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || !transport.authorized(context)) return new Outcome.Rejected();
		if (state.route.markerId() == null || state.bootstrapClosed || revision <= 0
			|| lifecycle != InventoryS2CPacket.Status.UPDATING && lifecycle != InventoryS2CPacket.Status.INCOMPLETE
			|| state.status == InventoryS2CPacket.Status.INCOMPLETE && lifecycle == InventoryS2CPacket.Status.UPDATING
			|| projection.form() != PublicationForm.KEYED_ABSOLUTE || payload.entries().size() != 1
			|| !payload.entries().keySet().equals(projection.authorizedKeys())
			|| !state.offered.isEmpty() && !state.offered.keySet().equals(projection.authorizedKeys())) return rejectPublication(state);
		if (state.bootstrap && lifecycle == InventoryS2CPacket.Status.INCOMPLETE && state.status != lifecycle) status(context, lifecycle);
		return publishPayload(state, payload, revision, false, lifecycle, lifecycle, true, projection, context, ledger, sourceEvidence);
	}
	private Outcome publishPayload(State state, CaptureResult.OpaqueKeyedFragment payload, long revision, boolean complete,
		InventoryS2CPacket.Status lifecycle, InventoryS2CPacket.Status quality, boolean bootstrap,
		AuthorizedProjection projection, Context context, CostLedger ledger, @Nullable InventoryPublicationEvidence sourceEvidence) {
		if (!InventoryResourceCodec.id(state.route.resourceKind()).equals(payload.codecId()))
			return rejectPublication(state);
		// An older duplicate does not resolve a newer publication's pending refusal.
		if (revision <= state.offeredWatermark && state.offeredWatermark != 0)
			return state.retirementPending || state.rebaselineRequired ? new Outcome.Rejected() : new Outcome.Accepted();
		if (state.rebaselineRequired) return rejectPublication(state);
		var admission = ledger.tryReserve(Map.of(PUBLICATIONS, (long) payload.entries().size()));
		if (admission.isEmpty()) { delay(state, state.publicationDelay, Reason.PUBLICATION_WORK.bit(), "publication", Unit.ENTRY, payload.entries().size()); return new Outcome.Deferred(); }
		admission.get().commit(Map.of(PUBLICATIONS, (long) payload.entries().size()));
		// Measured decode workspace from the actual detached bytes, never an absolute-frame mirror.
		var workspace = memory.tryReserve(publicationWorkspace(state, payload));
		if (workspace.isEmpty()) { delay(state, state.publicationDelay, Reason.MEMORY.bit(), "publication", Unit.ENTRY, payload.entries().size()); return new Outcome.Deferred(); }
		try (var decoding = workspace.get()) {
		List<InventoryS2CPacket.Entry> values = new ArrayList<>();
		Set<String> nextKeys = new LinkedHashSet<>(state.admittedKeys), nextGroups = new LinkedHashSet<>(state.admittedGroups);
		for (var entry : payload.entries().entrySet()) {
			if (!projection.authorizedKeys().contains(entry.getKey())) continue;
			@Nullable InventoryResourceValue item = InventoryResourceCodec.decode(state.route.resourceKind(), entry.getValue());
			if (item == null) { if (bootstrap) return rejectPublication(state); else continue; }
			if (item.aggregateRequired()) {
				retireGroup(context, item.key().itemId());
				values.removeIf(value -> value.itemId().equals(item.key().itemId()) && !value.fallback());
			}
			if (!item.aggregateRequired() && state.retiredGroups.contains(item.key().itemId())) continue;
			nextKeys.add(entry.getKey()); nextGroups.add(item.key().itemId());
			boolean aggregate = item.aggregateRequired();
			var value = new InventoryS2CPacket.Entry(entry.getKey(), item.key().itemId(), item.label(), item.displayJson(), item.amount(), revision,
				aggregate, aggregate ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : quality,
				aggregate ? revision : 0, aggregate, state.route.itemPingType());
			// The first completed scan must replace even an equal aggregate seed.
			if (bootstrap || state.bootstrap || !sameValue(state.offered.get(value.key()), value)) values.add(value);
		}
		if (state.bootstrap && !bootstrap && state.offered.keySet().stream().anyMatch(key -> values.stream().noneMatch(value -> value.key().equals(key))))
			return rejectPublication(state);
		if (state.rebaselineRequired) return rejectPublication(state);
		if (!state.retiringGroups.isEmpty() && complete) {
			for (String id : state.retiringGroups) if (values.stream().noneMatch(value -> value.fallback() && value.itemId().equals(id)))
				return rejectPublication(state);
		}
		var projectedKeys = new java.util.HashSet<>(state.offered.keySet());
		for (var value : values) {
			if (value.replaceGroup()) for (var old : state.offered.values()) if (old.itemId().equals(value.itemId())) projectedKeys.remove(old.key());
			projectedKeys.add(value.key());
		}
		if (projectedKeys.size() > MAX_KEYS) {
			report(state, InventoryFailureReason.ENTRY_LIMIT, InventoryOutcome.REJECTED, true, "projected-keys", projectedKeys.size(), state.offered.size(), MAX_KEYS, null);
			return rejectPublication(state);
		}
		long queue = values.stream().mapToLong(InventorySyncPublisher::cost).sum();
		boolean tracking = state.route.markerId() != null;
		long pendingCost = values.stream().mapToLong(e -> state.pending.containsKey(e.key()) ? cost(state.pending.get(e.key())) : 0).sum();
		// A completed preview certifies receipt of the cumulative projection, not
		// just this delta. Earlier transport handoffs are not acknowledgements.
		// Reuse owned immutable entries, but charge every repeated key to the queue
		// and every final part to the normal wire budget and client part barrier.
		boolean cumulative = state.restageLatest || !tracking && complete;
		if (cumulative) {
			// The cancelled immutable cut may contain older versions than offered.
			// Repend the latest offered values, including keys absent from this delta.
			pendingCost = state.pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum();
			for (var old : state.offered.values()) if (values.stream().noneMatch(value -> value.key().equals(old.key()))) queue = checkedAdd(queue, cost(old));
		}
		if (checkedAdd(queue, tracking ? trackingQueued : previewQueued) - pendingCost > (tracking ? settings.trackingRollingQueueBytes() : previewQueueBytes())) {
			delay(state, state.publicationDelay, Reason.QUEUE_CAPACITY.bit(), "publication", Unit.ENTRY, values.size());
			return new Outcome.Deferred();
		}
		var capacity = admitKeyCapacity(state, Math.max(nextKeys.size(), nextGroups.size()));
		if (!capacity.accepted()) {
			if (capacity.reason() == InventoryFailureReason.ENTRY_LIMIT) return rejectPublication(state);
			delay(state, state.publicationDelay, capacity.reason() == InventoryFailureReason.MEMORY_LIMIT ? Reason.MEMORY.bit()
				: Reason.PUBLICATION_WORK.bit(), "key-capacity", Unit.ENTRY, payload.entries().size());
			return new Outcome.Deferred();
		}
		// Every new immutable version is admitted all-or-none before installing it.
		// Old versions stay charged until no retained map or immutable batch uses them.
		List<RetainedMemoryLedger.Ticket> granted = new ArrayList<>(values.size());
		boolean installed = false;
		try {
		for (var value : values) {
			var held = memory.tryReserve(retainedEntryBytes(value));
			if (held.isEmpty()) { delay(state, state.publicationDelay, Reason.MEMORY.bit(), "publication", Unit.ENTRY, values.size()); return new Outcome.Deferred(); }
			try { granted.add(held.get()); }
			catch (RuntimeException | Error failure) { held.get().close(); throw failure; }
		}
		// Both old indexes and their fully built successors overlap under decoding's
		// grant. Do not clear/repend in place: allocation failure must leave latest
		// offers, pending queue accounting and retirement dirty state retryable.
		var offered = new LinkedHashMap<>(state.offered);
		var pending = new LinkedHashMap<>(cumulative ? state.offered : state.pending);
		for (var value : values) {
			if (!value.replaceGroup()) continue;
			offered.values().removeIf(old -> old.itemId().equals(value.itemId()) && !old.key().equals(value.key()));
			pending.values().removeIf(old -> old.itemId().equals(value.itemId()) && !old.key().equals(value.key()));
		}
		for (int index = 0; index < values.size(); index++) {
			var value = values.get(index);
			var ticket = granted.get(index);
			ticket.commit(retainedEntryBytes(value));
			pending.put(value.key(), value); offered.put(value.key(), value);
		}
		long delta = pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum()
			- state.pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum();
		// Build identity ownership before switching either index. Failure removes
		// just these newly granted versions; delivered/admitted owners stay intact.
		for (int index = 0; index < values.size(); index++) state.entryMemory.put(values.get(index), granted.get(index));
		state.pending = pending; state.offered = offered;
		state.admittedKeys = nextKeys; state.admittedGroups = nextGroups;
		if (tracking) trackingQueued += delta; else previewQueued += delta;
		observeMonitorQueue(state);
		state.restageLatest = false;
		installed = true;
		for (var value : values) if (value.replaceGroup()) {
			state.retiredGroups.add(value.itemId()); state.retiringGroups.remove(value.itemId());
		}
		releaseUnretainedEntries(state);
		state.offeredWatermark = Math.max(state.offeredWatermark, revision);
		replaceOfferedEvidence(state, sourceEvidence);
		state.retirementPending = false;
		state.complete = !state.deliveryIncomplete && state.retiringGroups.isEmpty() && complete;
		state.bootstrap = bootstrap;
		if (complete) state.bootstrapClosed = true;
		state.status = state.deliveryIncomplete || !state.retiringGroups.isEmpty() ? InventoryS2CPacket.Status.INCOMPLETE : lifecycle;
		if (state.status != InventoryS2CPacket.Status.INVALID && state.status != InventoryS2CPacket.Status.INCOMPLETE) state.failureReason = null;
		state.control = true;
		state.publicationDelay.progress(cycle(state));
		state.offeredCutPending = true;
		monitor.count(tracking ? Lane.TRACKING : Lane.PREVIEW, Counter.PUBLICATIONS_INSTALLED, 1);
		return new Outcome.Accepted();
		} finally {
			if (!installed) {
				for (var value : values) state.entryMemory.remove(value);
				granted.forEach(RetainedMemoryLedger.Ticket::close);
			}
		}
		}
	}
	private InventoryDecision<Boolean> admitKeyCapacity(State state, int needed) {
		if (needed > MAX_KEYS) {
			report(state, InventoryFailureReason.ENTRY_LIMIT, InventoryOutcome.REJECTED, true, "lifetime-keys", needed, state.admittedKeys.size(), MAX_KEYS, null);
			return InventoryDecision.reject(InventoryFailureReason.ENTRY_LIMIT, InventoryImpact.DEGRADED);
		}
		if (needed > state.keyCapacity) {
			int capacity = Math.min(MAX_KEYS, Math.max(needed, Math.max(2, state.keyCapacity * 2)));
			long bytes = capacity * 3072L;
			var held = memory.tryReserve(bytes);
			if (held.isEmpty()) return InventoryDecision.defer(InventoryFailureReason.MEMORY_LIMIT, InventoryImpact.DEGRADED);
			boolean retained = false;
			try {
				if (!backingWork.test(bytes + state.keyCapacity * 3072L))
					return InventoryDecision.defer(InventoryFailureReason.ITEM_WORK_LIMIT, InventoryImpact.DEGRADED);
				// Admission workspace already pays full replacement indexes and copying;
				// the old backing owner remains live until the replacement is installed.
				Set<String> keys = new LinkedHashSet<>(capacity * 2);
				keys.addAll(state.admittedKeys);
				Set<String> groups = new LinkedHashSet<>(capacity * 2);
				groups.addAll(state.admittedGroups);
				held.get().commit(bytes);
				if (state.keyMemory != null) state.keyMemory.close();
				state.keyMemory = held.get(); state.keyCapacity = capacity;
				state.admittedKeys = keys; state.admittedGroups = groups; retained = true;
			} finally { if (!retained) held.get().close(); }
		}
		return InventoryDecision.accept(true);
	}
	private static InventoryS2CPacket.Status quantityStatus(CaptureResult result, InventoryStep.SweepEvidence evidence) {
		// A stable-slot sweep is an accepted mixed-tick inventory count, not an atomic snapshot or a fabricated source version.
		boolean stableSweep = evidence == InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP
			&& result.availability() == CaptureResult.Availability.READABLE
			&& result.completeness() == CaptureResult.Completeness.COMPLETE
			&& result.consistency() == CaptureResult.Consistency.EVENTUAL
			&& result.coverage().expected().isPresent()
			&& result.coverage().scanned() == result.coverage().expected().getAsLong();
		return result.consistency() == CaptureResult.Consistency.VERIFIED || stableSweep
			? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN;
	}
	private static Outcome rejectPublication(State state) {
		state.publicationDelay.cancel();
		return new Outcome.Rejected();
	}
	private static long publicationWorkspace(State state, CaptureResult.OpaqueKeyedFragment payload) {
		long indexKeys = checkedAdd(state.delivered.size(), checkedAdd(state.admitted.size(),
			checkedAdd(state.offered.size(), checkedAdd(state.pending.size(), payload.entries().size()))));
		long upper = checkedAdd(8192L, checkedMultiply(1024L, indexKeys));
		for (var value : payload.entries().values()) upper = checkedAdd(upper, checkedAdd(checkedMultiply(value.size(), 8L), 2048L));
		return upper;
	}
	/**
	 * Synchronously retires exact group work, even when replacement admission
	 * subsequently defers. A cancelled tracking assembly needs a fresh recipient
	 * baseline; transport handoff is never evidence that multipart application
	 * completed. Registration prepaid the bounded lifetime retirement slab.
	 */
	public boolean retireGroup(Context context, String itemId) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return false;
		if (state.retiredGroups.contains(itemId)) return state.rebaselineRequired;
		if (itemId == null || itemId.isBlank() || itemId.length() > 256) throw new IllegalArgumentException("inventory retirement id");
		// Unknown groups never owned an exact publication and require no tombstone.
		if (!state.admittedGroups.contains(itemId)) return state.rebaselineRequired;
		if (state.retiredGroups.size() >= state.keyCapacity) throw new IllegalStateException("inventory retirement bound");
		state.retiredGroups.add(itemId); state.retiringGroups.add(itemId); state.retirementPending = true;
		boolean affected = !state.admitted.isEmpty();
		boolean exact = state.offered.values().stream().anyMatch(entry -> entry.itemId().equals(itemId) && !entry.fallback())
			|| state.delivered.values().stream().anyMatch(entry -> entry.itemId().equals(itemId) && !entry.fallback());
		// There is no application acknowledgement: even a fully handed baseline
		// can still be assembling at the recipient. Conservatively fence first
		// tracking retirement, including committed transport cuts.
		if (exact && state.route.markerId() != null) state.rebaselineRequired = true;
		if (affected) { withdraw(state); state.restageLatest = true; }
		state.offered.values().removeIf(entry -> entry.itemId().equals(itemId) && !entry.fallback());
		var iterator = state.pending.values().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (!entry.itemId().equals(itemId) || entry.fallback()) continue;
			if (state.route.markerId() == null) previewQueued -= cost(entry); else trackingQueued -= cost(entry);
			iterator.remove();
		}
		observeMonitorQueue(state);
		// No completion/digest for the superseded cut while replacement waits.
		state.complete = false; state.control = false; state.offeredCutPending = false;
		if (!state.fencePending) state.controlDelay.cancel();
		state.heartbeatDelay.cancel();
		releaseUnretainedEntries(state);
		return state.rebaselineRequired;
	}
	public void status(Context context, InventoryS2CPacket.Status status) {
		status(context, status, null);
	}
	public void status(Context context, InventoryS2CPacket.Status status, @Nullable InventoryFailureReason reason) {
		@Nullable State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		state.failureReason = reason;
		if (status == InventoryS2CPacket.Status.INVALID) {
			withdraw(state); state.complete = false; state.retirementPending = false; state.rebaselineRequired = false;
			releaseEvidence(state);
			state.bootstrapClosed = true;
			state.cancelDelays();
		}
		// An admitted provisional frame is immutable. Do not send its obsolete
		// UPDATING lifecycle after failure. A caller may explicitly restage the
		// retained seed as INCOMPLETE at a newer publication revision.
		if (status == InventoryS2CPacket.Status.INCOMPLETE && (state.bootstrap
			|| state.route.markerId() != null && state.admitted.stream().anyMatch(packet -> !packet.completeScan()))) {
			withdraw(state); state.complete = false;
		}
		state.status = status; state.control = true;
		if (status == InventoryS2CPacket.Status.INCOMPLETE) state.offeredCutPending = false;
	}
	public boolean pending(Context context) {
		State state = states.get(key(context));
		return state != null && state.context.equals(context) && (!state.pending.isEmpty() || !state.admitted.isEmpty());
	}
	public long deliveredParts(Context context) {
		@Nullable State state = states.get(key(context));
		return state == null || !state.context.equals(context) ? 0 : state.deliveredParts;
	}
	public @Nullable InventoryFailureReason deferredReason(Context context) {
		@Nullable State state = states.get(key(context));
		return state == null || !state.context.equals(context) ? null : state.deferredReason;
	}
	/**
	 * Withdraws one context's queued and admitted item data without touching
		 * its retained offered/delivered state, status, identity or their tickets, and
	 * refunds the queue counters. A cap change uses this to drop work that can
	 * no longer be written while the replacement fence and baseline are still
		 * pending; the caller republishes the reprojected cut afterwards.
		 * Versions used only by withdrawn admitted parts release their tickets.
	 */
	public void withdraw(Context context) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		withdraw(state);
		// The caller cancelled the data barrier too. Retained offered values are
		// not evidence of recipient delivery and cannot become an empty completion.
		if (state.route.markerId() == null && state.complete) {
			state.complete = false; state.control = false; state.controlDelay.cancel();
		}
	}
	/**
	 * True while this context's control frame (a marker fence or a completion
	 * summary) is still waiting for a successful handoff. A caller that must
	 * order a replacement fence before its replacement data waits for this to
	 * clear instead of treating enqueue as completion.
	 */
	public boolean controlPending(Context context) {
		State state = states.get(key(context));
		return state != null && state.context.equals(context) && state.control;
	}
	/**
	 * Inspection for the next Backend reconfiguration stage: keys of retained
	 * offered entries that no longer fit the current effective display/frame
	 * caps. Such data is never handed off; the caller reprojects or
	 * cancel/registers the context. Unaffected progress stays untouched.
	 */
	public List<String> oversizedKeys(Context context) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return List.of();
		List<String> keys = new ArrayList<>();
		for (var entry : state.offered.values()) if (!withinEffectiveCaps(entry)) keys.add(entry.key());
		return List.copyOf(keys);
	}
	public boolean oversized(Context context) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return false;
		for (var entry : state.offered.values()) if (!withinEffectiveCaps(entry)) return true;
		return false;
	}
	/**
	 * Flushes only terminal status/failure fences after a proof rejection applied
	 * outside the normal drain. Pending data is deliberately not retried here:
	 * its own next-tick attempt keeps the established deferral timing.
	 */
	public void drainTerminal() {
		drainControls();
		for (State state : new ArrayList<>(states.values())) drainStatus(state);
	}
	private void drainStatus(State state) {
		if (!transport.authorized(state.context)) { cancel(state.context); return; }
		// A terminal failure carries no quantity or completed cut. Retirement
		// fences data and success digests, not this explicit tracking status.
		boolean incomplete = state.route.markerId() != null && state.control && state.status == InventoryS2CPacket.Status.INCOMPLETE;
		if ((state.retirementPending || state.rebaselineRequired) && !incomplete) return;
		if (transport.admittedToSend(state.context) && state.fencePending) {
			// A completed small recovery can be ready in this same drain. Its clean
			// fence must still precede every baseline part, and is not a completed cut.
			if (!sendControl(state, () -> InventoryS2CPacket.status(state.route.resourceKind(), state.route.epoch(), 0, state.route.markerId(), state.context.baseline(),
				state.context.stateFence(), 0, state.status == InventoryS2CPacket.Status.INVALID
					? state.status : InventoryS2CPacket.Status.UPDATING, 0,
				state.status == InventoryS2CPacket.Status.INVALID ? state.failureReason : null).stamp(state.route.presentationEpoch(), state.route.view()))) return;
			state.fencePending = false;
			if (!incomplete && !state.complete && state.pending.isEmpty() && state.admitted.isEmpty()) state.control = false;
		}
		if (incomplete) {
			if (transport.admittedToSend(state.context) && sendControl(state, () -> InventoryS2CPacket.status(state.route.resourceKind(), state.route.epoch(), 0,
				state.route.markerId(), state.context.baseline(), state.context.stateFence(), state.bootstrap ? state.offeredWatermark : 0,
				InventoryS2CPacket.Status.INCOMPLETE, 0, state.failureReason).stamp(state.route.presentationEpoch(), state.route.view()))) state.control = false;
			return;
		}
		if (!transport.admittedToSend(state.context) || !state.control || state.complete
			|| !state.pending.isEmpty() || !state.admitted.isEmpty()) return;
		if (sendControl(state, () -> packet(state, true, List.of(), 0, 1))) state.control = false;
	}
	public void drain() {
		drainControls();
		// Status/invalidation never waits behind a different consumer's item traffic.
		var ordered = new ArrayList<>(states.values());
		if (!ordered.isEmpty()) java.util.Collections.rotate(ordered, -(drainCursor++ % ordered.size()));
		for (State state : ordered) drainStatus(state);
		for (State state : ordered) {
			if (!transport.authorized(state.context)) { cancel(state.context); continue; }
			if (state.retirementPending || state.rebaselineRequired) continue;
			if (!transport.admittedToSend(state.context) || state.fencePending) continue;
			// A gated recipient still receives fences, status and heartbeats; item data waits.
			if (!dataGated(state.context.recipient())) {
				if (state.admitted.isEmpty() && !state.pending.isEmpty()) admitBatch(state);
				while (state.part < state.admitted.size()) {
					InventoryS2CPacket packet = state.admitted.get(state.part);
					if (!send(state, packet, false)) break;
					state.part++;
					state.deliveredParts++; state.deferredReason = null;
					observeMonitorQueue(state);
				}
				if (!state.admitted.isEmpty() && state.part == state.admitted.size()) {
					for (var packet : state.admitted) for (var entry : packet.entries()) {
						if (entry.replaceGroup()) state.delivered.values().removeIf(old -> old.itemId().equals(entry.itemId()));
						state.delivered.put(entry.key(), entry);
						if (state.route.markerId() == null) previewQueued -= cost(entry); else trackingQueued -= cost(entry);
					}
					state.deliveredWatermark = state.admittedWatermark;
					state.deliveredChecksum = checksum(state, state.delivered.values());
					replaceDeliveredEvidence(state, state.admittedEvidence);
					// Every preview part already carries this cut's status/completion.
					// A redundant empty 0/1 control could impersonate a withheld data
					// index zero at the client and falsely close its multipart barrier.
					if (state.route.markerId() == null && state.admittedWatermark == state.offeredWatermark) state.control = false;
					state.admitted = List.of(); state.admittedChecksum = 0; state.part = 0; state.baseline = false;
					observeMonitorQueue(state);
					if (state.admittedWatermark == state.offeredWatermark) state.offeredCutPending = false;
					if (state.admittedEvidence != null) { state.admittedEvidence.close(); state.admittedEvidence = null; }
					if (state.batchMemory != null) { state.batchMemory.close(); state.batchMemory = null; }
					releaseUnretainedEntries(state);
					monitor.count(state.route.markerId() == null ? Lane.PREVIEW : Lane.TRACKING, Counter.CUTS_COMPLETED, 1);
				}
				if (state.control && state.pending.isEmpty() && state.admitted.isEmpty()) {
					long delivered = state.deliveredWatermark;
					if (sendControl(state, () -> packet(state, true, List.of(), 0, 1))) {
						state.control = false; state.deliveredWatermark = state.offeredWatermark; replaceDeliveredEvidence(state, state.offeredEvidence);
						if (state.offeredCutPending && state.deliveredWatermark > delivered
							&& state.status != InventoryS2CPacket.Status.INVALID && state.status != InventoryS2CPacket.Status.INCOMPLETE)
							monitor.count(state.route.markerId() == null ? Lane.PREVIEW : Lane.TRACKING, Counter.CUTS_COMPLETED, 1);
						state.offeredCutPending = false;
					}
				}
			}
			int heartbeat = settings.getTracking().getHeartbeatPeriods();
			if (state.route.markerId() != null && !state.baseline && heartbeat > 0 && state.pending.isEmpty() && state.admitted.isEmpty()
				&& (state.lastHeartbeat < 0 || trackingPeriod - state.lastHeartbeat >= heartbeat)) {
				if (sendControl(state, () -> InventoryS2CPacket.heartbeat(state.route.resourceKind(), state.route.epoch(), 0, state.route.markerId(), state.context.baseline(), state.context.stateFence(),
					state.deliveredWatermark, state.deliveredChecksum).stamp(state.route.presentationEpoch(), state.route.view()), true)) state.lastHeartbeat = trackingPeriod;
			}
		}
	}
	private void admitBatch(State state) {
		if (state.pending.isEmpty()) return;
		// Packet/list/index objects only: immutable entries keep their own tickets.
		// Preview filtering below measures exactly without allocating codec buffers.
		// data + stamp construct two packets; singleton entry lists, growable batch,
		// immutable batch and checksum sample/sort/UTF buffers overlap sequentially.
		// The delivered digest is computed at promotion under this same grant.
		int deliveredBound = Math.addExact(state.delivered.size(), state.pending.size());
		long workspace = checkedAdd(checksumWorkspace(Math.max(state.offered.size(), deliveredBound)),
			checkedAdd(4096L, checkedMultiply(1024L, state.pending.size())));
		var reservation = memory.tryReserve(workspace);
		if (reservation.isEmpty()) { delay(state, state.batchDelay, Reason.MEMORY.bit(), "batch", Unit.ENTRY, state.pending.size()); return; }
		boolean retained = false;
		try {
		if (state.route.markerId() == null) {
			long cap = previewClientBytes();
			long capacity = cap - Math.min(cap, 128 + cap / 16);
			// Measure actual multipart framing, including indexes >=128. A drop
			// changes READY to the longer INCOMPLETE status, requiring one recheck.
			// Within each pass, original indexes/count conservatively bound the
			// final reindexed widths: removing keys can only shrink those VarInts.
			// At most two passes, rather than rescanning displays after every drop.
			for (int pass = 0; pass < 2 && !state.pending.isEmpty(); pass++) {
				boolean removed = false, complete = state.complete;
				InventoryS2CPacket.Status status = state.status;
				@Nullable InventoryFailureReason reason = state.failureReason;
				int parts = state.pending.size(), index = 0;
				var iterator = state.pending.values().iterator();
				while (iterator.hasNext()) {
					var entry = iterator.next();
					long bytes;
					try {
						// Checksum has fixed wire width; do not sort/hash the projection
						// for each candidate measurement. Actual parts get its real digest.
						bytes = InventoryS2CPacket.data(state.route.resourceKind(), InventoryS2CPacket.Kind.PREVIEW, state.route.epoch(), state.route.requestId(), null,
							state.context.baseline(), state.context.stateFence(), state.offeredWatermark, index++, parts,
							complete, status, 0, List.of(entry), reason).stamp(state.route.presentationEpoch(), state.route.view()).encodedSize(displayBytes, frameBytes);
					} catch (IllegalArgumentException oversized) { bytes = Long.MAX_VALUE; }
					if (bytes <= capacity) continue;
					previewQueued -= cost(entry); iterator.remove(); removed = true;
					report(state, InventoryFailureReason.FRAME_LIMIT, InventoryOutcome.TRUNCATED, true, "preview-entry", bytes, 0, capacity, null);
					state.failureReason = InventoryFailureReason.FRAME_LIMIT;
					state.deliveryIncomplete = true; state.complete = false; state.status = InventoryS2CPacket.Status.INCOMPLETE; state.control = true;
				}
				if (!removed || status == state.status && reason == state.failureReason) break;
			}
		}
		if (state.pending.isEmpty()) { state.batchDelay.cancel(); state.dataDelay.cancel(); return; }
		// One immutable cut, one full-projection digest. Every part below carries
		// this exact value; a later offered revision cannot rewrite it.
		long cutChecksum = checksum(state, state.offered.values());
		List<InventoryS2CPacket> batch = new ArrayList<>();
		// One entry per bounded part: quotas and byte caps never reset for a new part.
		int parts = state.pending.size(), index = 0;
		for (var entry : state.pending.values()) batch.add(packet(state, false, List.of(entry), index++, parts, cutChecksum));
		state.admitted = List.copyOf(batch); state.pending.clear(); state.part = 0;
		state.admittedWatermark = state.offeredWatermark;
		state.admittedChecksum = cutChecksum;
		state.admittedEvidence = state.offeredEvidence == null ? null : state.offeredEvidence.retain();
		reservation.get().commit(workspace);
		state.batchMemory = reservation.get();
		state.batchDelay.progress(cycle(state));
		retained = true;
		} finally { observeMonitorQueue(state); if (!retained) reservation.get().close(); }
	}
	private InventoryS2CPacket packet(State state, boolean control, List<InventoryS2CPacket.Entry> entries, int part, int parts) {
		return packet(state, control, entries, part, parts, checksum(state, state.offered.values()));
	}
	private InventoryS2CPacket packet(State state, boolean control, List<InventoryS2CPacket.Entry> entries, int part, int parts,
		long checksum) {
		Route route = state.route;
		if (control && route.markerId() != null && !(state.baseline && state.complete && state.status != InventoryS2CPacket.Status.INVALID)) return InventoryS2CPacket.status(route.resourceKind(), route.epoch(), 0, route.markerId(), state.context.baseline(),
			state.context.stateFence(), state.offeredWatermark, state.status, checksum, state.failureReason).stamp(route.presentationEpoch(), route.view());
		return InventoryS2CPacket.data(route.resourceKind(), route.markerId() == null ? InventoryS2CPacket.Kind.PREVIEW : state.baseline ? InventoryS2CPacket.Kind.SNAPSHOT : InventoryS2CPacket.Kind.STREAM,
			route.epoch(), route.markerId() == null ? route.requestId() : 0, route.markerId(), state.context.baseline(), state.context.stateFence(), state.offeredWatermark,
			part, parts, state.complete, state.status, checksum, entries, state.failureReason).stamp(route.presentationEpoch(), route.view());
	}
	private long checksum(State state, Collection<InventoryS2CPacket.Entry> entries) {
		return InventoryChecksums.checksumEntries(state.route.resourceKind(), entries, checksumSamples);
	}
	/** Bounded digest samples/sort storage and UTF comparison scratch; display is not hashed. */
	private static long checksumWorkspace(int keys) { return checkedAdd(4096L, checkedMultiply(keys, 256L)); }
	private boolean sendControl(State state, java.util.function.Supplier<InventoryS2CPacket> factory) {
		return sendControl(state, factory, false);
	}
	private boolean sendControl(State state, java.util.function.Supplier<InventoryS2CPacket> factory, boolean heartbeat) {
		InventoryBudgetDelayState stage = heartbeat ? state.heartbeatDelay : state.controlDelay;
		String name = heartbeat ? "heartbeat" : "control";
		var grant = memory.tryReserve(checksumWorkspace(Math.max(state.offered.size(), state.delivered.size())));
		if (grant.isEmpty()) { delay(state, stage, Reason.MEMORY.bit(), name, Unit.FRAME, 1); return false; }
		try (var workspace = grant.get()) { return send(state, factory.get(), true); }
	}
	private boolean send(State state, InventoryS2CPacket packet, boolean control) {
		Lane lane = monitorLane(state, packet);
		if (!monitor.enabled()) return sendWork(state, packet, control);
		long timing = monitor.beginPhase(lane, Phase.PUBLISH);
		try { return sendWork(state, packet, control); }
		finally { monitor.end(timing); }
	}
	private int measuredBytes(State state, InventoryS2CPacket packet) {
		Lane lane = monitorLane(state, packet);
		if (!monitor.enabled()) return transport.encodedBytes(packet, displayBytes, frameBytes);
		long timing = monitor.beginPhase(lane, Phase.MEASURE);
		try { return transport.encodedBytes(packet, displayBytes, frameBytes); }
		finally { monitor.end(timing); }
	}
	private void observeSend(State state, InventoryS2CPacket packet, long bytes, TransportHandoff handoff, long generation) {
		if (!monitor.enabled() || generation != monitor.collectionGeneration()) return;
		// Wire counters follow the unchanged business ledger charged above; timing follows the actual SELECT workload.
		Lane lane = state.route.markerId() == null ? Lane.PREVIEW : Lane.TRACKING;
		@Nullable Counter frame = switch (packet.kind()) {
			case PREVIEW -> Counter.FRAMES_PREVIEW;
			case SNAPSHOT -> Counter.FRAMES_SNAPSHOT;
			case STREAM -> Counter.FRAMES_STREAM;
			default -> null;
		};
		monitor.sent(lane, frame, bytes, handoff);
	}
	private static Lane monitorLane(State state, InventoryS2CPacket packet) {
		return state.route.markerId() != null || packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT
			? Lane.TRACKING : Lane.PREVIEW;
	}
	private boolean sendWork(State state, InventoryS2CPacket packet, boolean control) {
		if (!control && dataGated(state.context.recipient())) return false;
		boolean heartbeat = packet.kind() == InventoryS2CPacket.Kind.HEARTBEAT;
		InventoryBudgetDelayState stage = heartbeat ? state.heartbeatDelay : control ? state.controlDelay : state.dataDelay;
		String name = heartbeat ? "heartbeat" : control ? "control" : "send";
		long workspace;
		try { workspace = packet.encodingWorkspace(displayBytes, frameBytes); }
		catch (IllegalArgumentException overBudget) { delay(state, stage, Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit(), name, Unit.FRAME, 1); return false; }
		RetainedMemoryLedger.@Nullable Ticket held = state.prepaidControl ? null : memory.tryReserve(workspace).orElse(null);
		if (!state.prepaidControl && held == null) { delay(state, stage, Reason.MEMORY.bit(), name, Unit.FRAME, 1); return false; }
		try (var reserved = held) {
			int bytes;
			try { bytes = measuredBytes(state, packet); }
			catch (IllegalArgumentException overBudget) { delay(state, stage, Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit(), name, Unit.FRAME, 1); return false; }
			if (bytes < 0 || bytes > InventoryS2CPacket.MAX_FRAME_BYTES) throw new IllegalArgumentException("encoded inventory frame");
			boolean tracking = state.route.markerId() != null;
			String client = (tracking ? "snapshot/" : "preview/") + state.context.recipient();
			long cap = tracking ? state.baseline ? snapshotClientBytes() : settings.trackingStreamPeriodBytesPerClientTarget() : previewClientBytes();
			String stream = state.context.recipient() + "/" + state.route.targetQuota();
			Map<String, Long> accounting = tracking && !state.baseline ? streamBytes : clientBytes;
			String subject = tracking && !state.baseline ? stream : client;
			String retainedSubject = tracking && !state.baseline ? "stream/" + subject : subject;
			if (!state.prepaidControl && !wireSubjects.containsKey(retainedSubject)) {
				if (wireSubjects.size() >= 4096) { delay(state, stage, Reason.SUBJECT_CAPACITY.bit(), name, Unit.BYTE, bytes); return false; }
				var cost = memory.tryReserve(1024); if (cost.isEmpty()) { delay(state, stage, Reason.MEMORY.bit(), name, Unit.BYTE, bytes); return false; }
				cost.get().commit(1024); wireSubjects.put(retainedSubject, cost.get());
			}
			long used = accounting.getOrDefault(subject, 0L);
			long reserve = control ? 0 : Math.min(cap, 128 + cap / 16);
			if (bytes > cap - used - reserve || (tracking && state.baseline && bytes > snapshotFragmentBytes())) {
				int reasons = Reason.WIRE_CLIENT_OR_STREAM.bit();
				if (bytes > cap - reserve || tracking && state.baseline && bytes > snapshotFragmentBytes()) reasons |= Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit();
				delay(state, stage, reasons, name, Unit.BYTE, bytes); return false;
			}
			if (tracking ? bytes > window.remaining() - (control ? 0 : Math.min(windowBase, 128 + windowBase / 16)) : bytes > previewGlobalBytes() - previewGlobal - reserve) {
				delay(state, stage, Reason.WIRE_GLOBAL.bit(), name, Unit.BYTE, bytes); return false;
			}
			// Capture the collection fence BEFORE final authorization. No clock or observer may follow the proof until send returns.
			long monitorGeneration = monitor.enabled() ? monitor.collectionGeneration() : -1;
			// Measurement and wire eligibility can invoke host code. Nothing callback-bearing follows this proof except send itself.
			if (state.monitored && (!states.containsValue(state) || !transport.authorized(state.context))) return false;
			if (!state.monitored && !transport.authorizedControl(state.context.recipient(), packet)) return false;
			@Nullable InventoryPublicationEvidence evidence = heartbeat ? state.deliveredEvidence : !control ? state.admittedEvidence : state.offeredEvidence;
			if (requiresSource(packet) && state.sourceDenied) return false;
			SendDecision decision = transport.beforeSend(state.context, packet, evidence);
			if (decision != SendDecision.ALLOW) {
				if (decision == SendDecision.DEFER) delay(state, stage, Reason.PROVIDER_WORK.bit(), name, Unit.FRAME, 1);
				return false;
			}
			CostLedger ledger = new CostLedger(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), cap - used));
			var ticket = ledger.tryReserve(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), (long) bytes)).orElseThrow();
			ticket.commit(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), (long) bytes));
			accounting.put(subject, used + bytes);
			if (tracking) window.trySpend(bytes); else previewGlobal += bytes;
			TransportHandoff handoff;
			try { handoff = transport.sendObserved(state.context.recipient(), packet); }
			catch (RuntimeException | LinkageError failure) {
				observeSend(state, packet, bytes, TransportHandoff.UNOBSERVED, monitorGeneration);
				warn("transport-send", state, packet, failure);
				return false; // conservatively charged; same immutable part retries
			}
			observeSend(state, packet, bytes, handoff, monitorGeneration);
			if (handoff == TransportHandoff.NOT_HANDED_OFF) {
				report(state, InventoryFailureReason.TRANSPORT_FAILURE, InventoryOutcome.DEFERRED, false, "not-handed-off", bytes, -1, -1, null);
				return false;
			}
			// Monitoring is callback-bearing too: it follows the irreversible handoff, never the final proof.
			if (state.monitored) stage.progress(cycle(state));
			return true;
		}
	}
	private void warn(String operation, State state, InventoryS2CPacket packet, Throwable failure) {
		report(state, InventoryFailureReason.TRANSPORT_FAILURE, InventoryOutcome.DEFERRED, false, operation, -1, -1, -1, failure);
	}
	private static void report(State state, InventoryFailureReason reason, InventoryOutcome outcome, boolean limit, String stage,
		long requested, long used, long cap, @Nullable Throwable failure) {
		InventoryDiagnostics.report(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
			reason == InventoryFailureReason.TRANSPORT_FAILURE ? InventoryDiagnosticSite.TRANSPORT : InventoryDiagnosticSite.PUBLICATION,
			InventoryOperation.PUBLISH, reason, InventoryImpact.DEGRADED, outcome, limit,
			() -> new InventoryDiagnosticDetail(stage, state.route.requestId(), -1, state.route.resourceKind().name(), requested, used, cap), failure);
	}
	@Override public void cancel(Context context) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		// Retire exactly this registered owner's contribution before removal. The
		// old drain may resume later, even after an equal context has registered anew.
		updateMonitorQueue(state, 0, 0);
		states.remove(key(context));
		if (state != null) {
			withdraw(state);
			releaseEvidence(state);
			state.cancelDelays();
			state.offered.clear(); state.delivered.clear();
			state.entryMemory.values().forEach(RetainedMemoryLedger.Ticket::close); state.entryMemory.clear();
			if (state.memory != null) state.memory.close();
			if (state.keyMemory != null) state.keyMemory.close();
		}
	}
	@Override public void rebase(Context context, long newStateFence) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || newStateFence < state.context.stateFence()) return;
		withdraw(state); state.cancelDelays(); state.offered.clear(); state.delivered.clear();
		releaseEvidence(state); state.sourceDenied = false;
		state.entryMemory.values().forEach(RetainedMemoryLedger.Ticket::close); state.entryMemory.clear();
		state.context = new Context(context.consumerId(), context.recipient(), context.sessionView(), newStateFence, context.baseline()); state.baseline = true;
		state.offeredWatermark = state.deliveredWatermark = 0; state.deliveredChecksum = 0; state.complete = false; state.control = false; state.deliveryIncomplete = false; state.status = InventoryS2CPacket.Status.UPDATING;
		state.retiredGroups.clear(); state.retiringGroups.clear(); state.restageLatest = false; state.rebaselineRequired = false; state.retirementPending = false;
		state.admittedKeys.clear(); state.admittedGroups.clear(); // backing capacity remains charged
	}
	private void withdraw(State state) {
		state.publicationDelay.cancel(); state.batchDelay.cancel(); state.dataDelay.cancel();
		long bytes = state.pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum();
		for (var packet : state.admitted) bytes += packet.entries().stream().mapToLong(InventorySyncPublisher::cost).sum();
		if (state.route.markerId() == null) previewQueued -= bytes; else trackingQueued -= bytes;
		state.pending.clear(); state.admitted = List.of(); state.admittedChecksum = 0; state.part = 0;
		state.offeredCutPending = false;
		observeMonitorQueue(state);
		if (state.admittedEvidence != null) { state.admittedEvidence.close(); state.admittedEvidence = null; }
		if (state.batchMemory != null) { state.batchMemory.close(); state.batchMemory = null; }
		releaseUnretainedEntries(state);
	}
	private static void replaceOfferedEvidence(State state, @Nullable InventoryPublicationEvidence evidence) {
		if (evidence != null) evidence.retain();
		if (state.offeredEvidence != null) state.offeredEvidence.close(); state.offeredEvidence = evidence;
	}
	private static void replaceDeliveredEvidence(State state, @Nullable InventoryPublicationEvidence evidence) {
		if (evidence != null) evidence.retain();
		if (state.deliveredEvidence != null) state.deliveredEvidence.close(); state.deliveredEvidence = evidence;
	}
	private static void releaseEvidence(State state) {
		replaceOfferedEvidence(state, null); replaceDeliveredEvidence(state, null);
	}
	/** Bounded flags only: no cancellation, release or provider call during a publisher traversal. */
	public void denyEvidence(InventoryPublicationEvidence evidence, boolean common) {
		for (State state : states.values()) if (matchesEvidence(state.offeredEvidence, evidence, common)
			|| matchesEvidence(state.admittedEvidence, evidence, common) || matchesEvidence(state.deliveredEvidence, evidence, common)) state.sourceDenied = true;
	}
	public boolean sourceDenied(Context context) {
		@Nullable State state = states.get(key(context)); return state != null && state.context.equals(context) && state.sourceDenied;
	}
	private static boolean matchesEvidence(@Nullable InventoryPublicationEvidence held, InventoryPublicationEvidence evidence, boolean common) {
		return held != null && (held == evidence || common && held.sameProof(evidence));
	}
	@NotNullByDefault
	public record PendingValidation(Context context, InventoryPublicationEvidence evidence) {}
	/** Callback-free selection. It reserves an opportunity, never authority. New mid-tick work waits for the next selection. */
	public Optional<PendingValidation> pendingValidation() {
		int count = states.size(); if (count == 0) return Optional.empty();
		int start = Math.floorMod(validationCursor, count), index = 0;
		@Nullable State first = null; int firstIndex = -1;
		for (State state : states.values()) {
			if (pendingEvidence(state) != null) {
				if (first == null) { first = state; firstIndex = index; }
				if (index >= start) { validationCursor = index + 1; return Optional.of(new PendingValidation(state.context, java.util.Objects.requireNonNull(pendingEvidence(state)))); }
			}
			index++;
		}
		if (first == null) return Optional.empty(); validationCursor = firstIndex + 1;
		return Optional.of(new PendingValidation(first.context, java.util.Objects.requireNonNull(pendingEvidence(first))));
	}
	private @Nullable InventoryPublicationEvidence pendingEvidence(State state) {
		if (state.sourceDenied || state.retirementPending || state.rebaselineRequired) return null;
		if (!dataGated(state.context.recipient())) {
			if (!state.admitted.isEmpty()) return state.admittedEvidence;
			if (!state.pending.isEmpty() || state.control && (state.complete || state.offeredWatermark > 0
				&& state.status != InventoryS2CPacket.Status.INVALID && state.status != InventoryS2CPacket.Status.INCOMPLETE)) return state.offeredEvidence;
		}
		int heartbeat = settings.getTracking().getHeartbeatPeriods();
		return state.route.markerId() != null && !state.baseline && heartbeat > 0 && state.pending.isEmpty() && state.admitted.isEmpty()
			&& (state.lastHeartbeat < 0 || trackingPeriod - state.lastHeartbeat >= heartbeat) ? state.deliveredEvidence : null;
	}
	private static void releaseUnretainedEntries(State state) {
		var iterator = state.entryMemory.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			var value = entry.getKey();
			if (state.offered.get(value.key()) == value || state.delivered.get(value.key()) == value || state.pending.get(value.key()) == value) continue;
			boolean admitted = false;
			for (var packet : state.admitted) for (var part : packet.entries()) if (part == value) admitted = true;
			if (!admitted) { entry.getValue().close(); iterator.remove(); }
		}
	}
	private boolean withinEffectiveCaps(InventoryS2CPacket.Entry entry) {
		if (entry.displayJson() != null && textBytes(entry.displayJson()) > displayBytes) return false;
		return FRAME_FIXED_BYTES + (long) ENTRY_FIXED_BYTES + entryTextBytes(entry) <= frameBytes;
	}
	private static boolean sameValue(InventoryS2CPacket.@Nullable Entry a, InventoryS2CPacket.Entry b) {
		return a != null && a.count() == b.count() && a.fallback() == b.fallback() && a.quality() == b.quality() && a.itemId().equals(b.itemId())
			&& a.label().equals(b.label()) && java.util.Objects.equals(a.displayJson(), b.displayJson()) && java.util.Objects.equals(a.itemPingType(), b.itemPingType());
	}
	private static long cost(InventoryS2CPacket.Entry entry) { return 128L + entry.key().length() * 3L + entry.itemId().length() * 3L + entry.label().length() * 3L + (entry.displayJson() == null ? 0 : entry.displayJson().length() * 3L); }
	private static long retainedEntryBytes(InventoryS2CPacket.Entry entry) {
		return cost(entry) + (entry.itemPingType() == null ? 0L : entry.itemPingType().length() * 3L) + 512L;
	}
	private static long entryTextBytes(InventoryS2CPacket.Entry entry) {
		return textBytes(entry.key()) + textBytes(entry.itemId()) + textBytes(entry.label())
			+ textBytes(entry.displayJson()) + textBytes(entry.itemPingType());
	}
	/** Allocation-free native UTF-8 length, including '?' replacement for malformed surrogates. */
	private static long textBytes(String value) {
		if (value == null) return 0;
		long bytes = 0;
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (current < 0x80) bytes += 1;
			else if (current < 0x800) bytes += 2;
			else if (Character.isHighSurrogate(current) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
			else bytes += Character.isSurrogate(current) ? 1 : 3;
		}
		return bytes;
	}
	private static long checkedAdd(long first, long second) {
		try { return Math.addExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	private static long checkedMultiply(long first, long second) {
		try { return Math.multiplyExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	private static String key(Context context) { return context.recipient() + "/" + context.consumerId(); }
	/** OPENED is one per request; other controls keep their commit/frame identity. */
	private static String controlKey(UUID recipient, InventoryS2CPacket packet) {
		long identity = packet.kind() == InventoryS2CPacket.Kind.OPENED || packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED
			|| packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE ? packet.requestId() : packet.commitId();
		return recipient + "/" + packet.kind() + "/" + packet.resourceKind() + "/" + identity;
	}
	private void releaseWireSubjects(String prefix) {
		var iterator = wireSubjects.entrySet().iterator();
		while (iterator.hasNext()) { var entry = iterator.next(); if (entry.getKey().startsWith(prefix)) { entry.getValue().close(); iterator.remove(); } }
	}
	@Override public void close() {
		controls.values().forEach(InventorySyncPublisher::releaseControl); controls.clear();
		for (State state : List.copyOf(states.values())) cancel(state.context);
		wireSubjects.values().forEach(RetainedMemoryLedger.Ticket::close); wireSubjects.clear(); clientBytes.clear(); streamBytes.clear();
		dataGates.clear(); controlHandoff = null;
	}
}
