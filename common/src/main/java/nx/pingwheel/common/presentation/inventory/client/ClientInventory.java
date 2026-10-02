package nx.pingwheel.common.presentation.inventory.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;
import java.util.function.Consumer;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.inventory.InventoryChecksums;

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
 * fences/tombstones, checksum comparison and throttled resync scheduling.
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
public final class ClientInventory {

	/** Returned by {@link #open(Target)} when no preview channel could be created. */
	public static final long NO_REQUEST = -1L;

	public static final int MAX_PREVIEW_CHANNELS = 32;
	public static final int MAX_TRACKED_CHANNELS = 128;
	public static final int MAX_PREVIEW_ENTRIES = 256;
	public static final int MAX_METADATA_ENTRIES = 128;
	public static final int MAX_UNKNOWN_SESSIONS = 4;
	public static final int MAX_UNKNOWN_ENTRIES = 256;
	public static final int MAX_UNKNOWN_BYTES = 65536;
	public static final int MAX_METADATA_BYTES_PER_CHANNEL = 262144;
	public static final int MAX_PREVIEW_BYTES = 262144;
	public static final int MAX_TOMBSTONES = 64;

	private static final int HELLO_RETRY_TICKS = 40;
	private static final int MAX_HELLO_ATTEMPTS = 5;
	private static final int MAX_ASSEMBLY_PARTS = 256;
	private static final int MAX_TRACKING_BATCHES = 8;
	private static final long FALLBACK_UNKNOWN_WINDOW_TICKS = 1200L;
	private static final int STORE_CHANNELS = 128;

	/** Rendering projection of one committed entry; metadata may be absent for a fallback. */
	public record EntryView(String key, String itemId, String label, String displayJson, long count,
		boolean fallback, InventoryS2CPacket.Status quality, String itemPingType) {
		public EntryView(String key, String itemId, String label, String displayJson, long count, boolean fallback, InventoryS2CPacket.Status quality) {
			this(key, itemId, label, displayJson, count, fallback, quality, null);
		}
	}
	public record PreviewEntryReference(long requestId, long baselineId, long stateRevision, String entryKey) {}
	public enum DispatchOutcome { SENT, NOT_READY, STALE_REFERENCE, ALREADY_RELEASED, THROTTLED, TRANSPORT_FAILED }
	public record SelectionResult(long commitId, long requestId, MarkerId markerId, MarkerRejectReason rejection) {}

	/**
	 * Immutable preview projection. {@code entries} may be partial and is
	 * republished immediately; {@code receivedParts}/{@code totalParts} expose
	 * scan progress and {@code completeScan} marks a finished sender scan.
	 */
	public record Preview(long requestId, InventoryS2CPacket.Status status, boolean completeScan,
		int receivedParts, int totalParts, long baselineId, long statusRevision, long watermark,
		boolean invalid, List<EntryView> entries) {

		public static Preview empty(long requestId) {
			return new Preview(requestId, InventoryS2CPacket.Status.UPDATING, false, 0, 1, 0, 0, 0, false,
				List.of());
		}
	}

	/** Live per-key projection; {@code watermark} is the closed digest cut, not the newest live item. */
	public record Tracking(MarkerId markerId, InventoryS2CPacket.Status status, boolean grey, boolean complete,
		long baselineId, long statusRevision, long watermark, List<EntryView> entries) {

		public static Tracking empty(MarkerId markerId) {
			return new Tracking(markerId, InventoryS2CPacket.Status.UPDATING, false, false,
				InventoryClientStore.NO_BASELINE, -1L, 0L, List.of());
		}
	}

	/** Public counters for diagnostics and tests; no server or world state. */
	public record Stats(int previewChannels, int trackedChannels, int unknownSessions, int tombstones,
		int invalidPackets, long staleEpochPackets, long fencedPackets, long boundRejects,
		long droppedUnknownSessions, long expiredUnknownSessions, long repairRequests, long acceptedPackets,
		int helloAttempts, boolean ready, long epoch) {}

	private static final class PreviewState {
		final long requestId;
		final Target target;
		final BlockFace face;
		final String targetType;
		final Map<String, Long> groups = new LinkedHashMap<>();
		boolean released;
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
		boolean sent;
		int bytes;

		PreviewState(long requestId, Target target, BlockFace face, String targetType) {
			this.requestId = requestId;
			this.target = target;
			this.face = face; this.targetType = targetType;
		}
	}

	private static final class TrackingState {
		final MarkerId markerId;
		final InventoryClientStore.Channel channel;
		final Map<String, EntryView> metadata = new LinkedHashMap<>();
		final Map<String, Long> groups = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> closedEntries = new LinkedHashMap<>();
		final Map<Long, TrackingBatch> batches = new LinkedHashMap<>();
		final Map<Long, TrackingBatch> closedBatches = new LinkedHashMap<>();
		final Map<Long, Long> heartbeats = new LinkedHashMap<>();
		TrackingBatch pending;
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
		boolean closedCut;

		TrackingState(MarkerId markerId, InventoryClientStore.Channel channel) {
			this.markerId = markerId;
			this.channel = channel;
		}
	}

	/** A bounded logical delivery, independent of the immediately visible per-key values. */
	private static final class TrackingBatch {
		final long requestId;
		final long baselineId;
		final long statusRevision;
		final long watermark;
		final int totalParts;
		final Map<Integer, InventoryS2CPacket> parts = new LinkedHashMap<>();
		final Map<String, InventoryS2CPacket.Entry> entries = new LinkedHashMap<>();
		long bytes;

