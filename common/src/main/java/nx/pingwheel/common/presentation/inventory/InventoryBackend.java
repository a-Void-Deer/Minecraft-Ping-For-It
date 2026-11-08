package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.EnumMap;
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
import nx.pingwheel.common.marker.PingAuthorization;
import nx.pingwheel.common.marker.ServerMarker;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Server-thread coordinator: request authority, shared capture and marker-owned tracking. */
public final class InventoryBackend implements AutoCloseable {
	private static final nx.pingwheel.common.diagnostics.DiagnosticChannel PUBLISH_CREATED = nx.pingwheel.common.diagnostics.DiagnosticChannel.interval("inventory-publish-created", java.time.Duration.ofSeconds(10));
	public record Policy(long epoch, long view, @Unmodifiable Set<String> types, @Unmodifiable Set<String> fluidTypes,
		long revision) {
		public Policy(long epoch, long view, Set<String> types) { this(epoch, view, types, Set.of(), 0); }
		public Policy(long epoch, long view, Set<String> types, Set<String> fluidTypes) {
			this(epoch, view, types, fluidTypes, 0);
		}
		public Policy {
			types = Set.copyOf(types);
			fluidTypes = Set.copyOf(fluidTypes);
			if (revision < 0) throw new IllegalArgumentException("inventory hint revision");
		}
		public @Unmodifiable Set<String> types(InventoryResourceKind kind) { return kind == InventoryResourceKind.ITEM ? types : fluidTypes; }
		String stamp() { return epoch + "/" + view; }
	}
	/** Opaque explicit-input identity for one hint-adoption boundary. */
	public record PolicyToken(UUID owner, long sessionEpoch, long sessionView, long hintGeneration,
		long hintRevision, long providerRevision) {}
	/** Frozen OPEN lanes paired with the hint input identity observed for them. */
	public record PreviewLaneGrant(@Unmodifiable Set<InventoryResourceKind> lanes, PolicyToken token) {
		public PreviewLaneGrant { lanes = Set.copyOf(lanes); }
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
		@Nullable MarkerRejectReason prepare(Target committed, TargetKey targetKey, String targetType, List<UUID> audience);
		/** Runs once on the exact create result immediately before the atomic store commit. */
		default @Nullable MarkerRejectReason finishAuthorization(PingAuthorization authorization) { return null; }
	}
	/** Current presentation session/schema availability; it carries no field-permission result. */
	@org.jetbrains.annotations.NotNullByDefault
	public record SessionAccess(long epoch, long view, @Unmodifiable Set<InventoryResourceKind> compatibleKinds) {
		public SessionAccess { compatibleKinds = Set.copyOf(compatibleKinds); }
		public String stamp() { return epoch + "/" + view; }
	}
	public interface Host extends InventorySyncPublisher.Transport {
		/** Current hint/new-request pre-check only; never an existing marker or Preview's content grant. */
		Optional<Policy> policy(UUID player);
		/** Explicit HELLO demand for the cached future-input hint; default fixtures keep their current port. */
		default void policyDemand(UUID player) {}
		/** Explicit hint input identity captured before a new request freeze. */
		default PolicyToken policyToken(UUID player) { return new PolicyToken(player, 0L, 0L, 0L, 0L, 0L); }
		/** Fold a fresh OPEN grant's actual-type lanes into the cached hint; never an authority check. */
		default void adoptPreviewPolicy(UUID player, String targetTypeId, @Unmodifiable Set<InventoryResourceKind> lanes) {}
		default void adoptPreviewPolicy(UUID player, String targetTypeId, PreviewLaneGrant grant) {
			adoptPreviewPolicy(player, targetTypeId, grant.lanes());
		}
		/** Fold a successful create's already frozen actual lane into the hint; no permission recheck. */
		default void adoptCreatePolicy(UUID player, String targetTypeId, InventoryResourceKind kind, boolean allowed) {}
		default void adoptCreatePolicy(UUID player, String targetTypeId, InventoryResourceKind kind, boolean allowed,
			PolicyToken token) {
			adoptCreatePolicy(player, targetTypeId, kind, allowed);
		}
		/** Ready/online presentation session and schema-compatible inventory lanes only. */
		Optional<SessionAccess> session(UUID player);
		/** Read-only canonical marker lookup; no mutable store is exposed. */
		Optional<ServerMarker> activeMarker(MarkerId marker);
		/** One OPEN-time Preview entitlement decision for the owner and frozen target type. */
		@Unmodifiable Set<InventoryResourceKind> freezeInventoryPreviewLanes(UUID owner, String targetTypeId);
		/** Default wrapper for fixtures; production supplies a token from the same freeze. */
		default PreviewLaneGrant freezeInventoryPreviewGrant(UUID owner, String targetTypeId) {
			return new PreviewLaneGrant(freezeInventoryPreviewLanes(owner, targetTypeId), policyToken(owner));
		}
		Optional<Opened> open(UUID player, Target requested);
		/**
		 * Binds the preview's original input to the create's committed target.
		 * The default keeps ordinary identity exact; a provider-backed host
		 * additionally confirms both current physical read bindings correspond.
		 */
		default Optional<InventorySourceInput> bindCommitted(InventorySourceInput previewInput, Target committed) {
			return previewInput.ordinaryTarget().filter(block -> block.equals(committed))
				.map(block -> new InventorySourceInput(block, previewInput.readOwner(), previewInput.face(), previewInput.resourceKind()));
		}
		boolean annotationAllowed(InventorySourceInput input, String pingType);
		/**
		 * Rate/channel/audience/target authority precedes admission, which
		 * precedes storage; the returned accepted outcome is committed but not
		 * yet published, so the caller can install the tracking sidecar first.
		 */
		Created create(UUID player, Opened frozen, Admission admission);
		default Created create(UUID player, Opened frozen, InventoryResourceKind kind, Admission admission) { return create(player, frozen, admission); }
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
	/** The Inventory adapter/field entitlement is the sole recipient content authorization for a lane. */
	public static boolean inventoryAllowed(@Nullable PingAuthorization authorization, UUID recipient, InventoryResourceKind kind) {
		return authorization != null
			&& authorization.forRecipient(recipient).allowsField(InventoryPresentation.ADAPTER_ID, kind.fieldId());
	}
	/** Zero is a legal complete OPEN result: both lanes denied. */
	public static int laneMask(@Unmodifiable Set<InventoryResourceKind> lanes) {
		int mask = 0;
		for (InventoryResourceKind kind : lanes) mask |= 1 << kind.wireId();
		return mask;
	}
	private static final int MAX_SESSIONS = 128, MAX_TRACKING = 256, MAX_ENTRIES = 256, MAX_REPLAYS = 64;
	private static final long SESSION_BYTES = 65536, LEASE_BYTES = 32768;
	/**
	 * Per lifetime category: bounded identity/token strings (UTF-16), count/total/
	 * witness/reference/key nodes, retired/fold markers, ticket and backing slots.
	 * 40-byte objects and 8-byte references, with growth/alignment overlap, fit
	 * within 4096. Reserving the whole small index slab makes retirement infallible
	 * under later memory pressure. Variable displays have separate entry tickets.
	 */
	private static final long PREVIEW_BYTES = 8192L + MAX_ENTRIES * 4096L;
	private static final long CLAIM_BYTES = 1024L + MAX_ENTRIES * 512L;
	private static final long REPROJECT_DISPLAY_FIXED_BYTES = 1024L;
	private static final long AGGREGATE_DISPLAY_BYTES = 4096L, AGGREGATE_DISPLAY_WORK = 8192L;
	private static final int AGGREGATE_JSON_BYTES = 327;
	/**
	 * The existing 64 KiB Session slab includes the 64 bounded outcome packets,
	 * map nodes/backing slots and these 40-byte-or-smaller retry wrappers (under
	 * 3 KiB). No packet copy or separate pending index is retained for retry.
	 */
	private static final class CommitResult {
		final long requestId, commitId;
		final InventoryResourceKind resourceKind;
		final @Nullable MarkerId markerId;
		final @Nullable MarkerRejectReason rejection;
		InventoryS2CPacket packet;
		boolean pending = true, queued;
		CommitResult(InventoryS2CPacket packet, long requestId, long commitId, @Nullable MarkerId markerId,
			@Nullable MarkerRejectReason rejection) {
			this.packet = packet; this.requestId = requestId; this.commitId = commitId; this.resourceKind = packet.resourceKind();
			this.markerId = markerId; this.rejection = rejection;
		}
		boolean selected() { return rejection == null; }
	}
	private static final class Session {
		final UUID recipient;
		final long epoch;
		final RetainedMemoryLedger.Ticket memory;
		final Map<LaneKey, Preview> previews = new LinkedHashMap<>();
		final Map<Long, PreviewRequest> requests = new LinkedHashMap<>();
		final Map<Long, CommitResult> commits = new LinkedHashMap<>();
		/** At most one immutable desired policy per lane until successful handoff, paid by the session slab. */
		final Map<InventoryResourceKind, InventoryS2CPacket> pendingPolicies = new EnumMap<>(InventoryResourceKind.class);
		final Map<InventoryResourceKind, Long> pendingPolicyRevisions = new EnumMap<>(InventoryResourceKind.class);
		/** At most one complete OPEN result per request until that exact control is handed off. */
		final Map<Long, InventoryS2CPacket> pendingOpened = new LinkedHashMap<>();
		InventoryS2CPacket.Offer offer;
		/** Current requested cap generation; a handoff must match it, never a stale earlier OFFER. */
		long offerGeneration, handoffGeneration;
		/** The exact offer whose handoff is awaited; null until that generation is enqueued. */
		InventoryS2CPacket.Offer handoffOffer;
		@Nullable Policy policy;
		@Nullable SessionAccess access;
		long lastRequest, lastCommit;
		boolean reconfiguring, offerHandedOff, workReady;
		/** Desired hints are retained, but not admitted before this session's first OFFER handoff. */
		boolean policyAwaitingOffer = true;
		Session(long epoch, UUID recipient, SessionAccess access, @Nullable Policy policy, RetainedMemoryLedger.Ticket memory) {
			this.epoch = epoch; this.recipient = recipient; this.access = access; this.policy = policy; this.memory = memory;
		}
	}
	@org.jetbrains.annotations.NotNullByDefault
	private record LaneKey(long request, InventoryResourceKind kind) {}
	@org.jetbrains.annotations.NotNullByDefault
	private static final class PreviewRequest {
		final long request;
		final Opened frozen;
		final nx.pingwheel.common.domain.BlockFace face;
		final RetainedMemoryLedger.Ticket memory;
		/** Immutable OPEN-time lane entitlement, including an all-denied zero mask. */
		final int laneMask;
		long attempted;
		boolean openedHandedOff;
		PreviewRequest(long request, Opened frozen, nx.pingwheel.common.domain.BlockFace face, RetainedMemoryLedger.Ticket memory, int laneMask) {
			this.request = request; this.frozen = frozen; this.face = face; this.memory = memory; this.laneMask = laneMask;
		}
	}
	private static final class Preview {
		final PreviewRequest parent;
		final InventoryBudgetDelayState samplingDelay = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		final InventoryBudgetDelayState projectionDelay = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		final InventoryBudgetDelayState publicationDelay = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		final long request;
		final Opened frozen;
		final InventorySourceInput input;
		final InventoryRuntime.Consumer consumer;
		final RetainedMemoryLedger.Ticket memory;
		SyncPublisher.Context context;
		Map<InventoryScanner.Key, InventoryResourceValue> counts = new LinkedHashMap<>();
		final Map<InventoryScanner.Key, Integer> witnesses = new LinkedHashMap<>();
		Map<String, InventoryResourceValue> totals = new LinkedHashMap<>();
		Map<String, InventorySelection> references = new LinkedHashMap<>();
		final Map<String, String> keys = new LinkedHashMap<>();
		final Set<String> folded = new HashSet<>();
		/** Lifetime token/category bound includes retired identities, not just visible rows. */
		final Set<String> retired = new HashSet<>();
		int categories;
		final List<RetainedMemoryLedger.Ticket> entries = new ArrayList<>();
		/** Retained backend metadata reprojection state: ceiling, bounded pass flags and its ticket. */
		int metadataCeiling = InventoryDomainCodec.MAX_DISPLAY_BYTES;
		RetainedMemoryLedger.Ticket metadataMemory;
		boolean metadataChanged, rebaselineNeeded, fencePending, republishPending;
		long revision;
		boolean terminal;
		@Nullable PublicationObservation last;
		@Nullable InventoryPublicationEvidence publicationEvidence, deniedEvidence;
		InventorySharedRead.Check deniedCheck = InventorySharedRead.Check.VALID;
		long checkedTick = -1;
		/** Completed idle source checks stay on their own preview period; deferred checks stay overdue. */
		boolean healthChecked;
		long lastHealthCheckTick;
		Preview(PreviewRequest parent, InventorySourceInput input, InventoryRuntime.Consumer consumer,
			RetainedMemoryLedger.Ticket memory, SyncPublisher.Context context) {
			this.parent = parent; this.request = parent.request; this.frozen = parent.frozen; this.input = input; this.consumer = consumer; this.memory = memory; this.context = context;
		}
	}
	private static final class Recipient {
		final InventoryBudgetDelayState publicationDelay = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		final SessionAccess access;
		final SyncPublisher.Context context;
		long resyncAt;
		boolean folded;
		boolean bootstrapOffered;
		boolean rebaselineNeeded, displayDirty;
		Recipient(SessionAccess access, SyncPublisher.Context context) { this.access = access; this.context = context; }
	}
	private static final class Tracking {
		final InventoryBudgetDelayState projectionDelay = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		final InventoryBudgetDelayState registrationDelay = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		/** Last observed canonical record; authorization is always re-read from the live store. */
		ServerMarker marker;
		final InventorySourceInput input;
		final InventorySelection selection;
		final InventoryRuntime.Consumer consumer;
		final RetainedMemoryLedger.Ticket memory;
		final Map<UUID, Recipient> recipients = new LinkedHashMap<>();
		/** Capped presentation of the selected variant, separate from its original selected identity. */
		@Nullable InventoryResourceValue selectionPresentation;
		@Nullable RetainedMemoryLedger.Ticket presentationMemory;
		/** One detached selected value, never capture evidence or a scan accumulator. */
		@Nullable Bootstrap bootstrap;
		@Nullable InventoryCreationSnapshot creationSnapshot;
		@Nullable RetainedMemoryLedger.Ticket creationMemory;
		@Nullable InventoryPublicationEvidence creationEvidence;
		boolean creationClosed;
		/** Frozen-audience-sized denial index prepaid by admission, separate from lane-policy recovery. */
		final Set<UUID> creationDenied = new HashSet<>();
		final Map<UUID, InventoryRuntime.PublicationGrant> initialGrants = new LinkedHashMap<>();
		InventoryS2CPacket.Status bootstrapLifecycle = InventoryS2CPacket.Status.UPDATING;
		int presentationCeiling;
		long count, total, completedCount, completedTotal, revision, state = 1, nextSweep;
		boolean completedFolded;
		boolean folded, invalid, scanning = true, audienceDemand, recomputingScope;
		@Nullable PublicationObservation completed;
		@Nullable InventoryPublicationEvidence completedEvidence, bootstrapEvidence, deniedEvidence;
		InventorySharedRead.Check deniedCheck = InventorySharedRead.Check.VALID;
		long checkedTick = -1;
		/** Completed idle source checks stay on their own tracking period; deferred checks stay overdue. */
		boolean healthChecked;
		long lastHealthCheckTick;
		Tracking(ServerMarker marker, InventorySourceInput input, InventorySelection selection,
			InventoryRuntime.Consumer consumer, RetainedMemoryLedger.Ticket memory, int displayCeiling, long bootstrapCount) {
			this.marker = marker; this.input = input; this.selection = selection; this.consumer = consumer; this.memory = memory;
			bootstrap = new Bootstrap(bootstrapCount); revision = 1;
			folded = selection.aggregate();
			presentationCeiling = displayCeiling;
			if (selection.displayJson() != null) {
				selectionPresentation = InventoryResourceCodec.value(selection.aggregate() ? new InventoryScanner.Key(selection.kind(), selection.itemId(), "") : selection.exact(),
					0, selection.label(), selection.displayJson(), selection.aggregate());
				// The retained projection is only known compliant at its own byte
				// size: a selection admitted under a higher ceiling must still be
				// reprojected instead of being marked compliant for a lower one.
				presentationCeiling = (int) Math.max(displayCeiling, utf8Bytes(selection.displayJson()));
			}
		}
	}
	@org.jetbrains.annotations.NotNullByDefault
	private record Bootstrap(long count) {}
	/** Retained publication metadata stays paired with its own sweep evidence through repair and reprojection. */
	@org.jetbrains.annotations.NotNullByDefault
	private record PublicationObservation(CaptureResult result, InventoryStep.SweepEvidence evidence) {}
	private final Host host;
	private final InventoryRuntime runtime;
	private final InventorySyncPublisher publisher;
	private final BudgetDelaySink sink;
	private boolean cyclesInitialized;
	private long previewCycle, trackingCycle, monitoringTick = -1;
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
	private long publicationOpportunityTick = -1;
	private InventoryRuntime.@Nullable PublicationGrant publicationOpportunity;
	private SyncPublisher.@Nullable Context publicationOpportunityContext;
	private boolean publishing, closeRequested;
	private final Set<MarkerId> deferredRemovals = new HashSet<>();
	private final Set<UUID> deferredDisconnects = new HashSet<>();
	private final InventoryPeriodClock previewClock = new InventoryPeriodClock();
	/** Package-private test seam; production leaves it null. No global counter or logging. */
	private @Nullable java.util.function.IntConsumer encodedFragmentObserver;

