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

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Client-side, main-thread-confined bookkeeping for active markers.
 *
 * <p>Responsibilities: idempotent application of authoritative marker
 * snapshots, safe removal (including of never-seen ids), authoritative
 * same-target winner slots that may reference not-yet-known markers, and
 * separate synchronization/display lifetime housekeeping for packet loss and
 * client-side visual expiry.
 *
 * <p>Thread safety: this store is <strong>main-thread-confined</strong>. Every
 * method must be called from the client main thread (the same thread the S2C
 * packet handlers and the client tick run on). No synchronization is provided
 * and concurrent access is unsupported. The store never logs; the client entry
 * point observes and logs mutations itself.
 *
 * <h2>Upsert semantics</h2>
 *
 * <p>{@link #onCreated} is an idempotent upsert keyed by {@link MarkerId}:
 * re-applying an identical snapshot at the same client tick is a no-op, and a
 * retransmission of an identical snapshot at a later client tick keeps the
 * payload but refreshes the local fallback expiry while the id has not been
 * authoritatively removed; re-applying the same id with a different snapshot
 * deterministically replaces the stored marker with the latest payload (the
 * server is authoritative, so the newest received snapshot for an id wins).
 * Authoritative removals are tombstoned for this connection, so a delayed
 * create cannot resurrect a removed marker. The replacement does not move the
 * marker within the internal insertion order.
 *
 * <h2>Winner slots</h2>
 *
 * <p>{@code authoritativeWinnerByKey} stores the authoritative winner
 * {@link MarkerId} per {@link TargetKey} exactly as announced by the server
 * via {@link #onWinnerChanged}. The slot may reference a marker id that has
 * not been received yet, so winner-then-created packet order converges once
 * the marker arrives. A stored winner id is only exposed by the winner queries
 * when a marker with that id actually exists <em>and</em> its target key
 * matches the slot key, so a slot that briefly references a marker of a
 * different target is never observable.
 *
 * <h2>Loss recovery</h2>
 *
 * <p>{@link #expireFallback} transitions synchronized markers whose client-side
 * fallback expiry has passed to stale, retaining the visual until its separate
 * display deadline. Final removals clear winner slots and locally recompute a
 * fallback winner only as loss recovery; a subsequent authoritative S2C
 * winner update simply overwrites that value.
 *
 * <h2>Determinism</h2>
 *
 * <p>Markers are held in a {@link LinkedHashMap} and every list handed to
 * callers is immutable and sorted by ascending {@link MarkerId}. Winner maps
 * are returned as unmodifiable {@link LinkedHashMap}s ordered by ascending
 * {@link MarkerId}.
 */
public final class ClientMarkerStore {

	/**
	 * Supplies the visual lifetime for a newly received marker. The server
	 * synchronization lifetime is always derived from the snapshot itself; this
	 * hook only controls the independent client display deadline.
	 */
	@FunctionalInterface
	public interface DisplayDurationPolicy {
		long durationTicks(MarkerSnapshot snapshot);
	}

	/**
	 * Orders markers by ascending arrival tick, then ascending marker id, so
	 * the largest element is the same-target winner the server rule selects.
	 */
	private static final Comparator<ClientMarker> ARRIVAL_THEN_ID =
		Comparator.comparingLong(ClientMarker::arrivalTick).thenComparing(ClientMarker::id);

	private final long graceTicks;
	private final DisplayDurationPolicy displayDurationPolicy;
	private final Map<MarkerId, ClientMarker> markers = new LinkedHashMap<>();
	private final Map<TargetKey, MarkerId> authoritativeWinnerByKey = new LinkedHashMap<>();
	private final Set<MarkerId> authoritativeRemovedIds = new HashSet<>();
	private long currentLocalTick;

	/**
	 * @param graceTicks extra client ticks a marker may outlive its server
	 *                   expiry before {@link #expireFallback} drops it; must be
	 *                   non-negative
	 */
	public ClientMarkerStore(long graceTicks) {
		this(graceTicks, ClientMarker::serverDurationTicks);
	}

	/**
	 * Creates a store with a caller-supplied visual duration policy. The runtime
	 * supplies the local setting through this seam while the store keeps each
	 * marker's selected deadline independent from later setting changes.
	 */
	public ClientMarkerStore(long graceTicks, DisplayDurationPolicy displayDurationPolicy) {
		if (graceTicks < 0L) {
			throw new IllegalArgumentException("graceTicks must be non-negative: " + graceTicks);
		}

		this.displayDurationPolicy = Objects.requireNonNull(displayDurationPolicy, "displayDurationPolicy");
		this.graceTicks = graceTicks;
	}

	/** Convenience constructor for a fixed visual duration. */
	public ClientMarkerStore(long graceTicks, long displayDurationTicks) {
		this(graceTicks, snapshot -> displayDurationTicks);

		if (displayDurationTicks < 0L) {
			throw new IllegalArgumentException(
				"displayDurationTicks must be non-negative: " + displayDurationTicks);
		}
	}

	/**
	 * Applies an authoritative created-marker snapshot as of client tick
	 * {@code localTick}.
	 *
	 * <p>Idempotent upsert: an identical snapshot at the same local tick
	 * changes nothing; a retransmission of the same snapshot at a later local
	 * tick refreshes the local fallback expiry; a different snapshot for the
	 * same id replaces the stored marker with the latest payload. A marker id
	 * already observed in an authoritative removal is ignored, preventing a
	 * delayed create from resurrecting it. Winner slots are not touched here —
	 * a winner already announced for this id only becomes visible through the
	 * winner queries once the key matches, and a missing winner announcement is
	 * filled in by the server.
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

		long appliedTick = currentLocalTick;
		long displayDurationTicks = displayDurationPolicy.durationTicks(snapshot);

		if (displayDurationTicks < 0L) {
			throw new IllegalArgumentException(
				"displayDurationPolicy returned a negative duration: " + displayDurationTicks);
		}

		ClientMarker incoming = ClientMarker.from(
			snapshot, appliedTick, graceTicks, displayDurationTicks);
		ClientMarker existing = markers.get(snapshot.id());

		if (existing != null) {
			// A marker id identifies one server marker. Replays and payload
			// refreshes must never extend its visual deadline or resurrect a
			// visual that has already elapsed.
			incoming = copyWith(
				incoming,
				existing.displayExpiresAtLocalTick(),
				ClientMarkerState.SYNCHRONIZED);
		}

		List<ClientMarker> removed = new ArrayList<>();
		// A fresh synchronized marker for a stable target supersedes every
		// stale visual for that target before the new payload is exposed.
		removed.addAll(removeStaleForKey(incoming.targetKey(), incoming.id()));
		markers.put(snapshot.id(), incoming);

		return removed.stream()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * Removes the marker with {@code id}, if known, and remembers the
	 * authoritative removal for this connection.
	 *
	 * <p>Removing an unknown id is otherwise a safe no-op. Every winner slot
	 * whose stored id equals {@code id} is cleared, so a stale authoritative
	 * winner can never outlive its marker.
	 */
	public void onRemoved(MarkerId id) {
		Objects.requireNonNull(id, "id");

		authoritativeRemovedIds.add(id);
		removeMarker(id);
	}

	/**
	 * Applies an authoritative removal at the current client tick.
	 *
	 * <p>{@link MarkerRemovalReason#EXPIRED} ends synchronization but retains a
	 * stale visual until its independent display deadline. Every other reason
	 * is an immediate hard removal. The returned records are the client records
	 * actually deleted; callers use that distinction to keep target-name cleanup
	 * in lockstep with final record removal.
	 */
	public List<ClientMarker> onRemoved(
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
		ClientMarker marker = markers.get(id);
		Set<TargetKey> removedWinnerKeys = new HashSet<>();
		authoritativeRemovedIds.add(id);

		if (reason != MarkerRemovalReason.EXPIRED) {
			return removeMarker(id).stream().toList();
		}

		if (marker == null) {
			// A winner announcement can precede both the create and removal
			// packets. Once removal is authoritative, discard such an unknown
			// slot so a later delayed create cannot make it observable.
			authoritativeWinnerByKey.values().removeIf(id::equals);
			return List.of();
		}

		if (!marker.isVisuallyActiveAt(currentLocalTick)) {
			rememberWinner(marker, removedWinnerKeys);
			List<ClientMarker> removed = removeMarker(id).stream().toList();
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

	/** Applies a removal using the store's latest observed client tick. */
	public List<ClientMarker> onRemoved(MarkerId id, MarkerRemovalReason reason) {
		return onRemoved(id, reason, currentLocalTick);
	}

	/**
	 * Whether an authoritative removal for {@code id} has already been
	 * observed. This is used by the runtime to suppress sound/chat effects for
	 * a delayed create packet that the store correctly ignores.
	 */
	public boolean isAuthoritativelyRemoved(MarkerId id) {
		return authoritativeRemovedIds.contains(Objects.requireNonNull(id, "id"));
	}

	/**
	 * Records the authoritative visible winner for {@code key}.
	 *
	 * <p>The id is stored even when the marker itself is not known yet, so a
	 * winner update arriving before the created snapshot still converges once
	 * the marker arrives. An empty {@code winnerId} clears the slot. The slot
	 * is only exposed by {@link #winnerId}, {@link #winnerMarker}, and
	 * {@link #visibleWinnersInDimension} while a marker with the stored id
	 * exists and matches the key.
	 */
	public List<ClientMarker> onWinnerChanged(TargetKey key, Optional<MarkerId> winnerId) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(winnerId, "winnerId");

		if (winnerId.isEmpty()) {
			authoritativeWinnerByKey.remove(key);
			return List.of();
		}

		MarkerId id = winnerId.get();
		ClientMarker marker = markers.get(id);
		if (marker == null && authoritativeRemovedIds.contains(id)) {
			authoritativeWinnerByKey.remove(key);
			return List.of();
		}
		List<ClientMarker> removed = marker != null
			&& key.equals(marker.targetKey())
			&& marker.isSynchronized()
			? removeStaleForKey(key, id)
			: List.of();

		authoritativeWinnerByKey.put(key, id);
		return removed;
	}

	/**
	 * Transitions synchronized markers whose
	 * {@link ClientMarker#fallbackExpiresAtLocalTick()} is less than or equal to
	 * {@code localTick} to stale, or removes records whose independent display
	 * deadline has elapsed, and returns the records actually removed.
	 *
	 * <p>For every dropped marker, every winner slot whose stored id equals the
	 * dropped marker's id is cleared, even when the slot's key differs from the
	 * marker's target. If the slot for the marker's own target key referenced
	 * the dropped marker, that slot is recomputed as a local fallback among
	 * the remaining markers of that target (latest {@code arrivalTick}, then
	 * larger {@link MarkerId}) or cleared when none remain; slots whose key
	 * mismatched the marker's target are simply cleared. Winner slots that did
	 * not reference a dropped marker are left untouched.
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

			if (current.isSynchronized()
				&& current.fallbackExpiresAtLocalTick() <= currentLocalTick) {
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
		authoritativeWinnerByKey.clear();
		authoritativeRemovedIds.clear();
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
	 * All records that are still within their independent visual lifetime at
	 * the latest observed client tick. Synchronization state is intentionally
	 * not part of this filter: stale markers render exactly like synchronized
	 * markers for now.
	 */
	public List<ClientMarker> renderMarkers() {
		return markers.values().stream()
			.filter(marker -> marker.isVisuallyActiveAt(currentLocalTick))
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
	 * The id of the marker currently controlling the visible outline for
	 * {@code key}, or empty if none is visible.
	 *
	 * <p>Empty is returned while the stored winner marker is not known yet or
	 * belongs to a different target key, so a mismatched slot is never exposed.
	 */
	public Optional<MarkerId> winnerId(TargetKey key) {
		Objects.requireNonNull(key, "key");

		MarkerId id = authoritativeWinnerByKey.get(key);

		if (id == null) {
			return Optional.empty();
		}

		ClientMarker marker = markers.get(id);

		if (marker == null || !key.equals(marker.targetKey())) {
			return Optional.empty();
		}

		if (!marker.isVisuallyActiveAt(currentLocalTick)) {
			return Optional.empty();
		}

		return Optional.of(id);
	}

	/**
	 * The marker currently controlling the visible outline for {@code key}, or
	 * empty if none is visible.
	 *
	 * <p>Empty is returned while the stored winner marker is not known yet or
	 * belongs to a different target key, so a mismatched slot is never exposed.
	 */
	public Optional<ClientMarker> winnerMarker(TargetKey key) {
		Objects.requireNonNull(key, "key");

		MarkerId id = authoritativeWinnerByKey.get(key);

		if (id == null) {
			return Optional.empty();
		}

		ClientMarker marker = markers.get(id);

		if (marker == null || !key.equals(marker.targetKey())) {
			return Optional.empty();
		}

		if (!marker.isVisuallyActiveAt(currentLocalTick)) {
			return Optional.empty();
		}

		return Optional.of(marker);
	}

	/**
	 * Every {@code (targetKey, visible winner marker)} pair whose key lives in
	 * {@code dimensionId}, as an unmodifiable map ordered by ascending
	 * {@link MarkerId}.
	 *
	 * <p>Winner slots that reference an unknown marker or a marker with a
	 * different target key are skipped, so mismatched slots are never exposed.
	 */
	public Map<TargetKey, ClientMarker> visibleWinnersInDimension(String dimensionId) {
		Objects.requireNonNull(dimensionId, "dimensionId");

		List<Map.Entry<TargetKey, MarkerId>> entries = authoritativeWinnerByKey.entrySet().stream()
			.filter(entry -> entry.getKey().dimensionId().equals(dimensionId))
			.sorted(Map.Entry.comparingByValue())
			.toList();

		Map<TargetKey, ClientMarker> visible = new LinkedHashMap<>();

		for (Map.Entry<TargetKey, MarkerId> entry : entries) {
			ClientMarker marker = markers.get(entry.getValue());

			if (marker == null
				|| !entry.getKey().equals(marker.targetKey())
				|| !marker.isVisuallyActiveAt(currentLocalTick)) {
				continue;
			}

			visible.put(entry.getKey(), marker);
		}

		return Collections.unmodifiableMap(visible);
	}

	/**
	 * The local fallback winner among the currently stored markers that share
	 * {@code key}: latest {@code arrivalTick}, then larger {@link MarkerId}.
	 * Returns {@code null} when no matching marker remains.
	 */
	private MarkerId fallbackWinnerId(TargetKey key) {
		ClientMarker winner = null;

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

	private List<ClientMarker> removeStaleForKey(TargetKey key, MarkerId exceptId) {
		return removeStaleForKey(key, exceptId, null);
	}

	private List<ClientMarker> removeStaleForKey(
		TargetKey key,
		MarkerId exceptId,
		Set<TargetKey> removedWinnerKeys
	) {
		List<ClientMarker> removed = new ArrayList<>();

		for (ClientMarker marker : markers.values().stream().toList()) {
			if (!marker.id().equals(exceptId)
				&& marker.isStale()
				&& key.equals(marker.targetKey())) {
				if (removedWinnerKeys != null
					&& marker.id().equals(authoritativeWinnerByKey.get(marker.targetKey()))) {
					removedWinnerKeys.add(marker.targetKey());
				}
				removeMarker(marker.id()).ifPresent(removed::add);
			}
		}

		return removed;
	}

	private Optional<ClientMarker> removeMarker(MarkerId id) {
		ClientMarker removed = markers.remove(id);
		authoritativeWinnerByKey.values().removeIf(id::equals);

		return Optional.ofNullable(removed);
	}

	private void rememberWinner(ClientMarker marker, Set<TargetKey> winnerKeys) {
		if (marker.id().equals(authoritativeWinnerByKey.get(marker.targetKey()))) {
			winnerKeys.add(marker.targetKey());
		}
	}

	private void recomputeWinners(Set<TargetKey> keys) {
		for (TargetKey key : keys) {
			MarkerId fallback = fallbackWinnerId(key);

			if (fallback == null) {
				authoritativeWinnerByKey.remove(key);
			} else {
				authoritativeWinnerByKey.put(key, fallback);
			}
		}
	}

	private static ClientMarker copyWith(
		ClientMarker marker,
		long displayExpiresAtLocalTick,
		ClientMarkerState state
	) {
		return new ClientMarker(
			marker.id(),
			marker.owner(),
			marker.target(),
			marker.targetTypeId(),
			marker.pingTypeId(),
			marker.anchor(),
			marker.arrivalTick(),
			marker.expiresAtTick(),
			marker.receivedAtLocalTick(),
			marker.fallbackExpiresAtLocalTick(),
			displayExpiresAtLocalTick,
			state);
	}
}
