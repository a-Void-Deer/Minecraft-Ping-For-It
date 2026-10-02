package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;

/** Actual bounded dedicated publication. Queued cuts remain immutable until fully transmitted. */
public final class InventorySyncPublisher implements SyncPublisher, AutoCloseable {
	public static final CostLedger.Counter PUBLICATIONS = new CostLedger.Counter("inventory/publication", CostLedger.Unit.WORK);
	public interface Transport {
		boolean authorized(Context context);
		default boolean authorizedControl(UUID recipient, InventoryS2CPacket packet) { return true; }
		default boolean admittedToSend(Context context) { return true; }
		int encodedBytes(InventoryS2CPacket packet);
		void send(UUID recipient, InventoryS2CPacket packet);
	}
	public record Route(long epoch, long presentationEpoch, long view, long requestId, MarkerId markerId,
		String targetQuota, String itemPingType) {}
	private static final long STATE_MEMORY = 4096, FRAME_WORKSPACE = 2L * (InventoryS2CPacket.MAX_FRAME_BYTES + InventoryS2CPacket.MAX_ENTRY_BYTES);
	private static final class State {
		Context context;
		Route route;
		final RetainedMemoryLedger.Ticket memory;
		final Map<String, InventoryS2CPacket.Entry> offered = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> delivered = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> pending = new LinkedHashMap<>();
		final List<RetainedMemoryLedger.Ticket> entryMemory = new ArrayList<>();
		List<InventoryS2CPacket> admitted = List.of();
		long admittedWatermark;
		RetainedMemoryLedger.Ticket batchMemory;
		int part;
		long offeredWatermark, deliveredWatermark, lastHeartbeat = -1;
		InventoryS2CPacket.Status status = InventoryS2CPacket.Status.UPDATING;
		boolean baseline = true, complete, control, deliveryIncomplete, fencePending;
		State(Context context, Route route, RetainedMemoryLedger.Ticket memory) {
			this.context = context; this.route = route; this.memory = memory; fencePending = route.markerId() != null;
		}
	}
	private final RetainedMemoryLedger memory;
	private final Transport transport;
	private final Map<String, State> states = new LinkedHashMap<>();
	private record Control(State state, InventoryS2CPacket packet) {}
	private final Map<String, Control> controls = new LinkedHashMap<>();
	private final Map<String, Long> clientBytes = new LinkedHashMap<>(), streamBytes = new LinkedHashMap<>();
	private final Map<String, RetainedMemoryLedger.Ticket> wireSubjects = new LinkedHashMap<>();
	private InventorySettings settings;
	private InventoryWireWindow window;
	private long trackingPeriod = -1, previewGlobal, previewQueued, trackingQueued;
	private long windowBase;
	private int windowGrace;
	private int drainCursor;
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock(), trackingClock = new InventoryPeriodClock();

