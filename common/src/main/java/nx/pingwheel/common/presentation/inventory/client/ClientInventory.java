package nx.pingwheel.common.presentation.inventory.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.monitor.client.ClientMonitorCollector;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.presentation.MarkerContentGrant;
import nx.pingwheel.common.presentation.PresentationGrant;
import nx.pingwheel.common.presentation.inventory.InventoryChecksums;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import nx.pingwheel.common.presentation.inventory.InventoryCreationSnapshot;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import nx.pingwheel.common.presentation.inventory.InventoryDiagnosticSite;
import nx.pingwheel.common.presentation.inventory.InventoryOperation;
import nx.pingwheel.common.presentation.inventory.InventoryImpact;
import nx.pingwheel.common.presentation.inventory.InventoryOutcome;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One client connection's inventory preview and tracking session.
 *
 * <p>Transport-free except for the injected packet sink: every decision here is
 * driven by decoded {@link InventoryS2CPacket} values and local tick time, so
 * the session is unit testable without a client world. The numeric counts,
 * revisions, fences and fragmented-baseline assembly live in
 * {@link InventoryClientStore}; this class owns the packet-level projection:
 * preview request channels, tracked marker channels with a bounded entry
 * metadata side map, the bounded unknown-baseline stream queue, status
 * fences, checksum comparison and throttled resync scheduling.
 *
 * <p>{@code HELLO} is attempted a bounded number of times after a connection
 * until an {@code OFFER} establishes the epoch and server periods. Every send
 * goes through the platform optional-channel path, so a server without the
 * inventory route simply never answers: the session stays unready and the UI
 * sees empty/unknown state instead of an authoritative empty inventory.
 *
 * <p>Preview publishes its partial entry list immediately. Tracking publishes
 * entry metadata only when a fragmented baseline commits or an absolute stream
 * update is applied; a partial assembly stays invisible. Missing server entries
 * stay missing (never zero) until a complete scan reports them.
 * Live tracking revisions merge per key while a separate bounded projection
 * advances the digest only through fully accepted fragment deliveries. Pending
 * deliveries do not overwrite or restart an older admitted delivery, and a
 * heartbeat for an unfinished cut is deferred rather than compared to live state.
 */
@NotNullByDefault
public final class ClientInventory {

	/** Returned by {@link #open(Target)} when no preview channel could be created. */
	public static final long NO_REQUEST = -1L;

	public static final int MAX_PREVIEW_CHANNELS = 64;
	public static final int MAX_TRACKED_CHANNELS = 512;
	public static final int MAX_PREVIEW_ENTRIES = 1024;
	public static final int MAX_METADATA_ENTRIES = 128;
	public static final int MAX_UNKNOWN_SESSIONS = 4;
	public static final int MAX_UNKNOWN_ENTRIES = 256;
	public static final int MAX_UNKNOWN_BYTES = 65536;
	public static final int MAX_UNKNOWN_SINGLETON_BYTES = 262144;
	public static final int MAX_METADATA_BYTES_PER_CHANNEL = 262144;
	public static final int MAX_PREVIEW_BYTES = 2 * 1024 * 1024;

	private static final int HELLO_RETRY_TICKS = 40;
	private static final int MAX_HELLO_ATTEMPTS = 5;
	private static final int MAX_ASSEMBLY_PARTS = 1024;
	private static final int MAX_TRACKING_BATCHES = 8;
	private static final long FALLBACK_UNKNOWN_WINDOW_TICKS = 1200L;
	private static final int STORE_CHANNELS = 512;

	/** Rendering projection of one committed entry; metadata may be absent for a fallback. */
	public record EntryView(InventoryResourceKind resourceKind, String key, String itemId, String label, @Nullable String displayJson, long count,
		boolean fallback, InventoryS2CPacket.@Nullable Status quality, @Nullable String itemPingType, @Nullable InventoryFailureReason qualityReason) {
		public EntryView(InventoryResourceKind resourceKind, String key, String itemId, String label, @Nullable String displayJson, long count,
			boolean fallback, InventoryS2CPacket.@Nullable Status quality, @Nullable String itemPingType) {
			this(resourceKind, key, itemId, label, displayJson, count, fallback, quality, itemPingType, null);
		}
		public EntryView(String key, String itemId, String label, @Nullable String displayJson, long count, boolean fallback, InventoryS2CPacket.@Nullable Status quality) {
			this(InventoryResourceKind.ITEM, key, itemId, label, displayJson, count, fallback, quality, null);
		}
		public EntryView(String key, String itemId, String label, @Nullable String displayJson, long count, boolean fallback, InventoryS2CPacket.@Nullable Status quality, @Nullable String itemPingType) {
			this(InventoryResourceKind.ITEM, key, itemId, label, displayJson, count, fallback, quality, itemPingType);
		}
		public String resourceId() { return itemId; }
	}
	public record PreviewEntryReference(InventoryResourceKind resourceKind, long requestId, long baselineId, long stateRevision, String entryKey) {
		public PreviewEntryReference(long requestId, long baselineId, long stateRevision, String entryKey) {
			this(InventoryResourceKind.ITEM, requestId, baselineId, stateRevision, entryKey);
		}
	}
	public enum DispatchOutcome { SENT, NOT_READY, STALE_REFERENCE, ALREADY_RELEASED, THROTTLED, TRANSPORT_FAILED }
	/**
	 * Monitor-only outcomes; existing diagnostic ingress counters keep their original meaning.
	 * APPLIED means an installed projection/control or an actually compared heartbeat.
	 * HELD fragments/controls and unchanged replays are neither successful applies nor rejects.
	 */
	private enum ApplyOutcome { APPLIED, REJECTED, HELD, UNCHANGED }
	public record SelectionResult(InventoryResourceKind resourceKind, long commitId, long requestId, @Nullable MarkerId markerId,
		@Nullable MarkerRejectReason rejection, @Nullable InventoryFailureReason failureReason) {}

	/**
	 * Immutable preview projection. {@code entries} may be partial and is
	 * republished immediately; {@code receivedParts}/{@code totalParts} expose
	 * scan progress and {@code completeScan} marks a finished sender scan.
	 */
	public record Preview(InventoryResourceKind resourceKind, long requestId, InventoryS2CPacket.Status status, boolean completeScan,
		int receivedParts, int totalParts, long baselineId, long statusRevision, long watermark,
		boolean invalid, @Unmodifiable List<EntryView> entries, @Nullable InventoryFailureReason failureReason) {
		public Preview { entries = List.copyOf(entries); }
		public Preview(InventoryResourceKind resourceKind, long requestId, InventoryS2CPacket.Status status, boolean completeScan,
			int receivedParts, int totalParts, long baselineId, long statusRevision, long watermark,
			boolean invalid, List<EntryView> entries) {
			this(resourceKind, requestId, status, completeScan, receivedParts, totalParts, baselineId, statusRevision, watermark, invalid, List.copyOf(entries), null);
		}

		public static Preview empty(long requestId) {
			return empty(requestId, InventoryResourceKind.ITEM);
		}
		public static Preview empty(long requestId, InventoryResourceKind kind) {
			return new Preview(kind, requestId, InventoryS2CPacket.Status.UPDATING, false, 0, 1, 0, 0, 0, false,
				List.of());
		}
	}

	/** Live per-key projection; {@code watermark} is the closed digest cut, not the newest live item. */
	public record Tracking(@Nullable InventoryResourceKind resourceKind, @Nullable MarkerId markerId, InventoryS2CPacket.Status status, boolean grey, boolean complete,
		long baselineId, long statusRevision, long watermark, @Unmodifiable List<EntryView> entries, @Nullable InventoryFailureReason failureReason) {
		public Tracking { entries = List.copyOf(entries); }
		public Tracking(@Nullable InventoryResourceKind resourceKind, @Nullable MarkerId markerId, InventoryS2CPacket.Status status, boolean grey, boolean complete,
			long baselineId, long statusRevision, long watermark, List<EntryView> entries) {
			this(resourceKind, markerId, status, grey, complete, baselineId, statusRevision, watermark, List.copyOf(entries), null);
		}
		public Tracking(@Nullable MarkerId markerId, InventoryS2CPacket.Status status, boolean grey, boolean complete,
			long baselineId, long statusRevision, long watermark, List<EntryView> entries) {
			this(InventoryResourceKind.ITEM, markerId, status, grey, complete, baselineId, statusRevision, watermark, List.copyOf(entries));
		}

		public static Tracking empty(@Nullable MarkerId markerId) {
			return new Tracking(null, markerId, InventoryS2CPacket.Status.UPDATING, false, false,
				InventoryClientStore.NO_BASELINE, -1L, 0L, List.of());
		}
	}

	/** Public counters for diagnostics and tests; no server or world state. */
	public record Stats(int previewChannels, int trackedChannels, int unknownSessions,
		int invalidPackets, long staleEpochPackets, long fencedPackets, long boundRejects,
		long droppedUnknownSessions, long expiredUnknownSessions, long repairRequests, long acceptedPackets,
		long capRejectedPackets, long capFencedStates, int helloAttempts, boolean ready, long epoch) {}

	private record PreviewKey(long requestId, InventoryResourceKind kind) {}
	@NotNullByDefault
	private static final class PendingCommit {
		final long requestId;
		final InventoryResourceKind kind;
		boolean terminalAccepted;
		PendingCommit(long requestId, InventoryResourceKind kind) { this.requestId = requestId; this.kind = kind; }
		long requestId() { return requestId; }
		InventoryResourceKind kind() { return kind; }
	}
	/** Bounded by known-marker retention; refreshes cannot restart receipt eligibility. */
	private static final class MarkerMetadata {
		final String targetType;
		final boolean sable;
		final PresentationGrant grant;
		final @Nullable InventoryResourceKind trackingKind;
		final Set<InventoryResourceKind> receiptDenials = EnumSet.noneOf(InventoryResourceKind.class);
		final Map<InventoryResourceKind, Long> retiredBaselines = new EnumMap<>(InventoryResourceKind.class);
		@Nullable InventoryCreationSnapshot creationSeed;
		@Nullable InventoryResourceKind creationKind;
		boolean creationReceived;
		boolean displayCreationSeed;

		MarkerMetadata(MarkerSnapshot marker, MarkerContentGrant content) {
			targetType = marker.targetTypeId();
			sable = marker.target() instanceof Target.ExternalBlockTarget external && external.providerId().equals("sable");
			grant = content.authorization();
			trackingKind = content.trackingKind();
			for (InventoryResourceKind kind : InventoryResourceKind.values())
				if (!grant.allowsField(nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID, kind.fieldId()))
					receiptDenials.add(kind);
		}
	}
	private static final class PreviewRequest {
		final Target target;
		final BlockFace face;
		final String targetType;
		final Map<InventoryResourceKind, Long> retiredBaselines = new EnumMap<>(InventoryResourceKind.class);
		boolean released;
		boolean sent;
		/** False until the server's complete OPENED entitlement; no lane data is accepted before it. */
		boolean opened;
		/** Only the first grant starts the scan phase clock; regrants are transport fencing. */
		boolean firstGrantSeen;
		int laneMask;
		long sendEpoch, sendPresentationEpoch, sendView;
		@Nullable InventoryPreviewLifecycle lifecycle;
		PreviewRequest(Target target, BlockFace face, String targetType) { this.target = target; this.face = face; this.targetType = targetType; }
	}
	private static final class PreviewState {
		final InventoryResourceKind resourceKind;
		final PreviewRequest request;
		final long requestId;
		final Target target;
		final BlockFace face;
		final String targetType;
		final Map<String, Long> groups = new LinkedHashMap<>();
		final Map<String, EntryView> entries = new LinkedHashMap<>();
		final Map<String, Long> itemRevisions = new LinkedHashMap<>();
		InventoryS2CPacket.Status status = InventoryS2CPacket.Status.UPDATING;
		long baselineId;
		long statusRevision;
		long watermark;
		final BitSet receivedPartIndexes = new BitSet();
		int totalParts = 1;
		boolean groupComplete;
		boolean groupRejected;
		boolean completeScan;
		boolean serverSeen;
		/** Prior-view rows are display history only until replacement data installs its own fences. */
		boolean awaitingRegrant;
		/** Same-view replacement publication must follow the regrant's empty INCOMPLETE fence. */
		boolean awaitingReplacementCut;
		int bytes;
		long controlRevision = -1L;
		long progressRevision;
		boolean terminal;
		/** A local inactivity stop lasts for this wire attempt, including same-epoch view rebuilds. */
		boolean watchdogStopped;
		@Nullable InventoryFailureReason failureReason;

		PreviewState(InventoryResourceKind resourceKind, PreviewRequest request, long requestId, Target target, BlockFace face, String targetType) {
			this.resourceKind = resourceKind;
			this.request = request;
			this.requestId = requestId;
			this.target = target;
			this.face = face; this.targetType = targetType;
		}
	}

	private static final class TrackingState {
		final InventoryResourceKind resourceKind;
		final MarkerId markerId;
		final InventoryClientStore.Channel channel;
		final Map<String, EntryView> metadata = new LinkedHashMap<>();
		final Map<String, Long> groups = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> closedEntries = new LinkedHashMap<>();
		final Map<Long, TrackingBatch> batches = new LinkedHashMap<>();
		final Map<Long, TrackingBatch> closedBatches = new LinkedHashMap<>();
		final Map<Long, Long> heartbeats = new LinkedHashMap<>();
		@Nullable TrackingBatch pending;
		InventoryS2CPacket.Status status = InventoryS2CPacket.Status.UPDATING;
		long serverRequestId;
		long baselineId = InventoryClientStore.NO_BASELINE;
		long statusRevision = -1L;
		long watermark;
		long committedChecksum;
		long nextRepairTick;
		long batchBytes;
		long closedBatchBytes;
		long completionWatermark = -1, completionChecksum, completionSince = -1, heartbeatGapSince = -1;
		boolean committed;
		/** Transport installation does not certify a completed source scan. */
		boolean sourceComplete;
		long failureWatermark = -1;
		/** Null means no retained closed cut; an empty list is a closed empty projection. */
		@Nullable @Unmodifiable List<EntryView> historical;
		InventoryS2CPacket.@Nullable Status historicalFailure;
		boolean closedCut;
		@Nullable InventoryFailureReason failureReason;

		TrackingState(InventoryResourceKind resourceKind, MarkerId markerId, InventoryClientStore.Channel channel) {
			this.resourceKind = resourceKind;
			this.markerId = markerId;
			this.channel = channel;
		}
	}

	/** A bounded logical delivery, independent of the immediately visible per-key values. */
	private static final class TrackingBatch {
		final InventoryResourceKind resourceKind;
		final long requestId;
		final long baselineId;
		final long statusRevision;
		final long watermark;
		final int totalParts;
		final Map<Integer, InventoryS2CPacket> parts = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> entries = new LinkedHashMap<>();
		long bytes;

		TrackingBatch(InventoryS2CPacket packet) {
			resourceKind = packet.resourceKind();
			requestId = packet.requestId();
			baselineId = packet.baselineId();
			statusRevision = packet.statusRevision();
			watermark = packet.watermark();
			totalParts = packet.partCount();
		}

		boolean matches(InventoryS2CPacket packet) {
			return resourceKind == packet.resourceKind() && requestId == packet.requestId() && baselineId == packet.baselineId()
				&& statusRevision == packet.statusRevision()
				&& watermark == packet.watermark() && totalParts == packet.partCount();
		}

		void add(InventoryS2CPacket packet) {
			add(packet, packet.entries());
		}

		void add(InventoryS2CPacket packet, Iterable<InventoryS2CPacket.Entry> acceptedEntries) {
			parts.put(packet.partIndex(), packet);
			mergeEntries(entries, acceptedEntries);
			bytes += packetBytes(packet);
		}

		boolean complete() {
			return parts.size() == totalParts;
		}

		InventoryS2CPacket.Status status() {
			return parts.get(totalParts - 1).status();
		}
		@Nullable InventoryFailureReason failureReason() { return parts.get(totalParts - 1).failureReason(); }
		boolean sourceComplete() {
			return complete() && parts.values().stream().anyMatch(InventoryS2CPacket::completeScan);
		}
	}

	private record UnknownKey(InventoryResourceKind resourceKind, MarkerId markerId, long baselineId, long statusRevision) {}