		TrackingBatch(InventoryS2CPacket packet) {
			requestId = packet.requestId();
			baselineId = packet.baselineId();
			statusRevision = packet.statusRevision();
			watermark = packet.watermark();
			totalParts = packet.partCount();
		}

		boolean matches(InventoryS2CPacket packet) {
			return requestId == packet.requestId() && baselineId == packet.baselineId()
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
	}

	private record UnknownKey(MarkerId markerId, long baselineId, long statusRevision) {}

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
	private final InventoryClientStore store;
	private final Map<Long, PreviewState> previews = new LinkedHashMap<>();
	private final Map<MarkerId, TrackingState> tracking = new LinkedHashMap<>();
	private final Map<UnknownKey, UnknownSession> unknown = new LinkedHashMap<>();
	private final Map<MarkerId, Long> tombstones = new LinkedHashMap<>();

	private InventoryS2CPacket.Offer offer;
	private long epoch;
	private long ticks;
	private long nextRequestId = 1L;
	private int helloAttempts;
	private boolean offered;
	private long presentationEpoch, presentationView, policyView = -1, nextCommitId = 1;
	private Set<String> allowedTypes = Set.of();
	private final Map<MarkerId, String> knownMarkers = new LinkedHashMap<>();
	private final Map<Long, Long> pendingCommits = new LinkedHashMap<>();
	private final Map<Long, SelectionResult> selectionResults = new LinkedHashMap<>();
	private int invalidPackets;
	private long staleEpochPackets;
	private long fencedPackets;
	private long boundRejects;
	private long droppedUnknownSessions;
	private long expiredUnknownSessions;
	private long repairRequests;
	private long acceptedPackets;

	public ClientInventory(Consumer<IPacket> sender) {
		this.sender = Objects.requireNonNull(sender, "sender");
		this.store = new InventoryClientStore(MAX_ASSEMBLY_PARTS, MAX_METADATA_ENTRIES, STORE_CHANNELS);
	}

	/** Whether an OFFER established the epoch and server periods for this connection. */
	public boolean ready() {
		return offered && policyView == presentationView && presentationEpoch != 0;
	}
	/** Accepted presentation RESET immediately purges old-view inventory, before POLICY arrives. */
	public void presentationReset(long epoch, long view) {
		if (epoch == 0 || view < 0 || (epoch == presentationEpoch && view <= presentationView)) return;
		purgeViews(); presentationEpoch = epoch; presentationView = view; policyView = -1; allowedTypes = Set.of();
	}
	public void markerCreated(MarkerSnapshot marker) {
		if (marker == null || knownMarkers.size() >= MAX_TRACKED_CHANNELS && !knownMarkers.containsKey(marker.id())) return;
		knownMarkers.put(marker.id(), marker.targetTypeId());
	}
	public void markerRemoved(MarkerId marker) {
		knownMarkers.remove(marker); TrackingState state = tracking.remove(marker);
		if (state != null) store.expire(state.channel); removeUnknownSessions(marker);
	}
	public SelectionResult selectionResult(long commitId) { return selectionResults.get(commitId); }
	private void purgeViews() {
		previews.clear(); for (TrackingState state : tracking.values()) store.expire(state.channel);
		tracking.clear(); unknown.clear(); tombstones.clear(); knownMarkers.clear(); pendingCommits.clear();
	}

	public long epoch() {
		return epoch;
	}

	/** Null until the first valid OFFER. */
	public InventoryS2CPacket.Offer offer() {
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
		if (!connected)
			return;
		ticks++;
		if (!offered && helloAttempts < MAX_HELLO_ATTEMPTS
			&& (helloAttempts == 0 || ticks % HELLO_RETRY_TICKS == 0)) {
			send(InventoryC2SPacket.hello());
			helloAttempts++;
		}
		expireUnknownSessions();
		for (TrackingState state : tracking.values()) {
			compareCompletion(state);
			compareHeartbeat(state);
			if (state.completionSince >= 0 && ticks - state.completionSince >= unknownWindowTicks()
				|| state.heartbeatGapSince >= 0 && ticks - state.heartbeatGapSince >= unknownWindowTicks())
				scheduleRepair(state);
		}
	}

	/** Clears every channel, metadata map, tombstone and unknown-baseline buffer. */
	public void reset() {
		previews.clear();
		for (TrackingState state : tracking.values())
			store.expire(state.channel);
		tracking.clear();
		unknown.clear();
		tombstones.clear();
		offer = null;
		epoch = 0L;
		ticks = 0L;
		nextRequestId = 1L;
		helloAttempts = 0;
		offered = false;
		presentationEpoch = presentationView = 0; policyView = -1; nextCommitId = 1; allowedTypes = Set.of();
		knownMarkers.clear(); pendingCommits.clear(); selectionResults.clear();
	}

	/**
	 * Opens a local preview request bound to one bounded target. The request id
	 * is returned immediately; before an OFFER the {@code OPEN} frame is queued
	 * and sent once the epoch is established. Returns {@link #NO_REQUEST} when
	 * the preview channel bound is reached or the target is absent.
	 */
	public long open(Target target) {
		return NO_REQUEST; // No face is never an unsided or inferred-UP view.
	}
	public long open(CapturedPingContext frozen) {
		if (frozen == null || frozen.blockHitFace().isEmpty() || !(frozen.resolvedTarget().target() instanceof Target.BlockTarget block)) return NO_REQUEST;
		return open(block, frozen.blockHitFace().get(), frozen.resolvedTarget().targetType().id());
	}
	public long open(Target.BlockTarget target, BlockFace face, String targetType) {
		if (target == null || face == null || !nx.pingwheel.common.presentation.PresentationSettings.isKnownTargetType(targetType)
			|| previews.size() >= MAX_PREVIEW_CHANNELS) {
			boundRejects++;
			return NO_REQUEST;
		}
		long requestId = nextRequestId++;
		PreviewState state = new PreviewState(requestId, target, face, targetType);
		previews.put(requestId, state);
		if (ready() && allowedTypes.contains(targetType)) {
			sendOpen(state);
		}
		return requestId;
	}