	public InventoryBackend(Host host, InventoryRuntime runtime, long epochSeed) {
		this(host, runtime, epochSeed, BudgetDelaySink.NONE);
	}
	public InventoryBackend(Host host, InventoryRuntime runtime, long epochSeed, BudgetDelaySink sink) {
		this.host = host; this.runtime = runtime; this.epochSeed = epochSeed;
		this.sink = sink == null ? BudgetDelaySink.NONE : sink;
		publisher = new InventorySyncPublisher(runtime.memory(), new InventorySyncPublisher.Transport() {
			@Override public boolean authorized(SyncPublisher.Context context) { return publicationAllowed(context); }
			@Override public boolean authorizedControl(UUID recipient, InventoryS2CPacket packet) {
				@Nullable Session session = sessions.get(recipient);
				@Nullable SessionAccess access = currentSessionAccess(recipient);
				return session != null && access != null && session.epoch == packet.epoch()
					&& access.epoch() == packet.presentationEpoch() && access.view() == packet.view()
					&& (packet.kind() != InventoryS2CPacket.Kind.POLICY
						|| session.pendingPolicies.get(packet.resourceKind()) == packet && currentPolicyMatches(recipient, packet,
							session.pendingPolicyRevisions.getOrDefault(packet.resourceKind(), -1L)))
					&& (packet.kind() != InventoryS2CPacket.Kind.OPENED || session.pendingOpened.get(packet.requestId()) == packet);
			}
			@Override public boolean admittedToSend(SyncPublisher.Context context) {
				return publicationAllowed(context) && previewOpened(context); // waiting for OPENED defers, never revokes the context
			}
			@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
				return authorizeSend(context, packet, evidence);
			}
			@Override public int encodedBytes(InventoryS2CPacket packet) { return host.encodedBytes(packet); }
			@Override public int encodedBytes(InventoryS2CPacket packet, int displayBytes, int frameBytes) { return host.encodedBytes(packet, displayBytes, frameBytes); }
			@Override public void send(UUID recipient, InventoryS2CPacket packet) { host.send(recipient, packet); }
		}, this.sink);
		publisher.setControlHandoff(this::controlHandoffAccepted);
	}
	public InventoryRuntime runtime() { return runtime; }
	/** Test-only observation of actual fan-out encoding; no production caller sets this. */
	void encodedFragmentProbe(@Nullable java.util.function.IntConsumer probe) { encodedFragmentObserver = probe; }
	/**
	 * Pure sidecar query for the atomic-initial receipt projection: the marker
	 * owns an active committed inventory selection, so its content receipt is
	 * the dedicated inventory kind. No selection value, count, or annotation is
	 * disclosed here, and no second metadata map is introduced.
	 */
	public boolean tracksInventory(MarkerId marker) {
		Tracking lease = marker == null ? null : tracking.get(marker);
		@Nullable ServerMarker canonical = marker == null ? null : host.activeMarker(marker).orElse(null);
		return lease != null && canonical != null && canonical.expiresAtTick() > tick;
	}
	public Optional<InventoryResourceKind> trackedKind(MarkerId marker) {
		@Nullable Tracking lease = tracking.get(marker);
		@Nullable ServerMarker canonical = marker == null ? null : host.activeMarker(marker).orElse(null);
		return lease == null || canonical == null || canonical.expiresAtTick() <= tick ? Optional.empty() : Optional.of(lease.selection.kind());
	}
	/** Immutable selected creation history, independent of Bootstrap and completed Tracking. */
	public Optional<InventoryCreationSnapshot> creationSnapshot(MarkerId marker) {
		@Nullable Tracking lease = tracking.get(marker);
		@Nullable ServerMarker canonical = marker == null ? null : host.activeMarker(marker).orElse(null);
		return lease == null || canonical == null || canonical.expiresAtTick() <= tick || lease.creationClosed
			? Optional.empty() : Optional.ofNullable(lease.creationSnapshot);
	}
	@org.jetbrains.annotations.NotNullByDefault
	public enum InitialDecision { READY, DEFERRED, SUPPRESSED }
	/** A paid one-shot source check, held through encoding and checked at the final transport handoff. */
	@org.jetbrains.annotations.NotNullByDefault
	public final class InitialPublication implements AutoCloseable {
		private final Tracking lease;
		private final UUID recipient;
		private final long epoch, view;
		private final InventoryRuntime.PublicationGrant source;
		private final InventorySyncPublisher.InitialStaging staging;
		private boolean closed;
		private InitialPublication(Tracking lease, UUID recipient, long epoch, long view,
			InventoryRuntime.PublicationGrant source, InventorySyncPublisher.InitialStaging staging) {
			this.lease = lease; this.recipient = recipient; this.epoch = epoch; this.view = view; this.source = source; this.staging = staging;
			publishing = true;
		}
		public InitialDecision beforeHandoff() {
			if (closed || !creationAllowed(lease, recipient, epoch, view)) return InitialDecision.SUPPRESSED;
			var check = source.validate();
			if (check == InventorySharedRead.Check.DEFERRED) return InitialDecision.DEFERRED;
			if (check != InventorySharedRead.Check.VALID) {
				lease.creationClosed = true;
				return InitialDecision.SUPPRESSED;
			}
			return creationAllowed(lease, recipient, epoch, view) ? InitialDecision.READY : InitialDecision.SUPPRESSED;
		}
		public void settle(long wireBytes, long workspace) { staging.settle(wireBytes, workspace); }
		@Override public void close() {
			if (closed) return;
			closed = true;
			try { source.close(); }
			finally {
				try { staging.close(); }
				finally {
					finishInitialPublication();
				}
			}
		}
	}
	private void finishInitialPublication() {
		publishing = false;
		for (MarkerId marker : Set.copyOf(deferredRemovals)) remove(marker); deferredRemovals.clear();
		for (UUID player : Set.copyOf(deferredDisconnects)) disconnect(player); deferredDisconnects.clear();
		if (closeRequested) close();
	}
	/** Marker/audience lifetime only; a frozen lane denial still permits an admitted suppressed initial. */
	public boolean initialAudienceActive(MarkerId marker, UUID recipient) {
		@Nullable Tracking lease = tracking.get(marker);
		@Nullable ServerMarker canonical = host.activeMarker(marker).orElse(null);
		return lease != null && canonical != null && !closeRequested && !deferredRemovals.contains(marker) && !deferredDisconnects.contains(recipient)
			&& !deferredDisconnects.contains(canonical.owner()) && canonicalAudienceActive(canonical, recipient, tick);
	}
	/** Canonical lifetime survives sidecar-only cleanup but never actual marker or audience removal. */
	public boolean initialCanonicalAudienceActive(MarkerId marker, UUID recipient, long now) {
		return canonicalAudienceActive(host.activeMarker(marker).orElse(null), recipient, now);
	}
	private static boolean canonicalAudienceActive(@Nullable ServerMarker marker, UUID recipient, long now) {
		return marker != null && marker.expiresAtTick() > now && marker.recipients().contains(recipient);
	}
	private boolean creationAllowed(Tracking lease, UUID recipient, long epoch, long view) {
		@Nullable ServerMarker marker = host.activeMarker(lease.marker.id()).orElse(null);
		@Nullable SessionAccess access = currentSessionAccess(recipient);
		return marker != null && tracking.get(lease.marker.id()) == lease && !closeRequested && !lease.creationClosed
			&& !lease.invalid && !lease.recomputingScope && !lease.consumer.invalidated() && lease.deniedEvidence == null
			&& !deferredRemovals.contains(lease.marker.id()) && !deferredDisconnects.contains(marker.owner())
			&& !deferredDisconnects.contains(recipient) && !lease.creationDenied.contains(recipient) && marker.expiresAtTick() > tick
			&& marker.recipients().contains(recipient)
			&& inventoryAllowed(marker.authorization(), recipient, lease.selection.kind())
			&& access != null && access.epoch() == epoch && access.view() == view && access.compatibleKinds().contains(lease.selection.kind());
	}
	public InitialDecision creationDecision(MarkerId marker, UUID recipient, long epoch, long view) {
		@Nullable Tracking lease = tracking.get(marker);
		return lease != null && lease.creationSnapshot != null && creationAllowed(lease, recipient, epoch, view)
			? InitialDecision.READY : InitialDecision.SUPPRESSED;
	}
	public void suppressCreation(MarkerId marker, UUID recipient) {
		@Nullable Tracking lease = tracking.get(marker);
		@Nullable ServerMarker canonical = host.activeMarker(marker).orElse(null);
		if (lease != null && canonical != null && canonical.recipients().contains(recipient)) lease.creationDenied.add(recipient);
	}
	/** Missing admission is DEFERRED, never a permission denial. Does not require Basic-known or POLICY handoff. */
	public Optional<InitialPublication> reserveInitial(MarkerId marker, UUID recipient, long epoch, long view,
		long wireBytes, long encodingWorkspace) {
		if (publishing) return Optional.empty();
		@Nullable Tracking lease = tracking.get(marker);
		if (lease == null || !creationAllowed(lease, recipient, epoch, view) || lease.creationEvidence == null) return Optional.empty();
		var staging = publisher.reserveInitial(wireBytes, encodingWorkspace);
		if (staging.isEmpty()) return Optional.empty();
		@Nullable InventoryRuntime.PublicationGrant source = lease.initialGrants.remove(recipient);
		if (source == null) source = runtime.reservePublication(lease.creationEvidence).orElse(null);
		if (source == null) { staging.get().close(); return Optional.empty(); }
		if (!runtime.claimDetachedItemWork(encodingWorkspace)) { source.close(); staging.get().close(); return Optional.empty(); }
		return Optional.of(new InitialPublication(lease, recipient, epoch, view, source, staging.get()));
	}
	/** Suppressed inventory initials still pay construction and transport workspace, but disclose no source data. */
	public Optional<InitialEnvelope> reserveInitialEnvelope(long wireBytes, long workspace) {
		if (publishing) return Optional.empty();
		var held = publisher.reserveInitial(wireBytes, workspace);
		if (held.isEmpty()) return Optional.empty();
		if (!runtime.claimDetachedItemWork(workspace)) { held.get().close(); return Optional.empty(); }
		publishing = true;
		return Optional.of(new InitialEnvelope(held.get()));
	}
	@org.jetbrains.annotations.NotNullByDefault
	public final class InitialEnvelope implements AutoCloseable {
		private final InventorySyncPublisher.InitialStaging staging;
		private boolean closed;
		private InitialEnvelope(InventorySyncPublisher.InitialStaging staging) { this.staging = staging; }
		public void settle(long wireBytes, long workspace) { staging.settle(wireBytes, workspace); }
		@Override public void close() { if (!closed) { closed = true; try { staging.close(); } finally { finishInitialPublication(); } } }
	}
	private void advance(long now, InventorySettings settings) {
		if (now != tick) closePublicationOpportunity(); // close against the OLD ledger before Runtime resets it
		tick = now; this.settings = settings; runtime.advance(now, settings); publisher.advance(now, settings);
		if (publicationOpportunityTick != now) {
			publicationOpportunityTick = now;
			var pending = publisher.pendingValidation(); // metadata only; precedes reconfiguration, ingress and all source work
			if (pending.isPresent()) {
				publicationOpportunityContext = pending.get().context();
				publicationOpportunity = runtime.reservePublication(pending.get().evidence()).orElse(null);
			}
		}
		// Clocks change without producer observations. Close the actual old open
		// buffers before any new-period reprojection or ingress can report work.
		long preview = runtime.previewPeriodId(), tracked = runtime.trackingPeriodId();
		if (cyclesInitialized) {
			if (preview != previewCycle) endCycle(Module.INVENTORY_PREVIEW, previewCycle);
			if (tracked != trackingCycle) endCycle(Module.INVENTORY_TRACKING, trackingCycle);
		}
		previewCycle = preview; trackingCycle = tracked; cyclesInitialized = true;
		if (monitoringTick != now) {
			monitoringTick = now;
			for (Session session : sessions.values()) for (Preview item : session.previews.values()) {
				item.samplingDelay.beginTick(preview); item.projectionDelay.beginTick(preview); item.publicationDelay.beginTick(preview);
			}
			for (Tracking lease : tracking.values()) {
				lease.projectionDelay.beginTick(tracked); lease.registrationDelay.beginTick(tracked);
				for (Recipient recipient : lease.recipients.values()) recipient.publicationDelay.beginTick(tracked);
			}
		}
		if (previewClock.advance(now, settings.getPreview().getPeriodTicks())) {
			previewVariants.clear(); previewVariantClaims.clear(); variantMemory.values().forEach(RetainedMemoryLedger.Ticket::close); variantMemory.clear();
		}
		// Reconfiguration can observe resource refusals even on ingress. Retire
		// expired work before it, using the marker's existing hard deadline only.
		for (Tracking lease : List.copyOf(tracking.values())) if (lease.marker.expiresAtTick() <= now) remove(lease.marker.id());
		trackEffectiveSizePair();
		processReconfigurations();
	}
	private void closePublicationOpportunity() {
		if (publicationOpportunity != null) { publicationOpportunity.close(); publicationOpportunity = null; }
		publicationOpportunityContext = null;
	}
	private InventorySyncPublisher.SendDecision authorizeSend(SyncPublisher.Context context, InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
		var hook = host.beforeSend(context, packet, evidence);
		if (hook != InventorySyncPublisher.SendDecision.ALLOW) return hook;
		if (packet.kind() == InventoryS2CPacket.Kind.OFFER || packet.kind() == InventoryS2CPacket.Kind.POLICY
			|| packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT
			|| packet.kind() == InventoryS2CPacket.Kind.OPENED) {
			@Nullable Session session = sessions.get(context.recipient());
			@Nullable SessionAccess access = currentSessionAccess(context.recipient());
			return session != null && access != null && !closeRequested && !deferredDisconnects.contains(context.recipient())
				&& session.epoch == packet.epoch() && access.epoch() == packet.presentationEpoch() && access.view() == packet.view()
				&& (packet.kind() != InventoryS2CPacket.Kind.POLICY
					|| session.pendingPolicies.get(packet.resourceKind()) == packet && currentPolicyMatches(context.recipient(), packet,
						session.pendingPolicyRevisions.getOrDefault(packet.resourceKind(), -1L)))
				&& (packet.kind() != InventoryS2CPacket.Kind.OPENED || session.pendingOpened.get(packet.requestId()) == packet)
				? InventorySyncPublisher.SendDecision.ALLOW : InventorySyncPublisher.SendDecision.DENY;
		}
		if (!publicationAllowed(context)) return InventorySyncPublisher.SendDecision.DENY;
		if (!previewOpened(context)) return InventorySyncPublisher.SendDecision.DEFER;
		if (!InventorySyncPublisher.requiresSource(packet)) return InventorySyncPublisher.SendDecision.ALLOW;
		if (evidence == null) return InventorySyncPublisher.SendDecision.DEFER;
		@Nullable Preview preview = null; @Nullable Tracking lease = null;
		@Nullable Session session = sessions.get(context.recipient());
		if (session != null) for (Preview candidate : session.previews.values()) if (candidate.context.equals(context)) preview = candidate;
		for (Tracking candidate : tracking.values()) {
			@Nullable Recipient recipient = candidate.recipients.get(context.recipient());
			if (recipient != null && recipient.context.equals(context)) lease = candidate;
		}
		if (preview != null && (preview.deniedEvidence != null || preview.consumer.invalidated())
			|| lease != null && (lease.deniedEvidence != null || lease.invalid || lease.consumer.invalidated())) return InventorySyncPublisher.SendDecision.DENY;
		InventoryRuntime.@Nullable PublicationGrant grant = null;
		boolean protectedOpportunity = false;
		if (context.equals(publicationOpportunityContext) && publicationOpportunity != null && publicationOpportunity.forEvidence(evidence)) {
			grant = publicationOpportunity; publicationOpportunityContext = null; protectedOpportunity = true;
		} else grant = runtime.reservePublication(evidence).orElse(null);
		if (grant == null) return InventorySyncPublisher.SendDecision.DEFER;
		InventorySharedRead.Check check;
		try { check = grant.validate(evidence); }
		finally { if (!protectedOpportunity) grant.close(); } // an old selected proof is released AFTER drain, never between the new proof and handoff
		if (check == InventorySharedRead.Check.VALID) {
			// Source metadata callbacks can revoke a viewer without removing the marker or calling this Backend.
			// This recipient-only fence follows the proof and precedes all wire accounting/handoff.
			@Nullable Session current = sessions.get(context.recipient());
			return publicationAllowed(context) && current != null && current.epoch == packet.epoch()
				&& !closeRequested && !deferredDisconnects.contains(context.recipient())
				&& (lease == null || !deferredRemovals.contains(lease.marker.id()) && !deferredDisconnects.contains(lease.marker.owner()))
				? InventorySyncPublisher.SendDecision.ALLOW : InventorySyncPublisher.SendDecision.DENY;
		}
		if (check == InventorySharedRead.Check.DEFERRED) return InventorySyncPublisher.SendDecision.DEFER;
		if (lease != null && evidence.scopeTransition()) {
			lease.deniedEvidence = evidence.retain(); lease.deniedCheck = check;
			return InventorySyncPublisher.SendDecision.DENY;
		}
		publisher.denyEvidence(evidence, check != InventorySharedRead.Check.AUTHORITY_DENIED);
		if (check == InventorySharedRead.Check.AUTHORITY_DENIED) {
			@Nullable InventorySourceInput denied = lease != null ? lease.input : preview != null ? preview.input : null;
			if (denied != null) latchAuthorityFailure(denied, evidence);
			return InventorySyncPublisher.SendDecision.DENY;
		}
		if (preview != null) { preview.deniedEvidence = evidence.retain(); preview.deniedCheck = check; }
		if (lease != null) { lease.deniedEvidence = evidence.retain(); lease.deniedCheck = check; }
		return InventorySyncPublisher.SendDecision.DENY;
	}
	/** A typed owner denial fences every current proof for that exact binding, without closing anything inside drain. */
	private void latchAuthorityFailure(InventorySourceInput input, InventoryPublicationEvidence evidence) {
		for (Tracking affected : tracking.values()) if (affected.input.sameBinding(input)) {
			if (affected.deniedEvidence == null) affected.deniedEvidence = evidence.retain();
			affected.deniedCheck = InventorySharedRead.Check.AUTHORITY_DENIED;
		}
		for (Session session : sessions.values()) for (Preview affected : session.previews.values()) if (affected.input.sameBinding(input)) {
			if (affected.deniedEvidence == null) affected.deniedEvidence = evidence.retain();
			affected.deniedCheck = InventorySharedRead.Check.AUTHORITY_DENIED;
		}
	}
	private boolean applyPublicationFailures() {
		boolean changed = false;
		for (Tracking lease : List.copyOf(tracking.values())) if (lease.deniedEvidence != null) {
			var evidence = lease.deniedEvidence; lease.deniedEvidence = null;
			changed = true;
			if (evidence.scopeTransition()) { try { recomputeScope(lease); } finally { evidence.close(); } continue; }
			try { applyPublicationFailure(lease.consumer, evidence, lease.deniedCheck); } finally { evidence.close(); }
		}
		for (Session session : sessions.values()) for (Preview preview : session.previews.values()) if (preview.deniedEvidence != null) {
			var evidence = preview.deniedEvidence; preview.deniedEvidence = null;
			changed = true;
			try { applyPublicationFailure(preview.consumer, evidence, preview.deniedCheck); invalidatePreview(session, preview); } finally { evidence.close(); }
		}
		for (Tracking lease : tracking.values()) if (lease.consumer.invalidated() && !lease.invalid) { invalidate(lease); changed = true; }
		for (Session session : sessions.values()) for (Preview preview : session.previews.values())
			if ((preview.consumer.invalidated() || publisher.sourceDenied(preview.context)) && preview.context.stateFence() == 1) {
				invalidatePreview(session, preview); changed = true;
			}
		return changed;
	}
	/** Propagate a failed historical cut before its origin is withdrawn, independently of its capture domain. */
	private void applyPublicationFailure(InventoryRuntime.Consumer consumer, InventoryPublicationEvidence evidence, InventorySharedRead.Check check) {
		runtime.publicationFailed(consumer, evidence, check);
		for (Tracking affected : List.copyOf(tracking.values())) {
			if (affected.consumer.invalidated()) { invalidate(affected); continue; }
			if (check == InventorySharedRead.Check.AUTHORITY_DENIED) continue;
			boolean completedFailed = matchingEvidence(affected.completedEvidence, evidence);
			boolean bootstrapFailed = matchingEvidence(affected.bootstrapEvidence, evidence);
			if (completedFailed) {
				affected.completed = null;
				if (affected.completedEvidence != null) { affected.completedEvidence.close(); affected.completedEvidence = null; }
			}
			if (bootstrapFailed) {
				affected.bootstrap = null;
				if (affected.bootstrapEvidence != null) { affected.bootstrapEvidence.close(); affected.bootstrapEvidence = null; }
			}
			if (completedFailed || bootstrapFailed || affected.recipients.values().stream().anyMatch(recipient -> publisher.sourceDenied(recipient.context))) {
				// Fence historical fragments/digests, not a differently proven current scan or completion.
				// New registration starts with an empty recovery fence and restages only retained valid evidence.
				affected.state++; resetRecipients(affected);
			}
		}
	}
	private static boolean matchingEvidence(@Nullable InventoryPublicationEvidence held, InventoryPublicationEvidence failed) {
		return held != null && (held.sameProof(failed) || failed.sameProof(held));
	}
	private void endCycle(Module module, long cycle) {
		if (sink == BudgetDelaySink.NONE) return;
		try { sink.endCycle(module, cycle, tick); }
		catch (RuntimeException | LinkageError ignored) { /* diagnostic isolation */ }
	}
	private void delay(InventoryBudgetDelayState stage, Module module, long id, String name, int reasons) {
		stage.observe(sink, module == Module.INVENTORY_PREVIEW ? previewCycle : trackingCycle, tick, reasons,
			() -> new WorkItem(module.name() + "/" + id + "/" + name, "Inventory " + id + " " + name), Quantity::unknown);
	}
	private void previewDelay(Preview preview, InventoryBudgetDelayState stage, String name, int reasons) {
		delay(stage, Module.INVENTORY_PREVIEW, preview.context.baseline(), name, reasons);
	}
	private void trackingDelay(Tracking lease, InventoryBudgetDelayState stage, String name, int reasons) {
		delay(stage, Module.INVENTORY_TRACKING, lease.marker.id().value(), name, reasons);
	}
	private void reportState(Module module) {
		if (sink == BudgetDelaySink.NONE) return;
		boolean delayed = runtime.delayed(module) || publisher.delayed(module);
		boolean eligible = runtime.eligible(module) || publisher.eligible(module), live = false;
		if (module == Module.INVENTORY_PREVIEW) {
			for (Session session : sessions.values()) for (Preview preview : session.previews.values()) {
				live |= preview.context.stateFence() == 1;
				delayed |= preview.samplingDelay.outstanding() || preview.projectionDelay.outstanding() || preview.publicationDelay.outstanding();
				eligible |= preview.samplingDelay.eligible() || preview.projectionDelay.eligible() || preview.publicationDelay.eligible();
			}
		} else for (Tracking lease : tracking.values()) {
			if (!lease.audienceDemand) continue;
			live = true;
			delayed |= lease.projectionDelay.outstanding() || lease.registrationDelay.outstanding();
			eligible |= lease.projectionDelay.eligible() || lease.registrationDelay.eligible();
			for (Recipient recipient : lease.recipients.values()) {
				delayed |= recipient.publicationDelay.outstanding(); eligible |= recipient.publicationDelay.eligible();
			}
		}
		DelayState state = delayed ? DelayState.DELAYED : eligible ? DelayState.HEALTHY : live ? DelayState.NEUTRAL : DelayState.IDLE;
		try { sink.state(module, module == Module.INVENTORY_PREVIEW ? previewCycle : trackingCycle, tick, state); }
		catch (RuntimeException | LinkageError ignored) { /* diagnostic isolation */ }
	}
	public void handle(UUID player, InventoryC2SPacket packet, long now, InventorySettings settings) {
		// Network ingress is normally serialized by the server. A host callback may reenter synchronously;
		// it cannot mutate publisher/source ownership while a proof or immutable part is being handed off.
		if (publishing) return;
		advance(now, settings);
		if (packet == null || packet.isCorrupt()) return;
		if (packet.kind() == InventoryC2SPacket.Kind.HELLO) {
			hello(player);
			publisher.drainControls();
			// An accepted OFFER queues the deferred initial POLICY during the
			// first drain; the second drain preserves OFFER-before-POLICY order.
			publisher.drainControls();
			return;
		}
		@Nullable Session session = sessions.get(player);
		if (session == null) return;
		refreshSession(player, session);
		// Request IDs never repeat within an inventory session. A same-epoch
		// view rebuild retains their ownership, so an in-flight cancellation may
		// release its request even before the client receives the replacement view.
		boolean cancellation = packet.kind() == InventoryC2SPacket.Kind.CLOSE && session.requests.containsKey(packet.requestId());
		if (session.access == null || packet.epoch() != session.epoch || packet.presentationEpoch() != session.access.epoch()
			|| packet.view() != session.access.view() && (!cancellation || packet.view() > session.access.view())) { publisher.drainControls(); return; }
		switch (packet.kind()) {
			case OPEN -> open(player, session, packet);
			case CLOSE -> closePreview(session, packet.requestId());
			case SELECT -> select(player, session, packet);
			case RESYNC -> resync(player, java.util.Objects.requireNonNull(packet.markerId()), packet.resourceKind());
			case HELLO -> {}
		}
		publisher.drainControls();
		// OPENED has now been handed off, so this request's lanes may be registered.
		// Registration performs no read; sampling still waits for the next tick.
		openHandedOffLanes(player, session);
	}
	private void openHandedOffLanes(UUID player, Session session) {
		if (session.access == null) return;
		for (PreviewRequest parent : session.requests.values()) for (var kind : InventoryResourceKind.values())
			if (parent.attempted == 0 && parent.openedHandedOff && (parent.laneMask & (1 << kind.wireId())) != 0
				&& session.access.compatibleKinds().contains(kind)
				&& !session.previews.containsKey(new LaneKey(parent.request, kind))) openLane(player, session, parent, kind);
	}
	private void hello(UUID player) {
		@Nullable SessionAccess access = currentSessionAccess(player);
		if (access == null) return; // presentation session/schema must be ready first
		@Nullable Session session = sessions.get(player);
		if (session == null) {
			if (sessions.size() >= MAX_SESSIONS) return;
			var held = runtime.memory().tryReserve(SESSION_BYTES);
			if (held.isEmpty()) return;
			held.get().commit(SESSION_BYTES);
			session = new Session(++epochSeed, player, access, null, held.get()); sessions.put(player, session);
		}
		// HELLO is one explicit hint demand. refreshSession then consumes the
		// cached snapshot instead of making a second full permission pass.
		host.policyDemand(player);
		refreshSession(player, session);
		// The control queue is ordered, but a deferred OFFER must never let a
		// later POLICY cross it. New sessions wait for the OFFER handoff.
		enqueueOffer(player, session);
		if (session.policy != null) {
			for (var kind : InventoryResourceKind.values()) queuePolicy(player, session, kind);
		}
	}
	private void queuePolicy(UUID player, Session session, InventoryResourceKind kind) {
		@Nullable Policy policy = session.policy;
		if (policy == null) return;
		InventoryS2CPacket packet = InventoryS2CPacket.policy(kind, session.epoch, policy.epoch(), policy.view(), policy.types(kind));
		if (packet.equals(session.pendingPolicies.get(kind))
			&& java.util.Objects.equals(session.pendingPolicyRevisions.get(kind), policy.revision())) return;
		session.pendingPolicies.put(kind, packet);
		session.pendingPolicyRevisions.put(kind, policy.revision());
		// Admission can later be fresh-fenced away. Only this exact desired packet's
		// successful, still-current transport handoff completes the lane update.
		if (!session.policyAwaitingOffer) publisher.control(player, packet);
	}
	private void queueOpened(UUID player, Session session, PreviewRequest parent) {
		InventoryS2CPacket packet = InventoryS2CPacket.opened(session.epoch, session.access == null ? 0 : session.access.epoch(),
			session.access == null ? 0 : session.access.view(), parent.request, parent.laneMask);
		parent.openedHandedOff = false;
		session.pendingOpened.put(parent.request, packet);
		publisher.control(player, packet);
	}
	/**
	 * Current presentation session/schema is a live delivery gate; a policy change
	 * is only a future-input hint. A same-epoch view rebuild keeps every frozen
	 * entitlement, Preview request and Tracking recipient while replacing only
	 * the transport context and baseline fences.
	 */
	private void refreshSession(UUID player, Session session) {
		@Nullable SessionAccess nextAccess = currentSessionAccess(player);
		@Nullable Policy nextPolicy = host.policy(player).orElse(null);
		if (nextAccess == null) {
			for (long request : List.copyOf(session.requests.keySet())) closePreview(session, request);
			cancelTrackingRecipients(player);
			cancelPendingControls(player, session);
			session.access = null;
			updatePolicyHint(player, session, nextPolicy);
			return;
		}
		// A POLICY update is a future-input hint; it never prunes an existing
		// Preview lane, Tracking recipient, seed or committed outcome.
		updatePolicyHint(player, session, nextPolicy);
		SessionAccess previous = session.access;
		if (previous != null && previous.epoch() == nextAccess.epoch() && previous.view() == nextAccess.view()
			&& previous.compatibleKinds().equals(nextAccess.compatibleKinds())) return;
		boolean trueEpoch = previous != null && previous.epoch() != nextAccess.epoch();
		session.access = nextAccess;
		cancelPendingControls(player, session);
		if (trueEpoch) {
			// A true presentation epoch resets the client's complete inventory
			// view. Drop request identities and recipient contexts so a later
			// Basic replay re-registers from canonical state; never infer from
			// the previous epoch's cache.
			for (long request : List.copyOf(session.requests.keySet())) closePreview(session, request);
			cancelTrackingRecipients(player);
		} else {
			for (var entry : List.copyOf(session.previews.entrySet())) {
				Preview preview = entry.getValue();
				if (!nextAccess.compatibleKinds().contains(preview.input.resourceKind())) closeLane(session, entry.getKey());
				else rebaselinePreview(session, preview);
			}
			for (Tracking lease : List.copyOf(tracking.values())) {
				@Nullable ServerMarker canonical = host.activeMarker(lease.marker.id()).orElse(null);
				if (canonical == null) continue;
				Recipient current = lease.recipients.get(player);
				if (current == null) continue;
				// Only this player's session is being rebuilt. Never touch another
				// recipient's context, baseline or resync cooldown.
				if (!nextAccess.compatibleKinds().contains(lease.selection.kind())
					|| !inventoryAllowed(canonical.authorization(), player, lease.selection.kind())
					|| !canonical.recipients().contains(player) || !host.knows(player, lease.marker.id())) {
					publisher.cancel(current.context); lease.recipients.remove(player);
					continue;
				}
				rebaselineTracking(player, lease, current);
			}
		}
		for (Tracking lease : tracking.values()) lease.creationDenied.remove(player);
		for (PreviewRequest parent : session.requests.values()) queueOpened(player, session, parent);
		for (CommitResult result : session.commits.values()) {
			result.queued = false;
			// A refusal belongs to the request's old presentation view; it is not a
			// committed outcome and must not be restamped into the replacement view.
			if (!result.selected()) result.pending = false;
		}
		session.offer = null; session.handoffOffer = null; session.offerHandedOff = false;
		if (session.policy != null)
			for (var kind : InventoryResourceKind.values()) queuePolicy(player, session, kind);
	}
	private void updatePolicyHint(UUID player, Session session, @Nullable Policy next) {
		@Nullable Policy previous = session.policy;
		if (java.util.Objects.equals(previous, next)) return;
		session.policy = next;
		if (next == null) {
			for (var entry : List.copyOf(session.pendingPolicies.entrySet())) {
				publisher.cancelControl(player, entry.getValue());
				session.pendingPolicies.remove(entry.getKey());
			}
			session.pendingPolicyRevisions.clear();
			return;
		}
		for (var kind : InventoryResourceKind.values()) {
			if (previous == null || !previous.types(kind).equals(next.types(kind))) {
				queuePolicy(player, session, kind);
			} else if (session.pendingPolicies.containsKey(kind)) {
				// Same advertised content under a newer explicit snapshot: the
				// queued packet remains a correct hint, so adopt the new revision
				// without spending another control handoff.
				session.pendingPolicyRevisions.put(kind, next.revision());
			}
		}
	}
	private void cancelPendingControls(UUID player, Session session) {
		publisher.cancelControls(player);
		session.pendingPolicies.clear();
		session.pendingPolicyRevisions.clear();
		session.pendingOpened.clear();
		for (PreviewRequest parent : session.requests.values()) parent.openedHandedOff = false;
		for (CommitResult result : session.commits.values()) result.queued = false;
	}
	private void cancelTrackingRecipients(UUID player) {
		for (Tracking lease : tracking.values()) {
			Recipient recipient = lease.recipients.remove(player);
			if (recipient != null) publisher.cancel(recipient.context);
		}
	}
	private void open(UUID player, Session session, InventoryC2SPacket packet) {
		long request = packet.requestId();
		Target requested = packet.target();
		var face = packet.face();
		if (request <= session.lastRequest) {
			InventoryCaptureDiagnostics.previewOpen("request", "request-fence", request, requested, face);
			return;
		}
		if (session.requests.size() >= settings.getPreview().effectiveMaxTargetsPerClient()) {
			InventoryCaptureDiagnostics.previewOpen("capacity", "client-targets", request, requested, face);
			return;
		}
		session.lastRequest = request;
		var held = runtime.memory().tryReserve(4096);
		if (held.isEmpty()) {
			InventoryCaptureDiagnostics.previewOpen("memory", "request-ticket", request, requested, face);
			return;
		}
		boolean retained = false;
		try {
		boolean[] hostAttempted = {false};
		var admission = runtime.preflight(() -> {
			hostAttempted[0] = true;
			return host.open(player, requested);
		});
		if (admission.isEmpty()) {
			if (hostAttempted[0]) InventoryCaptureDiagnostics.previewOpen("host", "authority", request, requested, face);
			else InventoryCaptureDiagnostics.previewOpen("admission", "preflight-deferred", request, requested, face);
			return;
		}
		Opened frozen = admission.get();
		SessionAccess access = java.util.Objects.requireNonNull(session.access);
		// The one OPEN-time freeze is final for this request. Later permission or
		// configuration changes neither widen nor clear it; only current session
		// schema availability gates actual lane delivery.
		PreviewLaneGrant frozenGrant = host.freezeInventoryPreviewGrant(player, frozen.targetType());
		Set<InventoryResourceKind> frozenLanes = frozenGrant.lanes();
		int mask = laneMask(frozenLanes) & laneMask(access.compatibleKinds());
		held.get().commit(4096);
		var parent = new PreviewRequest(packet.requestId(), frozen, packet.face(), held.get(), mask);
		session.requests.put(parent.request, parent);
		retained = true;
		InventoryCaptureDiagnostics.previewOpen("opened", "request", request, frozen.target(), face);
		queueOpened(player, session, parent);
		// This actual-type result is already fresh. Fold it into the hint
		// without another all-type permission pass.
		Set<InventoryResourceKind> actual = new java.util.LinkedHashSet<>();
		for (var kind : InventoryResourceKind.values()) if ((mask & (1 << kind.wireId())) != 0) actual.add(kind);
		host.adoptPreviewPolicy(player, frozen.targetType(), new PreviewLaneGrant(Set.copyOf(actual), frozenGrant.token()));
		} finally {
			if (!retained) held.get().close();
		}
	}
	private void openLane(UUID player, Session session, PreviewRequest parent, InventoryResourceKind kind) {
		if (session.access == null || !session.access.compatibleKinds().contains(kind)) return;
		var held = runtime.memory().tryReserve(PREVIEW_BYTES);
		if (held.isEmpty()) {
			InventoryCaptureDiagnostics.previewLaneRejected("memory", "lane-ticket", parent.request, kind, parent.frozen.target(), parent.face);
			return;
		}
		InventoryRuntime.@Nullable Consumer attached = null;
		SyncPublisher.@Nullable Context registered = null;
		boolean retained = false;
		try {
			var input = new InventorySourceInput(parent.frozen.target(), player, parent.face, kind);
			attached = runtime.attach(input, new InventoryRuntime.PreviewSubject(player)).orElse(null);
			if (attached == null) {
				InventoryCaptureDiagnostics.previewLaneRejected("attach", "runtime-consumer", parent.request, kind, parent.frozen.target(), parent.face);
				return;
			}
			attached.requirePublicationEvidence();
			var context = context("preview/" + parent.request + "/" + kind, player, session.access, 1);
			if (!publisher.register(context, route(session, parent.request, null, null, null, kind))) {
				InventoryCaptureDiagnostics.previewLaneRejected("publisher", "register", parent.request, kind, parent.frozen.target(), parent.face);
				return;
			}
			registered = context; held.get().commit(PREVIEW_BYTES);
			var preview = new Preview(parent, input, attached, held.get(), context);
			preview.metadataCeiling = effectiveDisplayCeiling();
			session.previews.put(new LaneKey(parent.request, kind), preview); retained = true;
			InventoryCaptureDiagnostics.previewLaneOpened(parent.request, kind, parent.frozen.target(), parent.face);
		} finally { if (!retained) { if (registered != null) publisher.cancel(registered); try { if (attached != null) attached.close(); } finally { held.get().close(); } } }
	}
	private void select(UUID player, Session session, InventoryC2SPacket packet) {
		// A lane never offered by an existing OPEN cannot consume its shared
		// commit/replay fence. An unknown request still gets the normal correlated
		// rejection; an admitted lane's bad token is a shared failed attempt.
		Preview preview = session.previews.get(new LaneKey(packet.requestId(), packet.resourceKind()));
		CommitResult replay = session.commits.get(packet.commitId());
		if (replay != null) {
			if (replay.packet.resourceKind() == packet.resourceKind() && replay.packet.requestId() == packet.requestId()) {
				@Nullable SessionAccess access = currentSessionAccess(player);
				boolean samePresentationFence = access != null
					&& replay.packet.presentationEpoch() == access.epoch() && replay.packet.view() == access.view();
				// A refused, never-handed-off intent belongs to its original
				// presentation fence; only a committed selection may replay across a
				// same-epoch view rebuild.
				if (replay.selected() || samePresentationFence) {
					replay.pending = true; enqueueResult(player, session, replay);
				}
			}
			return;
		}
		if (packet.commitId() <= session.lastCommit) return;
		if (preview == null && session.requests.containsKey(packet.requestId())) return;
		session.lastCommit = packet.commitId();
		MarkerRejectReason rejection = MarkerRejectReason.INVALID_REQUEST;
		ServerMarker marker = null;
		if (preview != null && preview.parent.attempted == 0) {
			@Nullable InventorySelection ref = preview.references.get(packet.entryKey());
			// The data gate holds item frames but never SELECT, so an affected pending
			// cut must be rejected here: its retained display no longer fits the
			// current effective ceiling, and installing it would create a tracking
			// presentation the effective cap can never write. The unused attempt stays
			// with the preview so the fresh reference its replacement baseline carries
			// can still create the Ping; the commit id itself remains authoritative
			// through the recorded replay below.
			boolean staleCut = preview.retired.contains(packet.entryKey()) || supersededCut(ref);
			if (!staleCut) preview.parent.attempted = packet.commitId();
			if (!staleCut && ref != null && packet.baselineId() == preview.context.baseline() && packet.stateRevision() == preview.context.stateFence()) {
				Prepared prepared = new Prepared(preview, ref, packet.entryKey(), packet.pingType());
				PolicyToken createPolicyToken = host.policyToken(player);
				try {
					// The complete Prepared object carries both admission and the
					// authorization finalizer; a method reference would drop it.
					Created outcome = host.create(player, preview.frozen, preview.input.resourceKind(), prepared);
					rejection = outcome.rejection(); marker = outcome.marker();
					if (marker != null) {
						// Storage is committed: install the sidecar, then publish the
						// existing CREATED/winner changes, then attach the recipients
						// the sidecar already reserved. A publication failure after
						// commit is never reported as a retryable rejection.
						prepared.commit(marker);
						// The create already froze this actual lane under current
						// authority. Fold that result into the future-input hint
						// without another all-type permission pass.
						host.adoptCreatePolicy(player, preview.frozen.targetType(), preview.input.resourceKind(), true,
							createPolicyToken);
						try { host.publishCreated(outcome); }
						catch (RuntimeException | LinkageError publicationFailure) {
							nx.pingwheel.common.diagnostics.Diagnostics.server().atNormal().rateLimited(PUBLISH_CREATED).error("inventory/publish-created", "committed-marker-delivery-failed", publicationFailure,
								() -> "committed marker=" + outcome.marker().id() + " request=" + packet.requestId()
									+ " commit=" + packet.commitId() + " owner=" + player + " epoch=" + session.epoch
									+ " " + InventoryCaptureDiagnostics.context(preview.input));
						}
						prepared.attachRecipients(marker);
					}
				} catch (RuntimeException | LinkageError failure) {
					InventoryCaptureDiagnostics.warn("select-create", preview.input, null,
						() -> "request=" + packet.requestId() + " commit=" + packet.commitId() + " owner=" + player, failure);
					rejection = MarkerRejectReason.INVALID_REQUEST;
				}
				finally { prepared.close(); }
			}
		}
		var result = InventoryS2CPacket.selected(packet.resourceKind(), session.epoch, packet.requestId(), packet.commitId(), marker == null ? null : marker.id(), rejection)
			.stamp(session.access == null ? 0 : session.access.epoch(), session.access == null ? 0 : session.access.view());
		if (session.commits.size() >= MAX_REPLAYS) {
			CommitResult retired = session.commits.remove(session.commits.keySet().iterator().next());
			if (retired.queued) publisher.cancelControl(player, retired.packet);
		}
		CommitResult committed = new CommitResult(result, packet.requestId(), packet.commitId(), marker == null ? null : marker.id(), rejection);
		session.commits.put(packet.commitId(), committed);
		enqueueResult(player, session, committed);
		// Only the normal tick revalidates every queued source before draining
		// item data. An ingress callback can follow a same-tick authority change;
		// its selection outcome must not flush unrelated, previously checked data.
	}
	private void enqueueResult(UUID player, Session session, CommitResult result) {
		if (!result.pending || result.queued) return;
		SessionAccess access = currentSessionAccess(player);
		if (access == null) return;
		if (result.packet.presentationEpoch() != access.epoch() || result.packet.view() != access.view()) {
			// A committed outcome replays across a same-epoch view rebuild; it
			// never repeats create, authorization or grant evaluation.
			result.packet = InventoryS2CPacket.selected(result.resourceKind, session.epoch, result.requestId, result.commitId,
				result.markerId, result.rejection).stamp(access.epoch(), access.view());
		}
		result.queued = publisher.control(player, result.packet);
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
	private final class Prepared implements Admission, AutoCloseable {
		final Preview preview;
		final InventorySelection reference;
		final String token, pingType;
		final SyncPublisher.Context previewContext;
		final InventorySourceInput input;
		final Opened frozen;
		@Unmodifiable List<UUID> audience = List.of();
		@Nullable InventorySelection selection;
		@Nullable InventorySourceInput committedInput;
		@Nullable TargetKey admittedKey;
		@Nullable InventoryRuntime.Consumer consumer;
		@Nullable RetainedMemoryLedger.Ticket memory;
		@Nullable InventoryCreationSnapshot creationSnapshot;
		@Nullable RetainedMemoryLedger.Ticket creationMemory;
		@Nullable InventoryPublicationEvidence creationEvidence;
		@Nullable InventorySyncPublisher.InitialStaging initialStaging;
		final Map<UUID, InventoryRuntime.PublicationGrant> initialGrants = new LinkedHashMap<>();
		@Nullable Tracking installed;
		long leaseBytes, bootstrapCount;
		final Map<UUID, RetainedMemoryLedger.Ticket> recipients = new LinkedHashMap<>();
		Prepared(Preview preview, InventorySelection reference, String token, String pingType) {
			this.preview = preview; this.reference = reference; this.token = token; this.pingType = pingType;
			previewContext = preview.context; input = preview.input; frozen = preview.frozen;
		}
		@Override public @Nullable MarkerRejectReason prepare(Target committed, TargetKey targetKey, String type, List<UUID> audience) {
			this.audience = List.copyOf(audience);
			// The host invokes this only after create authority and any external materialization, never at SELECT ingress.
			TargetKey.requireCompatible(committed, targetKey);
			if (!frozen.targetType().equals(type)) return MarkerRejectReason.INVALID_REQUEST;
			@Nullable InventorySourceInput bound = host.bindCommitted(input, committed).orElse(null);
			// The host-returned input must describe the exact committed target
			// payload and opaque locator before any validation, preview evidence,
			// annotation or allocation; a returned candidate or another
			// committed target must never authorize a different source.
			if (bound == null || bound.resourceKind() != input.resourceKind() || bound.face() != input.face() || !bound.readOwner().equals(input.readOwner())
				|| !BlockReadSource.sameTargetBinding(committed, bound.target())
				|| !runtime.validate(bound) || !runtime.probe(preview.consumer).orElse(false))
				return MarkerRejectReason.INVALID_REQUEST;
			@Nullable Session session = sessions.get(input.readOwner());
			if (session == null || session.previews.get(new LaneKey(preview.request, input.resourceKind())) != preview || !preview.context.equals(previewContext)
				|| preview.context.stateFence() != 1 || preview.consumer.invalidated() || preview.retired.contains(token)
				|| preview.references.get(token) != reference) return MarkerRejectReason.INVALID_REQUEST;
			// A partial preview is sufficient, but a missing selected key is not zero.
			@Nullable InventoryResourceValue retained = preview.counts.get(reference.aggregate()
				? new InventoryScanner.Key(reference.kind(), reference.itemId(), "") : reference.exact());
			if (retained == null || preview.folded.contains(reference.itemId()) != reference.aggregate()) return MarkerRejectReason.INVALID_REQUEST;
			if (!runtime.preflight(() -> Optional.of(host.annotationAllowed(bound, pingType))).orElse(false))
				return MarkerRejectReason.INVALID_PING_TYPE;
			if (tracking.size() >= MAX_TRACKING) return MarkerRejectReason.INVALID_REQUEST;
			Set<String> variants = new HashSet<>();
			for (Tracking lease : tracking.values()) if (lease.marker.targetKey().equals(targetKey)) variants.add(identity(lease.selection));
			variants.add(identity(reference));
			if (variants.size() > settings.getTracking().effectiveMaxVariantsPerTarget()) return MarkerRejectReason.INVALID_REQUEST;
			// The lease slab owns bounded identity strings, the seed and its indexes;
			// display lifetime is charged independently of the preview/catalogue.
			leaseBytes = checkedAdd(checkedAdd(LEASE_BYTES + 512L, checkedMultiply(audience.size(), 128L)), reference.aggregate()
				? REPROJECT_DISPLAY_FIXED_BYTES + 2L * AGGREGATE_JSON_BYTES : replacementActual(reference.displayJson()));
			memory = runtime.memory().tryReserve(leaseBytes).orElse(null);
			if (memory == null) return MarkerRejectReason.INVALID_REQUEST;
			@Nullable String display = reference.displayJson();
			if (reference.aggregate()) {
				var workspace = runtime.memory().tryReserve(AGGREGATE_DISPLAY_BYTES);
				if (workspace.isEmpty()) return MarkerRejectReason.INVALID_REQUEST;
				try (var staging = workspace.get()) {
					if (!runtime.claimDetachedItemWork(AGGREGATE_DISPLAY_WORK)) return MarkerRejectReason.INVALID_REQUEST;
					display = aggregateJson(reference.kind(), reference.itemId(), reference.label());
					if (utf8Bytes(display) > AGGREGATE_JSON_BYTES) throw new IllegalStateException("aggregate display exceeds admission");
				}
			}
			selection = new InventorySelection(reference.kind(), reference.exact(), reference.itemId(), reference.label(), display, reference.aggregate(), pingType);
			bootstrapCount = retained.amount();
			consumer = runtime.attach(bound, new InventoryRuntime.TrackingSubject(targetKey)).orElse(null);
			if (consumer == null) return MarkerRejectReason.INVALID_REQUEST;
			consumer.requirePublicationEvidence();
			// Pin the paid active observation before detached receipt work consumes this tick's work envelope.
			if (reference.kind() == InventoryResourceKind.ITEM && runtime.tryJoinInProgress(consumer) == InventoryRuntime.JoinResult.INVALID) return MarkerRejectReason.INVALID_REQUEST;
			long snapshotWorkspace = InventoryCreationSnapshot.validationWorkspace(effectiveDisplayCeiling());
			var validation = runtime.memory().tryReserve(snapshotWorkspace);
			if (validation.isEmpty()) return MarkerRejectReason.INVALID_REQUEST;
			try (var workspace = validation.get()) {
				if (!runtime.claimDetachedItemWork(snapshotWorkspace)) return MarkerRejectReason.INVALID_REQUEST;
				boolean complete = preview.last != null && preview.last.result().availability() == CaptureResult.Availability.READABLE
					&& preview.last.result().completeness() == CaptureResult.Completeness.COMPLETE
					&& !preview.rebaselineNeeded && !preview.fencePending && !preview.republishPending
					&& publisher.previewCutComplete(preview.context, preview.revision);
				boolean stableSweep = complete && preview.last != null
					&& preview.last.evidence() == InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP
					&& preview.last.result().consistency() == CaptureResult.Consistency.EVENTUAL
					&& preview.last.result().coverage().expected().isPresent()
					&& preview.last.result().coverage().scanned() == preview.last.result().coverage().expected().getAsLong();
				boolean uncertain = preview.last == null || !complete
					|| preview.last.result().consistency() != CaptureResult.Consistency.VERIFIED && !stableSweep;
				// Aggregate display is the admitted projection; no source getter is involved.
				InventoryResourceValue selected = selection.aggregate() ? InventoryResourceCodec.value(
					new InventoryScanner.Key(selection.kind(), selection.itemId(), ""), retained.amount(), retained.label(),
					selection.displayJson(), true) : retained;
				long retainedUpper = 512L + 2L * (token.length() + selected.resourceId().length() + selected.label().length()
					+ pingType.length() + (selected.displayJson() == null ? 0 : selected.displayJson().length()));
				creationMemory = runtime.memory().tryReserve(retainedUpper).orElse(null);
				if (creationMemory == null) return MarkerRejectReason.INVALID_REQUEST;
				creationSnapshot = InventoryCreationSnapshot.fromRetained(token, selection, selected, complete, uncertain);
				creationMemory.commit(creationSnapshot.retainedBytes());
			}
			var binding = runtime.acquirePublicationBinding(consumer);
			if (binding.check() != InventorySharedRead.Check.VALID || binding.evidence() == null) return MarkerRejectReason.INVALID_REQUEST;
			creationEvidence = binding.evidence();
			committedInput = bound;
			admittedKey = targetKey;
			return null;
		}
		/**
		 * The exact create authorization result is consumed once, before the store
		 * commit. Recipient fanout, initial source grants and registration tickets
		 * are reserved for the frozen allowed audience, never for the current
		 * POLICY hint. Current readiness only decides which reservations exist now.
		 */
		@Override public @Nullable MarkerRejectReason finishAuthorization(PingAuthorization authorization) {
			if (authorization == null || consumer == null || memory == null || committedInput == null || selection == null
				|| creationSnapshot == null || creationEvidence == null) return MarkerRejectReason.INVALID_REQUEST;
			List<UUID> allowed = new ArrayList<>(audience.size());
			java.util.Set<UUID> seen = new java.util.LinkedHashSet<>();
			for (UUID recipient : audience)
				if (seen.add(recipient) && inventoryAllowed(authorization, recipient, reference.kind())) allowed.add(recipient);
			long snapshotBytes = nx.pingwheel.common.network.InventoryCreationSnapshotCodec.encodedSize(creationSnapshot);
			for (UUID recipient : allowed) {
				var source = runtime.reservePublication(creationEvidence);
				if (source.isEmpty()) return MarkerRejectReason.INVALID_REQUEST;
				initialGrants.put(recipient, source.get());
			}
			// Precommit pays the actual snapshot fanout. The final packet's Basic/marker framing
			// is measured and admitted separately at the single initial exit after storage.
			initialStaging = publisher.reserveInitial(snapshotBytes * allowed.size(),
				allowed.size() * (4096L + 8L * snapshotBytes)).orElse(null);
			if (initialStaging == null) return MarkerRejectReason.INVALID_REQUEST;
			for (UUID recipient : allowed) {
				@Nullable SessionAccess access = currentSessionAccess(recipient);
				if (access == null || !access.compatibleKinds().contains(reference.kind())) continue;
				@Nullable Session recipientSession = sessions.get(recipient);
				if (recipientSession == null || recipientSession.access == null
					|| !recipientSession.access.stamp().equals(access.stamp())) continue;
				if (recipients.size() >= publisher.remainingRegistrations()) return MarkerRejectReason.INVALID_REQUEST;
				var ticket = publisher.reserveRegistration();
				if (ticket.isEmpty()) return MarkerRejectReason.INVALID_REQUEST;
				recipients.put(recipient, ticket.get());
			}
			return null;
		}
		/** Installs the committed tracking sidecar before any CREATED is published. */
		void commit(ServerMarker marker) {
			if (consumer == null || memory == null || committedInput == null || selection == null
				|| !marker.targetKey().equals(admittedKey)) throw new IllegalStateException("dedicated admission bypass or target key changed");
			memory.commit(leaseBytes);
			Tracking lease = new Tracking(marker, committedInput, selection, consumer, memory, effectiveDisplayCeiling(), bootstrapCount);
			lease.creationSnapshot = creationSnapshot; lease.creationMemory = creationMemory; lease.creationEvidence = creationEvidence;
			lease.initialGrants.putAll(initialGrants); initialGrants.clear(); installed = lease;
			creationSnapshot = null; creationMemory = null; creationEvidence = null;
			tracking.put(marker.id(), lease); consumer = null; memory = null;
			// Admission proved the extension fanout before storage. Full per-recipient envelopes
			// replace that temporary hold at publication; do not charge the same snapshot bytes twice.
			if (initialStaging != null) { initialStaging.close(); initialStaging = null; }
		}
		/**
		 * Registers the reserved recipients after the atomic Basic initial is
		 * known: the existing Basic-known-recipient gate still precedes every
		 * inventory data frame, and the admission-reserved registrations are
		 * honored exactly once.
		 */
		void attachRecipients(ServerMarker marker) {
			@Nullable Tracking lease = tracking.get(marker.id());
			if (lease == null) return;
			// CREATED callbacks may change authority. Recheck the committed owner
			// without reading contents before any cached selected value can send.
			var valid = runtime.probe(lease.consumer);
			if (valid.orElse(false)) lease.checkedTick = tick;
			var fanout = new TrackingFanout();
			try { for (var entry : recipients.entrySet()) addRecipient(lease, entry.getKey(), entry.getValue(), fanout); }
			finally { fanout.close(); }
			recipients.clear();
			if (valid.isPresent() && !valid.get()) invalidate(lease);
		}
		@Override public void close() {
			if (consumer != null) consumer.close(); if (memory != null) memory.close(); recipients.values().forEach(RetainedMemoryLedger.Ticket::close);
			if (creationMemory != null) creationMemory.close(); if (creationEvidence != null) creationEvidence.close();
			if (initialStaging != null) initialStaging.close();
			initialGrants.values().forEach(InventoryRuntime.PublicationGrant::close); initialGrants.clear();
			if (installed != null) { installed.initialGrants.values().forEach(InventoryRuntime.PublicationGrant::close); installed.initialGrants.clear(); }
		}
	}
	/**
	 * Idle source health checks use the consumer's own last completed check and
	 * the current period. A deferred probe records no completion, so it remains
	 * overdue; a live period change is evaluated against the same completion
	 * time instead of postponing the check. A regressed clock never hides it.
	 */
	private static boolean idleHealthDue(boolean checked, long lastCompletedTick, long now, long periodTicks) {
		if (!checked || periodTicks <= 0) return true;
		if (now < lastCompletedTick) return true;
		return now - lastCompletedTick >= periodTicks;
	}
	/**
	 * A completed Preview has no periodic recapture: its health probe runs on the
	 * Preview period, while rebaseline, repair, deferred publication or a cut not
	 * yet offered remains live work and keeps the original probe cadence.
	 */
	private boolean previewHealthDue(Preview preview) {
		if (!preview.terminal || preview.last == null) return true;
		if (preview.rebaselineNeeded || preview.fencePending || preview.republishPending || preview.metadataChanged
			|| preview.projectionDelay.outstanding() || preview.publicationDelay.outstanding()
			|| !publisher.accepted(preview.context, preview.revision)) return true;
		return idleHealthDue(preview.healthChecked, preview.lastHealthCheckTick, tick, settings.getPreview().getPeriodTicks());
	}
	/**
	 * Tracking keeps probing every tick while a sweep is unfinished or due.
	 * Only the wait between two completed sweeps is an idle health period;
	 * invalid recovery and pending bootstrap/repair progress stay per-tick.
	 */
	private boolean trackingHealthDue(Tracking lease) {
		if (lease.invalid || lease.recomputingScope || lease.scanning || tick >= lease.nextSweep) return true;
		if (lease.projectionDelay.outstanding() || lease.registrationDelay.outstanding()) return true;
		for (Recipient recipient : lease.recipients.values()) {
			if (recipient.rebaselineNeeded || recipient.publicationDelay.outstanding()) return true;
			if (lease.completed == null ? lease.bootstrap != null && !recipient.bootstrapOffered
				: !publisher.accepted(recipient.context, lease.revision)) return true;
		}
		return idleHealthDue(lease.healthChecked, lease.lastHealthCheckTick, tick, settings.getTracking().getPeriodTicks());
	}
	public void tick(long now, InventorySettings settings) {
		if (publishing) return; // metadata/transport callbacks cannot recursively drain or reset an outstanding grant
		advance(now, settings);
		for (var entry : List.copyOf(sessions.entrySet())) {
			refreshSession(entry.getKey(), entry.getValue());
			Session session = entry.getValue();
			if (session.access == null) continue;
			var offer = publisher.offer(settings.getPreview().getPeriodTicks(), settings.getTracking().getPeriodTicks(),
				settings.getTracking().getResyncMinPeriods(), settings.getTracking().getHeartbeatPeriods());
			if (!offer.equals(session.offer)) enqueueOffer(entry.getKey(), session);
			if (!session.policyAwaitingOffer) for (InventoryS2CPacket packet : List.copyOf(session.pendingPolicies.values()))
				if (!publisher.controlQueued(entry.getKey(), packet)) publisher.control(entry.getKey(), packet);
			for (InventoryS2CPacket packet : List.copyOf(session.pendingOpened.values()))
				if (!publisher.controlQueued(entry.getKey(), packet)) publisher.control(entry.getKey(), packet);
			for (CommitResult result : session.commits.values()) enqueueResult(entry.getKey(), session, result);
		}
		var leases = new ArrayList<>(tracking.values());
		if (!leases.isEmpty()) java.util.Collections.rotate(leases, -(trackingCursor++ % leases.size()));
		for (Tracking lease : leases) {
			if (host.activeMarker(lease.marker.id()).isEmpty()) { remove(lease.marker.id()); continue; }
			lease.checkedTick = lease.invalid ? tick : -1; // already-withdrawn INVALID control needs no recovery read grant
			syncRecipients(lease);
			lease.consumer.serviceableDemand(!lease.recipients.isEmpty());
			if (lease.recipients.isEmpty()) {
				lease.projectionDelay.cancel();
				if (!lease.audienceDemand) lease.registrationDelay.cancel();
				continue;
			}
			if (lease.consumer.invalidated() && !lease.invalid) { invalidate(lease); continue; }
			if (!trackingHealthDue(lease)) continue;
			try (var attempt = lease.invalid ? runtime.beginProbeRecovery(lease.consumer) : runtime.beginProbe(lease.consumer)) {
				var valid = attempt.result();
				if (valid.isEmpty()) continue;
				lease.checkedTick = tick;
				lease.healthChecked = true; lease.lastHealthCheckTick = tick;
				if (!valid.get()) { invalidate(lease); continue; }
				boolean preparedAttempt = false;
				if (!lease.invalid && !lease.recomputingScope && lease.bootstrap != null && lease.bootstrapEvidence == null) {
					// Transfer the probe's metadata envelope before the independent bootstrap
					// binding needs another resolution workspace. This prepares no contents and
					// retains every later scan/publication authority check.
					if (runtime.prepare(lease.consumer, attempt) == InventorySourceAccess.Preparation.DEFERRED) continue;
					preparedAttempt = true;
					var acquired = runtime.acquirePublicationBinding(lease.consumer);
					if (acquired.check() == InventorySharedRead.Check.DEFERRED) continue;
					if (acquired.check() != InventorySharedRead.Check.VALID) { invalidate(lease); continue; }
					lease.bootstrapEvidence = acquired.evidence();
				}
				for (Recipient recipient : List.copyOf(lease.recipients.values())) if (recipient.rebaselineNeeded)
					rebaselineTracking(recipient.context.recipient(), lease, recipient);
				if (!lease.invalid) publishTracking(lease);
				if (lease.scanning) sampleTracking(lease, preparedAttempt ? null : attempt);
				else if (now >= lease.nextSweep) {
					lease.consumer.restart(); lease.count = lease.total = 0; lease.folded = lease.selection.aggregate(); lease.scanning = true; sampleTracking(lease, attempt);
				}
			}
		}
		var previews = new ArrayList<Map.Entry<UUID, Preview>>();
		for (var entry : sessions.entrySet()) {
			Session session = entry.getValue();
			if (session.access == null) continue;
			for (PreviewRequest parent : session.requests.values()) for (var kind : InventoryResourceKind.values())
				if (parent.attempted == 0 && parent.openedHandedOff && (parent.laneMask & (1 << kind.wireId())) != 0
					&& session.access.compatibleKinds().contains(kind)
					&& !session.previews.containsKey(new LaneKey(parent.request, kind))) openLane(entry.getKey(), session, parent, kind);
		}
		for (var entry : sessions.entrySet()) for (Preview preview : entry.getValue().previews.values()) previews.add(Map.entry(entry.getKey(), preview));
		if (!previews.isEmpty()) java.util.Collections.rotate(previews, -(previewCursor++ % previews.size()));
		for (var entry : previews) {
			Preview preview = entry.getValue();
			@Nullable Session previewSession = sessions.get(entry.getKey());
			if (previewSession == null) continue;
			preview.checkedTick = -1;
			if (preview.context.stateFence() > 1) {
				// A deferred INVALID is terminal: retry its context/fence on a normal
				// tick, but never probe or rescan the retired source.
				progressPreviewBaseline(previewSession, preview);
				preview.checkedTick = tick;
				continue;
			}
			if (!previewHealthDue(preview)) continue;
			if (!progressPreviewBaseline(previewSession, preview)) continue;
			try (var attempt = runtime.beginProbe(preview.consumer)) {
				var valid = attempt.result();
				if (valid.isEmpty()) continue;
				preview.checkedTick = tick;
				preview.healthChecked = true; preview.lastHealthCheckTick = tick;
				if (!valid.get()) invalidatePreview(sessions.get(entry.getKey()), preview);
				else if (!preview.terminal) samplePreview(entry.getKey(), preview, attempt);
				else if (preview.last != null) publishPreview(preview);
			}
		}
		// A later shared probe/step can retire an earlier, already-checked terminal consumer.
		// Cancel/rebase outside publisher traversal so no admitted old fragment can escape this tick.
		for (Tracking lease : tracking.values()) if (lease.consumer.invalidated() && !lease.invalid) invalidate(lease);
		for (Session session : sessions.values()) for (Preview preview : session.previews.values())
			if (preview.consumer.invalidated() && preview.context.stateFence() == 1) invalidatePreview(session, preview);
		applyPublicationFailures();
		publishing = true;
		try {
			// A budget-deferred initial OFFER may first succeed on this tick.
			// Admit its waiting hints before the normal drain's control snapshot,
			// using the same byte window as the two bounded HELLO drains.
			publisher.drainControls();
			publisher.drain();
		}
		finally { publishing = false; closePublicationOpportunity(); }
		// A proof rejected during drain enqueues its INVALID/withdrawal control after
		// that traversal. Flush those failure controls in the same tick instead of
		// leaving stale UPDATING/data state until the next ordinary tick.
		if (applyPublicationFailures()) {
			publishing = true;
			try { publisher.drainTerminal(); }
			finally { publishing = false; closePublicationOpportunity(); }
			applyPublicationFailures();
		}
		for (MarkerId marker : Set.copyOf(deferredRemovals)) remove(marker); deferredRemovals.clear();
		for (UUID player : Set.copyOf(deferredDisconnects)) disconnect(player); deferredDisconnects.clear();
		if (closeRequested) { close(); return; }
		reportState(Module.INVENTORY_PREVIEW); reportState(Module.INVENTORY_TRACKING);
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
			if (!session.reconfiguring) continue;
			// Current session/schema is required for transport; it is not a fresh
			// content authorization and never prunes a frozen recipient.
			refreshSession(recipient, session);
			if (session.access == null) continue;
			if (session.handoffOffer == null && !enqueueOffer(recipient, session)) continue;
			if (!session.offerHandedOff) continue;
			boolean ready = true;
			int display = effectiveDisplayCeiling();
			for (Preview preview : List.copyOf(session.previews.values())) ready &= reconcilePreview(session, preview, display);
			for (Tracking lease : List.copyOf(tracking.values())) {
				Recipient current = lease.recipients.get(recipient);
				if (current == null) continue;
				@Nullable ServerMarker canonical = host.activeMarker(lease.marker.id()).orElse(null);
				if (canonical == null || !session.access.compatibleKinds().contains(lease.selection.kind())
					|| !inventoryAllowed(canonical.authorization(), recipient, lease.selection.kind())
					|| !canonical.recipients().contains(recipient) || !host.knows(recipient, lease.marker.id())) {
					publisher.cancel(current.context); lease.recipients.remove(recipient); continue;
				}
				// Ordinary recipient synchronization installs the new session
				// context; keep its data gate closed until that context is reconciled.
				if (!current.access.stamp().equals(session.access.stamp())) { ready = false; continue; }
				ready &= reconcileTracking(recipient, lease, display);
			}
			session.workReady = ready;
			if (ready) { publisher.releaseData(recipient); session.reconfiguring = false; session.handoffOffer = null; }
		}
	}
	private boolean enqueueOffer(UUID recipient, Session session) {
		if (session.access == null) return false;
		var p = settings.getPreview(); var t = settings.getTracking();
		var offer = publisher.offer(p.getPeriodTicks(), t.getPeriodTicks(), t.getResyncMinPeriods(), t.getHeartbeatPeriods());
		// Replacement withdraws the superseded control even when admission refuses.
		// Keep both the comparison and handoff retryable under the latest settings.
		session.offer = null; session.handoffOffer = null; session.offerHandedOff = false;
		boolean queued = publisher.control(recipient, InventoryS2CPacket.offer(session.epoch, offer)
			.stamp(session.access.epoch(), session.access.view()));
		if (queued) { session.offer = offer; session.handoffOffer = offer; }
		return queued;
	}
	/**
	 * Control-handoff callback. POLICY completes only its exact current desired
	 * owner under current authority; admission and stale completions do not count.
	 * Only a same-epoch, same-presentation-view OFFER
	 * whose exact caps are the current generation's requested caps counts as a
	 * successful handoff; a stale OFFER, an unrelated control or an unknown
	 * recipient never clears a gate. Returning true releases the gate only when
	 * the required work is already ready.
	 */
	private boolean controlHandoffAccepted(UUID recipient, InventoryS2CPacket packet) {
		@Nullable Session session = sessions.get(recipient);
		if (session == null) return false;
		@Nullable SessionAccess access = currentSessionAccess(recipient);
		if (packet.kind() == InventoryS2CPacket.Kind.OPENED) {
			if (access != null && session.pendingOpened.get(packet.requestId()) == packet
				&& packet.epoch() == session.epoch && packet.presentationEpoch() == access.epoch() && packet.view() == access.view()) {
				session.pendingOpened.remove(packet.requestId());
				@Nullable PreviewRequest parent = session.requests.get(packet.requestId());
				if (parent != null && parent.laneMask == packet.laneBits()) parent.openedHandedOff = true;
			}
			// An OPEN entitlement acknowledgement never releases the resource-data gate.
			return false;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.POLICY) {
			if (sessions.get(recipient) == session && !closeRequested && !deferredDisconnects.contains(recipient)
				&& access != null && session.epoch == packet.epoch()
				&& access.epoch() == packet.presentationEpoch() && access.view() == packet.view()
				&& session.pendingPolicies.get(packet.resourceKind()) == packet) {
				session.pendingPolicies.remove(packet.resourceKind());
				session.pendingPolicyRevisions.remove(packet.resourceKind());
				return !session.reconfiguring;
			}
			return false;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT) {
			CommitResult result = session.commits.get(packet.commitId());
			if (result != null && result.packet == packet) { result.pending = false; result.queued = false; }
			return !session.reconfiguring;
		}
		if (packet.kind() != InventoryS2CPacket.Kind.OFFER) return !session.reconfiguring;
		if (access == null || packet.epoch() != session.epoch
			|| packet.presentationEpoch() != access.epoch() || packet.view() != access.view()) return false;
		if (session.handoffOffer == null || session.handoffGeneration != session.offerGeneration
			|| !session.handoffOffer.equals(packet.offer())) return false;
		session.offerHandedOff = true;
		if (session.policyAwaitingOffer) {
			session.policyAwaitingOffer = false;
			for (InventoryS2CPacket pending : session.pendingPolicies.values()) publisher.control(recipient, pending);
		}
		return !session.reconfiguring || session.workReady;
	}
	private int effectiveDisplayCeiling() {
		return (int) Math.clamp(appliedDisplayBytes, 1, InventoryDomainCodec.MAX_DISPLAY_BYTES);
	}
	/** Lowers one preview's retained observation and backend metadata, then fences a fresh baseline when affected. */
	private boolean reconcilePreview(Session session, Preview preview, int displayCap) {
		var reprojection = runtime.reprojectDisplays(preview.consumer, displayCap);
		boolean ready = reprojection.status() != InventoryRuntime.ReprojectionStatus.DEFERRED;
		if (ready && !reprojectPreviewMetadata(preview, displayCap)) ready = false;
		if (preview.metadataChanged || publisher.oversized(preview.context)) {
			preview.rebaselineNeeded = true; preview.metadataChanged = false;
		}
		if (ready && preview.rebaselineNeeded) {
			ready = rebaselinePreview(session, preview);
			if (ready) preview.rebaselineNeeded = false;
		}
		if (!ready) return false;
		if (preview.projectionDelay.outstanding()) preview.projectionDelay.progress(previewCycle);
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
	/** One reused OLD-input parser plus summed replacements and map overlap, before any parsing. */
	private boolean reprojectPreviewMetadata(Preview preview, int displayCap) {
		if (preview.metadataCeiling <= displayCap) return true;
		var port = host.displayReprojector().orElse(null);
		if (port == null) { preview.metadataCeiling = displayCap; return true; }
		long parser = 0, replacements = 0;
		long work = InventoryRuntime.reprojectionCopyWork((long) preview.counts.size() + preview.totals.size() + preview.references.size());
		for (InventoryResourceValue item : preview.counts.values()) {
			work = checkedAdd(work, InventoryRuntime.reprojectionWork(item.displayJson(), displayCap));
			parser = Math.max(parser, parserBound(item.displayJson(), displayCap));
			replacements = checkedAdd(replacements, replacementBound(item.displayJson(), displayCap));
		}
		for (InventoryResourceValue item : preview.totals.values()) {
			work = checkedAdd(work, InventoryRuntime.reprojectionWork(item.displayJson(), displayCap));
			parser = Math.max(parser, parserBound(item.displayJson(), displayCap));
			replacements = checkedAdd(replacements, replacementBound(item.displayJson(), displayCap));
		}
		for (InventorySelection reference : preview.references.values()) {
			work = checkedAdd(work, InventoryRuntime.reprojectionWork(reference.displayJson(), displayCap));
			parser = Math.max(parser, parserBound(reference.displayJson(), displayCap));
			replacements = checkedAdd(replacements, replacementBound(reference.displayJson(), displayCap));
		}
		if (parser == 0) { preview.metadataCeiling = displayCap; return true; }
		long upper = checkedAdd(8192L, checkedAdd(parser, replacements));
		var held = runtime.memory().tryReserve(upper);
		if (held.isEmpty()) { previewDelay(preview, preview.projectionDelay, "projection", Reason.MEMORY.bit()); return false; }
		long measured = 0;
		boolean changed = false;
		try {
			if (!runtime.claimDetachedItemWork(work)) {
				held.get().close();
				previewDelay(preview, preview.projectionDelay, "projection", Reason.PUBLICATION_WORK.bit());
				return false;
			}
			// Build the complete replacement off to the side. A bad projector or
			// underestimated demand leaves every original map/token/cursor intact.
			var counts = new LinkedHashMap<>(preview.counts);
			var totals = new LinkedHashMap<>(preview.totals);
			var references = new LinkedHashMap<>(preview.references);
			for (var entry : counts.entrySet()) {
				InventoryResourceValue item = entry.getValue();
				var projected = reprojectItem(port, item, displayCap);
				measured = checkedAdd(measured, replacementActual(projected == null ? item.displayJson() : projected.displayJson()));
				if (projected != null) { entry.setValue(projected); changed = true; }
			}
			for (var entry : totals.entrySet()) {
				InventoryResourceValue item = entry.getValue();
				var projected = reprojectItem(port, item, displayCap);
				measured = checkedAdd(measured, replacementActual(projected == null ? item.displayJson() : projected.displayJson()));
				if (projected != null) { entry.setValue(projected); changed = true; }
			}
			for (var entry : references.entrySet()) {
				InventorySelection reference = entry.getValue();
				if (reference.kind() == InventoryResourceKind.FLUID) {
					boolean trim = utf8Bytes(reference.displayJson()) > displayCap;
					measured = checkedAdd(measured, replacementActual(trim ? null : reference.displayJson()));
					if (trim) { entry.setValue(new InventorySelection(reference.kind(), reference.exact(), reference.itemId(), reference.label(), null,
						reference.aggregate(), reference.itemPingType())); changed = true; }
					continue;
				}
				var projected = parserBound(reference.displayJson(), displayCap) == 0 ? null
					: reprojectDisplay(port, reference.itemId(), reference.label(), reference.displayJson(), reference.aggregate(), displayCap);
				measured = checkedAdd(measured, replacementActual(projected == null ? reference.displayJson() : projected.displayJson()));
				if (projected == null) continue;
				entry.setValue(new InventorySelection(reference.kind(), reference.exact(), reference.itemId(), reference.label(), projected.displayJson(),
					reference.aggregate(), reference.itemPingType()));
				changed = true;
			}
			if (measured > replacements) throw new IllegalStateException("inventory replacement exceeds admission");
			held.get().commit(measured);
			preview.counts = counts; preview.totals = totals; preview.references = references;
		} catch (RuntimeException | Error failure) {
			held.get().close(); throw failure;
		}
		// The committed replacement covers every current count/total/reference,
		// including unchanged values. Old sampling owners survive until the swap.
		preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.entries.clear();
		if (preview.metadataMemory != null) preview.metadataMemory.close(); // new ticket admitted before the old one is released
		preview.metadataMemory = held.get();
		preview.metadataCeiling = displayCap;
		preview.projectionDelay.progress(previewCycle);
		if (changed) preview.metadataChanged = true;
		return true;
	}
	private @Nullable InventoryResourceValue reprojectItem(InventoryRuntime.DisplayReprojector port, @Nullable InventoryResourceValue item, int displayCap) {
		if (item == null || item.displayJson() == null || utf8Bytes(item.displayJson()) <= displayCap) return null;
		if (item.resourceKind() == InventoryResourceKind.FLUID) return InventoryResourceCodec.value(item.key(), item.amount(), item.label(), null, item.aggregateRequired());
		var projected = reprojectDisplay(port, item.key().itemId(), item.label(), item.displayJson(), item.aggregateRequired(), displayCap);
		if (projected == null) return null;
		return InventoryResourceCodec.value(item.key(), item.amount(), item.label(), projected.displayJson(), item.aggregateRequired());
	}
	private InventoryItemCodec.Display reprojectDisplay(InventoryRuntime.DisplayReprojector port, String itemId, String label, String displayJson, boolean aggregate, int displayCap) {
		var retained = new InventoryItemCodec.Display(itemId, label, displayJson, aggregate);
		var projected = port.reproject(retained, displayCap);
		if (utf8Bytes(projected.displayJson()) > displayCap) throw new IllegalStateException("inventory projector exceeds display admission");
		return java.util.Objects.equals(projected.displayJson(), displayJson) ? null
			: new InventoryItemCodec.Display(itemId, label, projected.displayJson(), aggregate);
	}
	private static long parserBound(@Nullable String displayJson, int displayCap) {
		long oldBytes = utf8Bytes(displayJson);
		return oldBytes <= displayCap ? 0 : InventoryItemBudget.reprojectionWorkspaceUpperBound(Math.toIntExact(oldBytes));
	}
	private static long replacementBound(@Nullable String displayJson, int displayCap) {
		return checkedAdd(checkedMultiply(Math.min(utf8Bytes(displayJson), displayCap), 2L), REPROJECT_DISPLAY_FIXED_BYTES);
	}
	private static long replacementActual(@Nullable String projectedJson) {
		return checkedAdd(checkedMultiply(utf8Bytes(projectedJson), 2L), REPROJECT_DISPLAY_FIXED_BYTES);
	}
	private static long utf8Bytes(@Nullable String value) {
		if (value == null) return 0;
		long bytes = 0;
		for (int index = 0; index < value.length(); index++) {
			char unit = value.charAt(index);
			if (unit < 0x80) bytes++;
			else if (unit < 0x800) bytes += 2;
			else if (Character.isHighSurrogate(unit) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) { bytes += 4; index++; }
			else bytes += Character.isSurrogate(unit) ? 1 : 3; // Native UTF-8 encoder replaces malformed surrogates with '?'.
		}
		return bytes;
	}
	private static long checkedMultiply(long first, long second) {
		try { return Math.multiplyExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	private static long checkedAdd(long first, long second) {
		try { return Math.addExact(first, second); }
		catch (ArithmeticException overflow) { throw new IllegalStateException("inventory accounting overflow"); }
	}
	/** Cancels the old context and registers a fresh baseline; the replacement fence is handed off before data. */
	private boolean rebaselinePreview(Session session, Preview preview) {
		if (session.access == null) return false;
		boolean terminalInvalid = preview.context.stateFence() > 1;
		publisher.cancel(preview.context);
		SyncPublisher.Context fresh = new SyncPublisher.Context(preview.context.consumerId(), preview.context.recipient(),
			session.access.stamp(), preview.context.stateFence(), ++baseline);
		if (!publisher.register(fresh, route(session, preview.request, null, null, null, preview.input.resourceKind()))) {
			preview.rebaselineNeeded = true;
			previewDelay(preview, preview.projectionDelay, "projection", (publisher.remainingRegistrations() == 0 ? Reason.SUBJECT_CAPACITY : Reason.MEMORY).bit()); return false;
		}
		preview.projectionDelay.progress(previewCycle);
		preview.context = fresh;
		preview.fencePending = true; preview.republishPending = true; preview.rebaselineNeeded = false;
		// A retired source never returns to INCOMPLETE after a same-epoch view
		// rebuild; its terminal INVALID must survive until transport handoff.
		publisher.status(fresh, terminalInvalid ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.INCOMPLETE);
		return true;
	}
	/**
	 * Normal-tick progress for a Preview context rebuild: register, hand off the
	 * empty fence, then republish the retained cut. OPENED still gates data.
	 */
	private boolean progressPreviewBaseline(Session session, Preview preview) {
		if (preview.rebaselineNeeded && !rebaselinePreview(session, preview)) return false;
		if (preview.fencePending) {
			if (publisher.controlPending(preview.context)) return false;
			preview.fencePending = false;
		}
		if (preview.republishPending) {
			preview.republishPending = false;
			if (preview.last != null && (!preview.counts.isEmpty() || !preview.folded.isEmpty())) {
				var outcome = publishPreview(preview);
				if (outcome instanceof SyncPublisher.Outcome.Deferred) preview.republishPending = true;
			}
		}
		return true;
	}
	/** Lowers one tracking lease's retained observation and its capped selection presentation. */
	private boolean reconcileTracking(UUID recipient, Tracking lease, int displayCap) {
		var reprojection = runtime.reprojectDisplays(lease.consumer, displayCap);
		boolean ready = reprojection.status() != InventoryRuntime.ReprojectionStatus.DEFERRED;
		if (ready && !reprojectTrackingPresentation(lease, displayCap)) ready = false;
		Recipient current = lease.recipients.get(recipient);
		if (current == null) return ready;
		if (current.displayDirty) current.rebaselineNeeded = true;
		if (publisher.oversized(current.context)) current.rebaselineNeeded = true;
		if (ready && current.rebaselineNeeded) {
			ready = rebaselineTracking(recipient, lease, current);
			if (ready) { current.displayDirty = false; current.rebaselineNeeded = false; }
		}
		if (ready && lease.projectionDelay.outstanding()) lease.projectionDelay.progress(trackingCycle);
		return ready;
	}
	/** Keeps the original selected identity while the capped presentation is reprojected under memory admission. */
	private boolean reprojectTrackingPresentation(Tracking lease, int displayCap) {
		if (lease.presentationCeiling <= displayCap) return true;
		var port = host.displayReprojector().orElse(null);
		if (port == null || lease.selectionPresentation == null) { lease.presentationCeiling = displayCap; return true; }
		InventoryResourceValue retained = lease.selectionPresentation;
		if (utf8Bytes(retained.displayJson()) <= displayCap) { lease.presentationCeiling = displayCap; return true; }
		long replacements = replacementBound(retained.displayJson(), displayCap);
		long bound = checkedAdd(parserBound(retained.displayJson(), displayCap), replacements);
		var held = runtime.memory().tryReserve(bound);
		if (held.isEmpty()) { trackingDelay(lease, lease.projectionDelay, "projection", Reason.MEMORY.bit()); return false; }
		InventoryResourceValue projected;
		try {
			long work = checkedAdd(InventoryRuntime.reprojectionCopyWork(1), InventoryRuntime.reprojectionWork(retained.displayJson(), displayCap));
			if (!runtime.claimDetachedItemWork(work)) {
				held.get().close();
				trackingDelay(lease, lease.projectionDelay, "projection", Reason.PUBLICATION_WORK.bit());
				return false;
			}
			var itemProjection = retained.resourceKind() == InventoryResourceKind.FLUID ? null
				: reprojectDisplay(port, retained.resourceId(), retained.label(), retained.displayJson(), retained.aggregateRequired(), displayCap);
			projected = retained.resourceKind() == InventoryResourceKind.FLUID ? InventoryResourceCodec.value(retained.key(), retained.amount(), retained.label(), null, retained.aggregateRequired())
				: itemProjection == null ? retained : InventoryResourceCodec.value(retained.key(), retained.amount(), retained.label(), itemProjection.displayJson(), retained.aggregateRequired());
			long actual = replacementActual(projected.displayJson());
			if (actual > replacements) throw new IllegalStateException("inventory replacement exceeds admission");
			held.get().commit(actual);
		}
		catch (RuntimeException | Error failure) { held.get().close(); throw failure; }
		if (!projected.equals(retained)) {
			lease.selectionPresentation = projected;
			if (lease.completed != null || lease.bootstrap != null) for (Recipient recipient : lease.recipients.values()) recipient.displayDirty = true;
		}
		if (lease.presentationMemory != null) lease.presentationMemory.close();
		lease.presentationMemory = held.get();
		lease.presentationCeiling = displayCap;
		lease.projectionDelay.progress(trackingCycle);
		return true;
	}
	/** Fresh tracking registration keeps the existing STATUS fence, state fence, cooldown and folded state. */
	private boolean rebaselineTracking(UUID recipient, Tracking lease, Recipient current) {
		Session session = sessions.get(recipient);
		if (session == null || session.access == null) return false;
		publisher.cancel(current.context);
		SyncPublisher.Context fresh = new SyncPublisher.Context(current.context.consumerId(), recipient,
			session.access.stamp(), lease.state, ++baseline);
		if (!publisher.register(fresh, route(session, 0, lease.marker.id(), lease.marker.targetKey(), lease.selection.itemPingType(), lease.selection.kind()))) {
			current.rebaselineNeeded = true;
			delay(current.publicationDelay, Module.INVENTORY_TRACKING, current.context.baseline(), "registration",
				(publisher.remainingRegistrations() == 0 ? Reason.SUBJECT_CAPACITY : Reason.MEMORY).bit()); return false;
		}
		Recipient replacement = new Recipient(current.access, fresh);
		replacement.folded = current.folded; replacement.resyncAt = current.resyncAt;
		lease.recipients.put(recipient, replacement);
		if (lease.bootstrap != null) lease.revision++;
		publisher.status(fresh, trackingLifecycle(lease));
		publishTracking(lease, replacement);
		return true;
	}
	/** Direct sampling callers keep the original probe-then-sample boundary. */
	private void samplePreview(UUID player, Preview preview) {
		try (var attempt = runtime.beginProbe(preview.consumer)) { samplePreview(player, preview, attempt); }
	}
	private void samplePreview(UUID player, Preview preview, InventoryRuntime.ProbeAttempt attempt) {
		InventorySourceAccess.Preparation preparation = runtime.prepare(preview.consumer, attempt);
		if (preparation == InventorySourceAccess.Preparation.DEFERRED) return;
		if (preparation != InventorySourceAccess.Preparation.READY) {
			var observation = runtime.step(preview.consumer, 1);
			if (observation.isEmpty()) return;
			CaptureResult result = observation.get().result();
			if (result.availability() != CaptureResult.Availability.READABLE) {
				invalidatePreview(sessions.get(player), preview); return;
			}
			preview.last = new PublicationObservation(result, observation.get().inventorySweepEvidence()); preview.terminal = true; preview.revision++;
			preview.samplingDelay.cancel(); preview.consumer.stopScanning();
			publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE);
			publishPreview(preview);
			return;
		}
		if (!variantMemory.containsKey(player)) {
			if (variantMemory.size() >= MAX_SESSIONS) { previewDelay(preview, preview.samplingDelay, "sampling", Reason.SUBJECT_CAPACITY.bit()); return; }
			var claimMemory = runtime.memory().tryReserve(CLAIM_BYTES);
			if (claimMemory.isEmpty()) { previewDelay(preview, preview.samplingDelay, "sampling", Reason.MEMORY.bit()); return; }
			claimMemory.get().commit(CLAIM_BYTES); variantMemory.put(player, claimMemory.get());
		}
		int cap = Math.max(effectiveDisplayCeiling(), preview.metadataCeiling);
		// Runtime will independently acquire W and its page/catalog tickets. Leave
		// room for those grants instead of consuming all headroom with this page.
		// Backend owns detached count/total/reference display versions, not A1's
		// canonical native arrays. Their bounded keys/indexes live in the slab.
		long entryBytes = checkedMultiply(3L, checkedAdd(checkedMultiply(2L, cap), 4096L));
		long previousBytes = previewRetainedBytes(preview);
		int slotBound = InventorySourceAccess.MAX_STEP;
		while (slotBound > 0 && checkedAdd(previousBytes, checkedAdd(checkedMultiply(slotBound, entryBytes),
			runtime.stepMemoryUpperBound(preview.consumer, slotBound))) > runtime.memory().remaining()) slotBound--;
		if (slotBound == 0) { previewDelay(preview, preview.samplingDelay, "sampling", Reason.MEMORY.bit()); return; }
		var held = runtime.memory().tryReserve(checkedAdd(previousBytes, checkedMultiply(slotBound, entryBytes)));
		if (held.isEmpty()) { previewDelay(preview, preview.samplingDelay, "sampling", Reason.MEMORY.bit()); return; }
		preview.samplingDelay.progress(previewCycle);
		boolean retained = false;
		try {
			var reservation = held.get();
			var observation = runtime.step(preview.consumer, slotBound);
			if (observation.isEmpty()) return;
			CaptureResult result = observation.get().result();
			if (result.availability() != CaptureResult.Availability.READABLE) {
				invalidatePreview(sessions.get(player), preview); return;
			}
			for (var item : observation.get().slots()) if (item != null && utf8Bytes(item.displayJson()) > cap)
				throw new IllegalStateException("inventory observation exceeds admitted display ceiling");
			int observedSlot = (int) result.coverage().scanned() - observation.get().slots().size();
			try {
				for (var item : observation.get().slots()) {
					int witness = observedSlot++;
					if (item == null) continue;
					if (item.resourceKind() != preview.input.resourceKind()) throw new IllegalStateException("preview observation lane");
					String id = item.key().itemId();
					if (item.aggregateRequired() && !preview.folded.contains(id) && preview.folded.size() >= MAX_ENTRIES) {
						preview.terminal = true; publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE); break;
					}
					// Compute both sums before installing either. A folded group consumes
					// each subsequent slot once, independent of its original exact key.
					InventoryResourceValue total;
					try { total = add(preview.totals.get(id), item); }
					catch (ArithmeticException overflow) {
						if (item.aggregateRequired()) retirePreviewGroup(preview, id);
						throw overflow;
					}
					if (item.aggregateRequired()) retirePreviewGroup(preview, id);
					boolean folded = preview.folded.contains(id);
					InventoryScanner.Key key = folded ? new InventoryScanner.Key(preview.input.resourceKind(), id, "") : item.key();
					// Retain counted identity now; display work can retry at publication without replaying this slot.
					InventoryResourceValue next = folded ? InventoryResourceCodec.value(key, total.amount(), total.label(), null, true)
						: add(preview.counts.get(key), item);
					boolean fresh = !preview.counts.containsKey(key);
					String claim = variantClaim(key);
					boolean unclaimed = !previewVariantClaims.getOrDefault(player, Set.of()).contains(claim);
					if (fresh && (preview.categories >= MAX_ENTRIES || unclaimed && previewVariants.getOrDefault(player, 0) >= Math.min(MAX_ENTRIES, settings.getPreview().effectiveMaxVariantsPerClientPeriod()))) {
						preview.terminal = true; publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE); break;
					}
					if (fresh) {
						preview.categories++;
						if (unclaimed) {
							previewVariants.merge(player, 1, Integer::sum);
							previewVariantClaims.computeIfAbsent(player, ignored -> new HashSet<>()).add(claim);
						}
					}
					preview.counts.put(key, next);
					preview.witnesses.putIfAbsent(key, witness);
					preview.totals.put(id, total);
				}
			} catch (ArithmeticException overflow) {
				InventoryCaptureDiagnostics.warn("preview-count", preview.input, null, () -> "reason=count-overflow", overflow);
				preview.terminal = true; publisher.status(preview.context, InventoryS2CPacket.Status.INCOMPLETE);
			}
			long actual = previewRetainedBytes(preview);
			reservation.commit(actual); // never hide an underestimate with min(actual, upper)
			preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.entries.clear();
			if (preview.metadataMemory != null) { preview.metadataMemory.close(); preview.metadataMemory = null; }
			if (actual > 0) { preview.entries.add(reservation); retained = true; }
		preview.last = new PublicationObservation(preview.terminal ? new CaptureResult(result.payload(), result.coverage(), result.availability(),
				CaptureResult.Completeness.INCOMPLETE, result.consistency(), result.sourceVersion(), result.nextCursor()) : result,
				observation.get().inventorySweepEvidence());
			preview.terminal |= result.completeness() != CaptureResult.Completeness.CONTINUE;
			if (preview.terminal) preview.consumer.stopScanning();
			if (preview.publicationEvidence == null) preview.publicationEvidence = runtime.publicationEvidence(preview.consumer, InventoryRuntime.EvidenceScope.SCANNING).orElse(null);
			preview.revision++;
			publishPreview(preview);
		} finally { if (!retained) held.get().close(); }
	}
	/** Current detached versions; old selection references may still own older JSON. */
	private static long previewRetainedBytes(Preview preview) {
		long bytes = 0;
		for (var item : preview.counts.values()) bytes = checkedAdd(bytes, replacementActual(item.displayJson()));
		for (var item : preview.totals.values()) bytes = checkedAdd(bytes, replacementActual(item.displayJson()));
		for (var ref : preview.references.values()) bytes = checkedAdd(bytes, replacementActual(ref.displayJson()));
		return bytes;
	}
	private void retirePreviewGroup(Preview preview, String id) {
		if (preview.folded.contains(id)) return;
		// Only admitted groups need a lifetime retirement marker. A new refused
		// category is admitted under the same finite category bound immediately below.
		if (preview.folded.size() >= MAX_ENTRIES) throw new IllegalStateException("inventory fold bound");
		preview.folded.add(id);
		var references = preview.references.entrySet().iterator();
		while (references.hasNext()) {
			var entry = references.next();
			if (entry.getValue().itemId().equals(id) && !entry.getValue().aggregate()) {
				preview.retired.add(entry.getKey()); references.remove();
			}
		}
		preview.counts.keySet().removeIf(key -> key.itemId().equals(id));
		preview.witnesses.keySet().removeIf(key -> key.itemId().equals(id));
		publisher.retireGroup(preview.context, id);
	}
	private SyncPublisher.Outcome publishPreview(Preview preview) {
			if (!preview.parent.openedHandedOff) return new SyncPublisher.Outcome.Deferred(); // OPENED must precede data
			if (preview.fencePending) return new SyncPublisher.Outcome.Deferred(); // the fresh empty PREVIEW fence must be handed off before replacement data
			// Authority was checked this tick. An accepted unchanged cut needs no
			// reconstruction; queued sends retain their own independent recorders.
			if (publisher.accepted(preview.context, preview.revision)) return new SyncPublisher.Outcome.Accepted();
			if (preview.publicationEvidence == null) preview.publicationEvidence = runtime.publicationEvidence(preview.consumer, InventoryRuntime.EvidenceScope.SCANNING).orElse(null);
			if (preview.publicationEvidence == null) return new SyncPublisher.Outcome.Deferred();
			long aggregates = 0;
			for (var key : preview.counts.keySet()) if (preview.folded.contains(key.itemId()) && preview.totals.containsKey(key.itemId())) aggregates++;
			// Factory buffers/results coexist with the projection; ordinary staging pays DTO validation.
			var held = runtime.memory().tryReserve(checkedAdd(checkedAdd(8192L, checkedMultiply(preview.counts.size(), 1024L)),
				checkedMultiply(aggregates, AGGREGATE_DISPLAY_BYTES)));
			if (held.isEmpty()) { previewDelay(preview, preview.publicationDelay, "publication", Reason.MEMORY.bit()); return new SyncPublisher.Outcome.Deferred(); }
			try (var staging = held.get()) {
			if (!runtime.claimDetachedItemWork(checkedMultiply(aggregates, AGGREGATE_DISPLAY_WORK))) {
				previewDelay(preview, preview.publicationDelay, "publication", Reason.PUBLICATION_WORK.bit());
				return new SyncPublisher.Outcome.Deferred();
			}
			Map<String, InventoryResourceValue> projection = new LinkedHashMap<>();
			for (var item : preview.counts.values()) if (!preview.folded.contains(item.key().itemId())) {
				String key = opaque(preview, item.key().itemId() + "/exact/" + item.key().componentsKey());
				projection.put(key, item); preview.references.put(key, new InventorySelection(preview.input.resourceKind(), item.key(), item.key().itemId(), item.label(), item.displayJson(), false, "attention"));
			}
			for (String id : preview.folded) {
				InventoryResourceValue total = preview.totals.get(id);
				if (total == null || !preview.counts.containsKey(new InventoryScanner.Key(preview.input.resourceKind(), id, ""))) continue;
				InventoryResourceValue item = aggregate(total); String key = opaque(preview, id + "/aggregate");
				preview.references.values().removeIf(ref -> ref.itemId().equals(id) && !ref.aggregate());
				// Aggregate selection needs only ID/amount; do not retain a second display owner after staging closes.
				projection.put(key, item); preview.references.put(key, new InventorySelection(preview.input.resourceKind(), null, id, item.label(), null, true, "attention"));
			}
			return publish(preview.context, null, projection, preview.last, preview.revision, InventoryS2CPacket.Status.UPDATING, preview.publicationEvidence);
			}
	}
	private static InventoryResourceValue add(@Nullable InventoryResourceValue previous, InventoryResourceValue item) {
		return item.withAmount(previous == null ? item.amount() : Math.addExact(previous.amount(), item.amount()));
	}
	private void invalidatePreview(Session session, Preview preview) {
		boolean invalidTransition = preview.context.stateFence() == 1;
		preview.samplingDelay.cancel(); preview.projectionDelay.cancel(); preview.publicationDelay.cancel();
		preview.consumer.discardObservation();
		if (preview.publicationEvidence != null) { preview.publicationEvidence.close(); preview.publicationEvidence = null; }
		preview.checkedTick = tick; // invalid control is independent of item/read admission
		boolean registered = true;
		if (preview.context.stateFence() == 1) {
			publisher.cancel(preview.context);
			if (session.access != null) {
				preview.context = new SyncPublisher.Context(preview.context.consumerId(), preview.context.recipient(), session.access.stamp(), 2, preview.context.baseline());
				registered = publisher.register(preview.context, route(session, preview.request, null, null, null, preview.input.resourceKind()));
			}
		}
		preview.terminal = true; preview.last = null; preview.references.clear(); preview.witnesses.clear(); preview.counts.clear();
		preview.totals.clear(); preview.keys.clear(); preview.folded.clear(); preview.retired.clear(); preview.categories = 0; preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.entries.clear();
		preview.rebaselineNeeded = !registered; preview.fencePending = false; preview.republishPending = false; preview.metadataChanged = false;
		preview.metadataCeiling = effectiveDisplayCeiling();
		if (preview.metadataMemory != null) { preview.metadataMemory.close(); preview.metadataMemory = null; }
		if (invalidTransition) InventoryCaptureDiagnostics.previewInvalidated(preview.request, preview.input.resourceKind(), preview.input.target(), preview.input.face());
		// A failed registration must not silently drop the terminal control; the
		// next normal tick retries this context while the consumer stays retired.
		if (registered) publisher.status(preview.context, InventoryS2CPacket.Status.INVALID);
	}
	private InventoryResourceValue aggregate(InventoryResourceValue item) {
		return InventoryResourceCodec.value(new InventoryScanner.Key(item.resourceKind(), item.resourceId(), ""), item.amount(), item.label(),
			aggregateJson(item.resourceKind(), item.resourceId(), item.label()), true);
	}
	private @Nullable String aggregateJson(InventoryResourceKind kind, String id, String label) {
		return kind == InventoryResourceKind.ITEM ? InventoryItemCodec.aggregateDisplay(id, label, effectiveDisplayCeiling()).displayJson()
			: InventoryFluidDisplayCodec.encode(new InventoryFluidDisplayCodec.Projection(id));
	}
	private static String opaque(Preview preview, String identity) { return preview.keys.computeIfAbsent(identity, ignored -> UUID.randomUUID().toString()); }
	private void sampleTracking(Tracking lease, InventoryRuntime.@Nullable ProbeAttempt attempt) {
		var observed = attempt == null ? runtime.step(lease.consumer) : runtime.step(lease.consumer, attempt);
		if (observed.isEmpty()) return;
		CaptureResult result = observed.get().result();
		if (result.availability() != CaptureResult.Availability.READABLE) {
			invalidate(lease); return;
		}
		if (lease.invalid) {
			lease.invalid = false; lease.state++; lease.completed = null;
			lease.bootstrapLifecycle = InventoryS2CPacket.Status.UPDATING; resetRecipients(lease);
		}
		try {
			for (var item : observed.get().slots()) if (item != null && item.resourceKind() == lease.selection.kind() && item.key().itemId().equals(lease.selection.itemId())) {
				long total, count;
				try {
					total = Math.addExact(lease.total, item.amount());
					count = item.key().equals(lease.selection.exact()) ? Math.addExact(lease.count, item.amount()) : lease.count;
				} catch (ArithmeticException overflow) {
					if (item.aggregateRequired() && !lease.folded) retireTrackingGroup(lease);
					throw overflow;
				}
				if (item.aggregateRequired() && !lease.folded) retireTrackingGroup(lease);
				lease.total = total; lease.count = count;
			}
		} catch (ArithmeticException overflow) {
			InventoryCaptureDiagnostics.warn("tracking-count", lease.input, null, () -> "reason=count-overflow", overflow);
			lease.consumer.stopScanning(); lease.scanning = false; lease.nextSweep = tick + settings.getTracking().getPeriodTicks();
			incompleteTracking(lease);
			return;
		}
		if (result.completeness() == CaptureResult.Completeness.CONTINUE) return;
		lease.consumer.stopScanning();
		lease.scanning = false; lease.nextSweep = tick + settings.getTracking().getPeriodTicks();
		if (result.completeness() != CaptureResult.Completeness.COMPLETE) {
			incompleteTracking(lease); return;
		}
		lease.consumer.retainCompleted();
		if (lease.completedEvidence != null) lease.completedEvidence.close();
		lease.completedEvidence = runtime.publicationEvidence(lease.consumer, InventoryRuntime.EvidenceScope.COMPLETED).orElse(null);
		// A blocked immutable seed cut must not delay or relabel the first
		// successful full result. Retained offered ownership remains available
		// for the publisher's strictly newer, explicit selected-key replacement.
		if (lease.bootstrap != null) for (Recipient recipient : lease.recipients.values()) publisher.withdraw(recipient.context);
		lease.revision++; lease.completed = new PublicationObservation(result, observed.get().inventorySweepEvidence());
		lease.recomputingScope = false;
		lease.completedCount = lease.count; lease.completedTotal = lease.total; lease.completedFolded = lease.folded;
		lease.bootstrap = null;
		if (lease.bootstrapEvidence != null) { lease.bootstrapEvidence.close(); lease.bootstrapEvidence = null; }
		publishTracking(lease);
	}
	private void retireTrackingGroup(Tracking lease) {
		if (!lease.selection.aggregate()) lease.creationClosed = true;
		lease.folded = true;
		// A selected exact subtotal is not an all-ID total. Historical received
		// values may remain grey, but no queued exact seed may escape the fold.
		lease.bootstrap = null;
		if (lease.bootstrapEvidence != null) { lease.bootstrapEvidence.close(); lease.bootstrapEvidence = null; }
		for (var entry : lease.recipients.entrySet()) {
			Recipient recipient = entry.getValue(); recipient.folded = true;
			if (publisher.retireGroup(recipient.context, lease.selection.itemId())) recipient.rebaselineNeeded = true;
		}
		// Last complete evidence contains the whole-ID total independently of
		// this incomplete replacement scan. Never publish its partial accumulator.
		if (lease.completed != null) { lease.completedFolded = true; lease.revision++; }
	}
	private void incompleteTracking(Tracking lease) {
		if (lease.completed == null && lease.bootstrapLifecycle != InventoryS2CPacket.Status.INCOMPLETE) {
			lease.bootstrapLifecycle = InventoryS2CPacket.Status.INCOMPLETE; lease.revision++;
		}
		for (Recipient recipient : lease.recipients.values()) {
			publisher.status(recipient.context, InventoryS2CPacket.Status.INCOMPLETE);
			// Withdrawal must not be undone by the routine same-baseline replay.
			// A fresh repair baseline can explicitly restage the retained seed.
			recipient.bootstrapOffered = true;
		}
	}
	private static InventoryS2CPacket.Status trackingLifecycle(Tracking lease) {
		return lease.invalid ? InventoryS2CPacket.Status.INVALID : lease.completed == null
			? lease.bootstrapLifecycle : InventoryS2CPacket.Status.UPDATING;
	}
	private void invalidate(Tracking lease) {
		lease.creationClosed = true;
		if (lease.consumer.scopeTransition()) { recomputeScope(lease); return; }
		lease.recomputingScope = false;
		lease.projectionDelay.cancel(); lease.registrationDelay.cancel();
		for (Recipient recipient : lease.recipients.values()) recipient.publicationDelay.cancel();
		lease.consumer.discardObservation();
		if (lease.completedEvidence != null) { lease.completedEvidence.close(); lease.completedEvidence = null; }
		if (lease.bootstrapEvidence != null) { lease.bootstrapEvidence.close(); lease.bootstrapEvidence = null; }
		lease.bootstrap = null; // permanent, including cancel/register during recovery
		lease.checkedTick = tick; // withdrawing valid state never waits for another probe grant
		if (!lease.invalid) {
			lease.state++; lease.invalid = true; lease.completed = null;
			resetRecipients(lease);
		}
		// INVALID recovery keeps the established per-tick eligible attempt instead
		// of waiting a normal sweep period; a budget-deferred attempt stays due.
		lease.scanning = false; lease.nextSweep = tick;
	}
	/** New physical scope, same Sable FLUID lease. Retained quantity is client history, never new proof. */
	private void recomputeScope(Tracking lease) {
		lease.creationClosed = true;
		lease.consumer.recomputeScope();
		if (lease.completedEvidence != null) { lease.completedEvidence.close(); lease.completedEvidence = null; }
		if (lease.bootstrapEvidence != null) { lease.bootstrapEvidence.close(); lease.bootstrapEvidence = null; }
		lease.completed = null; lease.count = lease.total = 0; lease.folded = lease.selection.aggregate();
		lease.invalid = false; lease.recomputingScope = true; lease.scanning = true; lease.checkedTick = tick;
		lease.bootstrapLifecycle = InventoryS2CPacket.Status.UPDATING; lease.state++; lease.revision++;
		resetRecipients(lease);
	}
	/**
	 * Operation-local fan-out holder. The first recipient's successful publish
	 * workspace becomes the owner ticket for one encoded projection; all later
	 * recipients of the same projection reuse the immutable fragment while still
	 * owning their own CaptureResult, proof, admission and receipt reservation.
	 * The holder is closed before the publication operation returns.
	 */
	@org.jetbrains.annotations.NotNullByDefault
	private final class TrackingFanout implements AutoCloseable {
		private final Map<ProjectionKey, EncodedOwner> prepared = new LinkedHashMap<>();
		private @Nullable Acquired acquire(InventoryResourceKind kind, Map<String, InventoryResourceValue> items,
			RetainedMemoryLedger.Ticket owner, long upper) {
			if (items.size() != 1) return null;
			var entry = items.entrySet().iterator().next();
			InventoryResourceValue item = entry.getValue();
			ProjectionKey key = new ProjectionKey(kind, entry.getKey(), item.key(), item.amount(), item.label(),
				item.displayJson(), item.aggregateRequired());
			@Nullable EncodedOwner existing = prepared.get(key);
			if (existing != null) return new Acquired(existing.fragment(), false);
			// The caller has already reserved the publish workspace. Encoding must
			// not run before this point.
			var fragment = InventoryEncodedFragment.encode(kind, items);
			owner.commit(upper);
			prepared.put(key, new EncodedOwner(fragment, owner));
			@Nullable java.util.function.IntConsumer observer = encodedFragmentObserver;
			if (observer != null) observer.accept(1);
			return new Acquired(fragment, true);
		}
		@Override public void close() {
			for (EncodedOwner value : prepared.values()) value.owner().close();
			prepared.clear();
		}
	}
	@org.jetbrains.annotations.NotNullByDefault
	private record ProjectionKey(InventoryResourceKind kind, String entryKey, InventoryScanner.Key itemKey,
		long amount, String label, @Nullable String displayJson, boolean aggregate) {}
	@org.jetbrains.annotations.NotNullByDefault
	private record EncodedOwner(InventoryEncodedFragment fragment, RetainedMemoryLedger.Ticket owner) {}
	@org.jetbrains.annotations.NotNullByDefault
	private record Acquired(InventoryEncodedFragment fragment, boolean ownerTransferred) {}
	private void publishTracking(Tracking lease) {
		var fanout = new TrackingFanout();
		try {
			for (Recipient recipient : List.copyOf(lease.recipients.values())) publishTracking(lease, recipient, fanout);
		} finally {
			fanout.close();
		}
	}
	private void publishTracking(Tracking lease, Recipient recipient) {
		publishTracking(lease, recipient, null);
	}
	private void publishTracking(Tracking lease, Recipient recipient, @Nullable TrackingFanout fanout) {
		if (lease.invalid || lease.recomputingScope || lease.checkedTick != tick || lease.consumer.invalidated()) return;
		if (recipient.rebaselineNeeded) {
			// cancel/register is isolated to this recipient and does not reopen the
			// capture, rewrite original selection, or change marker expiry/cooldown.
			rebaselineTracking(recipient.context.recipient(), lease, recipient);
			return;
		}
		boolean provisional = lease.completed == null;
		@Nullable Bootstrap bootstrap = lease.bootstrap;
		if (provisional && (bootstrap == null || recipient.bootstrapOffered)) return;
		if (provisional && lease.bootstrapEvidence == null) return;
		if (!provisional && lease.completedEvidence == null) lease.completedEvidence = runtime.publicationEvidence(lease.consumer, InventoryRuntime.EvidenceScope.COMPLETED).orElse(null);
		if (!provisional && lease.completedEvidence == null) return;
		if (!provisional && publisher.accepted(recipient.context, lease.revision)) return;
		var selection = lease.selection;
		recipient.folded |= provisional ? selection.aggregate() : lease.completedFolded;
		@Nullable String presentation = !provisional && recipient.folded ? null
			: lease.selectionPresentation == null ? null : lease.selectionPresentation.displayJson();
		long bytes = trackingStagingBytes(selection.label(), presentation);
		if (!provisional && recipient.folded) bytes = checkedAdd(bytes, checkedAdd(AGGREGATE_DISPLAY_BYTES, AGGREGATE_JSON_BYTES));
		var grant = runtime.memory().tryReserve(bytes);
		if (grant.isEmpty()) { delay(recipient.publicationDelay, Module.INVENTORY_TRACKING, recipient.context.baseline(), "publication", Reason.MEMORY.bit()); return; }
		try (var staging = grant.get()) {
		if (!provisional && recipient.folded && !runtime.claimDetachedItemWork(AGGREGATE_DISPLAY_WORK)) {
			delay(recipient.publicationDelay, Module.INVENTORY_TRACKING, recipient.context.baseline(), "publication", Reason.PUBLICATION_WORK.bit()); return;
		}
		@Nullable String projected = !provisional && recipient.folded
			? aggregateJson(selection.kind(), selection.itemId(), selection.label()) : presentation;
		var item = InventoryResourceCodec.value(recipient.folded ? new InventoryScanner.Key(selection.kind(), selection.itemId(), "") : selection.exact(),
			provisional ? java.util.Objects.requireNonNull(bootstrap).count() : recipient.folded ? lease.completedTotal : lease.completedCount,
			selection.label(), projected, recipient.folded);
		var outcome = publish(recipient.context, fanout, Map.of(recipient.folded ? "aggregate" : "selected", item),
			lease.completed, lease.revision, lease.bootstrapLifecycle,
			provisional ? lease.bootstrapEvidence : lease.completedEvidence);
		if (provisional && outcome instanceof SyncPublisher.Outcome.Accepted) recipient.bootstrapOffered = true;
		}
	}
	/** Detached Item validation allocates UTF-8 arrays before the nested encode grant. */
	private static long trackingStagingBytes(String label, @Nullable String presentation) {
		return checkedAdd(4096L, checkedAdd(utf8Bytes(label), utf8Bytes(presentation)));
	}
	private SyncPublisher.Outcome publish(SyncPublisher.Context context, @Nullable TrackingFanout fanout,
		Map<String, InventoryResourceValue> items, @Nullable PublicationObservation observation, long revision,
		InventoryS2CPacket.Status bootstrapLifecycle, @Nullable InventoryPublicationEvidence evidence) {
		// Measured per-item encode workspace at the effective display ceiling, not an absolute-frame mirror.
		long upper = 8192L;
		for (var item : items.values()) {
			long encoded = checkedAdd(32L, checkedAdd(utf8Bytes(item.key().itemId()), checkedAdd(utf8Bytes(item.key().componentsKey()),
				checkedAdd(utf8Bytes(item.label()), utf8Bytes(item.displayJson())))));
			upper = checkedAdd(upper, checkedAdd(checkedMultiply(encoded, 8L), 2048L));
		}
		var admission = runtime.memory().tryReserve(upper);
		if (admission.isEmpty()) { publicationMemory(context); return new SyncPublisher.Outcome.Deferred(); }
		publicationProgress(context);
		var workspace = admission.get();
		boolean transferred = false;
		try {
		InventoryResourceKind kind = items.values().iterator().hasNext() ? items.values().iterator().next().resourceKind() : resourceKind(context);
		@Nullable InventoryEncodedFragment shared = null;
		if (fanout != null) {
			@Nullable Acquired acquired = fanout.acquire(kind, items, workspace, upper);
			if (acquired != null) { shared = acquired.fragment(); transferred = acquired.ownerTransferred(); }
		}
		CaptureResult.OpaqueKeyedFragment fragment;
		if (shared != null) {
			fragment = shared.fragment();
		} else {
			Map<String, CaptureResult.OpaqueValue> payload = new LinkedHashMap<>();
			items.forEach((key, item) -> payload.put(key, InventoryResourceCodec.encode(kind, item)));
			fragment = new CaptureResult.OpaqueKeyedFragment(InventoryResourceCodec.id(kind), payload);
		}
		var projection = new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, fragment.entries().keySet());
		var ledger = new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, 256L));
		if (observation == null) return publisher.publishBootstrap(fragment, revision, bootstrapLifecycle, projection, context, ledger, evidence);
		CaptureResult result = observation.result();
		CaptureResult publication = new CaptureResult(Optional.of(fragment),
			new CaptureResult.Coverage(result.coverage().demandStamp(), revision, result.coverage().scanned(), result.coverage().expected()),
			CaptureResult.Availability.READABLE, result.completeness(), result.consistency(), result.sourceVersion(), Optional.empty());
		return publisher.publish(publication, observation.evidence(), projection, context, ledger, evidence);
		} finally {
			if (!transferred) workspace.close();
		}
	}
	private void publicationMemory(SyncPublisher.Context context) {
		Session session = sessions.get(context.recipient());
		if (session != null) for (Preview preview : session.previews.values()) if (preview.context.equals(context)) {
			previewDelay(preview, preview.publicationDelay, "publication", Reason.MEMORY.bit()); return;
		}
		for (Tracking lease : tracking.values()) {
			Recipient recipient = lease.recipients.get(context.recipient());
			if (recipient != null && recipient.context.equals(context)) {
				delay(recipient.publicationDelay, Module.INVENTORY_TRACKING, context.baseline(), "publication", Reason.MEMORY.bit()); return;
			}
		}
	}
	private void publicationProgress(SyncPublisher.Context context) {
		Session session = sessions.get(context.recipient());
		if (session != null) for (Preview preview : session.previews.values()) if (preview.context.equals(context)) {
			if (preview.publicationDelay.outstanding()) preview.publicationDelay.progress(previewCycle); return;
		}
		for (Tracking lease : tracking.values()) {
			Recipient recipient = lease.recipients.get(context.recipient());
			if (recipient != null && recipient.context.equals(context)) {
				if (recipient.publicationDelay.outstanding()) recipient.publicationDelay.progress(trackingCycle); return;
			}
		}
	}
	private void syncRecipients(Tracking lease) {
		boolean pendingRegistration = false;
		lease.audienceDemand = false;
		@Nullable ServerMarker canonical = host.activeMarker(lease.marker.id()).orElse(null);
		if (canonical == null) {
			for (Recipient recipient : lease.recipients.values()) publisher.cancel(recipient.context);
			lease.recipients.clear(); lease.registrationDelay.cancel(); return;
		}
		lease.marker = canonical;
		Set<UUID> audience = new HashSet<>(canonical.recipients());
		var fanout = new TrackingFanout();
		try {
			for (UUID player : audience) {
				@Nullable SessionAccess access = currentSessionAccess(player);
				Recipient old = lease.recipients.get(player);
				if (access == null || !access.compatibleKinds().contains(lease.selection.kind())
					|| !inventoryAllowed(canonical.authorization(), player, lease.selection.kind())
					|| !host.knows(player, lease.marker.id())) {
					if (old != null) { publisher.cancel(old.context); lease.recipients.remove(player); } continue;
				}
				lease.audienceDemand = true;
				if (old != null && old.access.stamp().equals(access.stamp())) continue;
				if (old != null) { publisher.cancel(old.context); lease.recipients.remove(player); }
				addRecipient(lease, player, null, fanout);
				// A genuinely new consumer/late join must be checked immediately; a
				// renewal of the same recipient keeps its own completed-check due.
				if (old == null && lease.recipients.containsKey(player)) lease.healthChecked = false;
				pendingRegistration |= !lease.recipients.containsKey(player);
			}
		} finally { fanout.close(); }
		for (UUID player : List.copyOf(lease.recipients.keySet())) if (!audience.contains(player)) {
			Recipient removed = lease.recipients.remove(player); if (removed != null) publisher.cancel(removed.context);
		}
		if (!lease.audienceDemand) lease.registrationDelay.cancel();
		else if (!pendingRegistration && lease.registrationDelay.outstanding()) lease.registrationDelay.progress(trackingCycle);
	}
	private void addRecipient(Tracking lease, UUID player, @Nullable RetainedMemoryLedger.Ticket held) {
		addRecipient(lease, player, held, null);
	}
	private void addRecipient(Tracking lease, UUID player, @Nullable RetainedMemoryLedger.Ticket held,
		@Nullable TrackingFanout fanout) {
		@Nullable ServerMarker canonical = host.activeMarker(lease.marker.id()).orElse(null);
		@Nullable SessionAccess access = currentSessionAccess(player);
		if (canonical == null || access == null || !access.compatibleKinds().contains(lease.selection.kind())
			|| !inventoryAllowed(canonical.authorization(), player, lease.selection.kind())
			|| canonical.expiresAtTick() <= tick || !canonical.recipients().contains(player)
			|| !host.knows(player, lease.marker.id())) { if (held != null) held.close(); return; }
		Session session = sessions.get(player);
		if (session == null || session.access == null || !session.access.stamp().equals(access.stamp())) { if (held != null) held.close(); return; }
		SyncPublisher.Context context = context("tracking/" + lease.marker.id().value(), player, access, lease.state);
		var route = route(session, 0, lease.marker.id(), lease.marker.targetKey(), lease.selection.itemPingType(), lease.selection.kind());
		boolean registered = held == null ? publisher.register(context, route) : publisher.register(context, route, held);
		if (!registered) {
			trackingDelay(lease, lease.registrationDelay, "registration", (publisher.remainingRegistrations() == 0 ? Reason.SUBJECT_CAPACITY : Reason.MEMORY).bit()); return;
		}
		Recipient recipient = new Recipient(access, context);
		// A replacement recipient (RESYNC or a new audience member) keeps the
		// retained completed projection's fold state, so the last completed
		// all-ID total and the component-too-long quality survive until a new
		// complete observation replaces that evidence.
		if (lease.completed != null) recipient.folded = lease.completedFolded;
		lease.recipients.put(player, recipient);
		publisher.status(context, trackingLifecycle(lease));
		publishTracking(lease, recipient, fanout);
	}
	private void resetRecipients(Tracking lease) {
		for (Recipient recipient : lease.recipients.values()) publisher.cancel(recipient.context);
		lease.recipients.clear(); syncRecipients(lease);
	}
	private void resync(UUID player, MarkerId marker, InventoryResourceKind kind) {
		@Nullable Tracking lease = tracking.get(marker); if (lease == null) return;
		@Nullable ServerMarker canonical = host.activeMarker(marker).orElse(null);
		if (canonical == null || canonical.expiresAtTick() <= tick) return;
		if (lease.selection.kind() != kind) return;
		int resyncPeriods = settings.getTracking().getResyncMinPeriods();
		@Nullable Recipient old = lease.recipients.get(player); if (old == null || resyncPeriods > 0 && tick < old.resyncAt) return;
		if (publisher.pending(old.context)) return; // admitted fragment work is serviceable; never restart it
		long next = tick + (long) resyncPeriods * settings.getTracking().getPeriodTicks();
		if (lease.bootstrap != null) lease.revision++;
		publisher.cancel(old.context); lease.recipients.remove(player); addRecipient(lease, player, null);
		@Nullable Recipient replacement = lease.recipients.get(player); if (replacement != null) replacement.resyncAt = next;
	}
	/** OPENED handoff is a temporary ordering gate, distinct from permanent context authorization. */
	private boolean previewOpened(SyncPublisher.Context context) {
		@Nullable Session session = sessions.get(context.recipient());
		if (session == null) return false;
		for (Preview preview : session.previews.values()) if (preview.context.equals(context)) return preview.parent.openedHandedOff;
		return true; // Tracking has its independent known-marker and baseline gates.
	}
	private boolean publicationAllowed(SyncPublisher.Context context) {
		if (closeRequested || deferredDisconnects.contains(context.recipient())) return false;
		Session session = sessions.get(context.recipient());
		@Nullable SessionAccess access = currentSessionAccess(context.recipient());
		if (session == null || access == null || !context.sessionView().equals(access.stamp())) return false;
		for (Preview preview : session.previews.values()) if (preview.context.equals(context)) {
			PreviewRequest parent = preview.parent;
			return session.requests.get(parent.request) == parent
				&& (parent.laneMask & (1 << preview.input.resourceKind().wireId())) != 0
				&& access.compatibleKinds().contains(preview.input.resourceKind());
		}
		for (Tracking lease : tracking.values()) {
			Recipient recipient = lease.recipients.get(context.recipient());
			if (recipient != null && recipient.context.equals(context)) {
				@Nullable ServerMarker marker = host.activeMarker(lease.marker.id()).orElse(null);
				return marker != null && marker.expiresAtTick() > tick
					&& marker.recipients().contains(context.recipient())
					&& inventoryAllowed(marker.authorization(), context.recipient(), lease.selection.kind())
					&& access.compatibleKinds().contains(lease.selection.kind())
					&& !deferredRemovals.contains(marker.id()) && !deferredDisconnects.contains(marker.owner())
					&& host.knows(context.recipient(), marker.id());
			}
		}
		return false;
	}
	/**
	 * A queued POLICY hint remains the exact desired lane view only while the
	 * current server policy agrees with its types. A permission-only change does
	 * not advance the presentation view, so the exact pending owner alone would
	 * otherwise let an older hint survive into the next drain.
	 */
	private boolean currentPolicyMatches(UUID recipient, InventoryS2CPacket packet, long expectedRevision) {
		return host.policy(recipient)
			.map(policy -> policy.revision() == expectedRevision
				&& policy.types(packet.resourceKind()).equals(packet.allowedTypes()))
			.orElse(false);
	}
	private @Nullable SessionAccess currentSessionAccess(UUID player) {
		return host.session(player).orElse(null);
	}
	private SyncPublisher.Context context(String consumer, UUID recipient, SessionAccess access, long state) {
		return new SyncPublisher.Context(consumer, recipient, access.stamp(), state, ++baseline);
	}
	private InventorySyncPublisher.Route route(Session session, long request, @Nullable MarkerId marker, @Nullable TargetKey targetKey, @Nullable String pingType, InventoryResourceKind kind) {
		// Preview carries a request-scoped internal quota token; a candidate has no
		// marker key and its opaque locator is never a stable identity. Tracking
		// uses the marker's frozen authoritative key, never its physical read input.
		String quota = targetKey == null ? "preview/" + session.epoch + "/" + request : targetKey.toString();
		SessionAccess access = java.util.Objects.requireNonNull(session.access);
		return new InventorySyncPublisher.Route(session.epoch, access.epoch(), access.view(), request, marker, quota, pingType, kind);
	}
	private InventoryResourceKind resourceKind(SyncPublisher.Context context) {
		Session session = sessions.get(context.recipient());
		if (session != null) for (Preview preview : session.previews.values()) if (preview.context.equals(context)) return preview.input.resourceKind();
		for (Tracking lease : tracking.values()) if (lease.recipients.values().stream().anyMatch(recipient -> recipient.context.equals(context))) return lease.selection.kind();
		throw new IllegalStateException("unregistered inventory context");
	}
	private static String identity(InventorySelection selection) { return selection.kind() + "/" + selection.itemId() + "/" + (selection.aggregate() ? "aggregate" : selection.exact().componentsKey()); }
	private static String variantClaim(InventoryScanner.Key key) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest((key.kind() + "\u0000" + key.itemId() + "\u0000" + key.componentsKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
	private void closePreview(Session session, long request) {
		@Nullable InventoryS2CPacket pendingOpened = session.pendingOpened.remove(request);
		if (pendingOpened != null) publisher.cancelControl(session.recipient, pendingOpened);
		for (var kind : InventoryResourceKind.values()) closeLane(session, new LaneKey(request, kind));
		@Nullable PreviewRequest parent = session.requests.remove(request);
		if (parent != null) { parent.openedHandedOff = false; parent.memory.close(); }
	}
	private void closeLane(Session session, LaneKey lane) {
		Preview preview = session.previews.remove(lane); if (preview == null) return;
		publisher.cancel(preview.context); preview.consumer.close(); preview.entries.forEach(RetainedMemoryLedger.Ticket::close); preview.memory.close();
		if (preview.publicationEvidence != null) preview.publicationEvidence.close();
		if (preview.deniedEvidence != null) preview.deniedEvidence.close();
		if (preview.metadataMemory != null) preview.metadataMemory.close();
	}
	public void remove(MarkerId marker) {
		if (publishing) { if (tracking.containsKey(marker)) deferredRemovals.add(marker); return; }
		Tracking lease = tracking.remove(marker); if (lease == null) return;
		lease.initialGrants.values().forEach(InventoryRuntime.PublicationGrant::close); lease.initialGrants.clear();
		lease.recipients.values().forEach(recipient -> publisher.cancel(recipient.context)); lease.consumer.close(); lease.memory.close();
		if (lease.creationMemory != null) lease.creationMemory.close();
		if (lease.creationEvidence != null) lease.creationEvidence.close();
		if (lease.presentationMemory != null) lease.presentationMemory.close();
		if (lease.completedEvidence != null) lease.completedEvidence.close();
		if (lease.bootstrapEvidence != null) lease.bootstrapEvidence.close();
		if (lease.deniedEvidence != null) lease.deniedEvidence.close();
	}
	public void disconnect(UUID player) {
		if (publishing) { deferredDisconnects.add(player); return; }
		publisher.cancelControls(player);
		publisher.releaseData(player); // a disconnected recipient keeps no pending gate
		Session session = sessions.remove(player);
		if (session != null) { for (long request : List.copyOf(session.requests.keySet())) closePreview(session, request); session.memory.close(); }
		for (Tracking lease : List.copyOf(tracking.values())) {
			var initial = lease.initialGrants.remove(player); if (initial != null) initial.close();
			lease.creationDenied.remove(player);
			if (lease.marker.owner().equals(player)) remove(lease.marker.id());
			else { Recipient recipient = lease.recipients.remove(player); if (recipient != null) publisher.cancel(recipient.context); }
		}
	}
	@Override public void close() {
		if (publishing) { closeRequested = true; return; }
		closePublicationOpportunity();
		for (UUID player : List.copyOf(sessions.keySet())) disconnect(player);
		for (MarkerId id : List.copyOf(tracking.keySet())) remove(id);
		previewVariantClaims.clear(); previewVariants.clear(); variantMemory.values().forEach(RetainedMemoryLedger.Ticket::close); variantMemory.clear();
		publisher.close(); runtime.close();
	}
}
