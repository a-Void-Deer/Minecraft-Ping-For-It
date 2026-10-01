package nx.pingwheel.common.presentation.inventory.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
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
	private static final int MAX_ASSEMBLY_PARTS = 8;
	private static final long FALLBACK_UNKNOWN_WINDOW_TICKS = 1200L;
	private static final int STORE_CHANNELS = 128;

	/** Rendering projection of one committed entry; metadata may be absent for a fallback. */
	public record EntryView(String key, String itemId, String label, String displayJson, long count,
		boolean fallback, InventoryS2CPacket.Status quality) {}

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

	/** Immutable tracking projection; {@code grey} keeps the last values under invalidity. */
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

		PreviewState(long requestId, Target target) {
			this.requestId = requestId;
			this.target = target;
		}
	}

	private static final class TrackingState {
		final MarkerId markerId;
		final InventoryClientStore.Channel channel;
		final Map<String, EntryView> metadata = new LinkedHashMap<>();
		final Map<String, EntryView> pendingMetadata = new LinkedHashMap<>();
		InventoryS2CPacket.Status status = InventoryS2CPacket.Status.UPDATING;
		long serverRequestId;
		long baselineId = InventoryClientStore.NO_BASELINE;
		long statusRevision = -1L;
		long watermark;
		long committedChecksum;
		long nextRepairTick;
		int metadataBytes;
		int pendingBytes;
		boolean committed;
		boolean pendingHasIdentity;
		long pendingBaseline = InventoryClientStore.NO_BASELINE;
		long pendingRevision = -1L;

		TrackingState(MarkerId markerId, InventoryClientStore.Channel channel) {
			this.markerId = markerId;
			this.channel = channel;
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
		return offered;
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
	}

	/** Clears every channel, metadata map, tombstone and unknown-baseline buffer. */
	public void reset() {
		previews.clear();
		tracking.clear();
		unknown.clear();
		tombstones.clear();
		offer = null;
		epoch = 0L;
		ticks = 0L;
		nextRequestId = 1L;
		helloAttempts = 0;
		offered = false;
	}

	/**
	 * Opens a local preview request bound to one bounded target. The request id
	 * is returned immediately; before an OFFER the {@code OPEN} frame is queued
	 * and sent once the epoch is established. Returns {@link #NO_REQUEST} when
	 * the preview channel bound is reached or the target is absent.
	 */
	public long open(Target target) {
		if (target == null || previews.size() >= MAX_PREVIEW_CHANNELS) {
			boundRejects++;
			return NO_REQUEST;
		}
		long requestId = nextRequestId++;
		PreviewState state = new PreviewState(requestId, target);
		previews.put(requestId, state);
		if (offered) {
			sendOpen(state);
		}
		return requestId;
	}

	/** Releases a preview request and forgets its partial list. */
	public void close(long requestId) {
		PreviewState state = previews.remove(requestId);
		if (state != null && state.sent && offered) {
			send(InventoryC2SPacket.close(epoch, requestId));
		}
	}

	/**
	 * Sends one bounded entry selection for an open preview request. Returns
	 * {@code false} without sending when the request is unknown, the session is
	 * not ready, or any text exceeds the wire bound.
	 */
	public boolean select(long requestId, String entryKey, String itemId, String pingType) {
		PreviewState state = previews.get(requestId);
		if (state == null || !offered || !bounded(entryKey) || !bounded(itemId) || !bounded(pingType))
			return false;
		send(InventoryC2SPacket.select(epoch, requestId, entryKey, itemId, pingType));
		return true;
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
					view.fallback(), view.quality()));
		}
		return new Tracking(markerId, state.status, store.isInvalid(state.channel), state.committed,
			store.baselineId(state.channel), state.statusRevision, state.watermark, List.copyOf(entries));
	}

	public Stats stats() {
		return new Stats(previews.size(), tracking.size(), unknown.size(), tombstones.size(), invalidPackets,
			staleEpochPackets, fencedPackets, boundRejects, droppedUnknownSessions, expiredUnknownSessions,
			repairRequests, acceptedPackets, helloAttempts, offered, epoch);
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
		acceptedPackets++;
		switch (packet.kind()) {
			case PREVIEW -> acceptPreview(packet);
			case SNAPSHOT, STREAM -> acceptTrackedData(packet);
			case STATUS -> acceptStatus(packet);
			case HEARTBEAT -> acceptHeartbeat(packet);
			case OFFER -> {}
		}
	}

	private void acceptOffer(InventoryS2CPacket packet) {
		if (offered || packet.offer() == null)
			return;
		offered = true;
		epoch = packet.epoch();
		offer = packet.offer();
		for (PreviewState state : previews.values()) {
			if (!state.sent)
				sendOpen(state);
		}
	}

	private void sendOpen(PreviewState state) {
		state.sent = true;
		send(InventoryC2SPacket.open(epoch, state.requestId, state.target));
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
			Long knownRevision = state.itemRevisions.get(entry.key());
			if (knownRevision != null && entry.itemRevision() < knownRevision)
				continue;
			EntryView view = viewOf(entry);
			EntryView previous = state.entries.get(entry.key());
			int size = byteSize(view);
			int nextBytes = state.bytes - (previous == null ? 0 : byteSize(previous)) + size;
			if (nextBytes > MAX_PREVIEW_BYTES
				|| (previous == null && state.entries.size() >= MAX_PREVIEW_ENTRIES)) {
				boundRejects++;
				allAccepted = false;
				continue;
			}
			state.bytes = nextBytes;
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
		state.watermark = Math.max(state.watermark, packet.watermark());
		if (state.committed && packet.baselineId() == store.baselineId(state.channel)
			&& packet.statusRevision() == state.statusRevision && !store.assembling(state.channel)) {
			applyUpdates(state, packet);
			return;
		}
		boolean sameFence = packet.statusRevision() == state.statusRevision
			&& packet.baselineId() == state.baselineId;
		if (packet.kind() == InventoryS2CPacket.Kind.SNAPSHOT || sameFence) {
			assemble(state, packet);
			return;
		}
		bufferUnknown(state, packet);
	}

	private void assemble(TrackingState state, InventoryS2CPacket packet) {
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
		if (state.pendingBaseline != packet.baselineId() || state.pendingRevision != packet.statusRevision()) {
			state.pendingMetadata.clear();
			state.pendingBytes = 0;
			state.pendingHasIdentity = false;
		}
		state.pendingBaseline = packet.baselineId();
		state.pendingRevision = packet.statusRevision();
		stashPending(state, packet.entries());
		InventoryClientStore.PartOutcome outcome = store.part(state.channel, packet.baselineId(),
			packet.statusRevision(), packet.partIndex(), valuesOf(packet.entries()), ticks);
		if (outcome == InventoryClientStore.PartOutcome.REJECTED_BOUND
			|| outcome == InventoryClientStore.PartOutcome.CONFLICT
			|| outcome == InventoryClientStore.PartOutcome.CONFLICT_PART) {
			boundRejects++;
			return;
		}
		if (outcome == InventoryClientStore.PartOutcome.COMMITTED) {
			commitTracking(state, packet.baselineId(), packet.statusRevision(), packet.watermark(), packet.status());
			return;
		}
		flushUnknownIntoAssembly(state, packet.baselineId(), packet.statusRevision());
	}

	private void applyUpdates(TrackingState state, InventoryS2CPacket packet) {
		Map<String, InventoryClientStore.Value> before = store.values(state.channel);
		Map<String, InventoryClientStore.Value> updates = valuesOf(packet.entries());
		InventoryClientStore.Outcome outcome = store.apply(state.channel, packet.baselineId(),
			packet.statusRevision(), updates);
		if (outcome == InventoryClientStore.Outcome.REJECTED_BOUND) {
			boundRejects++;
			return;
		}
		if (outcome != InventoryClientStore.Outcome.APPLIED)
			return;
		Map<String, InventoryClientStore.Value> after = store.values(state.channel);
		for (InventoryS2CPacket.Entry entry : packet.entries()) {
			InventoryClientStore.Value now = after.get(entry.key());
			if (now == null || now.equals(before.get(entry.key())))
				continue;
			updateMetadata(state, viewOf(entry));
		}
		state.committedChecksum = computeChecksum(state);
	}

	private void commitTracking(TrackingState state, long baselineId, long statusRevision, long watermark,
		InventoryS2CPacket.Status status) {
		state.metadata.clear();
		state.metadata.putAll(state.pendingMetadata);
		state.metadataBytes = state.pendingBytes;
		state.pendingMetadata.clear();
		state.pendingBytes = 0;
		state.pendingHasIdentity = false;
		state.baselineId = baselineId;
		state.statusRevision = statusRevision;
		state.watermark = Math.max(state.watermark, watermark);
		if (status != null)
			state.status = status;
		state.committed = true;
		state.committedChecksum = computeChecksum(state);
	}

	private void stashPending(TrackingState state, List<InventoryS2CPacket.Entry> entries) {
		for (InventoryS2CPacket.Entry entry : entries) {
			EntryView view = viewOf(entry);
			EntryView previous = state.pendingMetadata.get(view.key());
			int size = byteSize(view);
			if (previous == null && (state.pendingMetadata.size() >= MAX_METADATA_ENTRIES
				|| state.pendingBytes + size > MAX_METADATA_BYTES_PER_CHANNEL)) {
				boundRejects++;
				continue;
			}
			state.pendingBytes = state.pendingBytes - (previous == null ? 0 : byteSize(previous)) + size;
			state.pendingMetadata.put(view.key(), view);
			state.pendingHasIdentity = true;
		}
	}

	private void updateMetadata(TrackingState state, EntryView view) {
		EntryView previous = state.metadata.get(view.key());
		int size = byteSize(view);
		if (previous == null && (state.metadata.size() >= MAX_METADATA_ENTRIES
			|| state.metadataBytes + size > MAX_METADATA_BYTES_PER_CHANNEL)) {
			boundRejects++;
			return;
		}
		state.metadataBytes = state.metadataBytes - (previous == null ? 0 : byteSize(previous)) + size;
		state.metadata.put(view.key(), view);
	}

	private void invalidateTracking(TrackingState state, long statusRevision) {
		if (statusRevision < state.statusRevision) {
			fencedPackets++;
			return;
		}
		store.invalidate(state.channel, statusRevision);
		state.status = InventoryS2CPacket.Status.INVALID;
		state.statusRevision = Math.max(state.statusRevision, statusRevision);
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
		state.watermark = Math.max(state.watermark, packet.watermark());
		if (status == InventoryS2CPacket.Status.INVALID) {
			invalidateTracking(state, packet.statusRevision());
			return;
		}
		boolean rebase = !state.committed
			|| packet.statusRevision() > state.statusRevision
			|| packet.baselineId() != store.baselineId(state.channel);
		if (rebase) {
			if (!store.rebase(state.channel, packet.baselineId(), packet.statusRevision())) {
				boundRejects++;
				return;
			}
			clearTrackingState(state);
		}
		state.status = status == null ? state.status : status;
		state.baselineId = packet.baselineId();
		state.statusRevision = packet.statusRevision();
		if (state.committed)
			state.committedChecksum = computeChecksum(state);
	}

	private void acceptHeartbeat(InventoryS2CPacket packet) {
		TrackingState state = tracking.get(packet.markerId());
		if (state == null || !state.committed || store.assembling(state.channel))
			return;
		if (packet.statusRevision() != state.statusRevision
			|| packet.baselineId() != store.baselineId(state.channel))
			return;
		// Only a closed watermark is comparable: a heartbeat ahead of the
		// committed fragments describes a future state and is not a mismatch.
		if (packet.watermark() != state.watermark)
			return;
		if (computeChecksum(state) != packet.checksum())
			scheduleRepair(state);
	}

	private void scheduleRepair(TrackingState state) {
		if (!offered)
			return;
		long window = unknownWindowTicks();
		if (ticks < state.nextRepairTick)
			return;
		state.nextRepairTick = ticks + Math.max(1L, window);
		send(InventoryC2SPacket.resync(epoch, state.serverRequestId, state.markerId));
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
			if (!store.assembling(state.channel))
				return;
			stashPending(state, fragment.entries());
			InventoryClientStore.PartOutcome outcome = store.part(state.channel, baselineId, statusRevision,
				fragment.partIndex(), valuesOf(fragment.entries()), ticks);
			if (outcome == InventoryClientStore.PartOutcome.COMMITTED) {
				commitTracking(state, baselineId, statusRevision, fragment.watermark(), fragment.status());
				return;
			}
		}
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
		state.metadataBytes = 0;
		state.pendingMetadata.clear();
		state.pendingBytes = 0;
		state.pendingHasIdentity = false;
		state.pendingBaseline = InventoryClientStore.NO_BASELINE;
		state.pendingRevision = -1L;
		state.committed = false;
		state.committedChecksum = 0L;
	}

	private long computeChecksum(TrackingState state) {
		InventoryChecksums.Builder builder = InventoryChecksums.builder();
		Map<String, InventoryClientStore.Value> committed = store.values(state.channel);
		for (Map.Entry<String, InventoryClientStore.Value> entry : committed.entrySet()) {
			EntryView view = state.metadata.get(entry.getKey());
			if (view == null)
				continue;
			builder.add(entry.getKey(), view.itemId(), entry.getValue().count(), qualityToken(view.quality()),
				view.fallback());
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
			entry.fallback(), entry.quality());
	}

	private static String qualityToken(InventoryS2CPacket.Status quality) {
		return quality == null ? null : quality.name();
	}

	private static boolean bounded(String value) {
		return value != null && !value.isBlank()
			&& value.getBytes(StandardCharsets.UTF_8).length <= InventoryC2SPacket.MAX_ID_BYTES;
	}

	private static int byteSize(EntryView view) {
		return 64 + utf8Length(view.itemId()) + utf8Length(view.label())
			+ (view.displayJson() == null ? 0 : utf8Length(view.displayJson()));
	}

	private static long packetBytes(InventoryS2CPacket packet) {
		long bytes = 32L;
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
