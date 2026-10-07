package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;

/** Server-thread coordinator: request authority, shared capture and marker-owned tracking. */
public final class InventoryBackend implements AutoCloseable {
	public record Policy(long epoch, long view, Set<String> types) {
		public Policy { types = Set.copyOf(types); }
		String stamp() { return epoch + "/" + view; }
	}
	public record Opened(Target.BlockTarget target, String targetType, String defaultPingType) {}
	public record Created(ServerMarker marker, MarkerRejectReason rejection) {
		public Created { if ((marker == null) == (rejection == null)) throw new IllegalArgumentException("create outcome"); }
	}
	@FunctionalInterface public interface Admission {
		MarkerRejectReason prepare(Target committed, String targetType, List<UUID> audience);
	}
	public interface Host extends InventorySyncPublisher.Transport {
		Optional<Policy> policy(UUID player);
		Optional<Opened> open(UUID player, Target requested);
		boolean annotationAllowed(InventorySourceInput input, String pingType);
		/** Rate/channel/audience/target authority precedes admission, which precedes storage; Basic CREATED precedes return. */
		Created create(UUID player, Opened frozen, Admission admission);
		boolean knows(UUID player, MarkerId marker);
	}
	private static final int MAX_SESSIONS = 128, MAX_TRACKING = 256, MAX_ENTRIES = 256, MAX_REPLAYS = 64;
	private static final long SESSION_BYTES = 65536, PREVIEW_BYTES = 8192, ENTRY_BYTES = 24576, LEASE_BYTES = 32768;
	private static final class Session {
		final long epoch;
		final RetainedMemoryLedger.Ticket memory;
		final Map<Long, Preview> previews = new LinkedHashMap<>();
		final Map<Long, InventoryS2CPacket> commits = new LinkedHashMap<>();
		InventoryS2CPacket.Offer offer;
		Policy policy;
		long lastRequest, lastCommit;
		Session(long epoch, Policy policy, RetainedMemoryLedger.Ticket memory) { this.epoch = epoch; this.policy = policy; this.memory = memory; }
	}
	private static final class Preview {
		final long request;
		final Opened frozen;
		final InventorySourceInput input;
		final InventoryRuntime.Consumer consumer;
		final RetainedMemoryLedger.Ticket memory;
		SyncPublisher.Context context;
		final Map<InventoryScanner.Key, InventoryDomainCodec.Item> counts = new LinkedHashMap<>();
		final Map<InventoryScanner.Key, Integer> witnesses = new LinkedHashMap<>();
		final Map<String, InventoryDomainCodec.Item> totals = new LinkedHashMap<>();
		final Map<String, InventorySelection> references = new LinkedHashMap<>();
		final Map<String, String> keys = new LinkedHashMap<>();
		final Set<String> folded = new HashSet<>();
		final List<RetainedMemoryLedger.Ticket> entries = new ArrayList<>();
		long revision, attempted;
		boolean terminal;
		CaptureResult last;
		long checkedTick = -1;
		Preview(long request, Opened frozen, InventorySourceInput input, InventoryRuntime.Consumer consumer,
			RetainedMemoryLedger.Ticket memory, SyncPublisher.Context context) {
			this.request = request; this.frozen = frozen; this.input = input; this.consumer = consumer; this.memory = memory; this.context = context;
		}
	}
	private static final class Recipient {
		final Policy policy;
		final SyncPublisher.Context context;
		long resyncAt;
		boolean folded;
		Recipient(Policy policy, SyncPublisher.Context context) { this.policy = policy; this.context = context; }
	}
	private static final class Tracking {
		final ServerMarker marker;
		final InventorySourceInput input;
		final InventorySelection selection;
		final InventoryRuntime.Consumer consumer;
		final RetainedMemoryLedger.Ticket memory;
		final Map<UUID, Recipient> recipients = new LinkedHashMap<>();
		long count, total, completedCount, completedTotal, revision, state = 1, nextSweep;
		boolean completedFolded;
		boolean folded, invalid, scanning = true;
		CaptureResult completed;
		long checkedTick = -1;
		Tracking(ServerMarker marker, InventorySourceInput input, InventorySelection selection,
			InventoryRuntime.Consumer consumer, RetainedMemoryLedger.Ticket memory) {
			this.marker = marker; this.input = input; this.selection = selection; this.consumer = consumer; this.memory = memory;
			folded = selection.aggregate();
		}
	}
	private final Host host;
	private final InventoryRuntime runtime;
	private final InventorySyncPublisher publisher;
	private final Map<UUID, Session> sessions = new LinkedHashMap<>();
	private final Map<MarkerId, Tracking> tracking = new LinkedHashMap<>();
	private final Map<UUID, Integer> previewVariants = new LinkedHashMap<>();
	private final Map<UUID, Set<String>> previewVariantClaims = new LinkedHashMap<>();
	private final Map<UUID, RetainedMemoryLedger.Ticket> variantMemory = new LinkedHashMap<>();
	private long epochSeed, baseline, tick;
	private InventorySettings settings;
	private int trackingCursor, previewCursor;
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock();

