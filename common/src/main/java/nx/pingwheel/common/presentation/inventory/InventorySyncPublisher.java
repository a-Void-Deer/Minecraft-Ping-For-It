package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.domain.MarkerId;
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
import nx.pingwheel.common.diagnostics.Diagnostics;

/** Actual bounded dedicated publication. Queued cuts remain immutable until fully transmitted. */
public final class InventorySyncPublisher implements SyncPublisher, AutoCloseable {
	public static final CostLedger.Counter PUBLICATIONS = new CostLedger.Counter("inventory/publication", CostLedger.Unit.WORK);
	public interface Transport {
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
	public record Route(long epoch, long presentationEpoch, long view, long requestId, MarkerId markerId,
		String targetQuota, String itemPingType) {}
	private static final long STATE_MEMORY = 4096;
	/**
	 * Registration owns a finite slab before constructing State: per key, two
	 * retirement set nodes + bounded UTF-16 id, offered/delivered/pending nodes,
	 * overlapping identity-ticket nodes and backing/growth slots. Delivered and
	 * admitted keys can overlap a newer projection. One slab slot also pays its
	 * share of those old owners (each cut has at most MAX_KEYS entries).
	 * 3072 uses 40-byte objects / 8-byte references with alignment headroom.
	 * Retirement needs no later grant, even after the cap falls below live cost.
	 */
	private static final int MAX_KEYS = 256;
	private static final long REGISTRATION_MEMORY = STATE_MEMORY + MAX_KEYS * 3072L;
	/** Conservative fixed framing upper bounds for measured packet reservations; never a frame budget. */
	private static final int FRAME_FIXED_BYTES = 128, ENTRY_FIXED_BYTES = 64;
	private static final class State {
		final Module module;
		final InventoryBudgetDelayState publicationDelay, batchDelay, dataDelay, controlDelay, heartbeatDelay;
		boolean monitored = true;
		Context context;
		Route route;
		final RetainedMemoryLedger.Ticket memory;
		Map<String, InventoryS2CPacket.Entry> offered = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> delivered = new LinkedHashMap<>();
		Map<String, InventoryS2CPacket.Entry> pending = new LinkedHashMap<>();
		/** One ticket per immutable object, shared by offered/delivered/pending/admitted references. */
		final Map<InventoryS2CPacket.Entry, RetainedMemoryLedger.Ticket> entryMemory = new IdentityHashMap<>();
		/** Bounded by the lifetime projected-key allowance, never a per-frame history. */
		final Set<String> retiredGroups = new LinkedHashSet<>(), retiringGroups = new LinkedHashSet<>();
		boolean restageLatest, rebaselineRequired, retirementPending;
		List<InventoryS2CPacket> admitted = List.of();
		long admittedWatermark;
		RetainedMemoryLedger.Ticket batchMemory;
		int part;
		long offeredWatermark, deliveredWatermark, lastHeartbeat = -1;
		InventoryS2CPacket.Status status = InventoryS2CPacket.Status.UPDATING;
		boolean baseline = true, complete, control, deliveryIncomplete, fencePending;
		State(Context context, Route route, RetainedMemoryLedger.Ticket memory) {
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
	private long tick = -1;
	private final Map<String, State> states = new LinkedHashMap<>();
	private record Control(State state, InventoryS2CPacket packet) {}
	private final Map<String, Control> controls = new LinkedHashMap<>();
	private final Map<String, Long> clientBytes = new LinkedHashMap<>(), streamBytes = new LinkedHashMap<>();
	private final Map<String, RetainedMemoryLedger.Ticket> wireSubjects = new LinkedHashMap<>();
	/** Recipients whose item-data handoff stays blocked until a successful control handoff releases them. */
	private final Set<UUID> dataGates = new LinkedHashSet<>();
	private ControlHandoff controlHandoff;
	private boolean drainingControls;
	private InventorySettings settings;
	private InventoryWireWindow window;
	private long trackingPeriod = -1, previewGlobal, previewQueued, trackingQueued;
	private long windowBase;
	private int windowGrace;
	private int drainCursor;
	private int displayBytes = InventoryLimits.DISPLAY_BASE_BYTES, frameBytes = InventoryLimits.FRAME_BASE_BYTES;
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock(), trackingClock = new InventoryPeriodClock();

	public InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport) { this(memory, transport, BudgetDelaySink.NONE); }
	public InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport, BudgetDelaySink sink) {
		this.memory = memory; this.transport = transport; this.sink = sink == null ? BudgetDelaySink.NONE : sink;
	}
	private long cycle(State state) { return periodId(state.module); }
	long periodId(Module module) { return module == Module.INVENTORY_PREVIEW ? previewClock.period() : trackingClock.period(); }
	private void delay(State state, InventoryBudgetDelayState stage, int reasons, String name, Unit unit, long amount) {
		if (!state.monitored) return; // generic negotiation/result controls have no module work identity
		stage.observe(sink, cycle(state), System.nanoTime(), reasons,
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
		if (packet.isCorrupt() || packet.kind() != InventoryS2CPacket.Kind.OFFER && packet.kind() != InventoryS2CPacket.Kind.POLICY
			&& packet.kind() != InventoryS2CPacket.Kind.SELECTED && packet.kind() != InventoryS2CPacket.Kind.REJECT) return false;
		String key = recipient + "/" + packet.kind() + "/" + packet.commitId();
		Control previous = controls.get(key);
		if (previous != null) { controls.remove(key); previous.state.memory.close(); }
		if (controls.size() >= 256 || controls.values().stream().filter(c -> c.state.context.recipient().equals(recipient)).count() >= 64) return false;
		var held = memory.tryReserve(STATE_MEMORY);
		if (held.isEmpty()) return false;
		held.get().commit(STATE_MEMORY);
		Context context = new Context("control", recipient, packet.presentationEpoch() + "/" + packet.view(), 1, 1);
		State state = new State(context, new Route(packet.epoch(), packet.presentationEpoch(), packet.view(), packet.requestId(), null, "control", null), held.get());
		state.monitored = false;
		controls.put(key, new Control(state, packet)); return true;
	}
	public void drainControls() {
		if (drainingControls) return;
		drainingControls = true;
		try {
		// At most 256 controls. New/replaced work belongs to the next drain;
		// cancellation/close may remove snapshot members before their turn.
		for (var entry : new LinkedHashMap<>(controls).entrySet()) {
			Control control = entry.getValue();
			if (controls.get(entry.getKey()) != control) continue;
			if (!transport.authorizedControl(control.state.context.recipient(), control.packet)) {
				if (controls.remove(entry.getKey(), control)) control.state.memory.close();
				continue;
			}
			if (send(control.state, control.packet, true)) {
				// Remove/refund accepted work BEFORE calling external code, even
				// when that code cancels, closes, replaces or throws.
				if (controls.remove(entry.getKey(), control)) control.state.memory.close();
				ControlHandoff handoff = controlHandoff;
				if (handoff != null) {
					boolean accepted;
					try { accepted = handoff.accepted(control.state.context.recipient(), control.packet); }
					catch (RuntimeException callbackFailure) { warn("control-handoff", control.state, control.packet, callbackFailure); accepted = false; }
					if (accepted) dataGates.remove(control.state.context.recipient());
				}
			}
		}
		} finally { drainingControls = false; }
	}
	public void cancelControls(UUID recipient) {
		var iterator = controls.values().iterator();
		while (iterator.hasNext()) { var control = iterator.next(); if (control.state.context.recipient().equals(recipient)) { control.state.memory.close(); iterator.remove(); } }
	}
	/** Retiring a bounded result owner also retires only its exact queued send. */
	public void cancelControl(UUID recipient, InventoryS2CPacket packet) {
		String key = recipient + "/" + packet.kind() + "/" + packet.commitId();
		Control control = controls.get(key);
		if (control != null && control.packet == packet && controls.remove(key, control)) control.state.memory.close();
	}
	/**
	 * Blocks every item-data handoff for one recipient until a successful
	 * control handoff (see {@link #setControlHandoff}) authorizes release. A
	 * compatibility caller that never gates keeps the old behavior.
	 */
	public void gateData(UUID recipient) { dataGates.add(Objects.requireNonNull(recipient, "recipient")); }
	public void releaseData(UUID recipient) { dataGates.remove(recipient); }
	public boolean dataGated(UUID recipient) { return dataGates.contains(recipient); }
	public void setControlHandoff(ControlHandoff handoff) { this.controlHandoff = handoff; }
	/** The exact effective display byte budget currently enforced by this publisher. */
	public int effectiveDisplayBytes() { return displayBytes; }
	/** The exact effective frame byte budget currently enforced by this publisher. */
	public int effectiveFrameBytes() { return frameBytes; }
	/** An OFFER must advertise the pair this publisher actually writes under. */
	public InventoryS2CPacket.Offer offer(int previewPeriodTicks, int trackingPeriodTicks, int resyncMinPeriods, int heartbeatPeriods) {
		return new InventoryS2CPacket.Offer(previewPeriodTicks, trackingPeriodTicks, resyncMinPeriods, heartbeatPeriods, displayBytes, frameBytes);
	}
	public boolean register(Context context, Route route) {
		var ticket = reserveRegistration();
		if (ticket.isEmpty()) return false;
		return register(context, route, ticket.get());
	}
	public Optional<RetainedMemoryLedger.Ticket> reserveRegistration() {
		return states.size() >= 512 ? Optional.empty() : memory.tryReserve(REGISTRATION_MEMORY);
	}
	public int remainingRegistrations() { return 512 - states.size(); }
	public boolean register(Context context, Route route, RetainedMemoryLedger.Ticket ticket) {
		String key = key(context);
		if (states.size() >= 512 || states.containsKey(key)) { ticket.close(); return false; }
		ticket.commit(REGISTRATION_MEMORY);
		try { states.put(key, new State(context, route, ticket)); return true; }
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
			for (State state : states.values()) {
				state.publicationDelay.beginTick(cycle(state)); state.batchDelay.beginTick(cycle(state));
				state.dataDelay.beginTick(cycle(state)); state.controlDelay.beginTick(cycle(state));
				state.heartbeatDelay.beginTick(cycle(state));
			}
		}
		if (settings.getTracking().getHeartbeatPeriods() == 0) for (State state : states.values()) state.heartbeatDelay.cancel();
	}
	/** Read-only acceptance, scoped to the current context and its retirement/rebaseline fences. */
	boolean accepted(Context context, long watermark) {
		State state = states.get(key(context));
		return state != null && state.context.equals(context) && state.offeredWatermark != 0
			&& watermark <= state.offeredWatermark && !state.retirementPending && !state.rebaselineRequired;
	}
	@Override public Outcome publish(CaptureResult result, AuthorizedProjection projection, Context context, CostLedger ledger) {
		return publish(result, InventoryStep.SweepEvidence.NONE, projection, context, ledger);
	}
	/** Inventory evidence belongs to this observation, never to the route or a preceding publication. */
	public Outcome publish(CaptureResult result, InventoryStep.SweepEvidence evidence, AuthorizedProjection projection, Context context, CostLedger ledger) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || !transport.authorized(context)) return new Outcome.Rejected();
		if (result.availability() != CaptureResult.Availability.READABLE) {
			withdraw(state);
			state.cancelDelays();
			state.retirementPending = false; state.rebaselineRequired = false;
			state.complete = false; state.status = InventoryS2CPacket.Status.INVALID; state.control = true; return new Outcome.Accepted();
		}
		if (state.route.markerId() != null && result.completeness() != CaptureResult.Completeness.COMPLETE) return rejectPublication(state);
		// An older duplicate does not resolve a newer publication's pending refusal.
		if (result.coverage().watermark() <= state.offeredWatermark && state.offeredWatermark != 0)
			return state.retirementPending || state.rebaselineRequired ? new Outcome.Rejected() : new Outcome.Accepted();
		if (!(result.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment payload) || !InventoryDomainCodec.ID.equals(payload.codecId()))
			return rejectPublication(state);
		if (state.rebaselineRequired) return rejectPublication(state);
		var admission = ledger.tryReserve(Map.of(PUBLICATIONS, (long) payload.entries().size()));
		if (admission.isEmpty()) { delay(state, state.publicationDelay, Reason.PUBLICATION_WORK.bit(), "publication", Unit.ENTRY, payload.entries().size()); return new Outcome.Deferred(); }
		admission.get().commit(Map.of(PUBLICATIONS, (long) payload.entries().size()));
		// Measured decode workspace from the actual detached bytes, never an absolute-frame mirror.
		var workspace = memory.tryReserve(publicationWorkspace(state, payload));
		if (workspace.isEmpty()) { delay(state, state.publicationDelay, Reason.MEMORY.bit(), "publication", Unit.ENTRY, payload.entries().size()); return new Outcome.Deferred(); }
		try (var decoding = workspace.get()) {
		List<InventoryS2CPacket.Entry> values = new ArrayList<>();
		for (var entry : payload.entries().entrySet()) {
			if (!projection.authorizedKeys().contains(entry.getKey())) continue;
			var item = InventoryDomainCodec.decode(entry.getValue());
			if (item == null) continue;
			if (item.aggregateRequired()) {
				retireGroup(context, item.key().itemId());
				values.removeIf(value -> value.itemId().equals(item.key().itemId()) && !value.fallback());
			}
			if (!item.aggregateRequired() && state.retiredGroups.contains(item.key().itemId())) continue;
			long revision = result.coverage().watermark();
			boolean aggregate = item.stripped();
			var value = new InventoryS2CPacket.Entry(entry.getKey(), item.key().itemId(), item.label(), item.displayJson(), item.count(), revision,
				aggregate, aggregate ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : quantityStatus(result, evidence),
				aggregate ? revision : 0, aggregate, state.route.itemPingType());
			if (!sameValue(state.offered.get(value.key()), value)) values.add(value);
		}
		if (state.rebaselineRequired) return rejectPublication(state);
		if (!state.retiringGroups.isEmpty() && result.completeness() == CaptureResult.Completeness.COMPLETE) {
			for (String id : state.retiringGroups) if (values.stream().noneMatch(value -> value.fallback() && value.itemId().equals(id)))
				return rejectPublication(state);
		}
		var projectedKeys = new java.util.HashSet<>(state.offered.keySet());
		for (var value : values) {
			if (value.replaceGroup()) for (var old : state.offered.values()) if (old.itemId().equals(value.itemId())) projectedKeys.remove(old.key());
			projectedKeys.add(value.key());
		}
		if (projectedKeys.size() > MAX_KEYS) return rejectPublication(state);
		long queue = values.stream().mapToLong(InventorySyncPublisher::cost).sum();
		boolean tracking = state.route.markerId() != null;
		long pendingCost = values.stream().mapToLong(e -> state.pending.containsKey(e.key()) ? cost(state.pending.get(e.key())) : 0).sum();
		if (state.restageLatest) {
			// The cancelled immutable cut may contain older versions than offered.
			// Repend the latest offered values, including keys absent from this delta.
			pendingCost = state.pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum();
			for (var old : state.offered.values()) if (values.stream().noneMatch(value -> value.key().equals(old.key()))) queue = checkedAdd(queue, cost(old));
		}
		if (checkedAdd(queue, tracking ? trackingQueued : previewQueued) - pendingCost > (tracking ? settings.trackingRollingQueueBytes() : settings.previewQueueBytes())) {
			delay(state, state.publicationDelay, Reason.QUEUE_CAPACITY.bit(), "publication", Unit.ENTRY, values.size());
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
		var pending = new LinkedHashMap<>(state.restageLatest ? state.offered : state.pending);
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
		if (tracking) trackingQueued += delta; else previewQueued += delta;
		state.restageLatest = false;
		installed = true;
		for (var value : values) if (value.replaceGroup()) state.retiringGroups.remove(value.itemId());
		releaseUnretainedEntries(state);
		state.offeredWatermark = Math.max(state.offeredWatermark, result.coverage().watermark());
		state.retirementPending = false;
		state.complete = !state.deliveryIncomplete && state.retiringGroups.isEmpty() && result.completeness() == CaptureResult.Completeness.COMPLETE;
		state.status = state.complete ? quantityStatus(result, evidence)
			: state.deliveryIncomplete || !state.retiringGroups.isEmpty() || result.completeness() == CaptureResult.Completeness.INCOMPLETE ? InventoryS2CPacket.Status.INCOMPLETE : InventoryS2CPacket.Status.UPDATING;
		state.control = true;
		state.publicationDelay.progress(cycle(state));
		return new Outcome.Accepted();
		} finally {
			if (!installed) {
				for (var value : values) state.entryMemory.remove(value);
				granted.forEach(RetainedMemoryLedger.Ticket::close);
			}
		}
		}
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
		if (state.retiredGroups.size() >= MAX_KEYS) throw new IllegalStateException("inventory retirement bound");
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
		// No completion/digest for the superseded cut while replacement waits.
		state.complete = false; state.control = false;
		if (!state.fencePending) state.controlDelay.cancel();
		state.heartbeatDelay.cancel();
		releaseUnretainedEntries(state);
		return state.rebaselineRequired;
	}
	public void status(Context context, InventoryS2CPacket.Status status) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		if (status == InventoryS2CPacket.Status.INVALID || status == InventoryS2CPacket.Status.EXPIRED) {
			withdraw(state); state.complete = false; state.retirementPending = false; state.rebaselineRequired = false;
			state.cancelDelays();
		}
		state.status = status; state.control = true;
	}
	public boolean pending(Context context) {
		State state = states.get(key(context));
		return state != null && state.context.equals(context) && (!state.pending.isEmpty() || !state.admitted.isEmpty());
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
	public void drain() {
		drainControls();
		// Status/invalidation never waits behind a different consumer's item traffic.
		var ordered = new ArrayList<>(states.values());
		if (!ordered.isEmpty()) java.util.Collections.rotate(ordered, -(drainCursor++ % ordered.size()));
		for (State state : ordered) {
			if (!transport.authorized(state.context)) { cancel(state.context); continue; }
			// A terminal failure carries no quantity or completed cut. Retirement
			// fences data and success digests, not this explicit tracking status.
			boolean incomplete = state.route.markerId() != null && state.control && state.status == InventoryS2CPacket.Status.INCOMPLETE;
			if ((state.retirementPending || state.rebaselineRequired) && !incomplete) continue;
			if (transport.admittedToSend(state.context) && state.fencePending) {
				// A completed small recovery can be ready in this same drain. Its clean
				// fence must still precede every baseline part, and is not a completed cut.
				if (!sendControl(state, () -> InventoryS2CPacket.status(state.route.epoch(), 0, state.route.markerId(), state.context.baseline(),
					state.context.stateFence(), 0, state.status == InventoryS2CPacket.Status.INVALID || state.status == InventoryS2CPacket.Status.EXPIRED
						? state.status : InventoryS2CPacket.Status.UPDATING, 0).stamp(state.route.presentationEpoch(), state.route.view()))) continue;
				state.fencePending = false;
				if (!incomplete && !state.complete && state.pending.isEmpty() && state.admitted.isEmpty()) state.control = false;
			}
			if (incomplete) {
				if (transport.admittedToSend(state.context) && sendControl(state, () -> InventoryS2CPacket.status(state.route.epoch(), 0,
					state.route.markerId(), state.context.baseline(), state.context.stateFence(), 0,
					InventoryS2CPacket.Status.INCOMPLETE, 0).stamp(state.route.presentationEpoch(), state.route.view()))) state.control = false;
				continue;
			}
			if (!transport.admittedToSend(state.context) || !state.control || state.complete
				|| !state.pending.isEmpty() || !state.admitted.isEmpty()) continue;
			if (sendControl(state, () -> packet(state, true, List.of(), 0, 1))) state.control = false;
		}
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
				}
				if (!state.admitted.isEmpty() && state.part == state.admitted.size()) {
					for (var packet : state.admitted) for (var entry : packet.entries()) {
						if (entry.replaceGroup()) state.delivered.values().removeIf(old -> old.itemId().equals(entry.itemId()));
						state.delivered.put(entry.key(), entry);
						if (state.route.markerId() == null) previewQueued -= cost(entry); else trackingQueued -= cost(entry);
					}
					state.deliveredWatermark = state.admittedWatermark;
					// Every preview part already carries this cut's status/completion.
					// A redundant empty 0/1 control could impersonate a withheld data
					// index zero at the client and falsely close its multipart barrier.
					if (state.route.markerId() == null && state.admittedWatermark == state.offeredWatermark) state.control = false;
					state.admitted = List.of(); state.part = 0; state.baseline = false;
					if (state.batchMemory != null) { state.batchMemory.close(); state.batchMemory = null; }
					releaseUnretainedEntries(state);
				}
				if (state.control && state.pending.isEmpty() && state.admitted.isEmpty()) {
					if (sendControl(state, () -> packet(state, true, List.of(), 0, 1))) { state.control = false; state.deliveredWatermark = state.offeredWatermark; }
				}
			}
			int heartbeat = settings.getTracking().getHeartbeatPeriods();
			if (state.route.markerId() != null && !state.baseline && heartbeat > 0 && state.pending.isEmpty() && state.admitted.isEmpty()
				&& (state.lastHeartbeat < 0 || trackingPeriod - state.lastHeartbeat >= heartbeat)) {
				if (sendControl(state, () -> InventoryS2CPacket.heartbeat(state.route.epoch(), 0, state.route.markerId(), state.context.baseline(), state.context.stateFence(),
					state.deliveredWatermark, InventoryChecksums.checksumEntries(state.delivered.values())).stamp(state.route.presentationEpoch(), state.route.view()), true)) state.lastHeartbeat = trackingPeriod;
			}
		}
	}
	private void admitBatch(State state) {
		if (state.pending.isEmpty()) return;
		// Packet/list/index objects only: immutable entries keep their own tickets.
		// Preview filtering below measures exactly without allocating codec buffers.
		// data + stamp construct two packets; singleton entry lists, growable batch,
		// immutable batch and checksum sample/sort/UTF buffers overlap sequentially.
		long workspace = checkedAdd(checksumWorkspace(state.offered.size()),
			checkedAdd(4096L, checkedMultiply(1024L, state.pending.size())));
		var reservation = memory.tryReserve(workspace);
		if (reservation.isEmpty()) { delay(state, state.batchDelay, Reason.MEMORY.bit(), "batch", Unit.ENTRY, state.pending.size()); return; }
		boolean retained = false;
		try {
		if (state.route.markerId() == null) {
			long cap = settings.previewClientPeriodBytes();
			long capacity = cap - Math.min(cap, 128 + cap / 16);
			var iterator = state.pending.values().iterator();
			while (iterator.hasNext()) {
				var entry = iterator.next();
				long bytes;
				try { bytes = packet(state, false, List.of(entry), 0, 1).encodedSize(displayBytes, frameBytes); }
				catch (IllegalArgumentException oversized) { bytes = Long.MAX_VALUE; }
				if (bytes <= capacity) continue;
				previewQueued -= cost(entry); iterator.remove();
				state.deliveryIncomplete = true; state.complete = false; state.status = InventoryS2CPacket.Status.INCOMPLETE; state.control = true;
			}
		}
		if (state.pending.isEmpty()) { state.batchDelay.cancel(); state.dataDelay.cancel(); return; }
		List<InventoryS2CPacket> batch = new ArrayList<>();
		// One entry per bounded part: quotas and byte caps never reset for a new part.
		int parts = state.pending.size(), index = 0;
		for (var entry : state.pending.values()) batch.add(packet(state, false, List.of(entry), index++, parts));
		state.admitted = List.copyOf(batch); state.pending.clear(); state.part = 0;
		state.admittedWatermark = state.offeredWatermark;
		reservation.get().commit(workspace);
		state.batchMemory = reservation.get();
		state.batchDelay.progress(cycle(state));
		retained = true;
		} finally { if (!retained) reservation.get().close(); }
	}
	private InventoryS2CPacket packet(State state, boolean control, List<InventoryS2CPacket.Entry> entries, int part, int parts) {
		Route route = state.route;
		if (control && route.markerId() != null && !(state.baseline && state.complete && state.status != InventoryS2CPacket.Status.INVALID)) return InventoryS2CPacket.status(route.epoch(), 0, route.markerId(), state.context.baseline(),
			state.context.stateFence(), state.offeredWatermark, state.status, InventoryChecksums.checksumEntries(state.offered.values())).stamp(route.presentationEpoch(), route.view());
		return InventoryS2CPacket.data(route.markerId() == null ? InventoryS2CPacket.Kind.PREVIEW : state.baseline ? InventoryS2CPacket.Kind.SNAPSHOT : InventoryS2CPacket.Kind.STREAM,
			route.epoch(), route.markerId() == null ? route.requestId() : 0, route.markerId(), state.context.baseline(), state.context.stateFence(), state.offeredWatermark,
			part, parts, state.complete, state.status, InventoryChecksums.checksumEntries(state.offered.values()), entries).stamp(route.presentationEpoch(), route.view());
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
		if (!control && dataGated(state.context.recipient())) return false;
		boolean heartbeat = packet.kind() == InventoryS2CPacket.Kind.HEARTBEAT;
		InventoryBudgetDelayState stage = heartbeat ? state.heartbeatDelay : control ? state.controlDelay : state.dataDelay;
		String name = heartbeat ? "heartbeat" : control ? "control" : "send";
		long workspace;
		try { workspace = packet.encodingWorkspace(displayBytes, frameBytes); }
		catch (IllegalArgumentException overBudget) { delay(state, stage, Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit(), name, Unit.FRAME, 1); return false; }
		var held = memory.tryReserve(workspace);
		if (held.isEmpty()) { delay(state, stage, Reason.MEMORY.bit(), name, Unit.FRAME, 1); return false; }
		try (var reserved = held.get()) {
			int bytes;
			try { bytes = transport.encodedBytes(packet, displayBytes, frameBytes); }
			catch (IllegalArgumentException overBudget) { delay(state, stage, Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit(), name, Unit.FRAME, 1); return false; }
			if (bytes < 0 || bytes > InventoryS2CPacket.MAX_FRAME_BYTES) throw new IllegalArgumentException("encoded inventory frame");
			boolean tracking = state.route.markerId() != null;
			String client = (tracking ? "snapshot/" : "preview/") + state.context.recipient();
			long cap = tracking ? state.baseline ? settings.trackingSnapshotClientPeriodBytes() : settings.trackingStreamPeriodBytesPerClientTarget() : settings.previewClientPeriodBytes();
			String stream = state.context.recipient() + "/" + state.route.targetQuota();
			Map<String, Long> accounting = tracking && !state.baseline ? streamBytes : clientBytes;
			String subject = tracking && !state.baseline ? stream : client;
			String retainedSubject = tracking && !state.baseline ? "stream/" + subject : subject;
			if (!wireSubjects.containsKey(retainedSubject)) {
				if (wireSubjects.size() >= 1024) { delay(state, stage, Reason.SUBJECT_CAPACITY.bit(), name, Unit.BYTE, bytes); return false; }
				var cost = memory.tryReserve(1024); if (cost.isEmpty()) { delay(state, stage, Reason.MEMORY.bit(), name, Unit.BYTE, bytes); return false; }
				cost.get().commit(1024); wireSubjects.put(retainedSubject, cost.get());
			}
			long used = accounting.getOrDefault(subject, 0L);
			long reserve = control ? 0 : Math.min(cap, 128 + cap / 16);
			if (bytes > cap - used - reserve || (tracking && state.baseline && bytes > settings.trackingSnapshotFragmentBytes())) {
				int reasons = Reason.WIRE_CLIENT_OR_STREAM.bit();
				if (bytes > cap - reserve || tracking && state.baseline && bytes > settings.trackingSnapshotFragmentBytes()) reasons |= Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit();
				delay(state, stage, reasons, name, Unit.BYTE, bytes); return false;
			}
			if (tracking ? bytes > window.remaining() - (control ? 0 : Math.min(windowBase, 128 + windowBase / 16)) : bytes > settings.previewGlobalPeriodBytes() - previewGlobal - reserve) {
				delay(state, stage, Reason.WIRE_GLOBAL.bit(), name, Unit.BYTE, bytes); return false;
			}
			CostLedger ledger = new CostLedger(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), cap - used));
			var ticket = ledger.tryReserve(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), (long) bytes)).orElseThrow();
			ticket.commit(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), (long) bytes));
			accounting.put(subject, used + bytes);
			if (tracking) window.trySpend(bytes); else previewGlobal += bytes;
			// Budget admission resolved even if the independent transport handoff fails.
			if (state.monitored) stage.progress(cycle(state));
			try { transport.send(state.context.recipient(), packet); return true; }
			catch (RuntimeException failure) {
				warn("transport-send", state, packet, failure);
				return false; // conservatively charged; same immutable part retries
			}
		}
	}
	private void warn(String operation, State state, InventoryS2CPacket packet, Throwable failure) {
		Diagnostics.warn("inventory/publisher/" + operation, transport.getClass().getName() + "/" + packet.kind(),
			() -> "kind=" + packet.kind() + " request=" + packet.requestId() + " recipient=" + state.context.recipient()
				+ " marker=" + packet.markerId() + " epoch=" + packet.epoch() + " presentationEpoch=" + packet.presentationEpoch()
				+ " view=" + packet.view() + " baseline=" + packet.baselineId() + " stateRevision=" + packet.statusRevision()
				+ " watermark=" + packet.watermark() + " part=" + packet.partIndex() + "/" + packet.partCount(), failure);
	}
	@Override public void cancel(Context context) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		states.remove(key(context));
		if (state != null) {
			withdraw(state);
			state.cancelDelays();
			state.offered.clear(); state.delivered.clear();
			state.entryMemory.values().forEach(RetainedMemoryLedger.Ticket::close); state.entryMemory.clear();
			state.memory.close();
		}
	}
	@Override public void rebase(Context context, long newStateFence) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || newStateFence < state.context.stateFence()) return;
		withdraw(state); state.cancelDelays(); state.offered.clear(); state.delivered.clear();
		state.entryMemory.values().forEach(RetainedMemoryLedger.Ticket::close); state.entryMemory.clear();
		state.context = new Context(context.consumerId(), context.recipient(), context.sessionView(), newStateFence, context.baseline()); state.baseline = true;
		state.offeredWatermark = state.deliveredWatermark = 0; state.complete = false; state.control = false; state.deliveryIncomplete = false; state.status = InventoryS2CPacket.Status.UPDATING;
		state.retiredGroups.clear(); state.retiringGroups.clear(); state.restageLatest = false; state.rebaselineRequired = false; state.retirementPending = false;
	}
	private void withdraw(State state) {
		state.publicationDelay.cancel(); state.batchDelay.cancel(); state.dataDelay.cancel();
		long bytes = state.pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum();
		for (var packet : state.admitted) bytes += packet.entries().stream().mapToLong(InventorySyncPublisher::cost).sum();
		if (state.route.markerId() == null) previewQueued -= bytes; else trackingQueued -= bytes;
		state.pending.clear(); state.admitted = List.of(); state.part = 0;
		if (state.batchMemory != null) { state.batchMemory.close(); state.batchMemory = null; }
		releaseUnretainedEntries(state);
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
	private static boolean sameValue(InventoryS2CPacket.Entry a, InventoryS2CPacket.Entry b) {
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
	private void releaseWireSubjects(String prefix) {
		var iterator = wireSubjects.entrySet().iterator();
		while (iterator.hasNext()) { var entry = iterator.next(); if (entry.getKey().startsWith(prefix)) { entry.getValue().close(); iterator.remove(); } }
	}
	@Override public void close() {
		controls.values().forEach(c -> c.state.memory.close()); controls.clear();
		for (State state : List.copyOf(states.values())) cancel(state.context);
		wireSubjects.values().forEach(RetainedMemoryLedger.Ticket::close); wireSubjects.clear(); clientBytes.clear(); streamBytes.clear();
		dataGates.clear(); controlHandoff = null;
	}
}