	/** Releases a preview request and forgets its partial list. */
	public void close(long requestId) {
		PreviewState state = previews.remove(requestId);
		if (state != null && state.sent && offered) {
			 send(InventoryC2SPacket.close(epoch, requestId).stamp(presentationEpoch, presentationView));
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
	public List<PreviewEntryReference> selectable(long requestId) {
		PreviewState state = previews.get(requestId);
		if (state == null || !state.serverSeen || state.released || !ready() || !allowedTypes.contains(state.targetType)
			|| state.status == InventoryS2CPacket.Status.INVALID || state.status == InventoryS2CPacket.Status.UNAVAILABLE) return List.of();
		return state.entries.keySet().stream().map(key -> new PreviewEntryReference(requestId, state.baselineId, state.statusRevision, key)).toList();
	}
	/** Single-use release. The injected dispatcher owns the shared courtesy limiter and transport result. */
	public DispatchOutcome select(PreviewEntryReference reference, String itemPingType, Function<InventoryC2SPacket, DispatchOutcome> dispatch) {
		if (!ready()) return DispatchOutcome.NOT_READY;
		PreviewState state = reference == null ? null : previews.get(reference.requestId());
		if (state == null || state.baselineId != reference.baselineId() || state.statusRevision != reference.stateRevision()
			|| !state.entries.containsKey(reference.entryKey()) || !bounded(itemPingType) || !allowedTypes.contains(state.targetType)) return DispatchOutcome.STALE_REFERENCE;
		if (state.released) return DispatchOutcome.ALREADY_RELEASED;
		state.released = true;
		if (state.status == InventoryS2CPacket.Status.INVALID || state.status == InventoryS2CPacket.Status.UNAVAILABLE || state.status == InventoryS2CPacket.Status.EXPIRED)
			return DispatchOutcome.STALE_REFERENCE;
		long commit = nextCommitId++;
		var packet = InventoryC2SPacket.select(epoch, presentationEpoch, presentationView, commit, state.requestId,
			reference.baselineId(), reference.stateRevision(), reference.entryKey(), itemPingType);
		DispatchOutcome outcome = dispatch.apply(packet);
		if (outcome == DispatchOutcome.SENT) {
			pendingCommits.put(commit, state.requestId);
			while (pendingCommits.size() > 64) pendingCommits.remove(pendingCommits.keySet().iterator().next());
		}
		return outcome;
	}

	/** Immutable preview projection; an unknown request yields an empty preview. */
	public Preview preview(long requestId) {
		PreviewState state = previews.get(requestId);
		if (state == null)
			return Preview.empty(requestId);
		return new Preview(state.requestId, state.status, state.completeScan,
			state.receivedPartIndexes.cardinality(),
			state.totalParts, state.baselineId, state.statusRevision, state.watermark,
			state.status == InventoryS2CPacket.Status.INVALID, List.copyOf(state.entries.values()));
	}

	/**
	 * Immutable tracking projection for one Ping. Committed values render with
	 * their last metadata; an invalidated channel reports {@code grey} while
	 * keeping those values.
	 */
	public Tracking tracking(MarkerId markerId) {
		if (markerId == null)
			return Tracking.empty(null);
		TrackingState state = tracking.get(markerId);
		if (state == null)
			return Tracking.empty(markerId);
		List<EntryView> entries = new ArrayList<>(state.metadata.size());
		Map<String, InventoryClientStore.Value> committed = store.values(state.channel);
		for (EntryView view : state.metadata.values()) {
			InventoryClientStore.Value value = committed.get(view.key());
			entries.add(value == null ? view
				: new EntryView(view.key(), view.itemId(), view.label(), view.displayJson(), value.count(),
					view.fallback(), view.quality(), view.itemPingType()));
		}
		return new Tracking(markerId, state.status, store.isInvalid(state.channel), state.committed,
			store.baselineId(state.channel), state.statusRevision, state.watermark, List.copyOf(entries));
	}

	public Stats stats() {
		return new Stats(previews.size(), tracking.size(), unknown.size(), tombstones.size(), invalidPackets,
			staleEpochPackets, fencedPackets, boundRejects, droppedUnknownSessions, expiredUnknownSessions,
			 repairRequests, acceptedPackets, helloAttempts, ready(), epoch);
	}

	/**
	 * Routes one decoded server response. Corrupt packets, packets for another
	 * epoch, and packets fenced by a newer status revision change nothing. An
	 * OFFER is only accepted as the first offer of this connection.
	 */
	public void accept(InventoryS2CPacket packet) {
		if (packet == null || packet.isCorrupt()) {
			invalidPackets++;
			return;
		}
		if (packet.kind() == InventoryS2CPacket.Kind.OFFER) {
			acceptOffer(packet);
			return;
		}
		if (!offered || packet.epoch() != epoch) {
			staleEpochPackets++;
			return;
		}
		if (packet.presentationEpoch() != presentationEpoch || packet.view() != presentationView) { fencedPackets++; return; }
		if (packet.kind() == InventoryS2CPacket.Kind.POLICY) {
			allowedTypes = packet.allowedTypes(); policyView = packet.view();
			for (TrackingState state : List.copyOf(tracking.values())) if (!allowedTypes.contains(knownMarkers.get(state.markerId))) {
				store.expire(state.channel); tracking.remove(state.markerId); removeUnknownSessions(state.markerId);
			}
			previews.values().removeIf(state -> !allowedTypes.contains(state.targetType));
			for (PreviewState state : previews.values()) if (!state.sent && allowedTypes.contains(state.targetType)) sendOpen(state);
			return;
		}
		if (!ready()) { fencedPackets++; return; }
		if (packet.kind() == InventoryS2CPacket.Kind.SELECTED || packet.kind() == InventoryS2CPacket.Kind.REJECT) {
			Long request = pendingCommits.remove(packet.commitId());
			if (request == null || request != packet.requestId()) return;
			selectionResults.put(packet.commitId(), new SelectionResult(packet.commitId(), request, packet.markerId(), packet.rejection()));
			while (selectionResults.size() > 64) selectionResults.remove(selectionResults.keySet().iterator().next());
			return;
		}
		if (packet.markerId() != null && (!knownMarkers.containsKey(packet.markerId()) || !allowedTypes.contains(knownMarkers.get(packet.markerId())))) { fencedPackets++; return; }
		acceptedPackets++;
		switch (packet.kind()) {
			case PREVIEW -> acceptPreview(packet);
			case SNAPSHOT, STREAM -> acceptTrackedData(packet);
			case STATUS -> acceptStatus(packet);
			case HEARTBEAT -> acceptHeartbeat(packet);
			case OFFER, POLICY, SELECTED, REJECT -> {}
		}
	}

	private void acceptOffer(InventoryS2CPacket packet) {
		if (packet.offer() == null || (offered && packet.epoch() != epoch)
			|| presentationEpoch != 0 && (packet.presentationEpoch() != presentationEpoch || packet.view() != presentationView))
			return;
		offered = true;
		epoch = packet.epoch();
		offer = packet.offer();
		for (PreviewState state : previews.values()) {
			if (!state.sent && ready() && allowedTypes.contains(state.targetType))
				sendOpen(state);
		}
	}

	private void sendOpen(PreviewState state) {
		state.sent = true;
		send(InventoryC2SPacket.open(epoch, presentationEpoch, presentationView, state.requestId, (Target.BlockTarget) state.target, state.face));
	}

	private void send(IPacket packet) {
		sender.accept(packet);
	}

	private void acceptPreview(InventoryS2CPacket packet) {
		PreviewState state = previews.get(packet.requestId());
		if (state == null) {
			fencedPackets++;
			return;
		}
		if (packet.statusRevision() < state.statusRevision
			|| (packet.statusRevision() == state.statusRevision && state.serverSeen
				&& packet.baselineId() < state.baselineId)) {
			fencedPackets++;
			return;
		}
		if (packet.partCount() > MAX_ASSEMBLY_PARTS) {
			boundRejects++;
			return;
		}

		boolean resetChannel = !state.serverSeen
			|| packet.baselineId() != state.baselineId
			|| packet.statusRevision() != state.statusRevision;
		boolean newerWatermark = !resetChannel && packet.watermark() > state.watermark;
		boolean current = resetChannel || packet.watermark() >= state.watermark;

		if (resetChannel) {
			if (state.serverSeen && packet.baselineId() != state.baselineId) {
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
		state.baselineId = packet.baselineId();
		state.statusRevision = packet.statusRevision();

		if (current) {
			state.receivedPartIndexes.set(packet.partIndex());
			if (packet.completeScan())
				state.groupComplete = true;
			if (packet.status() != null)
				state.status = packet.status();
		}

		boolean entriesAccepted = applyPreviewEntries(state, packet.entries());
		if (current && !entriesAccepted)
			state.groupRejected = true;

		if (current) {
			if (state.groupRejected && state.status != InventoryS2CPacket.Status.INVALID
				&& state.status != InventoryS2CPacket.Status.EXPIRED)
				state.status = InventoryS2CPacket.Status.INCOMPLETE;
			boolean allParts = state.receivedPartIndexes.cardinality() >= state.totalParts;
			if (state.groupComplete && allParts && !state.groupRejected)
				state.completeScan = true;
			if (state.groupRejected || isPreviewTerminalFailure(state.status))
				state.completeScan = false;
		}
		if (state.status == InventoryS2CPacket.Status.EXPIRED)
			previews.remove(state.requestId);
	}

	/** Merges one packet's absolute deltas per key; a rejected key makes the group incomplete. */
	private boolean applyPreviewEntries(PreviewState state, List<InventoryS2CPacket.Entry> entries) {
		boolean allAccepted = true;
		for (InventoryS2CPacket.Entry entry : entries) {
			Long group = state.groups.get(entry.itemId());
			if (group != null && (!entry.fallback() || entry.groupRevision() < group)) continue;
			Long knownRevision = state.itemRevisions.get(entry.key());
			if (knownRevision != null && entry.itemRevision() < knownRevision)
				continue;
			EntryView view = viewOf(entry);
			EntryView previous = state.entries.get(entry.key());
			int size = byteSize(view);
			int nextBytes = state.bytes - (previous == null ? 0 : byteSize(previous)) + size;
			List<String> removed = entry.replaceGroup() ? state.entries.values().stream().filter(old -> old.itemId().equals(entry.itemId()) && !old.key().equals(entry.key())).map(EntryView::key).toList() : List.of();
			for (String key : removed) nextBytes -= byteSize(state.entries.get(key));
			if (nextBytes > MAX_PREVIEW_BYTES
				|| (previous == null && state.entries.size() - removed.size() >= MAX_PREVIEW_ENTRIES)) {
				boundRejects++;
				allAccepted = false;
				continue;
			}
			state.bytes = nextBytes;
			for (String key : removed) { state.entries.remove(key); state.itemRevisions.remove(key); }
			if (entry.replaceGroup()) state.groups.put(entry.itemId(), entry.groupRevision());
			state.entries.put(view.key(), view);
			state.itemRevisions.put(view.key(), entry.itemRevision());
		}
		return allAccepted;
	}

	private static boolean isPreviewTerminalFailure(InventoryS2CPacket.Status status) {
		return status == InventoryS2CPacket.Status.INVALID
			|| status == InventoryS2CPacket.Status.INCOMPLETE
			|| status == InventoryS2CPacket.Status.UNAVAILABLE;
	}

	private void acceptTrackedData(InventoryS2CPacket packet) {
		MarkerId markerId = packet.markerId();
		if (fencedByTombstone(markerId, packet.statusRevision()))
			return;
		TrackingState state = trackingState(markerId);
		if (state == null)
			return;
		state.serverRequestId = packet.requestId();
		InventoryS2CPacket.Status status = packet.status();
		if (status == InventoryS2CPacket.Status.EXPIRED) {
			expireTracking(state, packet.statusRevision());
			return;
		}
		if (status == InventoryS2CPacket.Status.INVALID) {
			invalidateTracking(state, packet.statusRevision());
			return;
		}
		if (packet.statusRevision() < state.statusRevision) {
			fencedPackets++;
			return;
		}
		if ((packet.statusRevision() == state.statusRevision && state.baselineId != InventoryClientStore.NO_BASELINE
			&& packet.baselineId() < state.baselineId)
			|| (state.pending != null && (packet.statusRevision() < state.pending.statusRevision
				|| (packet.statusRevision() == state.pending.statusRevision
					&& packet.baselineId() < state.pending.baselineId)))) {
			fencedPackets++;
			return;
		}
		if (store.isInvalid(state.channel) && packet.statusRevision() == state.statusRevision) {
			fencedPackets++;
			return;
		}
		if (packet.partCount() > MAX_ASSEMBLY_PARTS) {
			boundRejects++;
			scheduleRepair(state);
			return;
		}
		if (state.committed && packet.baselineId() == store.baselineId(state.channel)
			&& packet.statusRevision() == state.statusRevision && !store.assembling(state.channel)) {
			applyUpdates(state, packet);
			return;
		}
		boolean sameFence = packet.statusRevision() == state.statusRevision
			&& packet.baselineId() == state.baselineId;
		boolean pendingFence = state.pending != null && state.pending.baselineId == packet.baselineId()
			&& state.pending.statusRevision == packet.statusRevision();
		if (packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT || sameFence || pendingFence) {
			assemble(state, packet);
			return;
		}
		bufferUnknown(state, packet);
	}

	private void assemble(TrackingState state, InventoryS2CPacket packet) {
		TrackingBatch batch = state.pending;
		boolean sameIdentity = batch != null && batch.baselineId == packet.baselineId()
			&& batch.statusRevision == packet.statusRevision();
		if (sameIdentity && batch.watermark != packet.watermark()
			&& packet.kind() == InventoryS2CPacket.Kind.STREAM) {
			bufferUnknown(state, packet);
			return;
		}
		if (!sameIdentity)
			batch = new TrackingBatch(packet);
		if (!admitPart(state, batch, packet, true))
			return;
		InventoryClientStore.PartOutcome begin = store.beginSnapshot(state.channel, packet.baselineId(),
			packet.statusRevision(), packet.partCount(), ticks);
		if (begin == InventoryClientStore.PartOutcome.REJECTED_BOUND
			|| begin == InventoryClientStore.PartOutcome.IGNORED_STALE
			|| begin == InventoryClientStore.PartOutcome.IGNORED_INVALID
			|| begin == InventoryClientStore.PartOutcome.CONFLICT) {
			if (begin == InventoryClientStore.PartOutcome.REJECTED_BOUND
				|| begin == InventoryClientStore.PartOutcome.CONFLICT)
				boundRejects++;
			return;
		}
		InventoryClientStore.PartOutcome outcome = store.part(state.channel, packet.baselineId(),
			packet.statusRevision(), packet.partIndex(), valuesOf(packet.entries()), ticks);
		if (outcome == InventoryClientStore.PartOutcome.REJECTED_BOUND
			|| outcome == InventoryClientStore.PartOutcome.CONFLICT
			|| outcome == InventoryClientStore.PartOutcome.CONFLICT_PART) {
			boundRejects++;
			return;
		}
		if (outcome != InventoryClientStore.PartOutcome.ACCEPTED
			&& outcome != InventoryClientStore.PartOutcome.COMMITTED)
			return;
		batch.add(packet);
		if (!sameIdentity && (state.baselineId != packet.baselineId()
			|| state.statusRevision != packet.statusRevision()))
			state.heartbeats.clear();
		state.pending = batch;
		if (outcome == InventoryClientStore.PartOutcome.COMMITTED) {
			commitTracking(state, batch);
		}
		flushUnknownIntoAssembly(state, packet.baselineId(), packet.statusRevision());
	}

	private void applyUpdates(TrackingState state, InventoryS2CPacket packet) {
		List<InventoryS2CPacket.Entry> permitted = packet.entries().stream().filter(entry -> {
			Long group = state.groups.get(entry.itemId());
			return group == null || entry.fallback() && entry.groupRevision() >= group;
		}).toList();
		packet = InventoryS2CPacket.data(packet.kind(), packet.epoch(), packet.requestId(), packet.markerId(), packet.baselineId(), packet.statusRevision(),
			packet.watermark(), packet.partIndex(), packet.partCount(), packet.completeScan(), packet.status(), packet.checksum(), permitted).stamp(packet.presentationEpoch(), packet.view());
		TrackingBatch batch = state.batches.get(packet.watermark());
		TrackingBatch closed = state.closedBatches.get(packet.watermark());
		if (closed != null) {
			// Closed deliveries retain a bounded replay guard; they never become new batches.
			admitPart(state, closed, packet, false);
			return;
		}
		// Replay detail is evictable, completion ownership is not: the closed
		// watermark permanently retires barriers in its range. A late independent
		// key can still contribute its absolute revision to that range, but an old
		// SNAPSHOT/STREAM fragment must never recreate an incomplete delivery.
		boolean historical = batch == null && state.closedCut && packet.watermark() <= state.watermark;
		if (batch == null) {
			if (!historical && state.batches.size() >= MAX_TRACKING_BATCHES) {
				boundRejects++;
				scheduleRepair(state);
				return;
			}
			batch = new TrackingBatch(packet);
		}
		if (!admitPart(state, batch, packet, false))
			return;
		long bytes = packetBytes(packet);
		if (!historical && state.batchBytes + bytes > MAX_METADATA_BYTES_PER_CHANNEL) {
			boundRejects++;
			scheduleRepair(state);
			return;
		}
		Map<String, InventoryClientStore.Value> before = store.values(state.channel);
		Map<String, EntryView> nextMetadata = new LinkedHashMap<>(state.metadata);
		Set<String> removed = new HashSet<>();
		for (var entry : packet.entries()) if (entry.replaceGroup()) {
			for (EntryView old : nextMetadata.values()) if (old.itemId().equals(entry.itemId()) && !old.key().equals(entry.key())) removed.add(old.key());
			removed.forEach(nextMetadata::remove);
		}
		List<InventoryS2CPacket.Entry> cutEntries = new ArrayList<>(packet.entries().size());
		for (InventoryS2CPacket.Entry entry : packet.entries()) {
			InventoryClientStore.Value previous = before.get(entry.key());
			if (previous == null || entry.itemRevision() > previous.revision())
				nextMetadata.put(entry.key(), viewOf(entry));
			if (previous != null && entry.itemRevision() == previous.revision()) {
				EntryView accepted = state.metadata.get(entry.key());
				cutEntries.add(new InventoryS2CPacket.Entry(accepted.key(), accepted.itemId(), accepted.label(),
					accepted.displayJson(), previous.count(), previous.revision(), accepted.fallback(), accepted.quality()));
			} else {
				cutEntries.add(entry);
			}
		}
		int nextBytes = metadataBytes(nextMetadata);
		Map<String, InventoryS2CPacket.Entry> nextCut = new LinkedHashMap<>(historical
			? state.closedEntries : batch.entries);
		mergeEntries(nextCut, cutEntries);
		if (nextMetadata.size() > MAX_METADATA_ENTRIES || nextBytes > MAX_METADATA_BYTES_PER_CHANNEL
			|| entryBytes(nextCut) > MAX_METADATA_BYTES_PER_CHANNEL) {
			boundRejects++;
			scheduleRepair(state);
			return;
		}
		Map<String, InventoryClientStore.Value> updates = valuesOf(packet.entries());
		InventoryClientStore.Outcome outcome = store.applyReplacing(state.channel, packet.baselineId(),
			packet.statusRevision(), updates, removed);
		if (outcome == InventoryClientStore.Outcome.REJECTED_BOUND) {
			boundRejects++;
			return;
		}
		if (outcome != InventoryClientStore.Outcome.APPLIED)
			return;
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
			return;
		}
		batch.add(packet, cutEntries);
		state.batches.put(packet.watermark(), batch);
		state.batchBytes += bytes;
		advanceClosedCut(state);
	}

	private void commitTracking(TrackingState state, TrackingBatch batch) {
		state.metadata.clear();
		state.groups.clear();
		for (InventoryS2CPacket.Entry entry : batch.entries.values())
			state.metadata.put(entry.key(), viewOf(entry));
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
		state.committed = true;
		state.closedCut = true;
		state.committedChecksum = computeChecksum(state);
		rememberClosedBatch(state, batch);
		retireUnknownBefore(state.markerId, batch.baselineId, batch.statusRevision, false);
		compareHeartbeat(state);
		compareCompletion(state);
	}

	/** Admission never mutates values, metadata, status or the accepted delivery barrier. */
	private boolean admitPart(TrackingState state, TrackingBatch batch, InventoryS2CPacket packet,
		boolean baseline) {
		if (!batch.matches(packet)) {
			boundRejects++;
			scheduleRepair(state);
			return false;
		}
		InventoryS2CPacket previous = batch.parts.get(packet.partIndex());
		if (previous != null) {
			if (!samePart(previous, packet)) {
				boundRejects++;
				scheduleRepair(state);
			}
			return false;
		}
		if (baseline) {
			for (InventoryS2CPacket.Entry entry : packet.entries()) {
				if (batch.entries.containsKey(entry.key())) {
					boundRejects++;
					scheduleRepair(state);
					return false;
				}
			}
		} else {
			for (InventoryS2CPacket.Entry entry : packet.entries()) {
				InventoryS2CPacket.Entry known = batch.entries.get(entry.key());
				if (known != null && known.itemRevision() == entry.itemRevision() && !known.equals(entry)) {
					boundRejects++;
					scheduleRepair(state);
					return false;
				}
			}
		}
		Map<String, InventoryS2CPacket.Entry> entries = new LinkedHashMap<>(batch.entries);
		mergeEntries(entries, packet.entries());
		if (entries.size() > MAX_METADATA_ENTRIES
			|| batch.bytes + packetBytes(packet) > MAX_METADATA_BYTES_PER_CHANNEL
			|| entryBytes(entries) > MAX_METADATA_BYTES_PER_CHANNEL) {
			boundRejects++;
			scheduleRepair(state);
			return false;
		}
		return true;
	}

	private void advanceClosedCut(TrackingState state) {
		// Close admitted deliveries in watermark order. Live values can already be
		// newer; using them here would make a valid earlier heartbeat look corrupt.
		while (!state.batches.isEmpty()) {
			TrackingBatch first = null;
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
				scheduleRepair(state);
				break;
			}
			state.closedEntries.clear();
			state.closedEntries.putAll(next);
			if (first.watermark > state.watermark) {
				state.watermark = first.watermark;
				state.status = first.status();
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
			InventoryS2CPacket.Entry previous = target.get(entry.key());
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

	private void invalidateTracking(TrackingState state, long statusRevision) {
		if (statusRevision < state.statusRevision) {
			fencedPackets++;
			return;
		}
		if (!store.invalidate(state.channel, statusRevision)) {
			fencedPackets++;
			return;
		}
		state.status = InventoryS2CPacket.Status.INVALID;
		state.statusRevision = Math.max(state.statusRevision, statusRevision);
		state.pending = null;
		clearCuts(state);
		retireUnknownBefore(state.markerId, InventoryClientStore.NO_BASELINE, statusRevision, true);
		state.committedChecksum = computeChecksum(state);
	}

	private void expireTracking(TrackingState state, long statusRevision) {
		store.expire(state.channel);
		tracking.remove(state.markerId);
		long known = tombstones.getOrDefault(state.markerId, -1L);
		tombstones.put(state.markerId, Math.max(known, statusRevision));
		pruneTombstones();
		removeUnknownSessions(state.markerId);
	}

	private void acceptStatus(InventoryS2CPacket packet) {
		MarkerId markerId = packet.markerId();
		if (fencedByTombstone(markerId, packet.statusRevision()))
			return;
		TrackingState state = trackingState(markerId);
		if (state == null)
			return;
		state.serverRequestId = packet.requestId();
		InventoryS2CPacket.Status status = packet.status();
		if (status == InventoryS2CPacket.Status.EXPIRED) {
			expireTracking(state, packet.statusRevision());
			return;
		}
		if (packet.statusRevision() < state.statusRevision) {
			fencedPackets++;
			return;
		}
		if (status == InventoryS2CPacket.Status.INVALID) {
			invalidateTracking(state, packet.statusRevision());
			return;
		}
		boolean rebase = !state.committed
			|| state.pending != null
			|| packet.statusRevision() > state.statusRevision
			|| packet.baselineId() != store.baselineId(state.channel);
		boolean establishedFence = packet.statusRevision() == state.statusRevision && packet.baselineId() == store.baselineId(state.channel);
		boolean completionFence = establishedFence || state.pending != null && state.pending.baselineId == packet.baselineId()
			&& state.pending.statusRevision == packet.statusRevision();
		if (rebase) {
			boolean keepAssembly = state.pending != null && state.pending.baselineId == packet.baselineId()
				&& state.pending.statusRevision == packet.statusRevision();
			if (!store.rebase(state.channel, packet.baselineId(), packet.statusRevision())) {
				boundRejects++;
				return;
			}
			TrackingBatch pending = keepAssembly ? state.pending : null;
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
	}

	private void compareCompletion(TrackingState state) {
		if (state.completionWatermark < 0 || !state.committed || !state.closedCut || store.assembling(state.channel)) return;
		if (state.completionWatermark < state.watermark) { clearCompletion(state); return; }
		for (long watermark : state.batches.keySet()) if (watermark <= state.completionWatermark) return;
		if (state.committedChecksum != state.completionChecksum) {
			scheduleRepair(state); // compare the closed projection only, never partially applied live entries
			return;
		}
		// A matching completed control can close unchanged observations without
		// inventing empty STREAM parts. The permanent closed watermark still owns replay retirement.
		state.watermark = state.completionWatermark;
		clearCompletion(state);
		compareHeartbeat(state);
	}
	private static void clearCompletion(TrackingState state) {
		state.completionWatermark = -1; state.completionSince = -1;
	}

	private void acceptHeartbeat(InventoryS2CPacket packet) {
		TrackingState state = tracking.get(packet.markerId());
		if (state == null || store.isInvalid(state.channel))
			return;
		boolean pendingFence = state.pending != null && state.pending.baselineId == packet.baselineId()
			&& state.pending.statusRevision == packet.statusRevision();
		if (state.pending != null && !pendingFence)
			return;
		if (!pendingFence && (packet.statusRevision() != state.statusRevision
			|| packet.baselineId() != store.baselineId(state.channel)))
			return;
		if (!pendingFence && state.closedCut && packet.watermark() < state.watermark)
			return;
		state.heartbeats.put(packet.watermark(), packet.checksum());
		var iterator = state.heartbeats.keySet().iterator();
		while (state.heartbeats.size() > MAX_TRACKING_BATCHES && iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
		compareHeartbeat(state);
	}

	private void compareHeartbeat(TrackingState state) {
		if (!state.committed || !state.closedCut || store.assembling(state.channel)) {
			state.heartbeatGapSince = -1;
			return;
		}
		for (long watermark : state.batches.keySet()) {
			if (watermark <= state.watermark)
				return;
		}
		Long checksum = state.heartbeats.remove(state.watermark);
		state.heartbeats.keySet().removeIf(watermark -> watermark < state.watermark);
		if (checksum != null && state.committedChecksum != checksum)
			scheduleRepair(state);
		boolean futureGap = state.heartbeats.keySet().stream().anyMatch(watermark -> watermark > state.watermark
			&& state.batches.keySet().stream().noneMatch(open -> open <= watermark));
		if (!futureGap) state.heartbeatGapSince = -1;
		else if (state.heartbeatGapSince < 0) state.heartbeatGapSince = ticks;
	}

	private void scheduleRepair(TrackingState state) {
		if (!offered)
			return;
		long window = unknownWindowTicks();
		if (ticks < state.nextRepairTick)
			return;
		state.nextRepairTick = ticks + Math.max(1L, window);
		send(InventoryC2SPacket.resync(epoch, 0, state.markerId).stamp(presentationEpoch, presentationView));
		repairRequests++;
	}

	private void bufferUnknown(TrackingState state, InventoryS2CPacket packet) {
		UnknownKey key = new UnknownKey(state.markerId, packet.baselineId(), packet.statusRevision());
		UnknownSession session = unknown.get(key);
		if (session == null) {
			if (unknown.size() >= MAX_UNKNOWN_SESSIONS) {
				boundRejects++;
				scheduleRepair(state);
				return;
			}
			session = new UnknownSession(ticks);
			unknown.put(key, session);
		}
		long size = packetBytes(packet);
		long count = packet.entries().size();
		if (session.entries + count > MAX_UNKNOWN_ENTRIES || session.bytes + size > MAX_UNKNOWN_BYTES) {
			unknown.remove(key);
			droppedUnknownSessions++;
			scheduleRepair(state);
			return;
		}
		session.fragments.add(packet);
		session.entries += count;
		session.bytes += size;
	}

	private void expireUnknownSessions() {
		long window = unknownWindowTicks();
		var iterator = unknown.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UnknownKey, UnknownSession> entry = iterator.next();
			if (ticks - entry.getValue().startTick < window)
				continue;
			iterator.remove();
			expiredUnknownSessions++;
			TrackingState state = tracking.get(entry.getKey().markerId());
			if (state != null)
				scheduleRepair(state);
		}
	}

	private void flushUnknownIntoAssembly(TrackingState state, long baselineId, long statusRevision) {
		UnknownSession session = unknown.remove(new UnknownKey(state.markerId, baselineId, statusRevision));
		if (session == null)
			return;
		for (InventoryS2CPacket fragment : session.fragments) {
			acceptTrackedData(fragment);
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

	private TrackingState trackingState(MarkerId markerId) {
		if (markerId == null)
			return null;
		TrackingState existing = tracking.get(markerId);
		if (existing != null)
			return existing;
		if (tracking.size() >= MAX_TRACKED_CHANNELS) {
			boundRejects++;
			return null;
		}
		TrackingState state = new TrackingState(markerId, InventoryClientStore.Channel.tracked(markerId.value()));
		tracking.put(markerId, state);
		return state;
	}

	private boolean fencedByTombstone(MarkerId markerId, long statusRevision) {
		if (markerId == null)
			return false;
		Long tombstone = tombstones.get(markerId);
		if (tombstone == null)
			return false;
		if (statusRevision <= tombstone) {
			fencedPackets++;
			return true;
		}
		tombstones.remove(markerId);
		return false;
	}

	private void pruneTombstones() {
		var iterator = tombstones.keySet().iterator();
		while (tombstones.size() > MAX_TOMBSTONES && iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
	}

	private static void clearTrackingState(TrackingState state) {
		state.metadata.clear();
		state.pending = null;
		clearCuts(state);
		state.committed = false;
		state.committedChecksum = 0L;
		state.watermark = 0L;
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
		InventoryChecksums.Builder builder = InventoryChecksums.builder();
		for (InventoryS2CPacket.Entry entry : state.closedEntries.values()) {
			builder.add(entry.key(), entry.itemId(), entry.count(), qualityToken(entry.quality()), entry.fallback());
		}
		return builder.checksum();
	}

	private long unknownWindowTicks() {
		InventoryS2CPacket.Offer current = offer;
		if (current == null)
			return FALLBACK_UNKNOWN_WINDOW_TICKS;
		return Math.max(1L, (long) current.resyncMinPeriods() * current.trackingPeriodTicks());
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
		return new EntryView(entry.key(), entry.itemId(), entry.label(), entry.displayJson(), entry.count(),
			entry.fallback(), entry.quality(), entry.itemPingType());
	}

	private static String qualityToken(InventoryS2CPacket.Status quality) {
		return quality == null ? null : quality.name();
	}

	private static boolean bounded(String value) {
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