	public InventoryBackend(Host host, InventoryRuntime runtime, long epochSeed) {
		this.host = host; this.runtime = runtime; this.epochSeed = epochSeed;
		publisher = new InventorySyncPublisher(runtime.memory(), new InventorySyncPublisher.Transport() {
			@Override public boolean authorized(SyncPublisher.Context context) { return publicationAllowed(context); }
			@Override public boolean authorizedControl(UUID recipient, InventoryS2CPacket packet) {
				Session session = sessions.get(recipient); Policy policy = host.policy(recipient).orElse(null);
				return session != null && policy != null && session.epoch == packet.epoch()
					&& policy.epoch() == packet.presentationEpoch() && policy.view() == packet.view();
			}
			@Override public boolean admittedToSend(SyncPublisher.Context context) {
				Session session = sessions.get(context.recipient());
				if (session == null) return false;
				for (Preview preview : session.previews.values()) if (preview.context.equals(context)) return preview.checkedTick == tick;
				for (Tracking lease : tracking.values()) {
					Recipient recipient = lease.recipients.get(context.recipient());
					if (recipient != null && recipient.context.equals(context)) return lease.checkedTick == tick;
				}
				return false;
			}
			@Override public int encodedBytes(InventoryS2CPacket packet) { return host.encodedBytes(packet); }
			@Override public void send(UUID recipient, InventoryS2CPacket packet) { host.send(recipient, packet); }
		});
	}
	public InventoryRuntime runtime() { return runtime; }
	private void advance(long now, InventorySettings settings) {
		tick = now; this.settings = settings; runtime.advance(now, settings); publisher.advance(now, settings);
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) {
			previewVariants.clear(); previewVariantClaims.clear(); variantMemory.values().forEach(RetainedMemoryLedger.Ticket::close); variantMemory.clear();
		}
	}
	public void handle(UUID player, InventoryC2SPacket packet, long now, InventorySettings settings) {
		advance(now, settings);
		if (packet == null || packet.isCorrupt()) return;
		if (packet.kind() == InventoryC2SPacket.Kind.HELLO) { hello(player); publisher.drainControls(); return; }
		Session session = sessions.get(player);
		if (session == null) return;
		refreshPolicy(player, session);
		if (session.policy == null || packet.epoch() != session.epoch || packet.presentationEpoch() != session.policy.epoch()
			|| packet.view() != session.policy.view()) { publisher.drainControls(); return; }
		switch (packet.kind()) {
			case OPEN -> open(player, session, packet);
			case CLOSE -> closePreview(session, packet.requestId());
			case SELECT -> select(player, session, packet);
			case RESYNC -> resync(player, packet.markerId());
			case HELLO -> {}
		}
		publisher.drainControls();
	}
	private void hello(UUID player) {
		Policy policy = host.policy(player).orElse(null);
		if (policy == null) return; // presentation must negotiate first
		Session session = sessions.get(player);
		if (session == null) {
			if (sessions.size() >= MAX_SESSIONS) return;
			var held = runtime.memory().tryReserve(SESSION_BYTES);
			if (held.isEmpty()) return;
			held.get().commit(SESSION_BYTES);
			session = new Session(++epochSeed, policy, held.get()); sessions.put(player, session);
		}
		var p = settings.getPreview(); var t = settings.getTracking();
		session.offer = new InventoryS2CPacket.Offer(p.getPeriodTicks(), t.getPeriodTicks(), t.getResyncMinPeriods(), t.getHeartbeatPeriods());
		publisher.control(player, InventoryS2CPacket.offer(session.epoch, session.offer).stamp(policy.epoch(), policy.view()));
		refreshPolicy(player, session);
		publisher.control(player, InventoryS2CPacket.policy(session.epoch, policy.epoch(), policy.view(), policy.types()));
	}
	private void refreshPolicy(UUID player, Session session) {
		Policy next = host.policy(player).orElse(null);
		if (java.util.Objects.equals(next, session.policy)) return;
		for (long request : List.copyOf(session.previews.keySet())) closePreview(session, request);
		session.policy = next;
		if (next != null) publisher.control(player, InventoryS2CPacket.policy(session.epoch, next.epoch(), next.view(), next.types()));
	}
	private void open(UUID player, Session session, InventoryC2SPacket packet) {
		if (packet.requestId() <= session.lastRequest || session.previews.size() >= settings.getPreview().effectiveMaxTargetsPerClient()) return;
		session.lastRequest = packet.requestId();
		var held = runtime.memory().tryReserve(PREVIEW_BYTES);
		if (held.isEmpty()) return;
		boolean retained = false;
		InventoryRuntime.Consumer attached = null;
		SyncPublisher.Context registered = null;
		try {
		Opened frozen = runtime.preflight(() -> host.open(player, packet.target())).orElse(null);
		if (frozen == null || !session.policy.types().contains(frozen.targetType())) return;
		InventorySourceInput input = new InventorySourceInput(frozen.target(), player, packet.face());
		var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(player));
		attached = consumer.orElse(null);
		SyncPublisher.Context context = context("preview/" + packet.requestId(), player, session.policy, 1);
		if (consumer.isEmpty() || !publisher.register(context, route(session, packet.requestId(), null, frozen.target(), null))) {
			return;
		}
		registered = context;
		held.get().commit(PREVIEW_BYTES);
		session.previews.put(packet.requestId(), new Preview(packet.requestId(), frozen, input, consumer.get(), held.get(), context));
		retained = true;
		} finally {
			if (!retained) {
				if (registered != null) publisher.cancel(registered);
				try { if (attached != null) attached.close(); } finally { held.get().close(); }
			}
		}
	}
	private void select(UUID player, Session session, InventoryC2SPacket packet) {
		InventoryS2CPacket replay = session.commits.get(packet.commitId());
		if (replay != null) { publisher.control(player, replay); return; }
		if (packet.commitId() <= session.lastCommit) return;
		session.lastCommit = packet.commitId();
		Preview preview = session.previews.get(packet.requestId());
		MarkerRejectReason rejection = MarkerRejectReason.INVALID_REQUEST;
		ServerMarker marker = null;
		if (preview != null && preview.attempted == 0) {
			preview.attempted = packet.commitId();
			InventorySelection ref = preview.references.get(packet.entryKey());
			if (ref != null && packet.baselineId() == preview.context.baseline() && packet.stateRevision() == preview.context.stateFence()
				&& session.policy.types().contains(preview.frozen.targetType())) {
				InventorySelection selection = new InventorySelection(ref.exact(), ref.itemId(), ref.label(), ref.displayJson(), ref.aggregate(), packet.pingType());
				int witness = preview.witnesses.entrySet().stream().filter(e -> selection.aggregate() ? e.getKey().itemId().equals(selection.itemId()) : e.getKey().equals(selection.exact()))
					.mapToInt(Map.Entry::getValue).findFirst().orElse(-1);
				Prepared prepared = new Prepared(preview.input, selection, preview.frozen, witness);
				try {
					Created outcome = host.create(player, preview.frozen, prepared::prepare);
					rejection = outcome.rejection(); marker = outcome.marker();
					if (marker != null) prepared.commit(marker);
				} catch (RuntimeException | LinkageError failure) { rejection = MarkerRejectReason.INVALID_REQUEST; }
				finally { prepared.close(); }
			}
		}
		var result = InventoryS2CPacket.selected(session.epoch, packet.requestId(), packet.commitId(), marker == null ? null : marker.id(), rejection)
			.stamp(session.policy.epoch(), session.policy.view());
		session.commits.put(packet.commitId(), result);
		while (session.commits.size() > MAX_REPLAYS) session.commits.remove(session.commits.keySet().iterator().next());
		publisher.control(player, result);
	}
	private final class Prepared implements AutoCloseable {
		final InventorySourceInput input; final InventorySelection selection; final Opened frozen; final int witness;
		InventoryRuntime.Consumer consumer; RetainedMemoryLedger.Ticket memory;
		final Map<UUID, RetainedMemoryLedger.Ticket> recipients = new LinkedHashMap<>();
		Prepared(InventorySourceInput input, InventorySelection selection, Opened frozen, int witness) {
			this.input = input; this.selection = selection; this.frozen = frozen; this.witness = witness;
		}
		MarkerRejectReason prepare(Target committed, String type, List<UUID> audience) {
			// The host invokes this only after ordinary create authority, never at SELECT ingress.
			if (!input.target().equals(committed) || !frozen.targetType().equals(type) || !runtime.validate(input)
				|| !runtime.selectionPresent(input, selection, witness)) return MarkerRejectReason.INVALID_REQUEST;
			if (!runtime.preflight(() -> Optional.of(host.annotationAllowed(input, selection.itemPingType()))).orElse(false))
				return MarkerRejectReason.INVALID_PING_TYPE;
			if (tracking.size() >= MAX_TRACKING) return MarkerRejectReason.INVALID_REQUEST;
			Set<String> variants = new HashSet<>(); TargetKey target = TargetKey.from(committed);
			for (Tracking lease : tracking.values()) if (lease.marker.targetKey().equals(target)) variants.add(identity(lease.selection));
			variants.add(identity(selection));
			if (variants.size() > settings.getTracking().effectiveMaxVariantsPerTarget()) return MarkerRejectReason.INVALID_REQUEST;
			memory = runtime.memory().tryReserve(LEASE_BYTES).orElse(null);
			if (memory == null) return MarkerRejectReason.INVALID_REQUEST;
			consumer = runtime.attach(input, new InventoryRuntime.TrackingSubject(target)).orElse(null);
			if (consumer == null) return MarkerRejectReason.INVALID_REQUEST;
			for (UUID recipient : audience) {
				Session session = sessions.get(recipient); Policy policy = host.policy(recipient).orElse(null);
				if (session == null || policy == null || !policy.types().contains(type)) continue;
				if (recipients.size() >= publisher.remainingRegistrations()) return MarkerRejectReason.INVALID_REQUEST;
				var ticket = publisher.reserveRegistration();
				if (ticket.isEmpty()) return MarkerRejectReason.INVALID_REQUEST;
				recipients.put(recipient, ticket.get());
			}
			return null;
		}
		void commit(ServerMarker marker) {
			if (consumer == null || memory == null) throw new IllegalStateException("dedicated admission bypass");
			memory.commit(LEASE_BYTES);
			Tracking lease = new Tracking(marker, input, selection, consumer, memory);
			tracking.put(marker.id(), lease); consumer = null; memory = null;
			for (var entry : recipients.entrySet()) addRecipient(lease, entry.getKey(), entry.getValue());
			recipients.clear();
		}
		@Override public void close() {
			if (consumer != null) consumer.close(); if (memory != null) memory.close(); recipients.values().forEach(RetainedMemoryLedger.Ticket::close);
		}
	}
	public void tick(long now, InventorySettings settings) {
		advance(now, settings);
		for (var entry : List.copyOf(sessions.entrySet())) {
			refreshPolicy(entry.getKey(), entry.getValue());
			Session session = entry.getValue();
			var offer = new InventoryS2CPacket.Offer(settings.getPreview().getPeriodTicks(), settings.getTracking().getPeriodTicks(),
				settings.getTracking().getResyncMinPeriods(), settings.getTracking().getHeartbeatPeriods());
			if (session.policy != null && !offer.equals(session.offer)) {
				session.offer = offer;
				publisher.control(entry.getKey(), InventoryS2CPacket.offer(session.epoch, offer).stamp(session.policy.epoch(), session.policy.view()));
			}
		}
		// Hard stop precedes every validation, recovery probe and read.
		var leases = new ArrayList<>(tracking.values());
		if (!leases.isEmpty()) java.util.Collections.rotate(leases, -(trackingCursor++ % leases.size()));
		for (Tracking lease : leases) {
			if (lease.marker.expiresAtTick() <= now) { remove(lease.marker.id()); continue; }
			syncRecipients(lease);
			if (lease.recipients.isEmpty()) continue;
			var valid = runtime.probe(lease.input);
			if (valid.isEmpty()) continue;
			lease.checkedTick = tick;
			if (!valid.get()) { invalidate(lease); continue; }
			if (lease.completed != null && !lease.invalid) for (Recipient recipient : lease.recipients.values()) publishTracking(lease, recipient);
			if (lease.scanning) sampleTracking(lease);
			else if (now >= lease.nextSweep) {
				lease.consumer.restart(); lease.count = lease.total = 0; lease.folded = lease.selection.aggregate(); lease.scanning = true; sampleTracking(lease);
			}
		}
		var previews = new ArrayList<Map.Entry<UUID, Preview>>();
		for (var entry : sessions.entrySet()) for (Preview preview : entry.getValue().previews.values()) previews.add(Map.entry(entry.getKey(), preview));
		if (!previews.isEmpty()) java.util.Collections.rotate(previews, -(previewCursor++ % previews.size()));
		for (var entry : previews) {
			Preview preview = entry.getValue();
			var valid = runtime.probe(preview.input);
			if (valid.isEmpty()) continue;
			preview.checkedTick = tick;
			if (!valid.get()) invalidatePreview(sessions.get(entry.getKey()), preview);
			else if (!preview.terminal) samplePreview(entry.getKey(), preview);
			else if (preview.last != null) publishPreview(preview);
		}
		publisher.drain();
	}
	private void samplePreview(UUID player, Preview preview) {
		InventorySourceAccess.Preparation preparation = runtime.prepare(preview.consumer);
		if (preparation == InventorySourceAccess.Preparation.DEFERRED) return;
		if (preparation != InventorySourceAccess.Preparation.READY) {
			var observation = runtime.step(preview.consumer, 1);
			if (observation.isEmpty()) return;
			CaptureResult result = observation.get().result();
			if (result.availability() != CaptureResult.Availability.READABLE) {
				invalidatePreview(sessions.get(player), preview); return;
			}
			preview.last = result; preview.terminal = true; preview.revision++;
			publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE);
			publishPreview(preview);
			return;
		}
		if (!variantMemory.containsKey(player)) {
			if (variantMemory.size() >= MAX_SESSIONS) return;
			var claimMemory = runtime.memory().tryReserve(65536);
			if (claimMemory.isEmpty()) return;
			claimMemory.get().commit(65536); variantMemory.put(player, claimMemory.get());
		}
		int slotBound = (int) Math.min(InventorySourceAccess.MAX_STEP, Math.max(0,
			(runtime.memory().remaining() - 409600) / (ENTRY_BYTES + InventoryDomainCodec.MAX_BYTES * 2L)));
		if (slotBound == 0) return;
		var held = runtime.memory().tryReserve(slotBound * ENTRY_BYTES);
		if (held.isEmpty()) return;
		boolean retained = false;
		try {
			var reservation = held.get();
			var observation = runtime.step(preview.consumer, slotBound);
			if (observation.isEmpty()) return;
			CaptureResult result = observation.get().result();
			if (result.availability() != CaptureResult.Availability.READABLE) {
				invalidatePreview(sessions.get(player), preview); return;
			}
			int admitted = 0;
			int observedSlot = (int) result.coverage().scanned() - observation.get().slots().size();
			try {
				for (var item : observation.get().slots()) {
					int witness = observedSlot++;
					if (item == null) continue;
					boolean fresh = !preview.counts.containsKey(item.key());
					String claim = variantClaim(item.key());
					boolean unclaimed = !previewVariantClaims.getOrDefault(player, Set.of()).contains(claim);
					if (fresh && (preview.counts.size() >= MAX_ENTRIES || unclaimed && previewVariants.getOrDefault(player, 0) >= Math.min(MAX_ENTRIES, settings.getPreview().effectiveMaxVariantsPerClientPeriod()))) {
						preview.terminal = true; publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE); break;
					}
					if (fresh) {
						admitted++;
						if (unclaimed) {
							previewVariants.merge(player, 1, Integer::sum);
							previewVariantClaims.computeIfAbsent(player, ignored -> new HashSet<>()).add(claim);
						}
					}
					preview.counts.put(item.key(), add(preview.counts.get(item.key()), item));
					preview.witnesses.putIfAbsent(item.key(), witness);
					preview.totals.put(item.key().itemId(), add(preview.totals.get(item.key().itemId()), item));
					if (item.stripped()) preview.folded.add(item.key().itemId());
				}
			} catch (ArithmeticException overflow) { preview.terminal = true; publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE); }
			reservation.commit(admitted * ENTRY_BYTES);
			if (admitted > 0) { preview.entries.add(reservation); retained = true; }
			preview.last = preview.terminal ? new CaptureResult(result.payload(), result.coverage(), result.availability(),
				CaptureResult.Completeness.INCOMPLETE, result.consistency(), result.sourceVersion(), result.nextCursor()) : result;
			preview.revision++;
			publishPreview(preview);
			preview.terminal |= result.completeness() != CaptureResult.Completeness.CONTINUE;
		} finally { if (!retained) held.get().close(); }
	}
	private void publishPreview(Preview preview) {
			Map<String, InventoryDomainCodec.Item> projection = new LinkedHashMap<>();
			for (var item : preview.counts.values()) if (!preview.folded.contains(item.key().itemId())) {
				String key = opaque(preview, item.key().itemId() + "/" + item.key().componentsKey());
				projection.put(key, item); preview.references.put(key, new InventorySelection(item.key(), item.key().itemId(), item.label(), item.displayJson(), false, "attention"));
			}
			for (String id : preview.folded) {
				InventoryDomainCodec.Item item = aggregate(preview.totals.get(id)); String key = opaque(preview, id + "/aggregate");
				preview.references.values().removeIf(ref -> ref.itemId().equals(id) && !ref.aggregate());
				projection.put(key, item); preview.references.put(key, new InventorySelection(null, id, item.label(), item.displayJson(), true, "attention"));
			}
			publish(preview.context, projection, preview.last, preview.revision);
	}
	private static InventoryDomainCodec.Item add(InventoryDomainCodec.Item previous, InventoryDomainCodec.Item item) {
		return new InventoryDomainCodec.Item(item.key(), previous == null ? item.count() : Math.addExact(previous.count(), item.count()), item.label(), item.displayJson(), item.stripped());
	}
	private void invalidatePreview(Session session, Preview preview) {
		runtime.retireInvalid(preview.input);
		if (preview.context.stateFence() == 1) {
			publisher.cancel(preview.context);
			preview.context = new SyncPublisher.Context(preview.context.consumerId(), preview.context.recipient(), preview.context.sessionView(), 2, preview.context.baseline());
			publisher.register(preview.context, route(session, preview.request, null, preview.input.target(), null));
		}
		preview.terminal = true; preview.last = null; preview.references.clear(); preview.witnesses.clear(); preview.counts.clear();
		preview.totals.clear(); preview.keys.clear(); preview.folded.clear(); preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.entries.clear();
		publisher.status(preview.context, InventoryS2CPacket.Status.INVALID);
	}
	private static InventoryDomainCodec.Item aggregate(InventoryDomainCodec.Item item) {
		return new InventoryDomainCodec.Item(new InventoryScanner.Key(item.key().itemId(), ""), item.count(), item.label(), "{\"id\":\"" + item.key().itemId() + "\"}", true);
	}
	private static String opaque(Preview preview, String identity) { return preview.keys.computeIfAbsent(identity, ignored -> UUID.randomUUID().toString()); }
	private void sampleTracking(Tracking lease) {
		var observed = runtime.step(lease.consumer);
		if (observed.isEmpty()) return;
		CaptureResult result = observed.get().result();
		if (result.availability() != CaptureResult.Availability.READABLE) {
			invalidate(lease); return;
		}
		if (lease.invalid) {
			lease.invalid = false; lease.state++; lease.completed = null; resetRecipients(lease);
		}
		try {
			for (var item : observed.get().slots()) if (item != null && item.key().itemId().equals(lease.selection.itemId())) {
				lease.total = Math.addExact(lease.total, item.count());
				if (item.key().equals(lease.selection.exact())) lease.count = Math.addExact(lease.count, item.count());
				lease.folded |= item.stripped();
			}
		} catch (ArithmeticException overflow) { lease.scanning = false; lease.nextSweep = tick + settings.getTracking().getPeriodTicks(); for (Recipient recipient : lease.recipients.values()) publisher.status(recipient.context, InventoryS2CPacket.Status.INCOMPLETE); return; }
		if (result.completeness() == CaptureResult.Completeness.CONTINUE) return;
		lease.scanning = false; lease.nextSweep = tick + settings.getTracking().getPeriodTicks();
		if (result.completeness() != CaptureResult.Completeness.COMPLETE) {
			for (Recipient recipient : lease.recipients.values()) publisher.status(recipient.context, InventoryS2CPacket.Status.INCOMPLETE); return;
		}
		lease.revision++; lease.completed = result;
		lease.completedCount = lease.count; lease.completedTotal = lease.total; lease.completedFolded = lease.folded;
		for (Recipient recipient : lease.recipients.values()) publishTracking(lease, recipient);
	}
	private void invalidate(Tracking lease) {
		runtime.retireInvalid(lease.input);
		if (!lease.invalid) {
			lease.state++; lease.invalid = true; lease.completed = null;
			resetRecipients(lease);
		}
		lease.scanning = false; lease.nextSweep = tick + settings.getTracking().getPeriodTicks();
	}
	private void publishTracking(Tracking lease, Recipient recipient) {
		var selection = lease.selection;
		recipient.folded |= lease.completedFolded;
		var item = new InventoryDomainCodec.Item(recipient.folded ? new InventoryScanner.Key(selection.itemId(), "") : selection.exact(),
			recipient.folded ? lease.completedTotal : lease.completedCount, selection.label(), recipient.folded ? "{\"id\":\"" + selection.itemId() + "\"}" : selection.displayJson(), recipient.folded);
		publish(recipient.context, Map.of(recipient.folded ? "aggregate" : "selected", item), lease.completed, lease.revision);
	}
	private void publish(SyncPublisher.Context context, Map<String, InventoryDomainCodec.Item> items, CaptureResult result, long revision) {
		var admission = runtime.memory().tryReserve(items.size() * 2L * InventoryDomainCodec.MAX_BYTES + 8192);
		if (admission.isEmpty()) return;
		try (var workspace = admission.get()) {
		Map<String, CaptureResult.OpaqueValue> payload = new LinkedHashMap<>(); items.forEach((key, item) -> payload.put(key, InventoryDomainCodec.encode(item)));
		CaptureResult publication = new CaptureResult(Optional.of(new CaptureResult.OpaqueKeyedFragment(InventoryDomainCodec.ID, payload)),
			new CaptureResult.Coverage(result.coverage().demandStamp(), revision, result.coverage().scanned(), result.coverage().expected()),
			CaptureResult.Availability.READABLE, result.completeness(), result.consistency(), result.sourceVersion(), Optional.empty());
		publisher.publish(publication, new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.keySet()), context,
			new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L)));
		}
	}
	private void syncRecipients(Tracking lease) {
		for (UUID player : lease.marker.recipients()) {
			Session session = sessions.get(player); Policy policy = host.policy(player).orElse(null); Recipient old = lease.recipients.get(player);
			if (session == null || policy == null || !policy.types().contains(lease.marker.targetType().id()) || !host.knows(player, lease.marker.id())) {
				if (old != null) { publisher.cancel(old.context); lease.recipients.remove(player); } continue;
			}
			if (old != null && old.policy.equals(policy)) continue;
			if (old != null) { publisher.cancel(old.context); lease.recipients.remove(player); }
			addRecipient(lease, player, null);
		}
	}
	private void addRecipient(Tracking lease, UUID player, RetainedMemoryLedger.Ticket held) {
		Session session = sessions.get(player); Policy policy = host.policy(player).orElse(null);
		if (session == null || policy == null || !policy.types().contains(lease.marker.targetType().id()) || !host.knows(player, lease.marker.id())) { if (held != null) held.close(); return; }
		SyncPublisher.Context context = context("tracking/" + lease.marker.id().value(), player, policy, lease.state);
		var route = route(session, 0, lease.marker.id(), lease.input.target(), lease.selection.itemPingType());
		boolean registered = held == null ? publisher.register(context, route) : publisher.register(context, route, held);
		if (!registered) return;
		Recipient recipient = new Recipient(policy, context); lease.recipients.put(player, recipient);
		publisher.status(context, lease.invalid ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.UPDATING);
		if (lease.completed != null && !lease.invalid) publishTracking(lease, recipient);
	}
	private void resetRecipients(Tracking lease) {
		for (Recipient recipient : lease.recipients.values()) publisher.cancel(recipient.context);
		lease.recipients.clear(); syncRecipients(lease);
	}
	private void resync(UUID player, MarkerId marker) {
		Tracking lease = tracking.get(marker); if (lease == null || lease.marker.expiresAtTick() <= tick) return;
		Recipient old = lease.recipients.get(player); if (old == null || tick < old.resyncAt) return;
		if (publisher.pending(old.context)) return; // admitted fragment work is serviceable; never restart it
		long next = tick + (long) settings.getTracking().getResyncMinPeriods() * settings.getTracking().getPeriodTicks();
		publisher.cancel(old.context); lease.recipients.remove(player); addRecipient(lease, player, null);
		Recipient replacement = lease.recipients.get(player); if (replacement != null) replacement.resyncAt = next;
	}
	private boolean publicationAllowed(SyncPublisher.Context context) {
		Session session = sessions.get(context.recipient()); Policy policy = host.policy(context.recipient()).orElse(null);
		if (session == null || policy == null || !context.sessionView().equals(policy.stamp())) return false;
		for (Preview preview : session.previews.values()) if (preview.context.equals(context))
			return policy.types().contains(preview.frozen.targetType());
		for (Tracking lease : tracking.values()) {
			Recipient recipient = lease.recipients.get(context.recipient());
			if (recipient != null && recipient.context.equals(context)) return lease.marker.expiresAtTick() > tick
				&& policy.types().contains(lease.marker.targetType().id()) && host.knows(context.recipient(), lease.marker.id());
		}
		return false;
	}
	private SyncPublisher.Context context(String consumer, UUID recipient, Policy policy, long state) { return new SyncPublisher.Context(consumer, recipient, policy.stamp(), state, ++baseline); }
	private InventorySyncPublisher.Route route(Session session, long request, MarkerId marker, Target.BlockTarget target, String pingType) {
		return new InventorySyncPublisher.Route(session.epoch, session.policy.epoch(), session.policy.view(), request, marker, TargetKey.from(target).toString(), pingType);
	}
	private static String identity(InventorySelection selection) { return selection.itemId() + "/" + (selection.aggregate() ? "aggregate" : selection.exact().componentsKey()); }
	private static String variantClaim(InventoryScanner.Key key) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest((key.itemId() + "\u0000" + key.componentsKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	private void closePreview(Session session, long request) {
		Preview preview = session.previews.remove(request); if (preview == null) return;
		publisher.cancel(preview.context); preview.consumer.close(); preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.memory.close();
	}
	public void remove(MarkerId marker) {
		Tracking lease = tracking.remove(marker); if (lease == null) return;
		lease.recipients.values().forEach(recipient -> publisher.cancel(recipient.context)); lease.consumer.close(); lease.memory.close();
	}
	public void disconnect(UUID player) {
		publisher.cancelControls(player);
		Session session = sessions.remove(player);
		if (session != null) { for (long request : List.copyOf(session.previews.keySet())) closePreview(session, request); session.memory.close(); }
		for (Tracking lease : List.copyOf(tracking.values())) {
			if (lease.marker.owner().equals(player)) remove(lease.marker.id());
			else { Recipient recipient = lease.recipients.remove(player); if (recipient != null) publisher.cancel(recipient.context); }
		}
	}
	@Override public void close() {
		for (UUID player : List.copyOf(sessions.keySet())) disconnect(player);
		for (MarkerId id : List.copyOf(tracking.keySet())) remove(id);
		previewVariantClaims.clear(); previewVariants.clear(); variantMemory.values().forEach(RetainedMemoryLedger.Ticket::close); variantMemory.clear();
		publisher.close(); runtime.close();
	}
}
