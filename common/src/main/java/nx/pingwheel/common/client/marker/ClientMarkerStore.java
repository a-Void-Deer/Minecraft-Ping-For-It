package nx.pingwheel.common.client.marker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.client.duration.ClientMarkerDisplayDuration;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Client-side, main-thread-confined bookkeeping for active markers.
 *
 * <p>Responsibilities: idempotent application of authoritative marker
 * snapshots, safe removal (including of never-seen ids), authoritative
 * same-target winner slots that may reference not-yet-known markers, separate
 * synchronization/display lifetime housekeeping for packet loss and
 * client-side visual expiry, and the one-record-per-target HUD render
 * selection that leaves the underlying records and winner slots untouched.
 *
 * <p>Thread safety: this store is <strong>main-thread-confined</strong>. Every
 * method must be called from the client main thread (the same thread the S2C
 * packet handlers and the client tick run on). No synchronization is provided
 * and concurrent access is unsupported. The store never logs; the client entry
 * point observes and logs mutations itself.
 *
 * <h2>Upsert semantics</h2>
 *
 * <p>{@link #onCreated} is an upsert keyed by {@link MarkerId}: a retransmission
 * refreshes the synchronization fallback if the id has not been authoritatively
 * removed. The latest received payload wins, but the display time domain and
 * visual deadline selected on first insertion are retained.
 * Authoritative removals are tombstoned for this connection, so a delayed
 * create cannot resurrect a removed marker. The replacement does not move the
 * marker within the internal insertion order.
 *
 * <h2>Winner slots</h2>
 *
 * <p>Outlines consume the current server assignment, or after an empty
 * assignment the last server-assigned id with a retained authoritative expiry.
 * The HUD winner slot, including its local loss recovery, is
 * separate and cannot modify this outline history. Neither projection exposes
 * an unknown, mismatched, or visually elapsed record.
 *
 * <h2>HUD selection</h2>
 *
 * <p>{@link #hudRenderMarkers} collapses the visually active records to at
 * most one record per canonical {@link TargetKey} for the world overlay. It
 * prefers the exposed HUD winner slot and otherwise falls back to the
 * same-target record with the latest arrival and then the larger marker id.
 * The selection is a read-only projection: {@link #renderMarkers()} still
 * returns every visually active record, and no record or winner slot is
 * changed by selecting it.
 *
 * <h2>Loss recovery</h2>
 *
 * <p>{@link #expireFallback} removes a synchronized record whose fallback is
 * due if its visual has elapsed or a synchronized sibling supersedes it;
 * otherwise it retains the visual as stale until its display deadline. Loss
 * recovery may recompute the HUD winner but never assigns a new outline winner.
 *
 * <h2>Determinism</h2>
 *
 * <p>Markers are held in a {@link LinkedHashMap} and every list handed to
 * callers is immutable and sorted by ascending {@link MarkerId}. Winner maps
 * are returned as unmodifiable {@link LinkedHashMap}s ordered by ascending
 * {@link MarkerId}.
 */
public final class ClientMarkerStore {

	/** Selects the display time domain and any custom duration for a first insertion. */
	@FunctionalInterface
	public interface DisplayModePolicy {
		ClientMarkerDisplayDuration duration(MarkerSnapshot snapshot);
	}

	/**
	 * Orders markers by ascending arrival tick, then ascending marker id, so
	 * the largest element is the same-target winner the server rule selects.
	 */
	private static final Comparator<ClientMarker> ARRIVAL_THEN_ID =
		Comparator.comparingLong(ClientMarker::arrivalTick).thenComparing(ClientMarker::id);

	/** One server-assigned id per key; the current assignment belongs to its packet view. */
	private record OutlineWinner(MarkerId lastWinner, boolean assigned) {}

	private final long graceTicks;
	private final DisplayModePolicy displayModePolicy;
	private final ObservedServerClock serverClock;
	private final Map<MarkerId, ClientMarker> markers = new LinkedHashMap<>();
	private final Map<TargetKey, MarkerId> displayWinnerByKey = new LinkedHashMap<>();
	private final Map<TargetKey, OutlineWinner> outlineWinnerByKey = new LinkedHashMap<>();
	private final Set<MarkerId> authoritativeRemovedIds = new HashSet<>();
	private long currentLocalTick;

	/**
	 * Creates a store with the clock used for follow-server records and a policy
	 * that selects each new record's time domain.
	 *
	 * @param graceTicks non-negative synchronization fallback grace in client ticks
	 */
	public ClientMarkerStore(long graceTicks, ObservedServerClock serverClock, DisplayModePolicy displayModePolicy) {
		if (graceTicks < 0L) {
			throw new IllegalArgumentException("graceTicks must be non-negative: " + graceTicks);
		}

		this.displayModePolicy = Objects.requireNonNull(displayModePolicy, "displayModePolicy");
		this.serverClock = Objects.requireNonNull(serverClock, "serverClock");
		this.graceTicks = graceTicks;
	}

	/**
	 * Applies an authoritative created-marker snapshot as of client tick
	 * {@code localTick}.
	 *
	 * <p>Upserts the newest payload and refreshes the synchronization fallback;
	 * a same-id refresh does not change the visual deadline. A marker id
	 * already observed in an authoritative removal is ignored, preventing a
	 * delayed create from resurrecting it. Inserting a synchronized record
	 * removes stale same-target siblings, but does not assign a winner. An
	 * earlier assignment for this id becomes visible only when the matching
	 * record is known and visually active.
	 */
	public List<ClientMarker> onCreated(MarkerSnapshot snapshot, long localTick) {
		Objects.requireNonNull(snapshot, "snapshot");

		if (localTick < 0L) {
			throw new IllegalArgumentException("localTick must be non-negative: " + localTick);
		}

		observeLocalTick(localTick);
		if (authoritativeRemovedIds.contains(snapshot.id())) {
			// Marker ids are never reused within a server session. An authoritative
			// removal may arrive before its create packet, so a delayed create must
			// not resurrect that marker or replay its effects.
			return List.of();
		}

		@Nullable ClientMarker existing = markers.get(snapshot.id());
		ClientMarker incoming = existing == null ? firstReceipt(snapshot)
			: existing.refreshed(snapshot, currentLocalTick, graceTicks, serverClock);

		List<ClientMarker> removed = new ArrayList<>();
		// A fresh synchronized marker for a stable target supersedes every
		// stale visual for that target before the new payload is exposed.
		removed.addAll(removeStaleForKey(incoming.targetKey(), incoming.id()));
		markers.put(snapshot.id(), incoming);

		return removed.stream()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	private ClientMarker firstReceipt(MarkerSnapshot snapshot) {
		ClientMarkerDisplayDuration duration = displayModePolicy.duration(snapshot);
		return duration.followsServer()
			? ClientMarker.followingServer(snapshot, currentLocalTick, serverClock.receiptOrigin())
			: ClientMarker.withLocalDuration(snapshot, currentLocalTick, graceTicks, duration.localTicks());
	}

	/** Arms local grace at the time of the packet that establishes sufficient server progress. */
	public void onObservedServerTime(ObservedServerClock source, long localTick) {
		if (source != serverClock) return;
		observeLocalTick(localTick);
		for (var entry : markers.entrySet()) {
			ClientMarker marker = entry.getValue();
			ClientMarker observed = marker.observeFallbackGrace(currentLocalTick, graceTicks);
			if (observed != marker) entry.setValue(observed);
		}
	}

	/** Drops only the vanilla time baseline on a client world detach, not the marker records. */
	public void breakServerTimeBaseline() {
		serverClock.breakWorldBaseline();
	}

	/**
	 * Applies an authoritative removal at the current client tick.
	 *
	 * <p>Every removal tombstones the id, even when the record is unknown.
	 * {@link MarkerRemovalReason#EXPIRED} can retain a visually active custom
	 * record as stale unless a synchronized same-target sibling supersedes it.
	 * Follow-server records, and records removed for other reasons, are deleted
	 * immediately. The result contains only the records actually deleted.
	 */
	public @Unmodifiable List<ClientMarker> onRemoved(
		MarkerId id,
		MarkerRemovalReason reason,
		long localTick
	) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(reason, "reason");

		if (localTick < 0L) {
			throw new IllegalArgumentException("localTick must be non-negative: " + localTick);
		}

		observeLocalTick(localTick);
		@Nullable ClientMarker marker = markers.get(id);
		Set<TargetKey> removedWinnerKeys = new HashSet<>();
		authoritativeRemovedIds.add(id);

		if (reason != MarkerRemovalReason.EXPIRED) {
			return removeMarker(id).stream().toList();
		}

		if (marker == null) {
			// A winner announcement can precede both the create and removal
			// packets. Once removal is authoritative, discard such an unknown
			// slot so a later delayed create cannot make it observable.
			removeMarker(id);
			return List.of();
		}

		if (marker.followsServer() || !marker.isVisuallyActiveAt(currentLocalTick)) {
			rememberWinner(marker, removedWinnerKeys);
			@Unmodifiable List<ClientMarker> removed = removeMarker(id).stream().toList();
			recomputeWinners(removedWinnerKeys);
			return removed;
		}

		ClientMarker stale = marker.asStale();
		boolean hasSynchronizedSibling = hasSynchronizedSibling(stale.targetKey(), stale.id());
		List<ClientMarker> removed = removeStaleForKey(
			stale.targetKey(), stale.id(), removedWinnerKeys);
		// A synchronized sibling is authoritative enough to supersede this
		// stale record. The sibling's eventual winner packet still controls
		// which synchronized marker is visible.
		if (hasSynchronizedSibling) {
			rememberWinner(marker, removedWinnerKeys);
			removeMarker(id).ifPresent(removed::add);
		} else {
			markers.put(id, stale);
		}

		recomputeWinners(removedWinnerKeys);
		return removed.stream()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * Whether an authoritative removal for {@code id} has already been
	 * observed. This is used by the runtime to suppress sound/chat effects for
	 * a delayed create packet that the store correctly ignores.
	 */
	public boolean isAuthoritativelyRemoved(MarkerId id) {
		return authoritativeRemovedIds.contains(Objects.requireNonNull(id, "id"));
	}

	/** Dismisses only the selected, already expired own record; its tombstone survives. */
	public boolean dismissExpiredOwned(MarkerId id, UUID owner, String dimensionId) {
		@Nullable ClientMarker marker = markers.get(id);
		if (marker == null || !marker.owner().equals(owner) || !marker.target().dimensionId().equals(dimensionId)
			|| !retainedAuthoritativeExpiry(marker)) return false;
		removeMarker(id);
		return true;
	}

	private boolean retainedAuthoritativeExpiry(ClientMarker marker) {
		// Only EXPIRED can leave both a stored stale record and a removal tombstone.
		return marker.isStale() && authoritativeRemovedIds.contains(marker.id());
	}

	/**
	 * Records the authoritative visible winner for {@code key}.
	 *
	 * <p>The id is stored even when the marker itself is not known yet, so a
	 * winner update arriving before the created snapshot still converges once
	 * the matching marker arrives. An empty {@code winnerId} clears the HUD
	 * slot and retires the outline assignment without forgetting its last id.
	 * See {@link #winnerMarker} and {@link #outlineWinnersInDimension} for the
	 * distinct projections.
	 */
	public @Unmodifiable List<ClientMarker> onWinnerChanged(TargetKey key, Optional<MarkerId> winnerId) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(winnerId, "winnerId");

		if (winnerId.isEmpty()) {
			displayWinnerByKey.remove(key);
			outlineWinnerByKey.computeIfPresent(key, (ignored, winner) ->
				winner.assigned() ? new OutlineWinner(winner.lastWinner(), false) : winner);
			return List.of();
		}

		MarkerId id = winnerId.get();
		@Nullable ClientMarker marker = markers.get(id);
		if (marker == null && authoritativeRemovedIds.contains(id)) {
			displayWinnerByKey.remove(key);
			// This is still a replacement, so an earlier winner cannot reappear.
			outlineWinnerByKey.remove(key);
			return List.of();
		}
		List<ClientMarker> removed = marker != null
			&& key.equals(marker.targetKey())
			&& marker.isSynchronized()
			? removeStaleForKey(key, id)
			: List.of();

		displayWinnerByKey.put(key, id);
		outlineWinnerByKey.put(key, new OutlineWinner(id, true));
		return List.copyOf(removed);
	}

	/**
	 * Processes synchronized markers whose fallback is due and stale markers
	 * whose independent display deadline has elapsed. Returns the records
	 * actually removed.
	 *
	 * <p>A dropped marker is cleared from both winner projections. Only a HUD
	 * slot for that marker's own target may be recomputed locally, using the
	 * remaining visually active same-target records. Outline assignments are
	 * never recomputed by housekeeping.
	 *
	 * @param localTick the current client tick; must be non-negative
	 * @return an immutable list of the removed markers, sorted by ascending
	 *         {@link MarkerId}
	 */
	public List<ClientMarker> expireFallback(long localTick) {
		if (localTick < 0L) {
			throw new IllegalArgumentException("localTick must be non-negative: " + localTick);
		}

		observeLocalTick(localTick);
		List<ClientMarker> removed = new ArrayList<>();
		Set<TargetKey> removedWinnerKeys = new HashSet<>();

		for (ClientMarker marker : markers.values().stream().toList()) {
			ClientMarker current = markers.get(marker.id());

			if (current == null) {
				continue;
			}

			if (current.isSynchronized() && current.isFallbackDueAt(currentLocalTick)) {
				boolean hasSynchronizedSibling = hasSynchronizedSibling(current.targetKey(), current.id());
				List<ClientMarker> staleSiblings = removeStaleForKey(
					current.targetKey(), current.id(), removedWinnerKeys);
				removed.addAll(staleSiblings);

				if (hasSynchronizedSibling || !current.isVisuallyActiveAt(currentLocalTick)) {
					// A synchronized sibling supersedes this would-be stale
					// record. If its display lifetime has already elapsed, the
					// record is likewise fully over.
					rememberWinner(current, removedWinnerKeys);
					removeMarker(current.id()).ifPresent(removed::add);
					continue;
				}

				markers.put(current.id(), current.asStale());
				continue;
			}

			if (current.isStale() && !current.isVisuallyActiveAt(currentLocalTick)) {
				rememberWinner(current, removedWinnerKeys);
				removeMarker(current.id()).ifPresent(removed::add);
			}
		}

		recomputeWinners(removedWinnerKeys);

		return removed.stream()
			.distinct()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * Drops every marker and every winner slot.
	 */
	public void clear() {
		markers.clear();
		displayWinnerByKey.clear();
		outlineWinnerByKey.clear();
		authoritativeRemovedIds.clear();
		serverClock.reset();
	}

	/** Retires the preceding view's assignments without losing this connection's known last winners. */
	public void beginOutlineWinnerView() {
		var entries = outlineWinnerByKey.entrySet().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			OutlineWinner winner = entry.getValue();
			@Nullable ClientMarker marker = markers.get(winner.lastWinner());
			if (marker == null || !entry.getKey().equals(marker.targetKey())) {
				entries.remove();
			} else if (winner.assigned()) {
				// The new view re-announces only active winners. The old assignment
				// can survive solely as evidence for a retained authoritative expiry.
				entry.setValue(new OutlineWinner(winner.lastWinner(), false));
			}
		}
	}

	/**
	 * The marker with the given id, if active.
	 */
	public Optional<ClientMarker> marker(MarkerId id) {
		Objects.requireNonNull(id, "id");

		return Optional.ofNullable(markers.get(id));
	}

	/**
	 * All active markers as an immutable list, sorted by ascending
	 * {@link MarkerId}.
	 */
	public List<ClientMarker> allMarkers() {
		return markers.values().stream()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * All records whose visual deadline has not elapsed in the applicable time
	 * domain, regardless of synchronization state.
	 */
	public List<ClientMarker> renderMarkers() {
		return markers.values().stream()
			.filter(marker -> marker.isVisuallyActiveAt(currentLocalTick))
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * The world-HUD render selection: at most one visually active record per
	 * canonical {@link TargetKey}.
	 *
	 * <p>The exposed {@linkplain #winnerMarker HUD winner slot} is preferred.
	 * When no slot is exposed, the visually active same-target record with the latest
	 * {@code arrivalTick} and then the larger {@link MarkerId} is selected
	 * instead, matching the loss-recovery ordering. The selection is a
	 * read-only projection: it does not alter {@link #renderMarkers()}, the
	 * winner slots, or any stored record, so a merely non-selected record
	 * stays stored with its visual deadline and can be selected later.
	 *
	 * <p>The result is immutable and sorted by ascending {@link MarkerId}.
	 */
	public List<ClientMarker> hudRenderMarkers() {
		Map<TargetKey, ClientMarker> selected = new LinkedHashMap<>();

		for (ClientMarker marker : renderMarkers()) {
			selected.merge(marker.targetKey(), marker, (current, candidate) ->
				ARRIVAL_THEN_ID.compare(candidate, current) > 0 ? candidate : current);
		}

		for (Map.Entry<TargetKey, ClientMarker> entry : selected.entrySet()) {
			winnerMarker(entry.getKey()).ifPresent(entry::setValue);
		}

		return selected.values().stream()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * All active markers whose target lives in {@code dimensionId}, as an
	 * immutable list sorted by ascending {@link MarkerId}.
	 */
	public List<ClientMarker> markersInDimension(String dimensionId) {
		Objects.requireNonNull(dimensionId, "dimensionId");

		return markers.values().stream()
			.filter(marker -> marker.target().dimensionId().equals(dimensionId))
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * All active markers owned by {@code owner} whose target lives in
	 * {@code dimensionId}, as an immutable list sorted by ascending
	 * {@link MarkerId}.
	 */
	public List<ClientMarker> markersOwnedInDimension(String dimensionId, UUID owner) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(owner, "owner");

		return markers.values().stream()
			.filter(marker -> marker.target().dimensionId().equals(dimensionId))
			.filter(marker -> marker.owner().equals(owner))
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * The HUD winner slot for this target, including any temporary local winner
	 * set by loss recovery. This is not the final {@link #hudRenderMarkers} selection.
	 *
	 * <p>Exposes only a known, matching record whose visual is still active.
	 */
	public Optional<ClientMarker> winnerMarker(TargetKey key) {
		Objects.requireNonNull(key, "key");

		return Optional.ofNullable(visibleRecord(key, displayWinnerByKey.get(key)));
	}

	/**
	 * The per-dimension outline projection, ordered by marker id. Exposes a
	 * known, matching and visually active server assignment or, after that
	 * assignment is retired, its authoritatively expired record while retained.
	 * Never substitutes a locally chosen HUD winner.
	 */
	public @Unmodifiable Map<TargetKey, ClientMarker> outlineWinnersInDimension(String dimensionId) {
		Objects.requireNonNull(dimensionId, "dimensionId");
		Map<TargetKey, ClientMarker> visible = new LinkedHashMap<>();
		outlineWinnerByKey.entrySet().stream()
			.filter(entry -> entry.getKey().dimensionId().equals(dimensionId))
			.sorted(Comparator.comparing(entry -> entry.getValue().lastWinner()))
			.forEachOrdered(entry -> {
				OutlineWinner winner = entry.getValue();
				@Nullable ClientMarker marker = visibleRecord(entry.getKey(), winner.lastWinner());
				if (marker != null && (winner.assigned() || retainedAuthoritativeExpiry(marker))) {
					visible.put(entry.getKey(), marker);
				}
			});
		return Collections.unmodifiableMap(visible);
	}

	private @Nullable ClientMarker visibleRecord(TargetKey key, @Nullable MarkerId id) {
		@Nullable ClientMarker marker = id == null ? null : markers.get(id);
		return marker != null && key.equals(marker.targetKey()) && marker.isVisuallyActiveAt(currentLocalTick)
			? marker : null;
	}

	/**
	 * The local fallback winner among the currently stored markers that share
	 * {@code key}: latest {@code arrivalTick}, then larger {@link MarkerId}.
	 * Returns {@code null} when no matching marker remains.
	 */
	private @Nullable MarkerId fallbackWinnerId(TargetKey key) {
		@Nullable ClientMarker winner = null;

		for (ClientMarker marker : markers.values()) {
			if (!key.equals(marker.targetKey()) || !marker.isVisuallyActiveAt(currentLocalTick)) {
				continue;
			}

			if (winner == null || ARRIVAL_THEN_ID.compare(marker, winner) > 0) {
				winner = marker;
			}
		}

		return winner == null ? null : winner.id();
	}

	private void observeLocalTick(long localTick) {
		this.currentLocalTick = Math.max(this.currentLocalTick, localTick);
	}

	private boolean hasSynchronizedSibling(TargetKey key, MarkerId exceptId) {
		return markers.values().stream()
			.anyMatch(marker -> !marker.id().equals(exceptId)
				&& marker.isSynchronized()
				&& key.equals(marker.targetKey()));
	}

	/** Read-only projection of the stale sibling removal performed by {@link #onCreated}. */
	public boolean isSupersededByCreation(MarkerId id, MarkerSnapshot incoming) {
		@Nullable ClientMarker marker = markers.get(id);
		return !authoritativeRemovedIds.contains(incoming.id()) && marker != null
			&& isStaleSibling(marker, incoming.targetKey(), incoming.id());
	}

	private static boolean isStaleSibling(ClientMarker marker, TargetKey key, MarkerId exceptId) {
		return !marker.id().equals(exceptId) && marker.isStale() && key.equals(marker.targetKey());
	}

	private List<ClientMarker> removeStaleForKey(TargetKey key, MarkerId exceptId) {
		return removeStaleForKey(key, exceptId, null);
	}

	private List<ClientMarker> removeStaleForKey(
		TargetKey key,
		MarkerId exceptId,
		@Nullable Set<TargetKey> removedWinnerKeys
	) {
		List<ClientMarker> removed = new ArrayList<>();

		for (ClientMarker marker : markers.values().stream().toList()) {
			if (isStaleSibling(marker, key, exceptId)) {
				if (removedWinnerKeys != null
					&& marker.id().equals(displayWinnerByKey.get(marker.targetKey()))) {
					removedWinnerKeys.add(marker.targetKey());
				}
				removeMarker(marker.id()).ifPresent(removed::add);
			}
		}

		return removed;
	}

	private Optional<ClientMarker> removeMarker(MarkerId id) {
		@Nullable ClientMarker removed = markers.remove(id);
		displayWinnerByKey.values().removeIf(id::equals);
		outlineWinnerByKey.values().removeIf(winner -> id.equals(winner.lastWinner()));

		return Optional.ofNullable(removed);
	}

	private void rememberWinner(ClientMarker marker, Set<TargetKey> winnerKeys) {
		if (marker.id().equals(displayWinnerByKey.get(marker.targetKey()))) {
			winnerKeys.add(marker.targetKey());
		}
	}

	private void recomputeWinners(Set<TargetKey> keys) {
		for (TargetKey key : keys) {
			@Nullable MarkerId fallback = fallbackWinnerId(key);

			if (fallback == null) {
				displayWinnerByKey.remove(key);
			} else {
				displayWinnerByKey.put(key, fallback);
			}
		}
	}
}
