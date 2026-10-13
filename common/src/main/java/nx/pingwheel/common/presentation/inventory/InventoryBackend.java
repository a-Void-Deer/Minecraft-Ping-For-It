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
import nx.pingwheel.common.integration.externalblock.BlockReadSource;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerWinnerChange;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
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
	public record Opened(Target target, String targetType, String defaultPingType) {
		public Opened {
			if (!(target instanceof Target.BlockTarget) && !(target instanceof Target.ExternalBlockTarget external && external.isCandidate()))
				throw new IllegalArgumentException("inventory preview requires a block or external candidate");
		}
	}
	/**
	 * One committed create outcome: storage is committed and the authoritative
	 * name and winner changes are the already-computed publication inputs the
	 * host emits once the caller has installed the tracking sidecar.
	 */
	public record Created(ServerMarker marker, MarkerRejectReason rejection, TargetNameJson authoritativeName,
		List<MarkerWinnerChange> winnerChanges) {
		public Created {
			if ((marker == null) == (rejection == null)) throw new IllegalArgumentException("create outcome");
			winnerChanges = List.copyOf(winnerChanges == null ? List.of() : winnerChanges);
		}
		/** Source-compatible outcome for hosts and fixtures that predate the publication split. */
		public Created(ServerMarker marker, MarkerRejectReason rejection) { this(marker, rejection, null, List.of()); }
		public Created(ServerMarker marker, MarkerRejectReason rejection, TargetNameJson authoritativeName) {
			this(marker, rejection, authoritativeName, List.of());
		}
	}
	@FunctionalInterface public interface Admission {
		MarkerRejectReason prepare(Target committed, String targetType, List<UUID> audience);
	}
	public interface Host extends InventorySyncPublisher.Transport {
		Optional<Policy> policy(UUID player);
		Optional<Opened> open(UUID player, Target requested);
		/**
		 * Binds the preview's original input to the create's committed target.
		 * The default keeps ordinary identity exact; a provider-backed host
		 * additionally confirms both current physical read bindings correspond.
		 */
		default Optional<InventorySourceInput> bindCommitted(InventorySourceInput previewInput, Target committed) {
			return previewInput.ordinaryTarget().filter(block -> block.equals(committed))
				.map(block -> new InventorySourceInput(block, previewInput.readOwner(), previewInput.face()));
		}
		boolean annotationAllowed(InventorySourceInput input, String pingType);
		/**
		 * Rate/channel/audience/target authority precedes admission, which
		 * precedes storage; the returned accepted outcome is committed but not
		 * yet published, so the caller can install the tracking sidecar first.
		 */
		Created create(UUID player, Opened frozen, Admission admission);
		/**
		 * Emits the already-computed CREATED and winner changes after the
		 * tracking sidecar exists. A publication failure must never turn the
		 * committed create into a retryable rejection; the default keeps
		 * fixtures and hosts without a publication port source compatible.
		 */
		default void publishCreated(Created created) {}
		boolean knows(UUID player, MarkerId marker);
		/**
		 * Registry-aware detached display reprojection port for retained
		 * host-side metadata; empty keeps headless fixtures source compatible.
		 */
		default Optional<InventoryRuntime.DisplayReprojector> displayReprojector() { return Optional.empty(); }
	}
	/**
	 * Pure OPEN normalization fence for an external provider candidate: the
	 * server validator may correct classification, but provider, dimension,
	 * expected registry and the opaque locator must remain exactly the
	 * requested ones. Ordinary targets are not candidate-normalized and keep
	 * their existing OPEN behavior.
	 */
	public static boolean sameCandidateNormalization(Target requested, Target normalized) {
		return requested instanceof Target.ExternalBlockTarget external && external.isCandidate()
			&& BlockReadSource.sameTargetBinding(requested, normalized);
	}
	/**
	 * Pure descriptor correspondence for the provider read handoff: both current
	 * sources must name the same provider, sub-level, physical root block and
	 * dimension. Exact root equality is deliberate, so different hit roots of
	 * the same container form are different physical objects.
	 */
	public static boolean correspondingPhysicalBinding(BlockReadSource preview, BlockReadSource committed) {
		return preview != null && committed != null
			&& preview.providerId().equals(committed.providerId())
			&& preview.subLevelId().equals(committed.subLevelId())
			&& preview.blockTarget().equals(committed.blockTarget())
			&& preview.target().dimensionId().equals(committed.target().dimensionId());
	}
	private static final int MAX_SESSIONS = 128, MAX_TRACKING = 256, MAX_ENTRIES = 256, MAX_REPLAYS = 64;
	private static final long SESSION_BYTES = 65536, PREVIEW_BYTES = 8192, ENTRY_BYTES = 24576, LEASE_BYTES = 32768;
	/** Measured fixed overhead charged with one reprojected retained display replacement. */
	private static final long REPROJECT_DISPLAY_FIXED_BYTES = 512L;
	private static final class Session {
		final long epoch;
		final RetainedMemoryLedger.Ticket memory;
		final Map<Long, Preview> previews = new LinkedHashMap<>();
		final Map<Long, InventoryS2CPacket> commits = new LinkedHashMap<>();
		InventoryS2CPacket.Offer offer;
		/** Current requested cap generation; a handoff must match it, never a stale earlier OFFER. */
		long offerGeneration, handoffGeneration;
		/** The exact offer whose handoff is awaited; null until that generation is enqueued. */
		InventoryS2CPacket.Offer handoffOffer;
		Policy policy;
		long lastRequest, lastCommit;
		boolean reconfiguring, offerHandedOff, workReady;
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
		/** Retained backend metadata reprojection state: ceiling, bounded pass flags and its ticket. */
		int metadataCeiling = InventoryDomainCodec.MAX_DISPLAY_BYTES;
		RetainedMemoryLedger.Ticket metadataMemory;
		boolean metadataChanged, rebaselineNeeded, fencePending, republishPending;
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
		boolean rebaselineNeeded, displayDirty;
		Recipient(Policy policy, SyncPublisher.Context context) { this.policy = policy; this.context = context; }
	}
	private static final class Tracking {
		final ServerMarker marker;
		final InventorySourceInput input;
		final InventorySelection selection;
		final InventoryRuntime.Consumer consumer;
		final RetainedMemoryLedger.Ticket memory;
		final Map<UUID, Recipient> recipients = new LinkedHashMap<>();
		/** Capped presentation of the selected variant, separate from its original selected identity. */
		InventoryItemCodec.Display selectionPresentation;
		RetainedMemoryLedger.Ticket presentationMemory;
		int presentationCeiling;
		long count, total, completedCount, completedTotal, revision, state = 1, nextSweep;
		boolean completedFolded;
		boolean folded, invalid, scanning = true;
		CaptureResult completed;
		long checkedTick = -1;
		Tracking(ServerMarker marker, InventorySourceInput input, InventorySelection selection,
			InventoryRuntime.Consumer consumer, RetainedMemoryLedger.Ticket memory, int displayCeiling) {
			this.marker = marker; this.input = input; this.selection = selection; this.consumer = consumer; this.memory = memory;
			folded = selection.aggregate();
			presentationCeiling = displayCeiling;
			if (selection.displayJson() != null) {
				selectionPresentation = new InventoryItemCodec.Display(selection.itemId(), selection.label(), selection.displayJson(), false);
				// The retained projection is only known compliant at its own byte
				// size: a selection admitted under a higher ceiling must still be
				// reprojected instead of being marked compliant for a lower one.
				presentationCeiling = (int) Math.max(displayCeiling, utf8Bytes(selection.displayJson()));
			}
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
	/** Last applied effective integer pair, not a mutable settings reference. */
	private int appliedDisplayBytes = -1, appliedFrameBytes = -1;
	private long offerGeneration;
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
				for (Preview preview : session.previews.values()) if (preview.context.equals(context))
					return preview.checkedTick == tick && (preview.context.stateFence() > 1 || !preview.consumer.invalidated());
				for (Tracking lease : tracking.values()) {
					Recipient recipient = lease.recipients.get(context.recipient());
					if (recipient != null && recipient.context.equals(context))
						return lease.checkedTick == tick && (lease.invalid || !lease.consumer.invalidated());
				}
				return false;
			}
			@Override public int encodedBytes(InventoryS2CPacket packet) { return host.encodedBytes(packet); }
			@Override public void send(UUID recipient, InventoryS2CPacket packet) { host.send(recipient, packet); }
		});
		publisher.setControlHandoff(this::controlHandoffAccepted);
	}
	public InventoryRuntime runtime() { return runtime; }
	/**
	 * Pure sidecar query for the atomic-initial receipt projection: the marker
	 * owns an active committed inventory selection, so its content receipt is
	 * the dedicated inventory kind. No selection value, count, or annotation is
	 * disclosed here, and no second metadata map is introduced.
	 */
	public boolean tracksInventory(MarkerId marker) {
		Tracking lease = marker == null ? null : tracking.get(marker);
		return lease != null && lease.marker.expiresAtTick() > tick;
	}
	private void advance(long now, InventorySettings settings) {
		tick = now; this.settings = settings; runtime.advance(now, settings); publisher.advance(now, settings);
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) {
			previewVariants.clear(); previewVariantClaims.clear(); variantMemory.values().forEach(RetainedMemoryLedger.Ticket::close); variantMemory.clear();
		}
		trackEffectiveSizePair();
		processReconfigurations();
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
		enqueueOffer(player, session);
		refreshPolicy(player, session);
		publisher.control(player, InventoryS2CPacket.policy(session.epoch, policy.epoch(), policy.view(), policy.types()));
	}
	private void refreshPolicy(UUID player, Session session) {
		Policy next = host.policy(player).orElse(null);
		if (java.util.Objects.equals(next, session.policy)) return;
		for (long request : List.copyOf(session.previews.keySet())) closePreview(session, request);
		session.policy = next;
		// A queued OFFER stamped with the old presentation view is dropped by the
		// control authorization fence without a handoff callback; re-arm it so a
		// pending reconfiguration can never wait on a control that was discarded.
		session.handoffOffer = null; session.offerHandedOff = false;
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
		if (consumer.isEmpty() || !publisher.register(context, route(session, packet.requestId(), null, null, null))) {
			return;
		}
		registered = context;
		held.get().commit(PREVIEW_BYTES);
		Preview preview = new Preview(packet.requestId(), frozen, input, consumer.get(), held.get(), context);
		preview.metadataCeiling = effectiveDisplayCeiling();
		session.previews.put(packet.requestId(), preview);
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
			InventorySelection ref = preview.references.get(packet.entryKey());
			// The data gate holds item frames but never SELECT, so an affected pending
			// cut must be rejected here: its retained display no longer fits the
			// current effective ceiling, and installing it would create a tracking
			// presentation the effective cap can never write. The unused attempt stays
			// with the preview so the fresh reference its replacement baseline carries
			// can still create the Ping; the commit id itself remains authoritative
			// through the recorded replay below.
			boolean staleCut = supersededCut(ref);
			if (!staleCut) preview.attempted = packet.commitId();
			if (!staleCut && ref != null && packet.baselineId() == preview.context.baseline() && packet.stateRevision() == preview.context.stateFence()
				&& session.policy.types().contains(preview.frozen.targetType())) {
				InventorySelection selection = new InventorySelection(ref.exact(), ref.itemId(), ref.label(), ref.displayJson(), ref.aggregate(), packet.pingType());
				int witness = preview.witnesses.entrySet().stream().filter(e -> selection.aggregate() ? e.getKey().itemId().equals(selection.itemId()) : e.getKey().equals(selection.exact()))
					.mapToInt(Map.Entry::getValue).findFirst().orElse(-1);
				Prepared prepared = new Prepared(preview.input, selection, preview.frozen, witness);
				try {
					Created outcome = host.create(player, preview.frozen, prepared::prepare);
					rejection = outcome.rejection(); marker = outcome.marker();
					if (marker != null) {
						// Storage is committed: install the sidecar, then publish the
						// existing CREATED/winner changes, then attach the recipients
						// the sidecar already reserved. A publication failure after
						// commit is never reported as a retryable rejection.
						prepared.commit(marker);
						try { host.publishCreated(outcome); }
						catch (RuntimeException | LinkageError publicationFailure) { /* committed; fail soft */ }
						prepared.attachRecipients(marker);
					}
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
	/**
	 * True when a retained preview reference can no longer be published under
	 * the current effective display ceiling. A lowered ceiling fences the data
	 * gate but never SELECT, so this is the admission fence for a superseded
	 * cut; the preview keeps its selection attempt for the replacement
	 * baseline's fresh reference.
	 */
	private boolean supersededCut(InventorySelection reference) {
		return reference != null && reference.displayJson() != null
			&& utf8Bytes(reference.displayJson()) > effectiveDisplayCeiling();
	}
	private final class Prepared implements AutoCloseable {
		final InventorySourceInput input; final InventorySelection selection; final Opened frozen; final int witness;
		InventorySourceInput committedInput;
		InventoryRuntime.Consumer consumer; RetainedMemoryLedger.Ticket memory;
		final Map<UUID, RetainedMemoryLedger.Ticket> recipients = new LinkedHashMap<>();
		Prepared(InventorySourceInput input, InventorySelection selection, Opened frozen, int witness) {
			this.input = input; this.selection = selection; this.frozen = frozen; this.witness = witness;
		}
		MarkerRejectReason prepare(Target committed, String type, List<UUID> audience) {
			// The host invokes this only after create authority and any external materialization, never at SELECT ingress.
			if (!frozen.targetType().equals(type)) return MarkerRejectReason.INVALID_REQUEST;
			InventorySourceInput bound = host.bindCommitted(input, committed).orElse(null);
			// The host-returned input must describe the exact committed target
			// payload and opaque locator before any validation, witness,
			// annotation or allocation; a returned candidate or another
			// committed target must never authorize a different source.
			if (bound == null || bound.face() != input.face() || !bound.readOwner().equals(input.readOwner())
				|| !BlockReadSource.sameTargetBinding(committed, bound.target())
				|| !runtime.validate(bound) || !runtime.selectionPresent(bound, selection, witness))
				return MarkerRejectReason.INVALID_REQUEST;
			if (!runtime.preflight(() -> Optional.of(host.annotationAllowed(bound, selection.itemPingType()))).orElse(false))
				return MarkerRejectReason.INVALID_PING_TYPE;
			if (tracking.size() >= MAX_TRACKING) return MarkerRejectReason.INVALID_REQUEST;
			Set<String> variants = new HashSet<>(); TargetKey target = TargetKey.from(committed);
			for (Tracking lease : tracking.values()) if (lease.marker.targetKey().equals(target)) variants.add(identity(lease.selection));
			variants.add(identity(selection));
			if (variants.size() > settings.getTracking().effectiveMaxVariantsPerTarget()) return MarkerRejectReason.INVALID_REQUEST;
			memory = runtime.memory().tryReserve(LEASE_BYTES).orElse(null);
			if (memory == null) return MarkerRejectReason.INVALID_REQUEST;
			consumer = runtime.attach(bound, new InventoryRuntime.TrackingSubject(target)).orElse(null);
			if (consumer == null) return MarkerRejectReason.INVALID_REQUEST;
			committedInput = bound;
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
		/** Installs the committed tracking sidecar before any CREATED is published. */
		void commit(ServerMarker marker) {
			if (consumer == null || memory == null || committedInput == null) throw new IllegalStateException("dedicated admission bypass");
			memory.commit(LEASE_BYTES);
			Tracking lease = new Tracking(marker, committedInput, selection, consumer, memory, effectiveDisplayCeiling());
			tracking.put(marker.id(), lease); consumer = null; memory = null;
		}
		/**
		 * Registers the reserved recipients after the atomic Basic initial is
		 * known: the existing Basic-known-recipient gate still precedes every
		 * inventory data frame, and the admission-reserved registrations are
		 * honored exactly once.
		 */
		void attachRecipients(ServerMarker marker) {
			Tracking lease = tracking.get(marker.id());
			if (lease == null) return;
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
			var offer = publisher.offer(settings.getPreview().getPeriodTicks(), settings.getTracking().getPeriodTicks(),
				settings.getTracking().getResyncMinPeriods(), settings.getTracking().getHeartbeatPeriods());
			if (session.policy != null && !offer.equals(session.offer)) enqueueOffer(entry.getKey(), session);
		}
		// Hard stop precedes every validation, recovery probe and read.
		for (Tracking lease : List.copyOf(tracking.values())) if (lease.marker.expiresAtTick() <= now) remove(lease.marker.id());
		var leases = new ArrayList<>(tracking.values());
		if (!leases.isEmpty()) java.util.Collections.rotate(leases, -(trackingCursor++ % leases.size()));
		for (Tracking lease : leases) {
			lease.checkedTick = lease.invalid ? tick : -1; // already-withdrawn INVALID control needs no recovery read grant
			syncRecipients(lease);
			if (lease.recipients.isEmpty()) continue;
			if (lease.consumer.invalidated() && !lease.invalid) { invalidate(lease); continue; }
			var valid = lease.invalid ? runtime.probe(lease.input) : runtime.probe(lease.consumer);
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
			preview.checkedTick = -1;
			if (preview.context.stateFence() > 1) { preview.checkedTick = tick; continue; }
			var valid = runtime.probe(preview.consumer);
			if (valid.isEmpty()) continue;
			preview.checkedTick = tick;
			if (!valid.get()) invalidatePreview(sessions.get(entry.getKey()), preview);
			else if (!preview.terminal) samplePreview(entry.getKey(), preview);
			else if (preview.last != null) publishPreview(preview);
		}
		// A later shared probe/step can retire an earlier, already-checked terminal consumer.
		// Cancel/rebase outside publisher traversal so no admitted old fragment can escape this tick.
		for (Tracking lease : tracking.values()) if (lease.consumer.invalidated() && !lease.invalid) invalidate(lease);
		for (Session session : sessions.values()) for (Preview preview : session.previews.values())
			if (preview.consumer.invalidated() && preview.context.stateFence() == 1) invalidatePreview(session, preview);
		publisher.drain();
	}
	/**
	 * Detects an effective integer-pair change from the publisher's own resolved
	 * pair (the pair every write is measured under), keeps the previous ints and
	 * never stores the mutable settings reference. A lowering gates every
	 * recipient before SELECT adjudication or drain and withdraws queued cuts
	 * that can no longer be written; an increase only widens new-capture limits
	 * and never resurrects retained omitted data.
	 */
	private void trackEffectiveSizePair() {
		int display = publisher.effectiveDisplayBytes(), frame = publisher.effectiveFrameBytes();
		if (appliedDisplayBytes < 0) { appliedDisplayBytes = display; appliedFrameBytes = frame; return; }
		if (display == appliedDisplayBytes && frame == appliedFrameBytes) return;
		boolean lowered = display < appliedDisplayBytes || frame < appliedFrameBytes;
		appliedDisplayBytes = display; appliedFrameBytes = frame;
		if (!lowered) {
			// An increase never resurrects retained data, but a pending reconfiguration
			// must advertise the current enforced pair rather than a superseded lower one.
			for (var entry : sessions.entrySet()) {
				Session session = entry.getValue();
				if (!session.reconfiguring) continue;
				if (session.offer != null && session.offer.effectiveDisplayBytes() == display && session.offer.effectiveFrameBytes() == frame) continue;
				session.offerGeneration = ++offerGeneration;
				session.handoffGeneration = session.offerGeneration;
				session.handoffOffer = null; session.offerHandedOff = false;
			}
			return;
		}
		long generation = ++offerGeneration;
		for (var entry : sessions.entrySet()) {
			Session session = entry.getValue();
			session.reconfiguring = true;
			session.offerHandedOff = false; session.workReady = false;
			session.offerGeneration = generation; session.handoffGeneration = generation;
			session.handoffOffer = null; // an older generation's queued OFFER must never match
			publisher.gateData(entry.getKey());
		}
		withdrawObsoleteCuts();
	}
	/** Drops queued work whose retained values no longer fit the current effective caps. */
	private void withdrawObsoleteCuts() {
		for (var entry : sessions.entrySet()) {
			for (Preview preview : entry.getValue().previews.values())
				if (publisher.oversized(preview.context)) publisher.withdraw(preview.context);
			for (Tracking lease : tracking.values()) {
				Recipient recipient = lease.recipients.get(entry.getKey());
				if (recipient != null && publisher.oversized(recipient.context)) publisher.withdraw(recipient.context);
			}
		}
	}
	/**
	 * Bounded per-advance reconfiguration progress. The OFFER is only enqueued
	 * here; a matching successful transport handoff (never the enqueue) admits
	 * the reprojection/rebaseline work, and the gate opens only when that work
	 * is complete and the recipient is still authorized.
	 */
	private void processReconfigurations() {
		for (var entry : List.copyOf(sessions.entrySet())) {
			UUID recipient = entry.getKey(); Session session = entry.getValue();
			if (!session.reconfiguring || session.policy == null) continue;
			if (session.handoffOffer == null && !enqueueOffer(recipient, session)) continue;
			if (!session.offerHandedOff) continue;
			boolean ready = true;
			int display = effectiveDisplayCeiling();
			for (Preview preview : List.copyOf(session.previews.values())) ready &= reconcilePreview(session, preview, display);
			for (Tracking lease : List.copyOf(tracking.values())) {
				if (!lease.recipients.containsKey(recipient)) continue;
				ready &= reconcileTracking(recipient, lease, display);
			}
			session.workReady = ready;
			if (ready) { publisher.releaseData(recipient); session.reconfiguring = false; session.handoffOffer = null; }
		}
	}
	private boolean enqueueOffer(UUID recipient, Session session) {
		var p = settings.getPreview(); var t = settings.getTracking();
		session.offer = publisher.offer(p.getPeriodTicks(), t.getPeriodTicks(), t.getResyncMinPeriods(), t.getHeartbeatPeriods());
		boolean queued = publisher.control(recipient, InventoryS2CPacket.offer(session.epoch, session.offer)
			.stamp(session.policy.epoch(), session.policy.view()));
		if (queued) session.handoffOffer = session.offer;
		return queued;
	}
	/**
	 * Control-handoff callback. Only a same-epoch, same-presentation-view OFFER
	 * whose exact caps are the current generation's requested caps counts as a
	 * successful handoff; a stale OFFER, an unrelated control or an unknown
	 * recipient never clears a gate. Returning true releases the gate only when
	 * the required work is already ready.
	 */
	private boolean controlHandoffAccepted(UUID recipient, InventoryS2CPacket packet) {
		Session session = sessions.get(recipient);
		if (session == null) return false;
		if (packet.kind() != InventoryS2CPacket.Kind.OFFER) return !session.reconfiguring;
		if (session.policy == null || packet.epoch() != session.epoch
			|| packet.presentationEpoch() != session.policy.epoch() || packet.view() != session.policy.view()) return false;
		if (!session.reconfiguring) return true;
		if (session.handoffOffer == null || session.handoffGeneration != session.offerGeneration || packet.offer() == null
			|| packet.offer().effectiveDisplayBytes() != session.handoffOffer.effectiveDisplayBytes()
			|| packet.offer().effectiveFrameBytes() != session.handoffOffer.effectiveFrameBytes()) return false;
		session.offerHandedOff = true;
		return session.workReady;
	}
	private int effectiveDisplayCeiling() {
		return (int) Math.clamp(appliedDisplayBytes, 1, InventoryDomainCodec.MAX_DISPLAY_BYTES);
	}
	/** Lowers one preview's retained observation and backend metadata, then fences a fresh baseline when affected. */
	private boolean reconcilePreview(Session session, Preview preview, int displayCap) {
		var reprojection = runtime.reprojectDisplays(preview.consumer, displayCap);
		boolean ready = reprojection.status() != InventoryRuntime.ReprojectionStatus.DEFERRED;
		for (String id : reprojection.foldIds()) preview.folded.add(id);
		if (ready && !reprojectPreviewMetadata(preview, displayCap)) ready = false;
		if (preview.metadataChanged || publisher.oversized(preview.context)) {
			preview.rebaselineNeeded = true; preview.metadataChanged = false;
		}
		if (ready && preview.rebaselineNeeded) {
			ready = rebaselinePreview(session, preview);
			if (ready) preview.rebaselineNeeded = false;
		}
		if (!ready) return false;
		if (preview.fencePending) {
			if (publisher.controlPending(preview.context)) return false; // the empty PREVIEW fence must be handed off first
			preview.fencePending = false;
		}
		if (preview.republishPending) {
			preview.republishPending = false;
			if (preview.last != null && (!preview.counts.isEmpty() || !preview.folded.isEmpty())) publishPreview(preview);
		}
		return true;
	}
	/** Reserves measured replacement memory, then reprojects counts/totals/references at the tighter ceiling. */
	private boolean reprojectPreviewMetadata(Preview preview, int displayCap) {
		if (preview.metadataCeiling <= displayCap) return true;
		var port = host.displayReprojector().orElse(null);
		if (port == null) { preview.metadataCeiling = displayCap; return true; }
		long upper = 0;
		for (InventoryDomainCodec.Item item : preview.counts.values()) upper += replacementBound(item == null ? null : item.displayJson(), displayCap);
		for (InventoryDomainCodec.Item item : preview.totals.values()) upper += replacementBound(item == null ? null : item.displayJson(), displayCap);
		for (InventorySelection reference : preview.references.values()) upper += replacementBound(reference.displayJson(), displayCap);
		if (upper == 0) { preview.metadataCeiling = displayCap; return true; }
		var held = runtime.memory().tryReserve(upper);
		if (held.isEmpty()) return false;
		long measured = 0;
		boolean changed = false;
		try {
			for (var entry : preview.counts.entrySet()) {
				InventoryDomainCodec.Item item = entry.getValue();
				var projected = reprojectItem(port, item, displayCap);
				if (projected == null) continue;
				measured = checkedAdd(measured, replacementActual(projected.displayJson(), item.displayJson(), displayCap));
				if (projected.stripped()) preview.folded.add(item.key().itemId());
				entry.setValue(projected); changed = true;
			}
			for (var entry : preview.totals.entrySet()) {
				InventoryDomainCodec.Item item = entry.getValue();
				var projected = reprojectItem(port, item, displayCap);
				if (projected == null) continue;
				measured = checkedAdd(measured, replacementActual(projected.displayJson(), item.displayJson(), displayCap));
				if (projected.stripped()) preview.folded.add(item.key().itemId());
				entry.setValue(projected); changed = true;
			}
			for (var entry : preview.references.entrySet()) {
				InventorySelection reference = entry.getValue();
				var projected = reprojectDisplay(port, reference.itemId(), reference.label(), reference.displayJson(), displayCap);
				if (projected == null) continue;
				measured = checkedAdd(measured, replacementActual(projected.displayJson(), reference.displayJson(), displayCap));
				if (projected.componentsStripped()) preview.folded.add(reference.itemId());
				entry.setValue(new InventorySelection(reference.exact(), reference.itemId(), reference.label(), projected.displayJson(),
					reference.aggregate(), reference.itemPingType()));
				changed = true;
			}
		} catch (RuntimeException | Error failure) {
			held.get().close(); throw failure;
		}
		held.get().commit(Math.min(measured, upper));
		if (preview.metadataMemory != null) preview.metadataMemory.close(); // new ticket admitted before the old one is released
		preview.metadataMemory = held.get();
		preview.metadataCeiling = displayCap;
		if (changed) preview.metadataChanged = true;
		return true;
	}
	private InventoryDomainCodec.Item reprojectItem(InventoryRuntime.DisplayReprojector port, InventoryDomainCodec.Item item, int displayCap) {
		if (item == null || item.displayJson() == null || utf8Bytes(item.displayJson()) <= displayCap) return null;
		var projected = reprojectDisplay(port, item.key().itemId(), item.label(), item.displayJson(), displayCap);
		if (projected == null) return null;
		return new InventoryDomainCodec.Item(item.key(), item.count(), projected.label(), projected.displayJson(), projected.componentsStripped());
	}
	private InventoryItemCodec.Display reprojectDisplay(InventoryRuntime.DisplayReprojector port, String itemId, String label, String displayJson, int displayCap) {
		var retained = new InventoryItemCodec.Display(itemId, label, displayJson, false);
		var projected = port.reproject(retained, displayCap);
		return projected.equals(retained) ? null : projected;
	}
	private static long replacementBound(String displayJson, int displayCap) {
		return displayJson == null || utf8Bytes(displayJson) <= displayCap ? 0L : (long) displayCap + REPROJECT_DISPLAY_FIXED_BYTES;
	}
	private static long replacementActual(String projectedJson, String previousJson, int displayCap) {
		long measured = utf8Bytes(projectedJson) + REPROJECT_DISPLAY_FIXED_BYTES;
		return Math.min(measured, replacementBound(previousJson, displayCap));
	}
	private static long utf8Bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
	private static long checkedAdd(long first, long second) {
		try { return Math.addExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	/** Cancels the old context and registers a fresh baseline; the replacement fence is handed off before data. */
	private boolean rebaselinePreview(Session session, Preview preview) {
		publisher.cancel(preview.context);
		SyncPublisher.Context fresh = new SyncPublisher.Context(preview.context.consumerId(), preview.context.recipient(),
			preview.context.sessionView(), preview.context.stateFence(), ++baseline);
		if (!publisher.register(fresh, route(session, preview.request, null, null, null))) return false;
		preview.context = fresh;
		preview.fencePending = true; preview.republishPending = true;
		publisher.status(fresh, InventoryS2CPacket.Status.INCOMPLETE);
		return true;
	}
	/** Lowers one tracking lease's retained observation and its capped selection presentation. */
	private boolean reconcileTracking(UUID recipient, Tracking lease, int displayCap) {
		var reprojection = runtime.reprojectDisplays(lease.consumer, displayCap);
		boolean ready = reprojection.status() != InventoryRuntime.ReprojectionStatus.DEFERRED;
		if (reprojection.foldIds().contains(lease.selection.itemId())) lease.folded = true;
		if (ready && !reprojectTrackingPresentation(lease, displayCap)) ready = false;
		// A cap-driven fold applies to the retained completed projection too: a
		// replacement recipient must keep the last completed all-ID total and the
		// component-too-long quality instead of the exact selected count until a
		// genuinely new complete observation replaces that evidence.
		if (lease.folded && lease.completed != null) lease.completedFolded = true;
		Recipient current = lease.recipients.get(recipient);
		if (current == null) return ready;
		if (lease.folded && lease.completed != null && !current.folded) { current.folded = true; current.rebaselineNeeded = true; }
		if (current.displayDirty) current.rebaselineNeeded = true;
		if (publisher.oversized(current.context)) current.rebaselineNeeded = true;
		if (ready && current.rebaselineNeeded) {
			ready = rebaselineTracking(recipient, lease, current);
			if (ready) { current.displayDirty = false; current.rebaselineNeeded = false; }
		}
		return ready;
	}
	/** Keeps the original selected identity while the capped presentation is reprojected under memory admission. */
	private boolean reprojectTrackingPresentation(Tracking lease, int displayCap) {
		if (lease.presentationCeiling <= displayCap) return true;
		var port = host.displayReprojector().orElse(null);
		if (port == null || lease.selectionPresentation == null) { lease.presentationCeiling = displayCap; return true; }
		InventoryItemCodec.Display retained = lease.selectionPresentation;
		if (utf8Bytes(retained.displayJson()) <= displayCap) { lease.presentationCeiling = displayCap; return true; }
		long bound = (long) displayCap + REPROJECT_DISPLAY_FIXED_BYTES;
		var held = runtime.memory().tryReserve(bound);
		if (held.isEmpty()) return false;
		InventoryItemCodec.Display projected;
		try { projected = port.reproject(retained, displayCap); }
		catch (RuntimeException | Error failure) { held.get().close(); throw failure; }
		held.get().commit(Math.min(utf8Bytes(projected.displayJson()) + REPROJECT_DISPLAY_FIXED_BYTES, bound));
		if (!projected.equals(retained)) {
			lease.selectionPresentation = projected;
			if (lease.completed != null) for (Recipient recipient : lease.recipients.values()) recipient.displayDirty = true;
		}
		if (projected.componentsStripped()) lease.folded = true;
		if (lease.presentationMemory != null) lease.presentationMemory.close();
		lease.presentationMemory = held.get();
		lease.presentationCeiling = displayCap;
		return true;
	}
	/** Fresh tracking registration keeps the existing STATUS fence, state fence, cooldown and folded state. */
	private boolean rebaselineTracking(UUID recipient, Tracking lease, Recipient current) {
		publisher.cancel(current.context);
		Session session = sessions.get(recipient);
		if (session == null || session.policy == null) return true;
		SyncPublisher.Context fresh = new SyncPublisher.Context(current.context.consumerId(), recipient,
			current.context.sessionView(), lease.state, ++baseline);
		if (!publisher.register(fresh, route(session, 0, lease.marker.id(), lease.input.target(), lease.selection.itemPingType()))) return false;
		Recipient replacement = new Recipient(current.policy, fresh);
		replacement.folded = current.folded; replacement.resyncAt = current.resyncAt;
		lease.recipients.put(recipient, replacement);
		publisher.status(fresh, lease.invalid ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.UPDATING);
		if (lease.completed != null && !lease.invalid) publishTracking(lease, replacement);
		return true;
	}
	/** Measured per-slot encode workspace at the current effective display ceiling. */
	private long previewSlotWorkspace() {
		long displayCap = Math.clamp(settings.effectiveDisplayBytes(), 1L, InventoryDomainCodec.MAX_DISPLAY_BYTES);
		return 2L * (displayCap + (InventoryDomainCodec.MAX_BYTES - InventoryDomainCodec.MAX_DISPLAY_BYTES)) + 512L;
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
			(runtime.memory().remaining() - 409600) / (ENTRY_BYTES + previewSlotWorkspace())));
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
			if (preview.fencePending) return; // the fresh empty PREVIEW fence must be handed off before replacement data
			Map<String, InventoryDomainCodec.Item> projection = new LinkedHashMap<>();
			for (var item : preview.counts.values()) if (!preview.folded.contains(item.key().itemId())) {
				String key = opaque(preview, item.key().itemId() + "/" + item.key().componentsKey());
				projection.put(key, item); preview.references.put(key, new InventorySelection(item.key(), item.key().itemId(), item.label(), item.displayJson(), false, "attention"));
			}
			for (String id : preview.folded) {
				InventoryDomainCodec.Item total = preview.totals.get(id);
				if (total == null) continue;
				InventoryDomainCodec.Item item = aggregate(total); String key = opaque(preview, id + "/aggregate");
				preview.references.values().removeIf(ref -> ref.itemId().equals(id) && !ref.aggregate());
				projection.put(key, item); preview.references.put(key, new InventorySelection(null, id, item.label(), item.displayJson(), true, "attention"));
			}
			publish(preview.context, projection, preview.last, preview.revision);
	}
	private static InventoryDomainCodec.Item add(InventoryDomainCodec.Item previous, InventoryDomainCodec.Item item) {
		return new InventoryDomainCodec.Item(item.key(), previous == null ? item.count() : Math.addExact(previous.count(), item.count()), item.label(), item.displayJson(), item.stripped());
	}
	private void invalidatePreview(Session session, Preview preview) {
		preview.consumer.discardObservation();
		preview.checkedTick = tick; // invalid control is independent of item/read admission
		if (preview.context.stateFence() == 1) {
			publisher.cancel(preview.context);
			preview.context = new SyncPublisher.Context(preview.context.consumerId(), preview.context.recipient(), preview.context.sessionView(), 2, preview.context.baseline());
			publisher.register(preview.context, route(session, preview.request, null, null, null));
		}
		preview.terminal = true; preview.last = null; preview.references.clear(); preview.witnesses.clear(); preview.counts.clear();
		preview.totals.clear(); preview.keys.clear(); preview.folded.clear(); preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.entries.clear();
		preview.rebaselineNeeded = false; preview.fencePending = false; preview.republishPending = false; preview.metadataChanged = false;
		preview.metadataCeiling = effectiveDisplayCeiling();
		if (preview.metadataMemory != null) { preview.metadataMemory.close(); preview.metadataMemory = null; }
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
		lease.consumer.retainCompleted();
		lease.revision++; lease.completed = result;
		lease.completedCount = lease.count; lease.completedTotal = lease.total; lease.completedFolded = lease.folded;
		for (Recipient recipient : lease.recipients.values()) publishTracking(lease, recipient);
	}
	private void invalidate(Tracking lease) {
		lease.consumer.discardObservation();
		lease.checkedTick = tick; // withdrawing valid state never waits for another probe grant
		if (!lease.invalid) {
			lease.state++; lease.invalid = true; lease.completed = null;
			resetRecipients(lease);
		}
		lease.scanning = false; lease.nextSweep = tick + settings.getTracking().getPeriodTicks();
	}
	private void publishTracking(Tracking lease, Recipient recipient) {
		var selection = lease.selection;
		recipient.folded |= lease.completedFolded;
		String presentation = recipient.folded ? "{\"id\":\"" + selection.itemId() + "\"}"
			: lease.selectionPresentation == null ? null : lease.selectionPresentation.displayJson();
		var item = new InventoryDomainCodec.Item(recipient.folded ? new InventoryScanner.Key(selection.itemId(), "") : selection.exact(),
			recipient.folded ? lease.completedTotal : lease.completedCount, selection.label(), presentation, recipient.folded);
		publish(recipient.context, Map.of(recipient.folded ? "aggregate" : "selected", item), lease.completed, lease.revision);
	}
	private void publish(SyncPublisher.Context context, Map<String, InventoryDomainCodec.Item> items, CaptureResult result, long revision) {
		// Measured per-item encode workspace at the effective display ceiling, not an absolute-frame mirror.
		var admission = runtime.memory().tryReserve(items.size() * previewSlotWorkspace() + 8192);
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
		Recipient recipient = new Recipient(policy, context);
		// A replacement recipient (RESYNC or a new audience member) keeps the
		// retained completed projection's fold state, so the last completed
		// all-ID total and the component-too-long quality survive until a new
		// complete observation replaces that evidence.
		if (lease.completed != null) recipient.folded = lease.completedFolded;
		lease.recipients.put(player, recipient);
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
	private InventorySyncPublisher.Route route(Session session, long request, MarkerId marker, Target target, String pingType) {
		// Preview carries a request-scoped internal quota token; a candidate has no
		// marker key and its opaque locator is never a stable identity. Tracking
		// uses the committed target's real key.
		String quota = target == null ? "preview/" + session.epoch + "/" + request : TargetKey.from(target).toString();
		return new InventorySyncPublisher.Route(session.epoch, session.policy.epoch(), session.policy.view(), request, marker, quota, pingType);
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
		if (preview.metadataMemory != null) preview.metadataMemory.close();
	}
	public void remove(MarkerId marker) {
		Tracking lease = tracking.remove(marker); if (lease == null) return;
		lease.recipients.values().forEach(recipient -> publisher.cancel(recipient.context)); lease.consumer.close(); lease.memory.close();
		if (lease.presentationMemory != null) lease.presentationMemory.close();
	}
	public void disconnect(UUID player) {
		publisher.cancelControls(player);
		publisher.releaseData(player); // a disconnected recipient keeps no pending gate
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