	private static final class UnknownSession {
		final long startTick;
		final ArrayDeque<InventoryS2CPacket> fragments = new ArrayDeque<>();
		long entries;
		long bytes;

		UnknownSession(long startTick) {
			this.startTick = startTick;
		}
	}

	private final Consumer<IPacket> sender;
	private final InteractionTimeSource time;
	private final Map<Long, InventoryPreviewLifecycle> previewLifecycles = new LinkedHashMap<>();
	private long nextPreviewId = 1L;
	private Consumer<InventoryFailureReason> trackingFailure = ignored -> {};
	private final InventoryClientStore store;
	private @Nullable ClientMonitorCollector monitor;
	private final Map<PreviewKey, PreviewState> previews = new LinkedHashMap<>();
	private final Map<Long, PreviewRequest> requests = new LinkedHashMap<>();
	private final Map<MarkerId, TrackingState> tracking = new LinkedHashMap<>();
	private final Map<UnknownKey, UnknownSession> unknown = new LinkedHashMap<>();

	private InventoryS2CPacket.@Nullable Offer offer;
	/** Accepted effective byte caps of the current OFFER; zero before the first OFFER. */
	private int effectiveDisplayBytes;
	private int effectiveFrameBytes;
	private long epoch;
	private long ticks;
	private long nextRequestId = 1L;
	private int helloAttempts;
	private boolean offered;
	private long presentationEpoch, presentationView, nextCommitId = 1;
	private final Map<InventoryResourceKind, Long> policyViews = new EnumMap<>(InventoryResourceKind.class);
	private final Map<InventoryResourceKind, @Unmodifiable Set<String>> allowedTypes = new EnumMap<>(InventoryResourceKind.class);
	private final Set<InventoryResourceKind> schemaDisabled = EnumSet.noneOf(InventoryResourceKind.class);
	private final Map<MarkerId, MarkerMetadata> knownMarkers = new LinkedHashMap<>();
	/** Retained across lane policy revocation so a marker can never change resource domain. */
	private final Map<MarkerId, InventoryResourceKind> markerKinds = new LinkedHashMap<>();
	private final Map<Long, PendingCommit> pendingCommits = new LinkedHashMap<>();
	private final Map<Long, SelectionResult> selectionResults = new LinkedHashMap<>();
	private final Set<Long> releasedSelections = new java.util.LinkedHashSet<>();
	private int invalidPackets;
	private long staleEpochPackets;
	private long fencedPackets;
	private long boundRejects;
	private long droppedUnknownSessions;
	private long expiredUnknownSessions;
	private long repairRequests;
	private long acceptedPackets;
	private long capRejectedPackets;
	private long capFencedStates;

	public ClientInventory(Consumer<IPacket> sender) {
		this(sender, InteractionTimeSource.system());
	}
	public ClientInventory(Consumer<IPacket> sender, InteractionTimeSource time) {
		this.sender = Objects.requireNonNull(sender, "sender");
		this.time = Objects.requireNonNull(time, "time");
		this.store = new InventoryClientStore(MAX_ASSEMBLY_PARTS, MAX_METADATA_ENTRIES, STORE_CHANNELS);
	}
	public void trackingFailureSink(Consumer<InventoryFailureReason> sink) { trackingFailure = Objects.requireNonNull(sink); }

	/** Whether the inventory transport has an OFFER and a current presentation session. */
	public boolean ready() {
		return offered && presentationEpoch != 0;
	}
	/** Schema-compatible transport readiness; current POLICY is only a future-input hint. */
	public boolean ready(InventoryResourceKind kind) {
		return !schemaDisabled.contains(kind) && ready();
	}
	private boolean policyReady(InventoryResourceKind kind) {
		return ready(kind) && policyViews.getOrDefault(kind, -1L) == presentationView;
	}
	private boolean allows(InventoryResourceKind kind, @Nullable String targetType) {
		return policyReady(kind) && allowedTypes.getOrDefault(kind, Set.of()).contains(targetType);
	}
	/** Current accepted dedicated-route policy hint; absence means authority is not ready, never denial. */
	public Optional<Boolean> authorization(long currentEpoch, long currentView, String targetTypeId) {
		return authorization(InventoryResourceKind.ITEM, currentEpoch, currentView, targetTypeId);
	}
	public Optional<Boolean> authorization(InventoryResourceKind kind, long currentEpoch, long currentView, String targetTypeId) {
		if (schemaDisabled.contains(kind)) return Optional.of(false);
		if (!policyReady(kind) || currentEpoch != presentationEpoch || currentView != presentationView) return Optional.empty();
		return Optional.of(allows(kind, targetTypeId));
	}
	/** Receipt-only denial survives reallow within membership; unknown kind never borrows another lane's grant. */
	public Optional<Boolean> receiptAuthorization(MarkerId markerId, long currentEpoch, long currentView, String targetTypeId) {
		if (currentEpoch != presentationEpoch || currentView != presentationView) return Optional.empty();
		@Nullable MarkerMetadata known = knownMarkers.get(markerId);
		if (known == null) return Optional.empty();
		if (!known.targetType.equals(targetTypeId)) return Optional.of(false);
		@Nullable InventoryResourceKind kind = known.trackingKind;
		if (kind == null) return Optional.empty();
		return Optional.of(inventoryAllowed(known, kind) && !known.receiptDenials.contains(kind));
	}
	private static boolean inventoryAllowed(MarkerMetadata marker, InventoryResourceKind kind) {
		return marker.grant.allowsField(nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID, kind.fieldId());
	}
	/** Installs only the atomic CREATED projection, scoped to this accepted marker and membership. */
	public boolean creationSnapshot(MarkerId markerId, InventoryCreationSnapshot snapshot) {
		@Nullable MarkerMetadata marker = knownMarkers.get(markerId);
		if (marker == null || marker.creationReceived || presentationEpoch == 0) return false;
		InventoryResourceKind kind = snapshot.resourceKind();
		if (schemaDisabled.contains(kind)) return false;
		if (!inventoryAllowed(marker, kind)) return false;
		@Nullable InventoryResourceKind previous = markerKinds.get(markerId);
		if (previous != null && previous != kind || marker.creationKind != null && marker.creationKind != kind) return false;
		markerKinds.put(markerId, kind);
		marker.creationSeed = snapshot;
		marker.creationKind = kind;
		marker.creationReceived = true;
		@Nullable TrackingState state = tracking.get(markerId);
		marker.displayCreationSeed = state == null || !state.sourceComplete;
		return true;
	}
	/**
	 * Side-effect-free admission for the optional dedicated inventory sidecar of
	 * an incoming CREATED. Ordinary markers always admit. A payload-free
	 * SUPPRESSED receipt admits only its marker identity/frozen grant, so missing
	 * inventory schema or capacity cannot hide the Basic marker. A creation
	 * snapshot must additionally pass capacity, accepted schema, grant/domain
	 * compatibility and any existing marker identity before presentation or
	 * marker-store mutation.
	 */
	public boolean admitsCreation(@Nullable MarkerSnapshot snapshot, @Nullable MarkerContentGrant content,
		@Nullable InventoryCreationSnapshot creation) {
		return admitsCreation(snapshot, content, creation, ignored -> false);
	}

	/** Client-owner-thread binding; null removes instrumentation without changing session state. */
	public void monitor(@Nullable ClientMonitorCollector collector) {
		monitor = collector;
		store.monitor(collector);
	}

	/** Scalar map sizes only: no content walk, byte re-encoding or heap estimate. */
	public void observeMonitorState() {
		@Nullable ClientMonitorCollector collector = monitor;
		if (collector == null || !collector.enabled()) return;
		collector.gauge(Lane.PREVIEW, Gauge.CHANNELS, previews.size());
		collector.gauge(Lane.TRACKING, Gauge.CHANNELS, tracking.size());
		collector.gauge(Lane.PREVIEW, Gauge.SESSIONS, offered ? 1 : 0);
		collector.gauge(Lane.TRACKING, Gauge.SESSIONS, offered ? 1 : 0);
	}

