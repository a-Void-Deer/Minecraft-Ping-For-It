package nx.pingwheel.common.client.marker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

/**
 * Client-side, main-thread-confined bookkeeping for active markers.
 *
 * <p>Responsibilities: idempotent application of authoritative marker
 * snapshots, safe removal (including of never-seen ids), authoritative
 * same-target winner slots that may reference not-yet-known markers, and a
 * local loss-recovery expiry that drops markers when no authoritative removal
 * arrives (for example during packet loss).
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
 * payload but refreshes the local fallback expiry; re-applying the same id
 * with a different snapshot deterministically replaces the stored marker with
 * the latest payload (the server is authoritative, so the newest received
 * snapshot for an id wins). The replacement does not move the marker within
 * the internal insertion order.
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
 * <p>{@link #expireFallback} drops markers whose client-side fallback expiry
 * has passed. Every winner slot referencing a dropped marker is cleared; when
 * the slot for a dropped marker's own target key referenced that marker, a
 * local fallback winner is recomputed among the remaining markers of that
 * target using the server rule (latest {@code arrivalTick}, then larger
 * {@link MarkerId}) or the slot is cleared when none remain. This is loss
 * recovery only: a subsequent authoritative S2C winner update simply
 * overwrites the fallback value.
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
	 * Orders markers by ascending arrival tick, then ascending marker id, so
	 * the largest element is the same-target winner the server rule selects.
	 */
	private static final Comparator<ClientMarker> ARRIVAL_THEN_ID =
		Comparator.comparingLong(ClientMarker::arrivalTick).thenComparing(ClientMarker::id);

	private final long graceTicks;
	private final Map<MarkerId, ClientMarker> markers = new LinkedHashMap<>();
	private final Map<TargetKey, MarkerId> authoritativeWinnerByKey = new LinkedHashMap<>();

	/**
	 * @param graceTicks extra client ticks a marker may outlive its server
	 *                   expiry before {@link #expireFallback} drops it; must be
	 *                   non-negative
	 */
	public ClientMarkerStore(long graceTicks) {
		if (graceTicks < 0L) {
			throw new IllegalArgumentException("graceTicks must be non-negative: " + graceTicks);
		}

		this.graceTicks = graceTicks;
	}

	/**
	 * Applies an authoritative created-marker snapshot as of client tick
	 * {@code localTick}.
	 *
	 * <p>Idempotent upsert: an identical snapshot at the same local tick
	 * changes nothing; a retransmission of the same snapshot at a later local
	 * tick refreshes the local fallback expiry; a different snapshot for the
	 * same id replaces the stored marker with the latest payload. Winner slots
	 * are not touched here — a winner already announced for this id only
	 * becomes visible through the winner queries once the key matches, and a
	 * missing winner announcement is filled in by the server.
	 */
	public void onCreated(MarkerSnapshot snapshot, long localTick) {
		Objects.requireNonNull(snapshot, "snapshot");

		if (localTick < 0L) {
			throw new IllegalArgumentException("localTick must be non-negative: " + localTick);
		}

		markers.put(snapshot.id(), ClientMarker.from(snapshot, localTick, graceTicks));
	}

	/**
	 * Removes the marker with {@code id}, if known.
	 *
	 * <p>Removing an unknown id is a safe no-op. Every winner slot whose
	 * stored id equals {@code id} is cleared, so a stale authoritative winner
	 * can never outlive its marker.
	 */
	public void onRemoved(MarkerId id) {
		Objects.requireNonNull(id, "id");

		markers.remove(id);
		authoritativeWinnerByKey.values().removeIf(id::equals);
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
	public void onWinnerChanged(TargetKey key, Optional<MarkerId> winnerId) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(winnerId, "winnerId");

		if (winnerId.isEmpty()) {
			authoritativeWinnerByKey.remove(key);
			return;
		}

		authoritativeWinnerByKey.put(key, winnerId.get());
	}

	/**
	 * Drops every marker whose {@link ClientMarker#fallbackExpiresAtLocalTick()}
	 * is less than or equal to {@code localTick} and returns the removed
	 * markers.
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

		List<ClientMarker> removed = new ArrayList<>();

		for (ClientMarker marker : markers.values().stream().toList()) {
			if (marker.fallbackExpiresAtLocalTick() > localTick) {
				continue;
			}

			markers.remove(marker.id());
			removed.add(marker);
		}

		for (ClientMarker removedMarker : removed) {
			TargetKey key = removedMarker.targetKey();
			boolean wasStoredWinner = removedMarker.id().equals(authoritativeWinnerByKey.get(key));

			// Every slot referencing the dropped marker goes away, including a
			// mismatched slot whose key differs from the marker's target.
			authoritativeWinnerByKey.values().removeIf(removedMarker.id()::equals);

			if (!wasStoredWinner) {
				continue;
			}

			MarkerId fallback = fallbackWinnerId(key);

			if (fallback == null) {
				authoritativeWinnerByKey.remove(key);
			} else {
				authoritativeWinnerByKey.put(key, fallback);
			}
		}

		return removed.stream()
			.sorted(Comparator.comparing(ClientMarker::id))
			.toList();
	}

	/**
	 * Drops every marker and every winner slot.
	 */
	public void clear() {
		markers.clear();
		authoritativeWinnerByKey.clear();
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

			if (marker == null || !entry.getKey().equals(marker.targetKey())) {
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
			if (!key.equals(marker.targetKey())) {
				continue;
			}

			if (winner == null || ARRIVAL_THEN_ID.compare(marker, winner) > 0) {
				winner = marker;
			}
		}

		return winner == null ? null : winner.id();
	}
}