	public InventorySyncPublisher(RetainedMemoryLedger memory, Transport transport) { this.memory = memory; this.transport = transport; }
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
		controls.put(key, new Control(state, packet)); return true;
	}
	public void drainControls() {
		var iterator = controls.entrySet().iterator();
		while (iterator.hasNext()) {
			Control control = iterator.next().getValue();
			if (!transport.authorizedControl(control.state.context.recipient(), control.packet) || send(control.state, control.packet, true)) {
				control.state.memory.close(); iterator.remove();
			}
		}
	}
	public void cancelControls(UUID recipient) {
		var iterator = controls.values().iterator();
		while (iterator.hasNext()) { var control = iterator.next(); if (control.state.context.recipient().equals(recipient)) { control.state.memory.close(); iterator.remove(); } }
	}
	public boolean register(Context context, Route route) {
		var ticket = reserveRegistration();
		if (ticket.isEmpty()) return false;
		return register(context, route, ticket.get());
	}
	public Optional<RetainedMemoryLedger.Ticket> reserveRegistration() {
		return states.size() >= 512 ? Optional.empty() : memory.tryReserve(STATE_MEMORY);
	}
	public int remainingRegistrations() { return 512 - states.size(); }
	public boolean register(Context context, Route route, RetainedMemoryLedger.Ticket ticket) {
		String key = key(context);
		if (states.size() >= 512 || states.containsKey(key)) { ticket.close(); return false; }
		ticket.commit(STATE_MEMORY);
		states.put(key, new State(context, route, ticket)); return true;
	}
	public void advance(long now, InventorySettings settings) {
		this.settings = settings;
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
	}
	@Override public Outcome publish(CaptureResult result, AuthorizedProjection projection, Context context, CostLedger ledger) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || !transport.authorized(context)) return new Outcome.Rejected();
		if (result.availability() != CaptureResult.Availability.READABLE) {
			withdraw(state);
			state.complete = false; state.status = InventoryS2CPacket.Status.INVALID; state.control = true; return new Outcome.Accepted();
		}
		if (state.route.markerId() != null && result.completeness() != CaptureResult.Completeness.COMPLETE) return new Outcome.Rejected();
		if (result.coverage().watermark() <= state.offeredWatermark && state.offeredWatermark != 0) return new Outcome.Accepted();
		if (!(result.payload().orElse(null) instanceof CaptureResult.OpaqueKeyedFragment payload) || !InventoryDomainCodec.ID.equals(payload.codecId()))
			return new Outcome.Rejected();
		var admission = ledger.tryReserve(Map.of(PUBLICATIONS, (long) payload.entries().size()));
		if (admission.isEmpty()) return new Outcome.Deferred();
		admission.get().commit(Map.of(PUBLICATIONS, (long) payload.entries().size()));
		var workspace = memory.tryReserve(payload.entries().size() * 2L * InventoryDomainCodec.MAX_BYTES + 8192);
		if (workspace.isEmpty()) return new Outcome.Deferred();
		try (var decoding = workspace.get()) {
		List<InventoryS2CPacket.Entry> values = new ArrayList<>();
		for (var entry : payload.entries().entrySet()) {
			if (!projection.authorizedKeys().contains(entry.getKey())) continue;
			var item = InventoryDomainCodec.decode(entry.getValue());
			if (item == null) continue;
			long revision = result.coverage().watermark();
			boolean aggregate = item.stripped();
			var value = new InventoryS2CPacket.Entry(entry.getKey(), item.key().itemId(), item.label(), item.displayJson(), item.count(), revision,
				aggregate, aggregate ? InventoryS2CPacket.Status.COMPONENT_TOO_LONG : result.consistency() == CaptureResult.Consistency.VERIFIED
					? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN,
				aggregate ? revision : 0, aggregate, state.route.itemPingType());
			if (!sameValue(state.offered.get(value.key()), value)) values.add(value);
		}
		var projectedKeys = new java.util.HashSet<>(state.offered.keySet());
		for (var value : values) {
			if (value.replaceGroup()) for (var old : state.offered.values()) if (old.itemId().equals(value.itemId())) projectedKeys.remove(old.key());
			projectedKeys.add(value.key());
		}
		if (projectedKeys.size() > 256) return new Outcome.Rejected();
		long reservation = values.stream().filter(e -> !state.offered.containsKey(e.key())).count()
			* (long) (InventoryS2CPacket.MAX_ENTRY_BYTES * 2 + 1024);
		var held = memory.tryReserve(reservation);
		if (held.isEmpty()) return new Outcome.Deferred();
		long queue = values.stream().mapToLong(InventorySyncPublisher::cost).sum();
		boolean tracking = state.route.markerId() != null;
		long pendingCost = values.stream().mapToLong(e -> state.pending.containsKey(e.key()) ? cost(state.pending.get(e.key())) : 0).sum();
		if (queue + (tracking ? trackingQueued : previewQueued) - pendingCost > (tracking ? settings.trackingRollingQueueBytes() : settings.previewQueueBytes())) {
			held.get().close(); return new Outcome.Deferred();
		}
		held.get().commit(reservation);
		if (reservation > 0) state.entryMemory.add(held.get()); else held.get().close();
		for (var value : values) {
			if (value.replaceGroup()) {
				state.offered.values().removeIf(old -> old.itemId().equals(value.itemId()) && !old.key().equals(value.key()));
				var iterator = state.pending.values().iterator();
				while (iterator.hasNext()) {
					var old = iterator.next();
					if (old.itemId().equals(value.itemId()) && !old.key().equals(value.key())) {
						if (tracking) trackingQueued -= cost(old); else previewQueued -= cost(old);
						iterator.remove();
					}
				}
			}
			var previous = state.pending.put(value.key(), value);
			long delta = cost(value) - (previous == null ? 0 : cost(previous));
			if (tracking) trackingQueued += delta; else previewQueued += delta;
			state.offered.put(value.key(), value);
		}
		state.offeredWatermark = Math.max(state.offeredWatermark, result.coverage().watermark());
		state.complete = !state.deliveryIncomplete && result.completeness() == CaptureResult.Completeness.COMPLETE;
		state.status = state.complete ? result.consistency() == CaptureResult.Consistency.VERIFIED ? InventoryS2CPacket.Status.READY : InventoryS2CPacket.Status.UNCERTAIN
			: state.deliveryIncomplete || result.completeness() == CaptureResult.Completeness.INCOMPLETE ? InventoryS2CPacket.Status.INCOMPLETE : InventoryS2CPacket.Status.UPDATING;
		state.control = true;
		return new Outcome.Accepted();
		}
	}
	public void status(Context context, InventoryS2CPacket.Status status) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		if (status == InventoryS2CPacket.Status.INVALID || status == InventoryS2CPacket.Status.EXPIRED) { withdraw(state); state.complete = false; }
		state.status = status; state.control = true;
	}
	public boolean pending(Context context) {
		State state = states.get(key(context));
		return state != null && state.context.equals(context) && (!state.pending.isEmpty() || !state.admitted.isEmpty());
	}
	public void drain() {
		drainControls();
		// Status/invalidation never waits behind a different consumer's item traffic.
		var ordered = new ArrayList<>(states.values());
		if (!ordered.isEmpty()) java.util.Collections.rotate(ordered, -(drainCursor++ % ordered.size()));
		for (State state : ordered) {
			if (!transport.authorized(state.context)) { cancel(state.context); continue; }
			if (transport.admittedToSend(state.context) && state.fencePending) {
				// A completed small recovery can be ready in this same drain. Its clean
				// fence must still precede every baseline part, and is not a completed cut.
				var fence = InventoryS2CPacket.status(state.route.epoch(), 0, state.route.markerId(), state.context.baseline(),
					state.context.stateFence(), 0, state.status == InventoryS2CPacket.Status.INVALID || state.status == InventoryS2CPacket.Status.EXPIRED
						? state.status : InventoryS2CPacket.Status.UPDATING, 0).stamp(state.route.presentationEpoch(), state.route.view());
				if (!send(state, fence, true)) continue;
				state.fencePending = false;
				if (!state.complete && state.pending.isEmpty() && state.admitted.isEmpty()) state.control = false;
			}
			if (!transport.admittedToSend(state.context) || !state.control || state.complete
				|| !state.pending.isEmpty() || !state.admitted.isEmpty()) continue;
			if (send(state, packet(state, true, List.of(), 0, 1), true)) state.control = false;
		}
		for (State state : ordered) {
			if (!transport.authorized(state.context)) { cancel(state.context); continue; }
			if (!transport.admittedToSend(state.context) || state.fencePending) continue;
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
				state.admitted = List.of(); state.part = 0; state.baseline = false;
				if (state.batchMemory != null) { state.batchMemory.close(); state.batchMemory = null; }
			}
			if (state.control && state.pending.isEmpty() && state.admitted.isEmpty()) {
				var packet = packet(state, true, List.of(), 0, 1);
				if (send(state, packet, true)) { state.control = false; state.deliveredWatermark = state.offeredWatermark; }
			}
			int heartbeat = settings.getTracking().getHeartbeatPeriods();
			if (state.route.markerId() != null && !state.baseline && heartbeat > 0 && state.pending.isEmpty() && state.admitted.isEmpty()
				&& (state.lastHeartbeat < 0 || trackingPeriod - state.lastHeartbeat >= heartbeat)) {
				var packet = InventoryS2CPacket.heartbeat(state.route.epoch(), 0, state.route.markerId(), state.context.baseline(), state.context.stateFence(),
					state.deliveredWatermark, InventoryChecksums.checksumEntries(state.delivered.values())).stamp(state.route.presentationEpoch(), state.route.view());
				if (send(state, packet, true)) state.lastHeartbeat = trackingPeriod;
			}
		}
	}
	private void admitBatch(State state) {
		var reservation = memory.tryReserve(FRAME_WORKSPACE + state.pending.size() * (long) InventoryS2CPacket.MAX_ENTRY_BYTES);
		if (reservation.isEmpty()) return;
		boolean retained = false;
		try {
		if (state.route.markerId() == null) {
			long cap = settings.previewClientPeriodBytes();
			var iterator = state.pending.values().iterator();
			while (iterator.hasNext()) {
				var entry = iterator.next();
				if (transport.encodedBytes(packet(state, false, List.of(entry), 0, 1)) <= cap - Math.min(cap, 128 + cap / 16)) continue;
				previewQueued -= cost(entry); iterator.remove();
				state.deliveryIncomplete = true; state.complete = false; state.status = InventoryS2CPacket.Status.INCOMPLETE; state.control = true;
			}
		}
		if (state.pending.isEmpty()) return;
		List<InventoryS2CPacket> batch = new ArrayList<>();
		// One entry per bounded part: quotas and byte caps never reset for a new part.
		int parts = state.pending.size(), index = 0;
		for (var entry : state.pending.values()) batch.add(packet(state, false, List.of(entry), index++, parts));
		state.admitted = List.copyOf(batch); state.pending.clear(); state.part = 0;
		state.admittedWatermark = state.offeredWatermark;
		reservation.get().commit(state.admitted.size() * (long) InventoryS2CPacket.MAX_ENTRY_BYTES);
		state.batchMemory = reservation.get();
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
	private boolean send(State state, InventoryS2CPacket packet, boolean control) {
		var workspace = memory.tryReserve(FRAME_WORKSPACE);
		if (workspace.isEmpty()) return false;
		try (var held = workspace.get()) {
			int bytes = transport.encodedBytes(packet);
			if (bytes < 0 || bytes > InventoryS2CPacket.MAX_FRAME_BYTES) throw new IllegalArgumentException("encoded inventory frame");
			boolean tracking = state.route.markerId() != null;
			String client = (tracking ? "snapshot/" : "preview/") + state.context.recipient();
			long cap = tracking ? state.baseline ? settings.trackingSnapshotClientPeriodBytes() : settings.trackingStreamPeriodBytesPerClientTarget() : settings.previewClientPeriodBytes();
			String stream = state.context.recipient() + "/" + state.route.targetQuota();
			Map<String, Long> accounting = tracking && !state.baseline ? streamBytes : clientBytes;
			String subject = tracking && !state.baseline ? stream : client;
			String retainedSubject = tracking && !state.baseline ? "stream/" + subject : subject;
			if (!wireSubjects.containsKey(retainedSubject)) {
				if (wireSubjects.size() >= 1024) return false;
				var cost = memory.tryReserve(1024); if (cost.isEmpty()) return false;
				cost.get().commit(1024); wireSubjects.put(retainedSubject, cost.get());
			}
			long used = accounting.getOrDefault(subject, 0L);
			long reserve = control ? 0 : Math.min(cap, 128 + cap / 16);
			if (bytes > cap - used - reserve || (tracking && state.baseline && bytes > settings.trackingSnapshotFragmentBytes())) return false;
			if (tracking ? bytes > window.remaining() - (control ? 0 : Math.min(windowBase, 128 + windowBase / 16)) : bytes > settings.previewGlobalPeriodBytes() - previewGlobal - reserve) return false;
			CostLedger ledger = new CostLedger(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), cap - used));
			var ticket = ledger.tryReserve(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), (long) bytes)).orElseThrow();
			ticket.commit(Map.of(new CostLedger.Counter(subject, CostLedger.Unit.WIRE_BYTES), (long) bytes));
			accounting.put(subject, used + bytes);
			if (tracking) window.trySpend(bytes); else previewGlobal += bytes;
			try { transport.send(state.context.recipient(), packet); return true; }
			catch (RuntimeException failure) { return false; } // conservatively charged; same immutable part retries
		}
	}
	@Override public void cancel(Context context) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context)) return;
		states.remove(key(context));
		if (state != null) { withdraw(state); state.entryMemory.forEach(RetainedMemoryLedger.Ticket::close); state.memory.close(); }
	}
	@Override public void rebase(Context context, long newStateFence) {
		State state = states.get(key(context));
		if (state == null || !state.context.equals(context) || newStateFence < state.context.stateFence()) return;
		withdraw(state); state.offered.clear(); state.delivered.clear(); state.entryMemory.forEach(RetainedMemoryLedger.Ticket::close); state.entryMemory.clear();
		state.context = new Context(context.consumerId(), context.recipient(), context.sessionView(), newStateFence, context.baseline()); state.baseline = true;
		state.offeredWatermark = state.deliveredWatermark = 0; state.complete = false; state.control = false; state.deliveryIncomplete = false; state.status = InventoryS2CPacket.Status.UPDATING;
	}
	private void withdraw(State state) {
		long bytes = state.pending.values().stream().mapToLong(InventorySyncPublisher::cost).sum();
		for (var packet : state.admitted) bytes += packet.entries().stream().mapToLong(InventorySyncPublisher::cost).sum();
		if (state.route.markerId() == null) previewQueued -= bytes; else trackingQueued -= bytes;
		state.pending.clear(); state.admitted = List.of(); state.part = 0;
		if (state.batchMemory != null) { state.batchMemory.close(); state.batchMemory = null; }
	}
	private static boolean sameValue(InventoryS2CPacket.Entry a, InventoryS2CPacket.Entry b) {
		return a != null && a.count() == b.count() && a.fallback() == b.fallback() && a.quality() == b.quality() && a.itemId().equals(b.itemId())
			&& a.label().equals(b.label()) && java.util.Objects.equals(a.displayJson(), b.displayJson()) && java.util.Objects.equals(a.itemPingType(), b.itemPingType());
	}
	private static long cost(InventoryS2CPacket.Entry entry) { return 128L + entry.key().length() * 3L + entry.itemId().length() * 3L + entry.label().length() * 3L + (entry.displayJson() == null ? 0 : entry.displayJson().length() * 3L); }
	private static String key(Context context) { return context.recipient() + "/" + context.consumerId(); }
	private void releaseWireSubjects(String prefix) {
		var iterator = wireSubjects.entrySet().iterator();
		while (iterator.hasNext()) { var entry = iterator.next(); if (entry.getKey().startsWith(prefix)) { entry.getValue().close(); iterator.remove(); } }
	}
	@Override public void close() {
		controls.values().forEach(c -> c.state.memory.close()); controls.clear();
		for (State state : List.copyOf(states.values())) cancel(state.context);
		wireSubjects.values().forEach(RetainedMemoryLedger.Ticket::close); wireSubjects.clear(); clientBytes.clear(); streamBytes.clear();
	}
}