	/** Workload classification is independent of the packet's ITEM/FLUID resource lane. */
	public static @Nullable Lane monitorLane(@Nullable InventoryS2CPacket packet) {
		if (packet == null || packet.kind() == null) return null;
		return switch (packet.kind()) {
			case PREVIEW, OPENED, OPEN_REJECTED, PREVIEW_STATE, POLICY, SELECTED, REJECT -> Lane.PREVIEW;
			case SNAPSHOT, STREAM, STATUS, HEARTBEAT -> Lane.TRACKING;
			case OFFER -> null;
		};
	}
	/**
	 * Projects only metadata entries that the accepted create will supersede.
	 * The predicate must be read-only; actual eviction remains with marker-store
	 * commit after the complete CREATED has passed validation.
	 */
	public boolean admitsCreation(@Nullable MarkerSnapshot snapshot, @Nullable MarkerContentGrant content,
		@Nullable InventoryCreationSnapshot creation, Predicate<MarkerId> superseded) {
		if (content == null || content.trackingKind() == null) return true;
		if (snapshot == null) return false;
		InventoryResourceKind kind = content.trackingKind();
		@Nullable MarkerMetadata known = knownMarkers.get(snapshot.id());
		if (known != null) {
			return known.trackingKind == kind && known.targetType.equals(snapshot.targetTypeId())
				&& known.grant.equals(content.authorization());
		}
		// A payload-free SUPPRESSED receipt has no creation sidecar to install.
		// Its marker identity and frozen grant still drive Basic display and the
		// independent receipt denial, so inventory schema or channel capacity
		// cannot drop the whole CREATED. Any later Tracking data remains fenced by
		// the frozen lane grant and this marker never gains a creation seed/chat.
		if (creation == null) return true;
		if (schemaDisabled.contains(kind)) return false;
		if (creation.resourceKind() != kind
			|| !content.authorization().allowsField(
				nx.pingwheel.common.presentation.inventory.InventoryPresentation.ADAPTER_ID, kind.fieldId()))
			return false;
		int retained = knownMarkers.size();
		if (retained >= MAX_TRACKED_CHANNELS) {
			// Iterate the bounded metadata map, not all retained marker visuals.
			// Each real entry is credited once, never the incoming ID itself.
			for (MarkerId id : knownMarkers.keySet())
				if (!id.equals(snapshot.id()) && superseded.test(id)) retained--;
			if (retained >= MAX_TRACKED_CHANNELS) {
				ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_CREATION_METADATA, InventoryOperation.SELECT,
					InventoryFailureReason.TRACKING_CAPACITY, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
					-1, retained + 1L, retained, MAX_TRACKED_CHANNELS);
				return false;
			}
		}
		@Nullable InventoryResourceKind existing = markerKinds.get(snapshot.id());
		return existing == null || existing == kind;
	}
	public boolean acceptsCreationSnapshot(MarkerId markerId, @Nullable InventoryCreationSnapshot snapshot) {
		if (snapshot == null || schemaDisabled.contains(snapshot.resourceKind())) return false;
		@Nullable MarkerMetadata marker = knownMarkers.get(markerId);
		if (marker == null || !inventoryAllowed(marker, snapshot.resourceKind())) return false;
		@Nullable InventoryResourceKind existing = markerKinds.get(markerId);
		return existing == null || existing == snapshot.resourceKind();
	}
	public @Nullable InventoryCreationSnapshot creationSnapshot(MarkerId id) {
		@Nullable MarkerMetadata marker = knownMarkers.get(id);
		return marker == null ? null : marker.creationSeed;
	}
	/** A current suppressed refresh can hide the provisional display, never revoke the frozen grant. */
	public void revokeCreationSnapshot(MarkerId id) {
		@Nullable MarkerMetadata marker = knownMarkers.get(id);
		if (marker == null || marker.creationKind == null) return;
		marker.displayCreationSeed = false;
	}
	/** Accepted presentation schema gates only inventory; incompatible lanes never remain loading. */
	public void acceptedSchemas(Set<InventoryResourceKind> accepted) {
		for (InventoryResourceKind kind : InventoryResourceKind.values()) {
			if (accepted.contains(kind)) {
				schemaDisabled.remove(kind);
				for (MarkerMetadata marker : knownMarkers.values())
					if (inventoryAllowed(marker, kind)) marker.receiptDenials.remove(kind);
				for (var entry : requests.entrySet()) {
					PreviewRequest request = entry.getValue();
					if (request.released) continue;
					if (request.opened && (request.laneMask & (1 << kind.wireId())) == 0) continue;
					previews.computeIfAbsent(new PreviewKey(entry.getKey(), kind),
						key -> new PreviewState(kind, request, key.requestId(), request.target, request.face, request.targetType));
				}
				for (PreviewState state : List.copyOf(previews.values()))
					if (state.resourceKind == kind && requests.containsKey(state.requestId) && ready(kind)) sendOpen(state);
			} else {
				boolean wasDisabled = schemaDisabled.contains(kind);
				schemaDisabled.add(kind);
				int removedPreviews = (int) previews.keySet().stream().filter(key -> key.kind() == kind).count();
				int removedTracking = (int) tracking.values().stream().filter(state -> state.resourceKind == kind).count();
				for (MarkerMetadata marker : knownMarkers.values()) if (marker.creationKind == kind) {
					marker.creationSeed = null;
					marker.displayCreationSeed = false;
					marker.receiptDenials.add(kind);
				}
				for (var iterator = previews.entrySet().iterator(); iterator.hasNext();) {
					var entry = iterator.next();
					if (entry.getKey().kind() == kind) {
						entry.getValue().request.retiredBaselines.merge(kind, entry.getValue().baselineId, Math::max);
						iterator.remove();
					}
				}
				for (TrackingState state : List.copyOf(tracking.values())) if (state.resourceKind == kind) {
					store.expire(state.channel); tracking.remove(state.markerId); removeUnknownSessions(state.markerId);
				}
				if (!wasDisabled)
					ClientInventoryDiagnostics.laneSchemaDisabled(kind, removedPreviews, removedTracking);
			}
		}
	}
	public Optional<InventoryResourceKind> trackingKind(MarkerId markerId) { return Optional.ofNullable(markerKinds.get(markerId)); }
	public boolean schemaUnavailable() { return schemaDisabled.size() == InventoryResourceKind.values().length; }
	/**
	 * Same-epoch RESET keeps every frozen marker grant, Preview request/mask and
	 * displayable closed quantity while fencing old-view assembly and packets.
	 * A true epoch change clears the complete inventory session.
	 */
	public void presentationReset(long epoch, long view) {
		if (epoch == 0 || view < 0 || (epoch == presentationEpoch && view <= presentationView)) return;
		if (presentationEpoch == 0) {
			presentationEpoch = epoch; presentationView = view; policyViews.clear(); allowedTypes.clear();
			for (PreviewState state : List.copyOf(previews.values()))
				if (requests.containsKey(state.requestId) && ready(state.resourceKind)) sendOpen(state);
			return;
		}
		if (epoch != presentationEpoch) {
			purgeViews(); selectionResults.clear();
			presentationEpoch = epoch; presentationView = view; policyViews.clear(); allowedTypes.clear(); return;
		}
		presentationView = view; policyViews.clear(); allowedTypes.clear();
		for (PreviewRequest request : requests.values()) {
			for (PreviewState state : previews.values()) if (state.request == request) {
				request.retiredBaselines.merge(state.resourceKind, state.baselineId, Math::max);
				state.receivedPartIndexes.clear(); state.groupComplete = false; state.groupRejected = false; state.completeScan = false;
				state.serverSeen = false;
				state.awaitingRegrant = true;
				state.awaitingReplacementCut = true;
				state.terminal = state.watchdogStopped;
				// Transport revisions restart; attempt work evidence and inactivity time do not.
				state.controlRevision = -1;
			}
			request.opened = false;
			if (request.sent) request.sendView = view;
		}
		for (TrackingState state : tracking.values()) {
			@Nullable MarkerMetadata marker = knownMarkers.get(state.markerId);
			if (marker != null) marker.retiredBaselines.merge(state.resourceKind, state.baselineId, Math::max);
			retainHistory(state);
			state.metadata.clear();
			state.groups.clear();
			state.pending = null;
			clearCuts(state);
			store.discardAssembly(state.channel);
			state.committed = false; state.sourceComplete = false; state.committedChecksum = 0L; state.watermark = 0L;
			state.baselineId = InventoryClientStore.NO_BASELINE; state.statusRevision = -1L;
		}
		unknown.clear(); pendingCommits.clear();
	}
	/** Explicit deny-only bridge for fixtures that predate per-marker content grants. */
	public void markerCreated(@Nullable MarkerSnapshot marker) {
		markerCreated(marker, new MarkerContentGrant(PresentationGrant.denied(), null));
	}
	/** Installs the one complete per-marker grant and its immutable dedicated domain. */
	public void markerCreated(@Nullable MarkerSnapshot marker, @Nullable MarkerContentGrant content) {
		if (marker == null || content == null) return;
		if (knownMarkers.size() >= MAX_TRACKED_CHANNELS && !knownMarkers.containsKey(marker.id())) {
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_KNOWN_MARKER_METADATA, InventoryOperation.TRACKING,
				InventoryFailureReason.TRACKING_CAPACITY, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
				-1, knownMarkers.size() + 1L, knownMarkers.size(), MAX_TRACKED_CHANNELS);
			return;
		}
		if (knownMarkers.containsKey(marker.id())) return;
		@Nullable InventoryResourceKind domain = content.trackingKind();
		if (domain != null) {
			@Nullable InventoryResourceKind existing = markerKinds.get(marker.id());
			if (existing != null && existing != domain) return;
		}
		MarkerMetadata metadata = new MarkerMetadata(marker, content);
		if (domain != null) markerKinds.put(marker.id(), domain);
		knownMarkers.put(marker.id(), metadata);
	}
	public void markerRemoved(MarkerId marker) {
		knownMarkers.remove(marker); markerKinds.remove(marker); @Nullable TrackingState state = tracking.remove(marker);
		if (state != null) store.expire(state.channel); removeUnknownSessions(marker);
	}
	public @Nullable SelectionResult selectionResult(long commitId) { return selectionResults.get(commitId); }
	private void purgeViews() {
		cancelPreviewLifecycles();
		releasedSelections.clear();
		previews.clear(); requests.clear(); for (TrackingState state : tracking.values()) store.expire(state.channel);
		tracking.clear(); unknown.clear(); knownMarkers.clear(); markerKinds.clear(); pendingCommits.clear();
	}

	public long epoch() {
		return epoch;
	}

	/** Null until the first valid OFFER. */
	public InventoryS2CPacket.@Nullable Offer offer() {
		return offer;
	}

	public boolean helloPending() {
		return !offered && helloAttempts < MAX_HELLO_ATTEMPTS;
	}

	/**
	 * Advances HELLO negotiation and resync timers once per client tick.
	 * Skipped entirely when not connected so no packet is produced off-line.
	 */
	public void tick(boolean connected) {
		if (!connected) {
			cancelPreviewLifecycles();
			return;
		}
		ticks++;
		if (!offered && helloAttempts < MAX_HELLO_ATTEMPTS
			&& (helloAttempts == 0 || ticks % HELLO_RETRY_TICKS == 0)) {
			try { send(InventoryC2SPacket.hello()); }
			catch (RuntimeException failed) { ClientInventoryDiagnostics.transportFailed("hello", failed); }
			helloAttempts++;
		}
		advancePreviewLifecycles();
		expireUnknownSessions();
		for (TrackingState state : tracking.values()) {
			compareCompletion(state);
			compareHeartbeat(state);
			if (state.completionSince >= 0 && ticks - state.completionSince >= waitingWindowTicks()
				|| state.heartbeatGapSince >= 0 && ticks - state.heartbeatGapSince >= waitingWindowTicks())
				scheduleRepair(state);
		}
	}

	/** Clears every channel, metadata map and unknown-baseline buffer. */
	public void reset() {
		cancelPreviewLifecycles();
		previews.clear();
		requests.clear();
		schemaDisabled.clear();
		for (TrackingState state : tracking.values())
			store.expire(state.channel);
		tracking.clear();
		unknown.clear();
		offer = null;
		effectiveDisplayBytes = 0;
		effectiveFrameBytes = 0;
		epoch = 0L;
		ticks = 0L;
		nextRequestId = 1L;
		helloAttempts = 0;
		offered = false;
		presentationEpoch = presentationView = 0; policyViews.clear(); nextCommitId = 1; allowedTypes.clear();
		knownMarkers.clear(); markerKinds.clear(); pendingCommits.clear(); selectionResults.clear();
		releasedSelections.clear();
	}

	/**
	 * Opens a local preview request bound to one bounded target. The request id
	 * is returned immediately; before an OFFER the {@code OPEN} frame is queued
	 * and sent once the epoch is established. Returns {@link #NO_REQUEST} when
	 * the preview channel bound is reached or the target is absent.
	 */
	public long open(@Nullable Target target) {
		ClientInventoryDiagnostics.openRejected("missing-face", target, null, null);
		return NO_REQUEST; // No face is never an unsided or inferred-UP view.
	}
	/**
	 * Opens a preview request from a frozen capture. A native block target or an
	 * uncommitted external provider candidate with a real press-time face is
	 * retained verbatim: its provider locator and block-entity classification
	 * stay the captured wire identity, and no physical block or world-space
	 * coordinate is invented. A committed external target, an entity, a
	 * location, or a missing face never opens a channel.
	 */
	public long open(@Nullable CapturedPingContext frozen) {
		if (frozen == null) {
			ClientInventoryDiagnostics.openRejected("missing-context", null, null, null);
			return NO_REQUEST;
		}
		if (frozen.blockHitFace().isEmpty()) {
			ClientInventoryDiagnostics.openRejected("missing-face", frozen.resolvedTarget().target(), null,
				frozen.resolvedTarget().targetType().id());
			return NO_REQUEST;
		}
		Target target = frozen.resolvedTarget().target();
		if (!(target instanceof Target.BlockTarget)
			&& !(target instanceof Target.ExternalBlockTarget external && external.isCandidate())) {
			ClientInventoryDiagnostics.openRejected(target instanceof Target.ExternalBlockTarget
					? "committed-external-target" : "unsupported-target",
				target, frozen.blockHitFace().get(), frozen.resolvedTarget().targetType().id());
			return NO_REQUEST;
		}
		return open(target, frozen.blockHitFace().get(), frozen.resolvedTarget().targetType().id());
	}
	public long open(@Nullable Target target, @Nullable BlockFace face, String targetType) {
		return openAttempt(target, face, targetType, null);
	}
	private long openAttempt(@Nullable Target target, @Nullable BlockFace face, String targetType,
		@Nullable InventoryPreviewLifecycle lifecycle) {
		@Nullable String rejection = face == null ? "missing-face"
			: !isOpenTarget(target) ? "unsupported-target"
			: !nx.pingwheel.common.presentation.PresentationSettings.isKnownTargetType(targetType) ? "unknown-target-type"
			: schemaUnavailable() ? "schema-unavailable"
			: requests.size() >= MAX_PREVIEW_CHANNELS ? "preview-capacity" : null;
		if (rejection != null) {
			ClientInventoryDiagnostics.openRejected(rejection, target, face, targetType);
			boundRejects++;
			return NO_REQUEST;
		}
		long requestId = nextRequestId++;
		PreviewRequest request = new PreviewRequest(Objects.requireNonNull(target), Objects.requireNonNull(face, "face"), targetType);
		request.lifecycle = lifecycle;
		if (lifecycle != null) lifecycle.wireRequest = requestId;
		requests.put(requestId, request);
		for (InventoryResourceKind kind : InventoryResourceKind.values()) {
			if (schemaDisabled.contains(kind)) {
				ClientInventoryDiagnostics.laneDecision(requestId, kind, "lane-schema-disabled", targetType);
				continue;
			}
			// Current POLICY never removes a candidate lane; the server's complete
			// OPENED result decides every lane for this request.
			ClientInventoryDiagnostics.laneDecision(requestId, kind, ready(kind) ? "lane-pending-opened" : "lane-pending", targetType);
			previews.put(new PreviewKey(requestId, kind), new PreviewState(kind, request, requestId, target, face, targetType));
		}
		for (PreviewState state : List.copyOf(previews.values()))
			if (state.requestId == requestId && requests.containsKey(requestId) && ready(state.resourceKind)) sendOpen(state);
		return requestId;
	}

	/** Opens one logical held Preview. Retries replace only its wire attempt, never its frozen input. */
	public @Nullable InventoryPreviewLifecycle openPreview(@Nullable CapturedPingContext frozen) {
		if (frozen == null || frozen.blockHitFace().isEmpty() || !isOpenTarget(frozen.resolvedTarget().target())) return null;
		return openPreview(frozen.resolvedTarget().target(), frozen.blockHitFace().get(), frozen.resolvedTarget().targetType().id());
	}
	public @Nullable InventoryPreviewLifecycle openPreview(Target target, BlockFace face, String targetType) {
		if (!isOpenTarget(target) || !nx.pingwheel.common.presentation.PresentationSettings.isKnownTargetType(targetType)) return null;
		if (previewLifecycles.size() >= MAX_PREVIEW_CHANNELS) {
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_LOGICAL_PREVIEW_HANDLE, InventoryOperation.PREVIEW,
				InventoryFailureReason.REQUEST_CAPACITY, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.REJECTED, nextPreviewId,
				previewLifecycles.size() + 1L, previewLifecycles.size(), MAX_PREVIEW_CHANNELS);
			return null;
		}
		InventoryPreviewLifecycle lifecycle = new InventoryPreviewLifecycle(this, nextPreviewId++, target, face, targetType, time.nowMillis());
		previewLifecycles.put(lifecycle.localId(), lifecycle);
		startPreviewAttempt(lifecycle);
		return lifecycle;
	}
	private void startPreviewAttempt(InventoryPreviewLifecycle lifecycle) {
		if (lifecycle.cancelled || lifecycle.released || lifecycle.stopped) return;
		lifecycle.waiting = false;
		lifecycle.lastProgressMillis = time.nowMillis();
		lifecycle.attempts++;
		long request = openAttempt(lifecycle.target, lifecycle.face, lifecycle.targetType, lifecycle);
		if (request == NO_REQUEST) failPreview(lifecycle,
			schemaUnavailable() ? InventoryFailureReason.UNSUPPORTED_LANE : InventoryFailureReason.REQUEST_CAPACITY);
	}
	public boolean hasPreview(InventoryPreviewLifecycle lifecycle) {
		return lifecycle.owner() == this && previewLifecycles.get(lifecycle.localId()) == lifecycle && !lifecycle.cancelled;
	}
	public void closePreview(InventoryPreviewLifecycle lifecycle) {
		if (lifecycle.owner() != this || lifecycle.cancelled) return;
		lifecycle.cancelled = true;
		lifecycle.waiting = false;
		previewLifecycles.remove(lifecycle.localId());
		long wire = lifecycle.wireRequest;
		lifecycle.wireRequest = NO_REQUEST;
		close(wire);
	}
	/** Must run before dispatch, but leaves the current reference alive until SELECT then CLOSE. */
	public void releasePreview(InventoryPreviewLifecycle lifecycle) {
		if (lifecycle.owner() != this) return;
		lifecycle.released = true;
		lifecycle.waiting = false;
	}
	private void cancelPreviewLifecycles() {
		for (InventoryPreviewLifecycle lifecycle : previewLifecycles.values()) {
			lifecycle.cancelled = true;
			lifecycle.waiting = false;
			requests.remove(lifecycle.wireRequest);
			previews.keySet().removeIf(key -> key.requestId() == lifecycle.wireRequest);
			lifecycle.wireRequest = NO_REQUEST;
		}
		previewLifecycles.clear();
	}
	private void failPreview(InventoryPreviewLifecycle lifecycle, InventoryFailureReason reason) {
		if (!hasPreview(lifecycle) || lifecycle.released || lifecycle.waiting || lifecycle.stopped) return;
		lifecycle.lastReason = reason;
		long wire = lifecycle.wireRequest;
		lifecycle.wireRequest = NO_REQUEST;
		if (lifecycle.retriesUsed >= InventoryPreviewLifecycle.MAX_RETRIES) lifecycle.stopped = true;
		else {
			lifecycle.retriesUsed++;
			lifecycle.waiting = true;
			lifecycle.retryDeadlineMillis = time.nowMillis() + InventoryPreviewLifecycle.RETRY_DELAY_MILLIS;
		}
		// Retire rows and references before courtesy CLOSE or any reentrant transport callback.
		close(wire);
		ClientInventoryDiagnostics.previewFailed(lifecycle.localId(), wire, reason, lifecycle.retriesUsed, lifecycle.stopped);
	}
	private void advancePreviewLifecycles() {
		long now = time.nowMillis();
		for (InventoryPreviewLifecycle lifecycle : List.copyOf(previewLifecycles.values())) {
			if (lifecycle.cancelled || lifecycle.released || lifecycle.stopped) continue;
			if (lifecycle.waiting) {
				if (now >= lifecycle.retryDeadlineMillis) startPreviewAttempt(lifecycle);
				continue; // One attempt at most per advance; never catch up missed deadlines.
			}
			@Nullable PreviewRequest request = requests.get(lifecycle.wireRequest);
			if (request == null) continue;
			if (request.opened && !previewPending(request)) continue;
			long timeout = Math.max(InventoryPreviewLifecycle.MIN_INACTIVITY_MILLIS,
				offer == null ? 0L : waitingWindowTicks() * 50L);
			if (now - lifecycle.lastProgressMillis < timeout) continue;
			InventoryFailureReason reason = !ready() ? InventoryFailureReason.SESSION_TIMEOUT
				: !request.opened ? InventoryFailureReason.OPEN_TIMEOUT : InventoryFailureReason.STALL_TIMEOUT;
			if (request.opened) {
				for (PreviewState state : previews.values()) if (state.request == request && !state.terminal
					&& (!state.awaitingRegrant || state.controlRevision >= 0) && state.failureReason != null) {
					reason = state.failureReason;
					break;
				}
			}
			if (request.opened) {
				for (PreviewState state : previews.values()) if (state.request == request && !state.terminal && !state.completeScan) {
					state.failureReason = state.awaitingRegrant && state.controlRevision < 0 || state.failureReason == null ? reason : state.failureReason;
					state.terminal = true;
					state.watchdogStopped = true;
					state.status = !state.serverSeen || state.entries.isEmpty() ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.INCOMPLETE;
				}
				evaluatePreview(request);
			} else failPreview(lifecycle, reason);
		}
	}
	private boolean previewPending(PreviewRequest request) {
		return previews.values().stream().anyMatch(state -> state.request == request && !state.terminal && !state.completeScan);
	}
	private void evaluatePreview(PreviewRequest request) {
		@Nullable InventoryPreviewLifecycle lifecycle = request.lifecycle;
		if (lifecycle == null || !request.opened || previewPending(request)) return;
		for (PreviewState state : previews.values()) if (state.request == request
			&& (state.completeScan || state.serverSeen && state.status != InventoryS2CPacket.Status.INVALID && !state.entries.isEmpty())) return;
		InventoryFailureReason reason = request.laneMask == 0 ? InventoryFailureReason.FIELD_DENIED : InventoryFailureReason.STALL_TIMEOUT;
		for (PreviewState state : previews.values()) if (state.request == request && state.failureReason != null) { reason = state.failureReason; break; }
		failPreview(lifecycle, reason);
	}

	/** Releases a preview request and forgets its partial list. */
	public void close(long requestId) {
		@Nullable PreviewRequest request = requests.remove(requestId);
		previews.keySet().removeIf(key -> key.requestId() == requestId);
		if (request != null && request.sent && offered) {
			try { send(InventoryC2SPacket.close(request.sendEpoch, requestId).stamp(request.sendPresentationEpoch, request.sendView)); }
			catch (RuntimeException failed) { ClientInventoryDiagnostics.transportFailed("close", failed); }
		}
	}

	/**
	 * Sends one bounded entry selection for an open preview request. Returns
	 * {@code false} without sending when the request is unknown, the session is
	 * not ready, or any text exceeds the wire bound.
	 */
	public boolean select(long requestId, String entryKey, String itemId, String pingType) {
		return false; // SELECT cannot invent a reference from client item metadata.
	}
	public @Unmodifiable List<PreviewEntryReference> selectable(long requestId) {
		return selectable(requestId, InventoryResourceKind.ITEM);
	}
	public @Unmodifiable List<PreviewEntryReference> selectable(long requestId, InventoryResourceKind kind) {
		@Nullable PreviewState state = previews.get(new PreviewKey(requestId, kind));
		if (state == null || !state.request.opened || (state.request.laneMask & (1 << kind.wireId())) == 0
			|| !state.serverSeen || state.request.released || state.status == InventoryS2CPacket.Status.INVALID) return List.of();
		return state.entries.keySet().stream().map(key -> new PreviewEntryReference(kind, requestId, state.baselineId, state.statusRevision, key)).toList();
	}
	/** Single-use release. The injected dispatcher owns the shared courtesy limiter and transport result. */
	public DispatchOutcome select(@Nullable PreviewEntryReference reference, String itemPingType, Function<InventoryC2SPacket, DispatchOutcome> dispatch) {
		return select(reference, itemPingType, (packet, terminalAccepted) -> dispatch.apply(packet));
	}
	/** The attempt-local terminal latch survives reentrant view resets or result-cache eviction. */
	public DispatchOutcome select(@Nullable PreviewEntryReference reference, String itemPingType,
		BiFunction<InventoryC2SPacket, BooleanSupplier, DispatchOutcome> dispatch) {
		if (reference != null && releasedSelections.contains(reference.requestId())) return DispatchOutcome.ALREADY_RELEASED;
		if (reference == null || !ready(reference.resourceKind())) {
			trackingFailure.accept(InventoryFailureReason.SESSION_TIMEOUT);
			return DispatchOutcome.NOT_READY;
		}
		@Nullable PreviewState state = previews.get(new PreviewKey(reference.requestId(), reference.resourceKind()));
		if (state == null || !state.serverSeen || !state.request.opened || (state.request.laneMask & (1 << reference.resourceKind().wireId())) == 0
			|| state.baselineId != reference.baselineId() || state.statusRevision != reference.stateRevision()
			|| !state.entries.containsKey(reference.entryKey()) || !bounded(itemPingType)) {
			trackingFailure.accept(InventoryFailureReason.RETIRED_SELECTION);
			return DispatchOutcome.STALE_REFERENCE;
		}
		if (state.request.released) return DispatchOutcome.ALREADY_RELEASED;
		if (pendingCommits.size() >= 64) {
			if (state.request.lifecycle != null) releasePreview(state.request.lifecycle);
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_PENDING_SELECTIONS, InventoryOperation.SELECT,
				InventoryFailureReason.CONTROL_CAPACITY, InventoryImpact.PING_UNAVAILABLE, InventoryOutcome.REJECTED, state.requestId, 65, 64, 64);
			trackingFailure.accept(InventoryFailureReason.CONTROL_CAPACITY);
			return DispatchOutcome.NOT_READY;
		}
		if (state.request.lifecycle != null) releasePreview(state.request.lifecycle);
		state.request.released = true;
		releasedSelections.add(state.requestId);
		while (releasedSelections.size() > 64) releasedSelections.remove(releasedSelections.iterator().next());
		if (state.status == InventoryS2CPacket.Status.INVALID) {
			trackingFailure.accept(state.failureReason == null ? InventoryFailureReason.INVALID_SELECTION : state.failureReason);
			return DispatchOutcome.STALE_REFERENCE;
		}
		long commit = nextCommitId++;
		var packet = InventoryC2SPacket.select(state.resourceKind, epoch, presentationEpoch, presentationView, commit, state.requestId,
			reference.baselineId(), reference.stateRevision(), reference.entryKey(), itemPingType);
		PendingCommit pending = new PendingCommit(state.requestId, state.resourceKind);
		pendingCommits.put(commit, pending);
		DispatchOutcome outcome;
		try { outcome = dispatch.apply(packet, () -> pending.terminalAccepted); }
		catch (RuntimeException failed) {
			ClientInventoryDiagnostics.transportFailed("select", failed);
			if (pending.terminalAccepted) return DispatchOutcome.SENT;
			pendingCommits.remove(commit);
			close(state.requestId);
			trackingFailure.accept(InventoryFailureReason.TRANSPORT_FAILURE);
			return DispatchOutcome.TRANSPORT_FAILED;
		}
		if (pending.terminalAccepted) return DispatchOutcome.SENT;
		if (outcome != DispatchOutcome.SENT) pendingCommits.remove(commit);
		if (outcome == DispatchOutcome.TRANSPORT_FAILED) close(state.requestId);
		return outcome;
	}

	/**
	 * Whether this exact local preview request still exists. A purged request
	 * is absent, unlike a legitimately still-updating empty preview, so a
	 * consumer can distinguish revocation from normal loading.
	 */
	public boolean hasPreview(long requestId) {
		return previews.keySet().stream().anyMatch(key -> key.requestId() == requestId);
	}
	public boolean hasPreview(long requestId, InventoryResourceKind kind) {
		return previews.containsKey(new PreviewKey(requestId, kind));
	}

	/** Immutable preview projection; an unknown request yields an empty preview. */
	public Preview preview(long requestId) {
		return preview(requestId, InventoryResourceKind.ITEM);
	}
	public Preview preview(long requestId, InventoryResourceKind kind) {
		@Nullable PreviewState state = previews.get(new PreviewKey(requestId, kind));
		if (state == null)
			return Preview.empty(requestId, kind);
		return new Preview(kind, state.requestId, state.status, state.completeScan,
			state.receivedPartIndexes.cardinality(),
			state.totalParts, state.baselineId, state.statusRevision, state.watermark,
			state.status == InventoryS2CPacket.Status.INVALID, List.copyOf(state.entries.values()), state.failureReason);
	}

	/**
	 * Immutable tracking projection for one Ping. Committed values render with
	 * their last metadata; an invalidated channel reports {@code grey} while
	 * keeping those values.
	 */
	public Tracking tracking(@Nullable MarkerId markerId) {
		if (markerId == null)
			return Tracking.empty(null);
		@Nullable TrackingState state = tracking.get(markerId);
		if (state == null) return seededTracking(markerId, null);
		@Nullable MarkerMetadata metadata = knownMarkers.get(markerId);
		if (!state.sourceComplete && metadata != null && metadata.displayCreationSeed && metadata.creationSeed != null)
			return seededTracking(markerId, state);
		List<EntryView> entries = new ArrayList<>(state.metadata.size());
		Map<String, InventoryClientStore.Value> committed = store.values(state.channel);
		for (EntryView view : state.metadata.values()) {
			InventoryClientStore.@Nullable Value value = committed.get(view.key());
			entries.add(value == null ? view
				: new EntryView(view.resourceKind(), view.key(), view.itemId(), view.label(), view.displayJson(), value.count(),
					view.fallback(), view.quality(), view.itemPingType(), view.qualityReason()));
		}
		boolean history = !state.sourceComplete && state.historical != null;
		if (!state.sourceComplete && state.committed && state.closedCut) {
			// Fresh fragments may already be applied per key. The provisional
			// display still belongs to the last closed seed, not that partial cut.
			entries = state.closedEntries.values().stream().map(entry -> viewOf(state.resourceKind, entry)).toList();
		}
		// Keep accepted wire status for control/digest processing. A completion
		// control cannot present the retained seed as fresh before its data cut closes.
		InventoryS2CPacket.Status displayStatus = state.status;
		if (!state.sourceComplete) {
			if (state.historicalFailure != null) displayStatus = state.historicalFailure;
			else if (displayStatus == InventoryS2CPacket.Status.READY || displayStatus == InventoryS2CPacket.Status.UNCERTAIN)
				displayStatus = InventoryS2CPacket.Status.UPDATING;
		}
		return new Tracking(state.resourceKind, markerId, displayStatus,
			store.isInvalid(state.channel) || (history && !sableFluidUpdating(state, displayStatus)) || isPreviewTerminalFailure(state.status), state.sourceComplete,
			store.baselineId(state.channel), state.statusRevision, state.watermark, history ? state.historical : List.copyOf(entries), state.failureReason);
	}
	private Tracking seededTracking(MarkerId markerId, @Nullable TrackingState state) {
		@Nullable MarkerMetadata metadata = knownMarkers.get(markerId);
		@Nullable InventoryCreationSnapshot seed = metadata == null || !metadata.displayCreationSeed ? null : metadata.creationSeed;
		if (seed == null) return Tracking.empty(markerId);
		EntryView entry = new EntryView(seed.resourceKind(), seed.opaqueKey(), seed.resourceId(), seed.label(), seed.displayJson(),
			seed.amount(), seed.aggregate(), null,
			seed.annotationPingType());
		InventoryS2CPacket.Status status = state != null && state.historicalFailure != null ? state.historicalFailure
			: state != null && isPreviewTerminalFailure(state.status) ? state.status : InventoryS2CPacket.Status.UPDATING;
		return new Tracking(seed.resourceKind(), markerId, status,
			state != null && (store.isInvalid(state.channel) || state.historicalFailure != null
				|| isPreviewTerminalFailure(state.status)), false, state == null ? InventoryClientStore.NO_BASELINE : state.baselineId,
			state == null ? -1L : state.statusRevision, state == null ? 0L : state.watermark, List.of(entry), state == null ? null : state.failureReason);
	}
	private boolean sableFluidUpdating(TrackingState state, InventoryS2CPacket.Status displayStatus) {
		// Server UPDATING starts a fresh scope cut without declaring the old source
		// invalid. Retained values stay display-only; real failures remain latched.
		return sableFluid(state) && displayStatus == InventoryS2CPacket.Status.UPDATING && state.historicalFailure == null;
	}
	private boolean sableFluid(TrackingState state) {
		@Nullable MarkerMetadata marker = knownMarkers.get(state.markerId);
		return state.resourceKind == InventoryResourceKind.FLUID && marker != null && marker.sable;
	}

	public Stats stats() {
		return new Stats(requests.size(), tracking.size(), unknown.size(), invalidPackets,
			staleEpochPackets, fencedPackets, boundRejects, droppedUnknownSessions, expiredUnknownSessions,
			 repairRequests, acceptedPackets, capRejectedPackets, capFencedStates, helloAttempts, ready(), epoch);
	}

	/**
	 * Routes one decoded server response. Corrupt packets, packets for another
	 * epoch, and packets fenced by a newer status revision change nothing. An
	 * OFFER is only accepted as the first offer of this connection.
	 */
	public void accept(@Nullable InventoryS2CPacket packet) {
		@Nullable ClientMonitorCollector collector = monitor;
		if (collector == null || !collector.enabled()) { acceptUnmeasured(packet); return; }
		@Nullable Lane lane = monitorLane(packet);
		if (lane == null) { acceptUnmeasured(packet); return; }
		collector.count(lane, Counter.ACCEPTED, 0);
		collector.count(lane, Counter.REJECTED, 0);
		// These are decoded data-frame receptions, including domain-fenced,
		// duplicate and held frames; internal buffer replay is not a reception.
		if (packet != null && !packet.isCorrupt()) {
			switch (Objects.requireNonNull(packet.kind())) {
				case PREVIEW -> collector.count(lane, Counter.FRAMES_PREVIEW, 1);
				case SNAPSHOT -> collector.count(lane, Counter.FRAMES_SNAPSHOT, 1);
				case STREAM -> collector.count(lane, Counter.FRAMES_STREAM, 1);
				default -> {}
			}
		}
		collector.begin(lane, Phase.APPLY);
		try { observeApplyOutcome(lane, acceptUnmeasured(packet)); }
		finally { collector.end(lane, Phase.APPLY); }
	}

	private void observeApplyOutcome(Lane lane, ApplyOutcome outcome) {
		@Nullable ClientMonitorCollector collector = monitor;
		if (collector == null || !collector.enabled()) return;
		if (outcome == ApplyOutcome.APPLIED) collector.count(lane, Counter.ACCEPTED, 1);
		else if (outcome == ApplyOutcome.REJECTED) collector.count(lane, Counter.REJECTED, 1);
	}

	private boolean monitoring() { return monitor != null && monitor.enabled(); }

	private ApplyOutcome acceptUnmeasured(@Nullable InventoryS2CPacket packet) {
		if (packet == null || packet.isCorrupt()) {
			invalidPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.OFFER) {
			acceptOffer(packet);
			return ApplyOutcome.UNCHANGED; // OFFER has no workload-lane monitor outcome.
		}
		if (!offered || packet.epoch() != epoch) {
			staleEpochPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (packet.presentationEpoch() != presentationEpoch || packet.view() != presentationView) { fencedPackets++; return ApplyOutcome.REJECTED; }
		if (packet.kind() == InventoryS2CPacket.Kind.POLICY) {
			InventoryResourceKind lane = packet.resourceKind();
			boolean unchanged = monitoring() && policyViews.getOrDefault(lane, -1L) == packet.view()
				&& allowedTypes.getOrDefault(lane, Set.of()).equals(packet.allowedTypes());
			// POLICY is only a future-input hint. It never prunes an old Preview,
			// Tracking consumer, creation seed or receipt denial.
			allowedTypes.put(lane, packet.allowedTypes()); policyViews.put(lane, packet.view());
			ClientInventoryDiagnostics.lanePolicy(lane, !packet.allowedTypes().isEmpty(), packet.view(),
				packet.allowedTypes().size());
			return unchanged ? ApplyOutcome.UNCHANGED : ApplyOutcome.APPLIED;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.OPENED) {
			@Nullable PreviewRequest request = requests.get(packet.requestId());
			if (request == null || !ready() || packet.requestId() <= 0) { fencedPackets++; return ApplyOutcome.REJECTED; }
			if (request.opened) {
				if (request.laneMask != packet.laneBits()) { invalidPackets++; return ApplyOutcome.REJECTED; }
				return ApplyOutcome.UNCHANGED;
			}
			request.opened = true; request.laneMask = packet.laneBits();
			if (!request.firstGrantSeen && request.lifecycle != null) request.lifecycle.lastProgressMillis = time.nowMillis();
			request.firstGrantSeen = true;
			for (InventoryResourceKind kind : InventoryResourceKind.values())
				if ((request.laneMask & (1 << kind.wireId())) == 0) {
					@Nullable PreviewState denied = previews.remove(new PreviewKey(packet.requestId(), kind));
					if (denied != null) { request.retiredBaselines.merge(kind, denied.baselineId, Math::max); denied.entries.clear(); }
				}
			evaluatePreview(request);
			return ApplyOutcome.APPLIED;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.OPEN_REJECTED) {
			@Nullable PreviewRequest request = requests.get(packet.requestId());
			if (request == null || request.released || request.opened) { fencedPackets++; return ApplyOutcome.REJECTED; }
			if (request.lifecycle != null) failPreview(request.lifecycle, Objects.requireNonNull(packet.failureReason()));
			else for (PreviewState state : previews.values()) if (state.request == request) {
				state.status = InventoryS2CPacket.Status.INVALID; state.failureReason = packet.failureReason(); state.terminal = true;
			}
			return ApplyOutcome.APPLIED;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW_STATE) return acceptPreviewControl(packet);
		if (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT) {
			@Nullable PendingCommit pending = pendingCommits.get(packet.commitId());
			if (pending == null || pending.requestId() != packet.requestId() || pending.kind() != packet.resourceKind()) return ApplyOutcome.REJECTED;
			if (packet.kind() == InventoryS2CPacket.Kind.SELECTED && packet.markerId() != null
				&& knownMarkers.containsKey(packet.markerId())) {
				@Nullable InventoryResourceKind previous = markerKinds.putIfAbsent(packet.markerId(), packet.resourceKind());
				if (previous != null && previous != packet.resourceKind()) { fencedPackets++; return ApplyOutcome.REJECTED; }
			}
			pending.terminalAccepted = true;
			pendingCommits.remove(packet.commitId());
			@Nullable InventoryFailureReason reason = packet.failureReason() != null ? packet.failureReason()
				: packet.rejection() == null ? null : InventoryFailureReason.from(packet.rejection());
			selectionResults.put(packet.commitId(), new SelectionResult(packet.resourceKind(), packet.commitId(), pending.requestId(), packet.markerId(), packet.rejection(), reason));
			while (selectionResults.size() > 64) selectionResults.remove(selectionResults.keySet().iterator().next());
			if (packet.kind() == InventoryS2CPacket.Kind.REJECT && reason != null) {
				ClientInventoryDiagnostics.selectionRejected(pending.requestId(), packet.commitId(), reason);
				trackingFailure.accept(reason);
			}
			return ApplyOutcome.APPLIED;
		}
		if (!ready(packet.resourceKind())) { fencedPackets++; return ApplyOutcome.REJECTED; }
		@Nullable MarkerMetadata marker = knownMarkers.get(packet.markerId());
		if (packet.markerId() != null && (marker == null || marker.trackingKind != packet.resourceKind()
			|| !inventoryAllowed(marker, packet.resourceKind())
			|| markerKinds.containsKey(packet.markerId()) && markerKinds.get(packet.markerId()) != packet.resourceKind())) { fencedPackets++; return ApplyOutcome.REJECTED; }
		if (marker != null && packet.baselineId() <= marker.retiredBaselines.getOrDefault(packet.resourceKind(), -1L)) { fencedPackets++; return ApplyOutcome.REJECTED; }
		if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW) {
			@Nullable PreviewState state = previews.get(new PreviewKey(packet.requestId(), packet.resourceKind()));
			@Nullable PreviewRequest request = requests.get(packet.requestId());
			if (state == null || request == null || request.released || !request.opened || (request.laneMask & (1 << packet.resourceKind().wireId())) == 0) { fencedPackets++; return ApplyOutcome.REJECTED; }
		}
		if (isData(packet.kind()) && !withinEffectiveCaps(packet, effectiveDisplayBytes, effectiveFrameBytes)) {
			capRejectedPackets++;
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_EFFECTIVE_FRAME, InventoryOperation.ROUTE,
				effectiveCapReason(packet, effectiveDisplayBytes), InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), -1, -1, effectiveFrameBytes);
			if (packet.kind() == InventoryS2CPacket.Kind.PREVIEW) {
				@Nullable PreviewState state = previews.get(new PreviewKey(packet.requestId(), packet.resourceKind()));
				if (state != null) {
					state.failureReason = InventoryFailureReason.FRAME_LIMIT;
					state.terminal = true;
					state.status = state.entries.isEmpty() ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.INCOMPLETE;
					evaluatePreview(state.request);
				}
			}
			return ApplyOutcome.REJECTED;
		}
		acceptedPackets++;
		return switch (packet.kind()) {
			case PREVIEW -> acceptPreview(packet);
			case SNAPSHOT, STREAM -> acceptTrackedData(packet);
			case STATUS -> acceptStatus(packet);
			case HEARTBEAT -> acceptHeartbeat(packet);
			case OFFER, POLICY, SELECTED, REJECT, OPENED, OPEN_REJECTED, PREVIEW_STATE -> ApplyOutcome.UNCHANGED;
		};
	}

	private void acceptOffer(InventoryS2CPacket packet) {
		InventoryS2CPacket.@Nullable Offer next = packet.offer();
		if (next == null || (offered && packet.epoch() != epoch)
			|| presentationEpoch != 0 && (packet.presentationEpoch() != presentationEpoch || packet.view() != presentationView))
			return;
		InventoryS2CPacket.@Nullable Offer previous = offer;
		offered = true;
		epoch = packet.epoch();
		offer = next;
		if (previous == null) {
			effectiveDisplayBytes = next.effectiveDisplayBytes();
			effectiveFrameBytes = next.effectiveFrameBytes();
		} else {
			applyEffectiveCaps(next);
		}
		for (PreviewState state : List.copyOf(previews.values())) {
			if (requests.containsKey(state.requestId) && ready(state.resourceKind))
				sendOpen(state);
		}
	}

	/**
	 * Applies one same-epoch OFFER's effective caps. A lowered display or frame
	 * cap fences only state that cannot fit the new caps; an equal or raised cap
	 * changes nothing and never restores omitted metadata, so only future
	 * accepted server data adds details back.
	 */
	private void applyEffectiveCaps(InventoryS2CPacket.Offer next) {
		int display = next.effectiveDisplayBytes();
		int frame = next.effectiveFrameBytes();
		if (display < effectiveDisplayBytes || frame < effectiveFrameBytes)
			fenceIncompatibleState(display, frame);
		effectiveDisplayBytes = display;
		effectiveFrameBytes = frame;
	}

	/**
	 * Lowering lifecycle: incompatible partial cuts, unknown-baseline buffers
	 * and oversized display metadata are fenced while committed quantities,
	 * marker association, lifetime and compatible progress stay. A committed
	 * view keeps its key, label, quality and authoritative count and loses only
	 * the display payload the new cap cannot carry.
	 */
	private void fenceIncompatibleState(int displayBytes, int frameBytes) {
		for (PreviewState state : previews.values()) {
			if (stripOversizedDisplays(state.entries, displayBytes)) {
				state.bytes = metadataBytes(state.entries);
				capFencedStates++;
				reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_CAP_FENCE, InventoryFailureReason.DISPLAY_LIMIT, state.requestId, displayBytes);
			}
		}
		for (TrackingState state : tracking.values()) {
			if (state.pending != null && !batchWithinCaps(state.pending, displayBytes, frameBytes)) {
				reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_PENDING_FENCE, batchCapReason(state.pending, displayBytes), state.serverRequestId, frameBytes);
				state.pending = null;
				store.discardAssembly(state.channel);
				capFencedStates++;
			}
			var batches = state.batches.entrySet().iterator();
			while (batches.hasNext()) {
				var batch = batches.next();
				if (batchWithinCaps(batch.getValue(), displayBytes, frameBytes))
					continue;
				state.batchBytes -= batch.getValue().bytes;
				reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_BATCH_FENCE, batchCapReason(batch.getValue(), displayBytes), state.serverRequestId, frameBytes);
				batches.remove();
				capFencedStates++;
			}
			if (stripOversizedDisplays(state.metadata, displayBytes)) {
				capFencedStates++;
				reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_METADATA_FENCE, InventoryFailureReason.DISPLAY_LIMIT, state.serverRequestId, displayBytes);
			}
			for (var closed : state.closedEntries.entrySet()) {
				var entry = closed.getValue();
				if (entry.displayJson() != null && entry.displayJson().getBytes(StandardCharsets.UTF_8).length > displayBytes) {
					closed.setValue(new InventoryS2CPacket.Entry(entry.key(), entry.itemId(), entry.label(), null,
						entry.count(), entry.itemRevision(), entry.fallback(), entry.quality(), entry.groupRevision(), entry.replaceGroup(), entry.itemPingType(), entry.qualityReason()));
					capFencedStates++;
					reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_CLOSED_DISPLAY_FENCE, InventoryFailureReason.DISPLAY_LIMIT, state.serverRequestId, displayBytes);
				}
			}
			if (state.historical != null && !state.historical.isEmpty()) {
				Map<String, EntryView> history = new LinkedHashMap<>();
				for (var entry : state.historical) history.put(entry.key(), entry);
				if (stripOversizedDisplays(history, displayBytes)) {
					state.historical = List.copyOf(history.values()); capFencedStates++;
					reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_HISTORY_FENCE, InventoryFailureReason.DISPLAY_LIMIT, state.serverRequestId, displayBytes);
				}
			}
		}
		var sessions = unknown.entrySet().iterator();
		while (sessions.hasNext()) {
			UnknownSession session = sessions.next().getValue();
			for (InventoryS2CPacket fragment : session.fragments) {
				if (withinEffectiveCaps(fragment, displayBytes, frameBytes))
					continue;
				sessions.remove();
				capFencedStates++;
				reportCapFence(InventoryDiagnosticSite.CLIENT_EFFECTIVE_UNKNOWN_FENCE, effectiveCapReason(fragment, displayBytes), fragment.requestId(), frameBytes);
				break;
			}
		}
	}
	private static InventoryFailureReason effectiveCapReason(InventoryS2CPacket packet, int displayBytes) {
		return packet.entries().stream().anyMatch(entry -> entry.displayJson() != null && utf8Length(entry.displayJson()) > displayBytes)
			? InventoryFailureReason.DISPLAY_LIMIT : InventoryFailureReason.FRAME_LIMIT;
	}
	private static InventoryFailureReason batchCapReason(TrackingBatch batch, int displayBytes) {
		return batch.parts.values().stream().anyMatch(packet -> effectiveCapReason(packet, displayBytes) == InventoryFailureReason.DISPLAY_LIMIT)
			? InventoryFailureReason.DISPLAY_LIMIT : InventoryFailureReason.FRAME_LIMIT;
	}
	private static void reportCapFence(InventoryDiagnosticSite site, InventoryFailureReason reason, long request, long limit) {
		ClientInventoryDiagnostics.limit(site, InventoryOperation.ROUTE, reason, InventoryImpact.DEGRADED,
			InventoryOutcome.TRUNCATED, request, -1, -1, limit);
	}

	/** Discards only the display payload of a view whose JSON exceeds the new cap. */
	private static boolean stripOversizedDisplays(Map<String, EntryView> views, int displayBytes) {
		boolean changed = false;
		for (Map.Entry<String, EntryView> entry : views.entrySet()) {
			EntryView view = entry.getValue();
			if (view.displayJson() == null || utf8Length(view.displayJson()) <= displayBytes)
				continue;
			entry.setValue(new EntryView(view.resourceKind(), view.key(), view.itemId(), view.label(), null, view.count(),
				view.fallback(), view.quality(), view.itemPingType(), view.qualityReason()));
			changed = true;
		}
		return changed;
	}

	private static boolean batchWithinCaps(TrackingBatch batch, int displayBytes, int frameBytes) {
		for (InventoryS2CPacket part : batch.parts.values())
			if (!withinEffectiveCaps(part, displayBytes, frameBytes))
				return false;
		return true;
	}

	/** True only for the kinds that carry entry data under the effective caps. */
	private static boolean isData(InventoryS2CPacket.Kind kind) {
		return kind == InventoryS2CPacket.Kind.PREVIEW || kind == InventoryS2CPacket.Kind.SNAPSHOT
			|| kind == InventoryS2CPacket.Kind.STREAM;
	}

	/**
	 * Exact encoded-frame admission under the accepted caps, measured with the
	 * packet's own writer grammar. A frame or a single display field over the
	 * accepted caps is refused before any session state changes; the retention
	 * estimates never stand in for wire bytes.
	 */
	private static boolean withinEffectiveCaps(InventoryS2CPacket packet, int displayBytes, int frameBytes) {
		if (displayBytes <= 0 || frameBytes <= 0)
			return true;
		try {
			packet.encodedSize(displayBytes, frameBytes);
			return true;
		} catch (IllegalArgumentException overCap) {
			return false;
		}
	}

	private void sendOpen(PreviewState state) {
		if (state.request.sent) return;
		state.request.sent = true;
		state.request.sendEpoch = epoch;
		state.request.sendPresentationEpoch = presentationEpoch;
		state.request.sendView = presentationView;
		if (state.request.lifecycle != null) state.request.lifecycle.lastProgressMillis = time.nowMillis();
		try { send(InventoryC2SPacket.open(epoch, presentationEpoch, presentationView, state.requestId, state.target, state.face)); }
		catch (RuntimeException failed) {
			if (state.request.lifecycle != null) failPreview(state.request.lifecycle, InventoryFailureReason.TRANSPORT_FAILURE);
			else close(state.requestId);
			ClientInventoryDiagnostics.transportFailed("open", failed);
			return;
		}
		ClientInventoryDiagnostics.openSent(state.requestId, state.resourceKind, state.target, state.face);
	}

	/** An inventory-v2 OPEN target is one native block or one uncommitted external candidate. */
	private static boolean isOpenTarget(@Nullable Target target) {
		return target instanceof Target.BlockTarget
			|| (target instanceof Target.ExternalBlockTarget external && external.isCandidate());
	}

	private void send(IPacket packet) {
		sender.accept(packet);
	}

	private ApplyOutcome acceptPreviewControl(InventoryS2CPacket packet) {
		@Nullable PreviewState state = previews.get(new PreviewKey(packet.requestId(), packet.resourceKind()));
		boolean revocation = packet.condition() == InventoryS2CPacket.PreviewCondition.UNSUPPORTED
			|| packet.condition() == InventoryS2CPacket.PreviewCondition.FAILED
				&& (packet.failureReason() == InventoryFailureReason.FIELD_DENIED
					|| packet.failureReason() == InventoryFailureReason.SOURCE_AUTHORITY_DENIED
					|| packet.failureReason() == InventoryFailureReason.TARGET_GONE
					|| packet.failureReason() == InventoryFailureReason.SOURCE_CHANGED);
		if (state == null || state.request.released || !ready(state.resourceKind)
			|| state.request.opened && (state.request.laneMask & (1 << state.resourceKind.wireId())) == 0
			|| packet.controlRevision() <= state.controlRevision
			|| (state.terminal || state.completeScan) && !(state.watchdogStopped && revocation)) {
			fencedPackets++; return ApplyOutcome.REJECTED;
		}
		state.controlRevision = packet.controlRevision();
		if (state.watchdogStopped) {
			state.status = InventoryS2CPacket.Status.INVALID;
			state.failureReason = packet.failureReason();
			state.entries.clear(); state.itemRevisions.clear(); state.groups.clear(); state.bytes = 0;
			evaluatePreview(state.request);
			return ApplyOutcome.APPLIED;
		}
		if (packet.condition() == InventoryS2CPacket.PreviewCondition.PROGRESS
			&& packet.progressRevision() > state.progressRevision) {
			state.progressRevision = packet.progressRevision();
			state.failureReason = packet.failureReason();
			if (state.request.opened && state.request.lifecycle != null) state.request.lifecycle.lastProgressMillis = time.nowMillis();
		} else if (packet.condition() == InventoryS2CPacket.PreviewCondition.DEFERRED) {
			state.failureReason = packet.failureReason();
		} else if (packet.condition() == InventoryS2CPacket.PreviewCondition.FAILED
			|| packet.condition() == InventoryS2CPacket.PreviewCondition.UNSUPPORTED) {
			state.failureReason = packet.failureReason();
			if (packet.condition() == InventoryS2CPacket.PreviewCondition.FAILED && state.serverSeen
				&& state.status == InventoryS2CPacket.Status.INCOMPLETE
				&& packet.phase() == InventoryS2CPacket.PreviewPhase.DELIVERY
				&& (state.awaitingReplacementCut || state.receivedPartIndexes.cardinality() < state.totalParts)) return ApplyOutcome.APPLIED;
			state.terminal = true;
			state.status = !state.serverSeen || state.entries.isEmpty() ? InventoryS2CPacket.Status.INVALID : InventoryS2CPacket.Status.INCOMPLETE;
			evaluatePreview(state.request);
		}
		return ApplyOutcome.APPLIED;
	}

	private ApplyOutcome acceptPreview(InventoryS2CPacket packet) {
		@Nullable PreviewState state = previews.get(new PreviewKey(packet.requestId(), packet.resourceKind()));
		if (state == null) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		InventoryS2CPacket.Status previousStatus = state.status;
		if (packet.baselineId() <= state.request.retiredBaselines.getOrDefault(state.resourceKind, -1L)) { fencedPackets++; return ApplyOutcome.REJECTED; }
		if (packet.statusRevision() < state.statusRevision
			|| state.serverSeen && state.status == InventoryS2CPacket.Status.INVALID && packet.statusRevision() == state.statusRevision
				&& packet.status() != InventoryS2CPacket.Status.INVALID
			|| (packet.statusRevision() == state.statusRevision && state.serverSeen
				&& packet.baselineId() < state.baselineId)) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		// A late valid cut cannot undo a local stop or alter its retained selectable prefix.
		// INVALID still retires that prefix under the ordinary baseline/status fences above.
		if (state.watchdogStopped && packet.status() != InventoryS2CPacket.Status.INVALID) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (packet.partCount() > MAX_ASSEMBLY_PARTS) {
			boundRejects++;
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_PREVIEW_PARTS, InventoryOperation.PREVIEW,
				InventoryFailureReason.SNAPSHOT_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
				packet.requestId(), packet.partCount(), 0, MAX_ASSEMBLY_PARTS);
			return ApplyOutcome.REJECTED;
		}

		boolean replacementFence = state.awaitingReplacementCut && packet.entries().isEmpty()
			&& !packet.completeScan() && packet.status() == InventoryS2CPacket.Status.INCOMPLETE;
		boolean resetChannel = !state.serverSeen
			|| packet.baselineId() != state.baselineId
			|| packet.statusRevision() != state.statusRevision;
		boolean newerWatermark = !resetChannel && packet.watermark() > state.watermark;
		boolean current = resetChannel || packet.watermark() >= state.watermark;
		boolean progress = resetChannel || newerWatermark || current && !state.receivedPartIndexes.get(packet.partIndex());
		boolean changed = monitoring() && (resetChannel || newerWatermark || packet.watermark() == state.watermark
			&& packet.partCount() > state.totalParts || current && (!state.receivedPartIndexes.get(packet.partIndex())
				|| packet.completeScan() && !state.groupComplete || packet.status() != null && packet.status() != state.status));

		if (resetChannel) {
			if (state.awaitingRegrant || state.serverSeen && packet.baselineId() != state.baselineId) {
				state.entries.clear();
				state.itemRevisions.clear();
				state.groups.clear();
				state.bytes = 0;
			}
			state.receivedPartIndexes.clear();
			state.groupComplete = false;
			state.groupRejected = false;
			state.totalParts = packet.partCount();
			state.watermark = packet.watermark();
			state.completeScan = false;
		} else if (newerWatermark) {
			state.receivedPartIndexes.clear();
			state.groupComplete = false;
			state.groupRejected = false;
			state.totalParts = packet.partCount();
			state.watermark = packet.watermark();
			state.completeScan = false;
		} else if (packet.watermark() == state.watermark) {
			state.totalParts = Math.max(state.totalParts, packet.partCount());
		}

		state.serverSeen = true;
		state.awaitingRegrant = false;
		if (!packet.entries().isEmpty() || packet.completeScan() || packet.status() != InventoryS2CPacket.Status.INCOMPLETE)
			state.awaitingReplacementCut = false;
		state.baselineId = packet.baselineId();
		state.statusRevision = packet.statusRevision();

		if (current) {
			state.receivedPartIndexes.set(packet.partIndex());
			if (packet.completeScan())
				state.groupComplete = true;
			if (packet.status() != null)
				state.status = packet.status();
			state.failureReason = packet.failureReason();
		}

		ApplyOutcome entryOutcome = state.watchdogStopped ? ApplyOutcome.UNCHANGED : applyPreviewEntries(state, packet.entries());
		boolean entriesAccepted = entryOutcome != ApplyOutcome.REJECTED;
		if (current && !entriesAccepted)
			state.groupRejected = true;

		if (current) {
			if (state.groupRejected && state.status != InventoryS2CPacket.Status.INVALID)
				state.status = InventoryS2CPacket.Status.INCOMPLETE;
			boolean allParts = state.receivedPartIndexes.cardinality() >= state.totalParts;
			if (state.groupComplete && allParts && !state.groupRejected)
				state.completeScan = true;
			if (state.groupRejected || isPreviewTerminalFailure(state.status))
				state.completeScan = false;
			// INCOMPLETE describes the source result, not receipt of its multipart projection.
			// Later parts may carry the first selectable row; keep that explicit barrier pending.
			state.terminal = state.status == InventoryS2CPacket.Status.INVALID
				|| state.status == InventoryS2CPacket.Status.INCOMPLETE && !state.awaitingReplacementCut && (allParts || state.groupRejected);
		}
		if (!state.watchdogStopped && !replacementFence && (progress || entryOutcome == ApplyOutcome.APPLIED)
			&& entriesAccepted && state.request.lifecycle != null)
			state.request.lifecycle.lastProgressMillis = time.nowMillis();
		evaluatePreview(state.request);
		if (previousStatus != state.status)
			ClientInventoryDiagnostics.previewStatus(state.requestId, state.resourceKind, previousStatus, state.status,
				state.statusRevision);
		return !entriesAccepted ? ApplyOutcome.REJECTED : changed || entryOutcome == ApplyOutcome.APPLIED
			? ApplyOutcome.APPLIED : ApplyOutcome.UNCHANGED;
	}

	/** Merges one packet's absolute deltas per key; a rejected key makes the group incomplete. */
	private ApplyOutcome applyPreviewEntries(PreviewState state, List<InventoryS2CPacket.Entry> entries) {
		boolean allAccepted = true;
		boolean changed = false;
		for (InventoryS2CPacket.Entry entry : entries) {
			@Nullable Long group = state.groups.get(entry.itemId());
			if (group != null && (!entry.fallback() || entry.groupRevision() < group)) continue;
			@Nullable Long knownRevision = state.itemRevisions.get(entry.key());
			if (knownRevision != null && entry.itemRevision() < knownRevision)
				continue;
			EntryView view = viewOf(state.resourceKind, entry);
			@Nullable EntryView previous = state.entries.get(entry.key());
			int size = byteSize(view);
			int nextBytes = state.bytes - (previous == null ? 0 : byteSize(previous)) + size;
			List<String> removed = entry.replaceGroup() ? state.entries.values().stream().filter(old -> old.itemId().equals(entry.itemId()) && !old.key().equals(entry.key())).map(EntryView::key).toList() : List.of();
			for (String key : removed) nextBytes -= byteSize(state.entries.get(key));
			if (nextBytes > MAX_PREVIEW_BYTES
				|| (previous == null && state.entries.size() - removed.size() >= MAX_PREVIEW_ENTRIES)) {
				boundRejects++;
				state.failureReason = nextBytes > MAX_PREVIEW_BYTES ? InventoryFailureReason.MEMORY_LIMIT : InventoryFailureReason.ENTRY_LIMIT;
				ClientInventoryDiagnostics.limit(nextBytes > MAX_PREVIEW_BYTES ? InventoryDiagnosticSite.CLIENT_PREVIEW_RETENTION_BYTES
					: InventoryDiagnosticSite.CLIENT_PREVIEW_RETENTION_ENTRIES, InventoryOperation.PREVIEW,
					state.failureReason, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, state.requestId,
					nextBytes > MAX_PREVIEW_BYTES ? nextBytes : state.entries.size() + 1L,
					nextBytes > MAX_PREVIEW_BYTES ? state.bytes : state.entries.size(),
					nextBytes > MAX_PREVIEW_BYTES ? MAX_PREVIEW_BYTES : MAX_PREVIEW_ENTRIES);
				allAccepted = false;
				continue;
			}
			state.bytes = nextBytes;
			if (!view.equals(previous) || !removed.isEmpty()
				|| knownRevision == null || knownRevision != entry.itemRevision()) changed = true;
			for (String key : removed) { state.entries.remove(key); state.itemRevisions.remove(key); }
			if (entry.replaceGroup()) state.groups.put(entry.itemId(), entry.groupRevision());
			state.entries.put(view.key(), view);
			state.itemRevisions.put(view.key(), entry.itemRevision());
		}
		return !allAccepted ? ApplyOutcome.REJECTED : changed ? ApplyOutcome.APPLIED : ApplyOutcome.UNCHANGED;
	}

	private static boolean isPreviewTerminalFailure(InventoryS2CPacket.@Nullable Status status) {
		return status == InventoryS2CPacket.Status.INVALID
			|| status == InventoryS2CPacket.Status.INCOMPLETE;
	}

	private ApplyOutcome acceptTrackedData(InventoryS2CPacket packet) {
		@Nullable TrackingState state = trackingState(packet.resourceKind(), packet.markerId());
		if (state == null)
			return ApplyOutcome.REJECTED;
		state.serverRequestId = packet.requestId();
		InventoryS2CPacket.@Nullable Status status = packet.status();
		if (status == InventoryS2CPacket.Status.INVALID) {
			ApplyOutcome outcome = invalidateTracking(state, packet.statusRevision());
			if (outcome != ApplyOutcome.REJECTED) state.failureReason = packet.failureReason();
			return outcome;
		}
		if (packet.statusRevision() < state.statusRevision) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if ((packet.statusRevision() == state.statusRevision && state.baselineId != InventoryClientStore.NO_BASELINE
			&& packet.baselineId() < state.baselineId)
			|| (state.pending != null && (packet.statusRevision() < state.pending.statusRevision
				|| (packet.statusRevision() == state.pending.statusRevision
					&& packet.baselineId() < state.pending.baselineId)))) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (store.isInvalid(state.channel) && packet.statusRevision() == state.statusRevision) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (packet.partCount() > MAX_ASSEMBLY_PARTS) {
			boundRejects++;
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_TRACKING_PARTS, InventoryOperation.TRACKING,
				InventoryFailureReason.SNAPSHOT_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.partCount(), 0, MAX_ASSEMBLY_PARTS);
			scheduleRepair(state);
			return ApplyOutcome.REJECTED;
		}
		if (!packet.completeScan() && (state.sourceComplete || state.historicalFailure == InventoryS2CPacket.Status.INVALID
			|| packet.status() == InventoryS2CPacket.Status.UPDATING && state.failureWatermark >= 0)) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (state.committed && packet.baselineId() == store.baselineId(state.channel)
			&& packet.statusRevision() == state.statusRevision && !store.assembling(state.channel)) {
			return applyUpdates(state, packet);
		}
		boolean sameFence = packet.statusRevision() == state.statusRevision
			&& packet.baselineId() == state.baselineId;
		boolean pendingFence = state.pending != null && state.pending.baselineId == packet.baselineId()
			&& state.pending.statusRevision == packet.statusRevision();
		if (packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT || sameFence || pendingFence) {
			return assemble(state, packet);
		}
		return bufferUnknown(state, packet);
	}

	private ApplyOutcome assemble(TrackingState state, InventoryS2CPacket packet) {
		@Nullable TrackingBatch batch = state.pending;
		boolean sameIdentity = batch != null && batch.baselineId == packet.baselineId()
			&& batch.statusRevision == packet.statusRevision();
		if (sameIdentity && batch.watermark != packet.watermark()
			&& packet.kind() == InventoryS2CPacket.Kind.STREAM) {
			return bufferUnknown(state, packet);
		}
		if (!sameIdentity)
			batch = new TrackingBatch(packet);
		ApplyOutcome admission = admitPart(state, batch, packet, true);
		if (admission != ApplyOutcome.APPLIED) return admission;
		InventoryClientStore.PartOutcome begin = store.beginSnapshot(state.channel, packet.baselineId(),
			packet.statusRevision(), packet.partCount(), ticks);
		if (begin == InventoryClientStore.PartOutcome.REJECTED_BOUND
			|| begin == InventoryClientStore.PartOutcome.IGNORED_STALE
			|| begin == InventoryClientStore.PartOutcome.IGNORED_INVALID
			|| begin == InventoryClientStore.PartOutcome.CONFLICT) {
			if (begin == InventoryClientStore.PartOutcome.REJECTED_BOUND
				|| begin == InventoryClientStore.PartOutcome.CONFLICT)
				boundRejects++;
			if (begin == InventoryClientStore.PartOutcome.CONFLICT)
				ClientInventoryDiagnostics.nonLimit(InventoryDiagnosticSite.CLIENT_TRACKING_ASSEMBLY_CONFLICT, InventoryOperation.TRACKING,
					InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.partCount(), 0, MAX_ASSEMBLY_PARTS);
			return ApplyOutcome.REJECTED;
		}
		InventoryClientStore.PartOutcome outcome = store.part(state.channel, packet.baselineId(),
			packet.statusRevision(), packet.partIndex(), valuesOf(packet.entries()), ticks);
		if (outcome == InventoryClientStore.PartOutcome.REJECTED_BOUND
			|| outcome == InventoryClientStore.PartOutcome.CONFLICT
			|| outcome == InventoryClientStore.PartOutcome.CONFLICT_PART) {
			boundRejects++;
			if (outcome != InventoryClientStore.PartOutcome.REJECTED_BOUND)
				ClientInventoryDiagnostics.nonLimit(InventoryDiagnosticSite.CLIENT_TRACKING_PART_CONFLICT, InventoryOperation.TRACKING,
					InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.partIndex(), 0, packet.partCount());
			return ApplyOutcome.REJECTED;
		}
		if (outcome != InventoryClientStore.PartOutcome.ACCEPTED
			&& outcome != InventoryClientStore.PartOutcome.COMMITTED)
			return outcome == InventoryClientStore.PartOutcome.DUPLICATE_PART ? ApplyOutcome.UNCHANGED : ApplyOutcome.REJECTED;
		batch.add(packet);
		if (!sameIdentity && (state.baselineId != packet.baselineId()
			|| state.statusRevision != packet.statusRevision()))
			state.heartbeats.clear();
		state.pending = batch;
		if (outcome == InventoryClientStore.PartOutcome.COMMITTED) {
			commitTracking(state, batch);
		}
		flushUnknownIntoAssembly(state, packet.baselineId(), packet.statusRevision());
		return outcome == InventoryClientStore.PartOutcome.COMMITTED ? ApplyOutcome.APPLIED : ApplyOutcome.HELD;
	}

	private ApplyOutcome applyUpdates(TrackingState state, InventoryS2CPacket packet) {
		List<InventoryS2CPacket.Entry> permitted = packet.entries().stream().filter(entry -> {
			@Nullable Long group = state.groups.get(entry.itemId());
			return group == null || entry.fallback() && entry.groupRevision() >= group;
		}).toList();
		packet = InventoryS2CPacket.data(packet.resourceKind(), packet.kind(), packet.epoch(), packet.requestId(), packet.markerId(), packet.baselineId(), packet.statusRevision(),
			packet.watermark(), packet.partIndex(), packet.partCount(), packet.completeScan(), packet.status(), packet.checksum(), permitted, packet.failureReason()).stamp(packet.presentationEpoch(), packet.view());
		@Nullable TrackingBatch batch = state.batches.get(packet.watermark());
		@Nullable TrackingBatch closed = state.closedBatches.get(packet.watermark());
		if (closed != null) {
			// Closed deliveries retain a bounded replay guard; they never become new batches.
			ApplyOutcome admission = admitPart(state, closed, packet, false);
			return admission == ApplyOutcome.REJECTED ? admission : ApplyOutcome.UNCHANGED;
		}
		// Replay detail is evictable, completion ownership is not: the closed
		// watermark permanently retires barriers in its range. A late independent
		// key can still contribute its absolute revision to that range, but an old
		// SNAPSHOT/STREAM fragment must never recreate an incomplete delivery.
		boolean historical = batch == null && state.closedCut && packet.watermark() <= state.watermark;
		if (batch == null) {
			if (!historical && state.batches.size() >= MAX_TRACKING_BATCHES) {
				boundRejects++;
				ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_TRACKING_OPEN_BATCHES, InventoryOperation.TRACKING,
					InventoryFailureReason.QUEUE_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), state.batches.size() + 1L, state.batches.size(), MAX_TRACKING_BATCHES);
				scheduleRepair(state);
				return ApplyOutcome.REJECTED;
			}
			batch = new TrackingBatch(packet);
		}
		ApplyOutcome admission = admitPart(state, batch, packet, false);
		if (admission != ApplyOutcome.APPLIED) return admission;
		long bytes = packetBytes(packet);
		if (!historical && state.batchBytes + bytes > MAX_METADATA_BYTES_PER_CHANNEL) {
			boundRejects++;
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_TRACKING_BATCH_BYTES, InventoryOperation.TRACKING,
				InventoryFailureReason.MEMORY_LIMIT, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), bytes, state.batchBytes, MAX_METADATA_BYTES_PER_CHANNEL);
			scheduleRepair(state);
			return ApplyOutcome.REJECTED;
		}
		Map<String, InventoryClientStore.Value> before = store.values(state.channel);
		boolean measuring = monitoring();
		boolean changed = false;
		Map<String, EntryView> nextMetadata = new LinkedHashMap<>(state.metadata);
		Set<String> removed = new HashSet<>();
		for (var entry : packet.entries()) if (entry.replaceGroup()) {
			for (EntryView old : nextMetadata.values()) if (old.itemId().equals(entry.itemId()) && !old.key().equals(entry.key())) removed.add(old.key());
			removed.forEach(nextMetadata::remove);
			if (measuring && !Objects.equals(state.groups.get(entry.itemId()), entry.groupRevision())) changed = true;
		}
		if (measuring && removed.stream().anyMatch(before::containsKey)) changed = true;
		List<InventoryS2CPacket.Entry> cutEntries = new ArrayList<>(packet.entries().size());
		for (InventoryS2CPacket.Entry entry : packet.entries()) {
			InventoryClientStore.@Nullable Value previous = before.get(entry.key());
			if (previous == null || entry.itemRevision() > previous.revision()) {
				nextMetadata.put(entry.key(), viewOf(state.resourceKind, entry));
				if (measuring) changed = true;
			}
			if (previous != null && entry.itemRevision() == previous.revision()) {
				EntryView accepted = state.metadata.get(entry.key());
				cutEntries.add(new InventoryS2CPacket.Entry(accepted.key(), accepted.itemId(), accepted.label(),
					accepted.displayJson(), previous.count(), previous.revision(), accepted.fallback(), accepted.quality(),
					entry.groupRevision(), entry.replaceGroup(), accepted.itemPingType(), accepted.qualityReason()));
			} else {
				cutEntries.add(entry);
			}
		}
		int nextBytes = metadataBytes(nextMetadata);
		Map<String, InventoryS2CPacket.Entry> nextCut = new LinkedHashMap<>(historical
			? state.closedEntries : batch.entries);
		mergeEntries(nextCut, cutEntries);
		if (measuring && historical && !nextCut.equals(state.closedEntries)) changed = true;
		if (measuring && !historical && (!state.closedCut || packet.watermark() > state.watermark)) changed = true;
		if (nextMetadata.size() > MAX_METADATA_ENTRIES || nextBytes > MAX_METADATA_BYTES_PER_CHANNEL
			|| entryBytes(nextCut) > MAX_METADATA_BYTES_PER_CHANNEL) {
			boundRejects++;
			ClientInventoryDiagnostics.limit(nextMetadata.size() > MAX_METADATA_ENTRIES ? InventoryDiagnosticSite.CLIENT_TRACKING_METADATA_ENTRIES
				: nextBytes > MAX_METADATA_BYTES_PER_CHANNEL ? InventoryDiagnosticSite.CLIENT_TRACKING_METADATA_BYTES
				: InventoryDiagnosticSite.CLIENT_TRACKING_PROJECTED_CUT_BYTES, InventoryOperation.TRACKING,
				nextMetadata.size() > MAX_METADATA_ENTRIES ? InventoryFailureReason.ENTRY_LIMIT : InventoryFailureReason.MEMORY_LIMIT,
				InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
				packet.requestId(), nextMetadata.size() > MAX_METADATA_ENTRIES ? nextMetadata.size() : Math.max(nextBytes, entryBytes(nextCut)), 0,
				nextMetadata.size() > MAX_METADATA_ENTRIES ? MAX_METADATA_ENTRIES : MAX_METADATA_BYTES_PER_CHANNEL);
			scheduleRepair(state);
			return ApplyOutcome.REJECTED;
		}
		Map<String, InventoryClientStore.Value> updates = valuesOf(packet.entries());
		InventoryClientStore.Outcome outcome = store.applyReplacing(state.channel, packet.baselineId(),
			packet.statusRevision(), updates, removed);
		if (outcome == InventoryClientStore.Outcome.REJECTED_BOUND) {
			boundRejects++;
			return ApplyOutcome.REJECTED;
		}
		if (outcome != InventoryClientStore.Outcome.APPLIED)
			return ApplyOutcome.REJECTED;
		state.metadata.clear();
		state.metadata.putAll(nextMetadata);
		for (var entry : packet.entries()) if (entry.replaceGroup()) state.groups.put(entry.itemId(), entry.groupRevision());
		if (historical) {
			// Coalesce only this accepted old-range delta, not newer live values from
			// open deliveries. Per-key revisions preserve newer closed values; missing
			// historical keys remain a digest mismatch, never a permanent barrier.
			state.closedEntries.clear();
			state.closedEntries.putAll(nextCut);
			state.committedChecksum = computeChecksum(state);
			compareHeartbeat(state);
			return changed ? ApplyOutcome.APPLIED : ApplyOutcome.UNCHANGED;
		}
		batch.add(packet, cutEntries);
		state.batches.put(packet.watermark(), batch);
		state.batchBytes += bytes;
		advanceClosedCut(state);
		return changed ? ApplyOutcome.APPLIED : ApplyOutcome.UNCHANGED;
	}

	private void commitTracking(TrackingState state, TrackingBatch batch) {
		if (state.baselineId != batch.baselineId || state.statusRevision != batch.statusRevision) {
			retainHistory(state);
			state.sourceComplete = false;
		}
		state.metadata.clear();
		state.groups.clear();
		for (InventoryS2CPacket.Entry entry : batch.entries.values())
			state.metadata.put(entry.key(), viewOf(state.resourceKind, entry));
		for (var entry : batch.entries.values()) if (entry.replaceGroup()) state.groups.put(entry.itemId(), entry.groupRevision());
		state.pending = null;
		Map<Long, Long> heartbeats = new LinkedHashMap<>(state.heartbeats);
		long completionWatermark = state.completionWatermark, completionChecksum = state.completionChecksum, completionSince = state.completionSince;
		clearCuts(state);
		state.heartbeats.putAll(heartbeats);
		state.completionWatermark = completionWatermark; state.completionChecksum = completionChecksum; state.completionSince = completionSince;
		state.closedEntries.putAll(batch.entries);
		state.baselineId = batch.baselineId;
		state.statusRevision = batch.statusRevision;
		state.watermark = batch.watermark;
		state.status = batch.status();
		state.failureReason = batch.failureReason();
		state.sourceComplete |= batch.sourceComplete();
		if (state.sourceComplete) { state.historical = null; state.historicalFailure = null; state.failureWatermark = -1; retireCreationDisplay(state); }
		state.committed = true;
		state.closedCut = true;
		state.committedChecksum = computeChecksum(state);
		rememberClosedBatch(state, batch);
		retireUnknownBefore(state.markerId, batch.baselineId, batch.statusRevision, false);
		compareHeartbeat(state);
		compareCompletion(state);
	}

	/** Admission never mutates values, metadata, status or the accepted delivery barrier. */
	private ApplyOutcome admitPart(TrackingState state, TrackingBatch batch, InventoryS2CPacket packet,
		boolean baseline) {
		if (!batch.matches(packet)) {
			boundRejects++;
			ClientInventoryDiagnostics.nonLimit(InventoryDiagnosticSite.CLIENT_TRACKING_BATCH_CONFLICT, InventoryOperation.TRACKING,
				InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.partCount(), 0, batch.totalParts);
			scheduleRepair(state);
			return ApplyOutcome.REJECTED;
		}
		@Nullable InventoryS2CPacket previous = batch.parts.get(packet.partIndex());
		if (previous != null) {
			if (!samePart(previous, packet)) {
				boundRejects++;
				ClientInventoryDiagnostics.nonLimit(InventoryDiagnosticSite.CLIENT_TRACKING_REPLAY_CONFLICT, InventoryOperation.TRACKING,
					InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.partIndex(), 0, batch.totalParts);
				scheduleRepair(state);
				return ApplyOutcome.REJECTED;
			}
			return ApplyOutcome.UNCHANGED;
		}
		if (baseline) {
			for (InventoryS2CPacket.Entry entry : packet.entries()) {
				if (batch.entries.containsKey(entry.key())) {
					boundRejects++;
					ClientInventoryDiagnostics.nonLimit(InventoryDiagnosticSite.CLIENT_TRACKING_DUPLICATE_KEY, InventoryOperation.TRACKING,
						InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.entries().size(), batch.entries.size(), MAX_METADATA_ENTRIES);
					scheduleRepair(state);
					return ApplyOutcome.REJECTED;
				}
			}
		} else {
			for (InventoryS2CPacket.Entry entry : packet.entries()) {
				InventoryS2CPacket.@Nullable Entry known = batch.entries.get(entry.key());
				if (known != null && known.itemRevision() == entry.itemRevision() && !known.equals(entry)) {
					boundRejects++;
					ClientInventoryDiagnostics.nonLimit(InventoryDiagnosticSite.CLIENT_TRACKING_ENTRY_CONFLICT, InventoryOperation.TRACKING,
						InventoryFailureReason.INVALID_REQUEST, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), packet.entries().size(), batch.entries.size(), MAX_METADATA_ENTRIES);
					scheduleRepair(state);
					return ApplyOutcome.REJECTED;
				}
			}
		}
		Map<String, InventoryS2CPacket.Entry> entries = new LinkedHashMap<>(batch.entries);
		mergeEntries(entries, packet.entries());
		if (entries.size() > MAX_METADATA_ENTRIES
			|| batch.bytes + packetBytes(packet) > MAX_METADATA_BYTES_PER_CHANNEL
			|| entryBytes(entries) > MAX_METADATA_BYTES_PER_CHANNEL) {
			boundRejects++;
			ClientInventoryDiagnostics.limit(entries.size() > MAX_METADATA_ENTRIES ? InventoryDiagnosticSite.CLIENT_TRACKING_PART_RETENTION_ENTRIES
				: batch.bytes + packetBytes(packet) > MAX_METADATA_BYTES_PER_CHANNEL ? InventoryDiagnosticSite.CLIENT_TRACKING_PART_RETENTION_BYTES
				: InventoryDiagnosticSite.CLIENT_TRACKING_PART_PROJECTED_BYTES, InventoryOperation.TRACKING,
				entries.size() > MAX_METADATA_ENTRIES ? InventoryFailureReason.ENTRY_LIMIT : InventoryFailureReason.MEMORY_LIMIT,
				InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
				packet.requestId(), entries.size() > MAX_METADATA_ENTRIES ? entries.size() : Math.max(batch.bytes + packetBytes(packet), entryBytes(entries)), 0,
				entries.size() > MAX_METADATA_ENTRIES ? MAX_METADATA_ENTRIES : MAX_METADATA_BYTES_PER_CHANNEL);
			scheduleRepair(state);
			return ApplyOutcome.REJECTED;
		}
		return ApplyOutcome.APPLIED;
	}

	private void advanceClosedCut(TrackingState state) {
		// Close admitted deliveries in watermark order. Live values can already be
		// newer; using them here would make a valid earlier heartbeat look corrupt.
		while (!state.batches.isEmpty()) {
			@Nullable TrackingBatch first = null;
			for (TrackingBatch batch : state.batches.values()) {
				if (first == null || batch.watermark < first.watermark)
					first = batch;
			}
			if (!first.complete())
				break;
			Map<String, InventoryS2CPacket.Entry> next = new LinkedHashMap<>(state.closedEntries);
			mergeEntries(next, first.entries.values());
			if (next.size() > MAX_METADATA_ENTRIES || entryBytes(next) > MAX_METADATA_BYTES_PER_CHANNEL) {
				boundRejects++;
				ClientInventoryDiagnostics.limit(next.size() > MAX_METADATA_ENTRIES ? InventoryDiagnosticSite.CLIENT_TRACKING_CLOSED_CUT_ENTRIES
					: InventoryDiagnosticSite.CLIENT_TRACKING_CLOSED_CUT_BYTES, InventoryOperation.TRACKING,
					next.size() > MAX_METADATA_ENTRIES ? InventoryFailureReason.ENTRY_LIMIT : InventoryFailureReason.MEMORY_LIMIT,
					InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
					first.requestId, next.size() > MAX_METADATA_ENTRIES ? next.size() : entryBytes(next), 0,
					next.size() > MAX_METADATA_ENTRIES ? MAX_METADATA_ENTRIES : MAX_METADATA_BYTES_PER_CHANNEL);
				scheduleRepair(state);
				break;
			}
			state.closedEntries.clear();
			state.closedEntries.putAll(next);
			if (first.watermark > state.watermark) {
				state.watermark = first.watermark;
				state.status = first.status();
				state.failureReason = first.failureReason();
				state.sourceComplete |= first.sourceComplete();
				if (state.sourceComplete) { state.historical = null; state.historicalFailure = null; state.failureWatermark = -1; retireCreationDisplay(state); }
			}
			state.batches.remove(first.watermark);
			state.batchBytes -= first.bytes;
			rememberClosedBatch(state, first);
			state.committedChecksum = computeChecksum(state);
			compareHeartbeat(state);
		}
		compareHeartbeat(state);
		compareCompletion(state);
	}

	private static void rememberClosedBatch(TrackingState state, TrackingBatch batch) {
		state.closedBatches.put(batch.watermark, batch);
		state.closedBatchBytes += batch.bytes;
		var iterator = state.closedBatches.entrySet().iterator();
		while ((state.closedBatches.size() > MAX_TRACKING_BATCHES
			|| state.closedBatchBytes > MAX_METADATA_BYTES_PER_CHANNEL) && iterator.hasNext()) {
			TrackingBatch old = iterator.next().getValue();
			state.closedBatchBytes -= old.bytes;
			iterator.remove();
		}
	}

	private static boolean samePart(InventoryS2CPacket left, InventoryS2CPacket right) {
		// SNAPSHOT and a pre-baseline STREAM may be fragments of the same delivery.
		return left.requestId() == right.requestId() && left.entries().equals(right.entries())
			&& left.status() == right.status() && left.completeScan() == right.completeScan()
			&& left.checksum() == right.checksum();
	}

	private static void mergeEntries(Map<String, InventoryS2CPacket.Entry> target,
		Iterable<InventoryS2CPacket.Entry> entries) {
		for (InventoryS2CPacket.Entry entry : entries) {
			if (entry.replaceGroup()) target.values().removeIf(old -> old.itemId().equals(entry.itemId()) && !old.key().equals(entry.key()) && old.groupRevision() <= entry.groupRevision());
			if (!entry.fallback() && target.values().stream().anyMatch(old -> old.itemId().equals(entry.itemId()) && old.replaceGroup() && old.groupRevision() >= entry.groupRevision())) continue;
			InventoryS2CPacket.@Nullable Entry previous = target.get(entry.key());
			if (previous == null || entry.itemRevision() > previous.itemRevision())
				target.put(entry.key(), entry);
		}
	}

	private static int metadataBytes(Map<String, EntryView> entries) {
		int bytes = 0;
		for (EntryView entry : entries.values())
			bytes += byteSize(entry);
		return bytes;
	}

	private static int entryBytes(Map<String, InventoryS2CPacket.Entry> entries) {
		int bytes = 0;
		for (InventoryS2CPacket.Entry entry : entries.values())
			bytes += byteSize(viewOf(entry));
		return bytes;
	}

	private ApplyOutcome invalidateTracking(TrackingState state, long statusRevision) {
		if (statusRevision < state.statusRevision) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (!store.invalidate(state.channel, statusRevision)) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		boolean unchanged = monitoring() && state.status == InventoryS2CPacket.Status.INVALID && state.statusRevision == statusRevision;
		state.status = InventoryS2CPacket.Status.INVALID;
		retainHistory(state);
		state.historicalFailure = InventoryS2CPacket.Status.INVALID;
		state.sourceComplete = false;
		state.statusRevision = Math.max(state.statusRevision, statusRevision);
		state.pending = null;
		clearCuts(state);
		retireUnknownBefore(state.markerId, InventoryClientStore.NO_BASELINE, statusRevision, true);
		state.committedChecksum = computeChecksum(state);
		return unchanged ? ApplyOutcome.UNCHANGED : ApplyOutcome.APPLIED;
	}

	private ApplyOutcome acceptStatus(InventoryS2CPacket packet) {
		@Nullable TrackingState state = trackingState(packet.resourceKind(), packet.markerId());
		if (state == null)
			return ApplyOutcome.REJECTED;
		state.serverRequestId = packet.requestId();
		InventoryS2CPacket.@Nullable Status status = packet.status();
		if (packet.statusRevision() < state.statusRevision) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		if (status == InventoryS2CPacket.Status.INVALID) {
			ApplyOutcome outcome = invalidateTracking(state, packet.statusRevision());
			if (outcome != ApplyOutcome.REJECTED) state.failureReason = packet.failureReason();
			return outcome;
		}
		if (packet.statusRevision() == state.statusRevision && packet.baselineId() == state.baselineId
			&& (store.isInvalid(state.channel) || packet.watermark() < state.watermark
				&& (packet.watermark() > 0 || !isPreviewTerminalFailure(status))
				|| status == InventoryS2CPacket.Status.UPDATING && (state.sourceComplete || state.failureWatermark >= 0))) {
			fencedPackets++;
			return ApplyOutcome.REJECTED;
		}
		boolean rebase = !state.committed
			|| state.pending != null
			|| packet.statusRevision() > state.statusRevision
			|| packet.baselineId() != store.baselineId(state.channel);
		boolean establishedFence = packet.statusRevision() == state.statusRevision && packet.baselineId() == store.baselineId(state.channel);
		boolean completionFence = establishedFence || state.pending != null && state.pending.baselineId == packet.baselineId()
			&& state.pending.statusRevision == packet.statusRevision();
		// Rebase is a business operation condition, not proof of installation:
		// before the first baseline it runs even for an identical control. Count
		// actual fence/status changes or withdrawal of a committed projection.
		boolean changed = monitoring() && (packet.baselineId() != state.baselineId || packet.statusRevision() != state.statusRevision
			|| rebase && state.committed || status != null && status != state.status
			|| isPreviewTerminalFailure(status) && packet.watermark() > state.failureWatermark
			|| state.committed && completionFence && (status == InventoryS2CPacket.Status.READY || status == InventoryS2CPacket.Status.UNCERTAIN)
				&& packet.watermark() >= state.watermark && packet.watermark() >= state.completionWatermark
				// A consumed matching barrier is not a new apply merely because
				// comparison cleared its pending marker; an identical pending
				// barrier is likewise a replay, not another installed control.
				&& !(packet.watermark() == state.watermark && packet.checksum() == state.committedChecksum)
				&& !(packet.watermark() == state.completionWatermark && packet.checksum() == state.completionChecksum));
		if (rebase) {
			boolean keepAssembly = state.pending != null && state.pending.baselineId == packet.baselineId()
				&& state.pending.statusRevision == packet.statusRevision();
			if (!store.rebase(state.channel, packet.baselineId(), packet.statusRevision())) {
				boundRejects++;
				return ApplyOutcome.REJECTED;
			}
			@Nullable TrackingBatch pending = keepAssembly ? state.pending : null;
			Map<Long, Long> heartbeats = keepAssembly ? new LinkedHashMap<>(state.heartbeats) : Map.of();
			long completionWatermark = state.completionWatermark, completionChecksum = state.completionChecksum, completionSince = state.completionSince;
			clearTrackingState(state);
			state.pending = pending;
			state.heartbeats.putAll(heartbeats);
			if (establishedFence || keepAssembly) {
				state.completionWatermark = completionWatermark; state.completionChecksum = completionChecksum; state.completionSince = completionSince;
			}
			retireUnknownBefore(state.markerId, packet.baselineId(), packet.statusRevision(), false);
		}
		state.status = status == null ? state.status : status;
		state.failureReason = packet.failureReason();
		if (isPreviewTerminalFailure(state.status)) {
			state.failureWatermark = Math.max(state.failureWatermark, Math.max(state.watermark, packet.watermark()));
			if (!state.sourceComplete) { retainHistory(state); state.historicalFailure = state.status; }
		}
		state.baselineId = packet.baselineId();
		state.statusRevision = packet.statusRevision();
		if (state.committed)
			state.committedChecksum = computeChecksum(state);
		if (completionFence && (status == InventoryS2CPacket.Status.READY || status == InventoryS2CPacket.Status.UNCERTAIN)) {
			// These controls are emitted only after every part through this cut was
			// transmitted. They prove completion, not client receipt or application.
			if (packet.watermark() >= state.watermark && packet.watermark() >= state.completionWatermark) {
				state.completionWatermark = packet.watermark(); state.completionChecksum = packet.checksum();
				if (state.completionSince < 0) state.completionSince = ticks;
				compareCompletion(state);
			}
		}
		return changed ? ApplyOutcome.APPLIED : ApplyOutcome.UNCHANGED;
	}
	private void compareCompletion(TrackingState state) {
		if (state.completionWatermark < 0 || !state.committed || !state.closedCut || store.assembling(state.channel)) return;
		if (state.completionWatermark < state.watermark) { clearCompletion(state); return; }
		for (long watermark : state.batches.keySet()) if (watermark <= state.completionWatermark) return;
		// Before the first scan, a future control must wait for its data even
		// when its checksum differs from the seed. The waiting window owns repair.
		if (!state.sourceComplete && state.completionWatermark > state.watermark) return;
		if (state.committedChecksum != state.completionChecksum) {
			scheduleRepair(state); // compare the closed projection only, never partially applied live entries
			return;
		}
		// An equal aggregate digest can describe both seed and fresh totals. Do not
		// retire the first fresh delivery's range on checksum equality alone.
		if (!state.sourceComplete) {
			if (state.completionWatermark == state.watermark) clearCompletion(state);
			return;
		}
		// A matching completed control can close unchanged observations without
		// inventing empty STREAM parts. The permanent closed watermark still owns replay retirement.
		state.watermark = state.completionWatermark;
		// Only a received complete-scan data barrier establishes the first scan.
		// A matching STATUS alone may advance an unchanged, already complete cut.
		clearCompletion(state);
		compareHeartbeat(state);
	}
	private static void clearCompletion(TrackingState state) {
		state.completionWatermark = -1; state.completionSince = -1;
	}

	private ApplyOutcome acceptHeartbeat(InventoryS2CPacket packet) {
		@Nullable TrackingState state = tracking.get(packet.markerId());
		if (state == null || store.isInvalid(state.channel))
			return ApplyOutcome.UNCHANGED;
		boolean pendingFence = state.pending != null && state.pending.baselineId == packet.baselineId()
			&& state.pending.statusRevision == packet.statusRevision();
		if (state.pending != null && !pendingFence)
			return ApplyOutcome.UNCHANGED;
		if (!pendingFence && (packet.statusRevision() != state.statusRevision
			|| packet.baselineId() != store.baselineId(state.channel)))
			return ApplyOutcome.UNCHANGED;
		if (!pendingFence && state.closedCut && packet.watermark() < state.watermark)
			return ApplyOutcome.UNCHANGED;
		state.heartbeats.put(packet.watermark(), packet.checksum());
		var iterator = state.heartbeats.keySet().iterator();
		while (state.heartbeats.size() > MAX_TRACKING_BATCHES && iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
		return compareHeartbeat(state) ? ApplyOutcome.APPLIED : ApplyOutcome.HELD;
	}

	private boolean compareHeartbeat(TrackingState state) {
		if (!state.committed || !state.closedCut || store.assembling(state.channel)) {
			state.heartbeatGapSince = -1;
			return false;
		}
		for (long watermark : state.batches.keySet()) {
			if (watermark <= state.watermark)
				return false;
		}
		@Nullable Long checksum = state.heartbeats.remove(state.watermark);
		state.heartbeats.keySet().removeIf(watermark -> watermark < state.watermark);
		if (checksum != null && state.committedChecksum != checksum)
			scheduleRepair(state);
		boolean futureGap = state.heartbeats.keySet().stream().anyMatch(watermark -> watermark > state.watermark
			&& state.batches.keySet().stream().noneMatch(open -> open <= watermark));
		if (!futureGap) state.heartbeatGapSince = -1;
		else if (state.heartbeatGapSince < 0) state.heartbeatGapSince = ticks;
		return checksum != null;
	}

	private void scheduleRepair(TrackingState state) {
		if (!offered)
			return;
		long cooldown = repairCooldownTicks();
		if (cooldown > 0 && ticks < state.nextRepairTick)
			return;
		state.nextRepairTick = ticks + cooldown;
		send(InventoryC2SPacket.resync(state.resourceKind, epoch, 0, state.markerId).stamp(presentationEpoch, presentationView));
		repairRequests++;
		@Nullable ClientMonitorCollector collector = monitor;
		if (collector != null && collector.enabled()) collector.count(Lane.TRACKING, Counter.REPAIR_REQUESTS, 1);
	}

	private ApplyOutcome bufferUnknown(TrackingState state, InventoryS2CPacket packet) {
		UnknownKey key = new UnknownKey(state.resourceKind, state.markerId, packet.baselineId(), packet.statusRevision());
		@Nullable UnknownSession session = unknown.get(key);
		if (session == null) {
			if (unknown.size() >= MAX_UNKNOWN_SESSIONS) {
				boundRejects++;
				ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_UNKNOWN_SESSIONS, InventoryOperation.SNAPSHOT,
					InventoryFailureReason.SESSION_CAPACITY, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, packet.requestId(), unknown.size() + 1L, unknown.size(), MAX_UNKNOWN_SESSIONS);
				scheduleRepair(state);
				return ApplyOutcome.REJECTED;
			}
			session = new UnknownSession(ticks);
			unknown.put(key, session);
		}
		long size = packetBytes(packet);
		long count = packet.entries().size();
		long byteLimit = session.fragments.isEmpty() && count == 1 ? MAX_UNKNOWN_SINGLETON_BYTES : MAX_UNKNOWN_BYTES;
		if (session.entries + count > MAX_UNKNOWN_ENTRIES || session.bytes + size > byteLimit) {
			unknown.remove(key);
			droppedUnknownSessions++;
			ClientInventoryDiagnostics.limit(session.entries + count > MAX_UNKNOWN_ENTRIES ? InventoryDiagnosticSite.CLIENT_UNKNOWN_OVERFLOW_ENTRIES
				: session.fragments.isEmpty() && count == 1 ? InventoryDiagnosticSite.CLIENT_UNKNOWN_SINGLETON_BYTES : InventoryDiagnosticSite.CLIENT_UNKNOWN_OVERFLOW_BYTES,
				InventoryOperation.SNAPSHOT, session.entries + count > MAX_UNKNOWN_ENTRIES ? InventoryFailureReason.ENTRY_LIMIT : InventoryFailureReason.MEMORY_LIMIT,
				InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED,
				packet.requestId(), session.entries + count > MAX_UNKNOWN_ENTRIES ? count : size,
				session.entries + count > MAX_UNKNOWN_ENTRIES ? session.entries : session.bytes,
				session.entries + count > MAX_UNKNOWN_ENTRIES ? MAX_UNKNOWN_ENTRIES : byteLimit);
			scheduleRepair(state);
			return ApplyOutcome.REJECTED;
		}
		session.fragments.add(packet);
		session.entries += count;
		session.bytes += size;
		return ApplyOutcome.HELD;
	}

	private void expireUnknownSessions() {
		long window = waitingWindowTicks();
		var iterator = unknown.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UnknownKey, UnknownSession> entry = iterator.next();
			if (ticks - entry.getValue().startTick < window)
				continue;
			iterator.remove();
			expiredUnknownSessions++;
			@Nullable TrackingState state = tracking.get(entry.getKey().markerId());
			if (state != null)
				scheduleRepair(state);
		}
	}

	private void flushUnknownIntoAssembly(TrackingState state, long baselineId, long statusRevision) {
		@Nullable UnknownSession session = unknown.remove(new UnknownKey(state.resourceKind, state.markerId, baselineId, statusRevision));
		if (session == null)
			return;
		for (InventoryS2CPacket fragment : session.fragments) {
			observeApplyOutcome(Lane.TRACKING, acceptTrackedData(fragment));
		}
	}

	private void retireUnknownBefore(MarkerId markerId, long baselineId, long statusRevision, boolean invalid) {
		unknown.keySet().removeIf(key -> key.markerId().equals(markerId)
			&& (key.statusRevision() < statusRevision || (key.statusRevision() == statusRevision
				&& (invalid || key.baselineId() < baselineId))));
	}

	private void removeUnknownSessions(MarkerId markerId) {
		unknown.keySet().removeIf(key -> key.markerId().equals(markerId));
	}

	private @Nullable TrackingState trackingState(InventoryResourceKind kind, @Nullable MarkerId markerId) {
		if (markerId == null)
			return null;
		@Nullable TrackingState existing = tracking.get(markerId);
		if (existing != null)
			return existing.resourceKind == kind ? existing : null;
		if (tracking.size() >= MAX_TRACKED_CHANNELS) {
			boundRejects++;
			ClientInventoryDiagnostics.limit(InventoryDiagnosticSite.CLIENT_TRACKING_CHANNELS, InventoryOperation.TRACKING,
				InventoryFailureReason.TRACKING_CAPACITY, InventoryImpact.DEGRADED, InventoryOutcome.TRUNCATED, -1, tracking.size() + 1L, tracking.size(), MAX_TRACKED_CHANNELS);
			return null;
		}
		if (markerKinds.containsKey(markerId) && markerKinds.get(markerId) != kind) return null;
		markerKinds.put(markerId, kind);
		TrackingState state = new TrackingState(kind, markerId, InventoryClientStore.Channel.tracked(markerId.value()));
		tracking.put(markerId, state);
		return state;
	}

	private void clearTrackingState(TrackingState state) {
		retainHistory(state);
		state.metadata.clear();
		state.pending = null;
		clearCuts(state);
		state.committed = false;
		state.sourceComplete = false;
		state.committedChecksum = 0L;
		state.watermark = 0L;
	}
	private void retainHistory(TrackingState state) {
		// Only a received closed cut can replace history. Metadata may contain
		// unfinished per-key updates even after invalidation clears the cut.
		// A Sable fluid re-scope keeps its original quantity even if a provisional
		// replacement closes in transport before a fresh source scan completes.
		boolean keepScopeHistory = sableFluid(state) && state.historical != null && !state.sourceComplete;
		if (state.committed && state.closedCut && !keepScopeHistory)
			state.historical = state.closedEntries.values().stream().map(entry -> viewOf(state.resourceKind, entry)).toList();
		if (isPreviewTerminalFailure(state.status)) state.historicalFailure = state.status;
	}
	private void retireCreationDisplay(TrackingState state) {
		@Nullable MarkerMetadata marker = knownMarkers.get(state.markerId);
		if (marker != null) marker.displayCreationSeed = false;
	}

	private static void clearCuts(TrackingState state) {
		state.closedEntries.clear();
		state.batches.clear();
		state.closedBatches.clear();
		state.heartbeats.clear();
		state.batchBytes = 0L;
		state.closedBatchBytes = 0L;
		state.closedCut = false;
		clearCompletion(state); state.heartbeatGapSince = -1;
	}

	private long computeChecksum(TrackingState state) {
		InventoryChecksums.Builder builder = InventoryChecksums.builder(state.resourceKind);
		for (InventoryS2CPacket.Entry entry : state.closedEntries.values()) {
			builder.add(entry.key(), entry.itemId(), entry.count(), qualityToken(entry.quality()), entry.fallback());
		}
		return builder.checksum();
	}

	private long repairCooldownTicks() {
		InventoryS2CPacket.@Nullable Offer current = offer;
		if (current == null)
			return FALLBACK_UNKNOWN_WINDOW_TICKS;
		return (long) current.resyncMinPeriods() * current.trackingPeriodTicks();
	}

	/** Unknown baselines and completed-data gaps wait independently of repair throttling. */
	private long waitingWindowTicks() {
		InventoryS2CPacket.@Nullable Offer current = offer;
		if (current == null)
			return FALLBACK_UNKNOWN_WINDOW_TICKS;
		return Math.max(Math.max(3L * current.trackingPeriodTicks(), 8L), repairCooldownTicks());
	}

	private static Map<String, InventoryClientStore.Value> valuesOf(List<InventoryS2CPacket.Entry> entries) {
		Map<String, InventoryClientStore.Value> values = new LinkedHashMap<>();
		for (InventoryS2CPacket.Entry entry : entries) {
			values.put(entry.key(), new InventoryClientStore.Value(entry.count(), entry.itemRevision(),
				entry.quality() == null ? "valid" : entry.quality().name()));
		}
		return values;
	}

	private static EntryView viewOf(InventoryS2CPacket.Entry entry) {
		return viewOf(InventoryResourceKind.ITEM, entry);
	}
	private static EntryView viewOf(InventoryResourceKind kind, InventoryS2CPacket.Entry entry) {
		return new EntryView(kind, entry.key(), entry.itemId(), entry.label(), entry.displayJson(), entry.count(),
			entry.fallback(), entry.quality(), entry.itemPingType(), entry.qualityReason());
	}

	private static @Nullable String qualityToken(InventoryS2CPacket.@Nullable Status quality) {
		return quality == null ? null : quality.name();
	}

	private static boolean bounded(@Nullable String value) {
		return value != null && !value.isBlank()
			&& value.getBytes(StandardCharsets.UTF_8).length <= InventoryC2SPacket.MAX_ID_BYTES;
	}

	private static int byteSize(EntryView view) {
		return 64 + utf8Length(view.key()) + utf8Length(view.itemId()) + utf8Length(view.label())
			+ (view.displayJson() == null ? 0 : utf8Length(view.displayJson()));
	}

	private static long packetBytes(InventoryS2CPacket packet) {
		long bytes = 128L;
		for (InventoryS2CPacket.Entry entry : packet.entries()) {
			bytes += 48L + utf8Length(entry.key()) + utf8Length(entry.itemId()) + utf8Length(entry.label())
				+ (entry.displayJson() == null ? 0 : utf8Length(entry.displayJson()));
		}
		return bytes;
	}

	private static int utf8Length(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}
}
