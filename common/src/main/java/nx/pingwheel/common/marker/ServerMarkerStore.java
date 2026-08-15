package nx.pingwheel.common.marker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetType;

/**
 * Server-authoritative bookkeeping for active {@link ServerMarker}s.
 *
 * <p>Responsibilities: id assignment on creation, ownership-checked and
 * server-forced removal, tick-based expiry, owner-disconnect cleanup,
 * per-recipient audience maintenance, and authoritative same-target
 * visible-winner tracking via {@link MarkerWinner}.
 *
 * <p>Thread safety: every public method is {@code synchronized}; the store can
 * be shared between the server tick thread and the networking thread. The
 * store never logs and uses only JDK types.
 *
 * <h2>Determinism</h2>
 *
 * <p>Markers are held internally in a {@link LinkedHashMap} (insertion order,
 * which matches ascending {@link MarkerId} because ids are monotonic), and
 * every marker list handed to callers is sorted by ascending {@link MarkerId}.
 *
 * <p>Winner changes are reported per {@code (targetKey, recipient)} pair and
 * ordered deterministically:
 * <ol>
 *   <li>by the {@link MarkerId} of the marker whose creation or removal is
 *       associated with the change, ascending (batch removals process removed
 *       markers in ascending id order);</li>
 *   <li>within one marker, by recipient {@link UUID} in natural order (marker
 *       recipients are already stored sorted).</li>
 * </ol>
 * When several removed markers affect the same pair, only one change is
 * emitted for that pair and it is attributed to the first affected marker in
 * the ascending-id order (first encounter in a {@link LinkedHashSet}); the
 * before/after values are computed against the pre/post state of the whole
 * batch, so no transient intermediate changes are produced.
 */
public final class ServerMarkerStore {

	private final MarkerIdSource idSource;
	private final Map<MarkerId, ServerMarker> markers = new LinkedHashMap<>();

	public ServerMarkerStore(MarkerIdSource idSource) {
		this.idSource = Objects.requireNonNull(idSource, "idSource");
	}

	/**
	 * Assigns the next {@link MarkerId}, builds the {@link ServerMarker}, stores
	 * it, and reports the winner transitions it causes.
	 *
	 * <p>The returned {@link MarkerCreation} contains the stored marker and one
	 * {@link MarkerWinnerChange} per recipient of the new marker whose visible
	 * winner for the marker's target actually changed.
	 */
	public synchronized MarkerCreation create(
		UUID owner,
		Target target,
		TargetType targetType,
		PingType pingType,
		MarkerAnchor anchor,
		long arrivalTick,
		long expiresAtTick,
		List<UUID> recipients
	) {
		ServerMarker marker = new ServerMarker(
			idSource.nextId(),
			owner,
			target,
			targetType,
			pingType,
			anchor,
			arrivalTick,
			expiresAtTick,
			recipients);

		List<MarkerWinnerChange> winnerChanges = creationChanges(marker);

		markers.put(marker.id(), marker);

		return new MarkerCreation(marker, winnerChanges);
	}

	/**
	 * Removes the marker with {@code id} if it exists and is owned by
	 * {@code requester}, using {@link MarkerRemovalReason#CANCELLED}.
	 *
	 * <p>A {@code NOT_FOUND} or {@code NOT_OWNER} result never modifies the
	 * store.
	 */
	public synchronized MarkerRemovalResult removeOwned(UUID requester, MarkerId id) {
		Objects.requireNonNull(requester, "requester");
		Objects.requireNonNull(id, "id");

		ServerMarker marker = markers.get(id);

		if (marker == null) {
			return MarkerRemovalResult.notFound();
		}

		if (!marker.owner().equals(requester)) {
			return MarkerRemovalResult.notOwner();
		}

		return removeInternal(marker, MarkerRemovalReason.CANCELLED);
	}

	/**
	 * Authoritatively removes the marker with {@code id} for the given reason,
	 * regardless of ownership. No-op (with {@code NOT_FOUND}) when absent.
	 */
	public synchronized MarkerRemovalResult removeByServer(MarkerId id, MarkerRemovalReason reason) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(reason, "reason");

		ServerMarker marker = markers.get(id);

		if (marker == null) {
			return MarkerRemovalResult.notFound();
		}

		return removeInternal(marker, reason);
	}

	/**
	 * Removes every marker whose {@link ServerMarker#expiresAtTick()} is less
	 * than or equal to {@code currentTick}.
	 *
	 * <p>Removed markers are processed in ascending {@link MarkerId} order and
	 * each affected {@code (targetKey, recipient)} pair yields at most one
	 * {@link MarkerWinnerChange}, computed against the full before/after state
	 * of the batch.
	 */
	public synchronized MarkerBatchRemoval expire(long currentTick) {
		List<ServerMarker> expired = markers.values().stream()
			.filter(marker -> marker.expiresAtTick() <= currentTick)
			.toList();

		return removeAll(expired, MarkerRemovalReason.EXPIRED);
	}

	/**
	 * Removes every marker owned by {@code owner} using
	 * {@link MarkerRemovalReason#OWNER_DISCONNECTED}.
	 */
	public synchronized MarkerBatchRemoval removeOwnedBy(UUID owner) {
		return removeOwnedBy(owner, MarkerRemovalReason.OWNER_DISCONNECTED);
	}

	/**
	 * Removes every marker owned by {@code owner} for the given reason,
	 * deterministically (ascending {@link MarkerId}).
	 */
	public synchronized MarkerBatchRemoval removeOwnedBy(UUID owner, MarkerRemovalReason reason) {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(reason, "reason");

		List<ServerMarker> owned = markers.values().stream()
			.filter(marker -> marker.owner().equals(owner))
			.toList();

		return removeAll(owned, reason);
	}

	/**
	 * Removes {@code recipient} from every marker's audience by rebuilding the
	 * affected markers with smaller immutable recipient lists.
	 *
	 * <p>Markers that would be left with no recipients are dropped entirely: a
	 * disconnected recipient cannot receive synchronization, and a marker with
	 * an empty audience can never win for anyone else, so no winner change can
	 * be reported or is needed. The visible winners of every remaining
	 * recipient are unaffected.
	 *
	 * @return an immutable list of the markers that were dropped because
	 *         removing {@code recipient} left them with no audience, sorted by
	 *         ascending {@link MarkerId}. Markers that were merely rebuilt with
	 *         the remaining recipients are <em>not</em> included. Callers that
	 *         need to observe or log such drops may inspect the returned
	 *         markers.
	 */
	public synchronized List<ServerMarker> forgetRecipient(UUID recipient) {
		Objects.requireNonNull(recipient, "recipient");

		List<ServerMarker> dropped = new ArrayList<>();

		for (ServerMarker marker : markers.values().stream().toList()) {
			if (!marker.recipients().contains(recipient)) {
				continue;
			}

			List<UUID> remaining = marker.recipients().stream()
				.filter(uuid -> !uuid.equals(recipient))
				.toList();

			if (remaining.isEmpty()) {
				markers.remove(marker.id());
				dropped.add(marker);
			} else {
				markers.put(marker.id(), rebuildWithRecipients(marker, remaining));
			}
		}

		return dropped.stream()
			.sorted(Comparator.comparing(ServerMarker::id))
			.toList();
	}

	/**
	 * Returns the marker with the given id, if active.
	 */
	public synchronized Optional<ServerMarker> find(MarkerId id) {
		Objects.requireNonNull(id, "id");

		return Optional.ofNullable(markers.get(id));
	}

	/**
	 * All active markers, sorted by ascending {@link MarkerId}.
	 */
	public synchronized List<ServerMarker> allMarkers() {
		return markers.values().stream()
			.sorted(Comparator.comparing(ServerMarker::id))
			.toList();
	}

	/**
	 * All active markers owned by {@code owner}, sorted by ascending
	 * {@link MarkerId}.
	 */
	public synchronized List<ServerMarker> markersByOwner(UUID owner) {
		Objects.requireNonNull(owner, "owner");

		return markers.values().stream()
			.filter(marker -> marker.owner().equals(owner))
			.sorted(Comparator.comparing(ServerMarker::id))
			.toList();
	}

	/**
	 * The marker currently controlling the visible outline for {@code key} as
	 * seen by {@code recipient}, or empty if none is visible.
	 */
	public synchronized Optional<ServerMarker> winnerFor(TargetKey key, UUID recipient) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(recipient, "recipient");

		return MarkerWinner.winnerFor(markers.values(), key, recipient);
	}

	/**
	 * The number of active markers.
	 */
	public synchronized int size() {
		return markers.size();
	}

	/**
	 * Drops every active marker without reporting winner changes. Intended for
	 * server lifecycle reset, where no recipients remain to synchronize.
	 */
	public synchronized void clear() {
		markers.clear();
	}

	/**
	 * The winner transitions caused by adding {@code added}, computed before the
	 * marker is stored: only recipients whose visible winner changes appear.
	 */
	private List<MarkerWinnerChange> creationChanges(ServerMarker added) {
		TargetKey key = added.targetKey();
		List<MarkerWinnerChange> changes = new ArrayList<>();

		for (UUID recipient : added.recipients()) {
			ServerMarker before = MarkerWinner.winnerFor(markers.values(), key, recipient).orElse(null);
			ServerMarker after = before;

			// The added marker is visible to every one of its recipients, so it
			// always participates in the comparison.
			if (after == null || MarkerWinner.ARRIVAL_THEN_ID.compare(added, after) > 0) {
				after = added;
			}

			Optional<MarkerId> beforeId = Optional.ofNullable(before).map(ServerMarker::id);

			if (!beforeId.equals(Optional.of(after.id()))) {
				changes.add(new MarkerWinnerChange(key, recipient, beforeId, Optional.of(after.id())));
			}
		}

		return List.copyOf(changes);
	}

	/**
	 * Removes one known-present marker and reports its winner transitions.
	 */
	private MarkerRemovalResult removeInternal(ServerMarker removed, MarkerRemovalReason reason) {
		MarkerBatchRemoval batch = removeAll(List.of(removed), reason);

		return MarkerRemovalResult.removed(batch.removals().get(0), batch.winnerChanges());
	}

	/**
	 * Removes {@code toRemove} and reports one combined winner transition per
	 * affected pair.
	 *
	 * <p>The removed markers are sorted defensively into ascending
	 * {@link MarkerId} order inside this helper, so callers may pass them in
	 * any order without affecting the deterministic removal and winner-change
	 * ordering.
	 */
	private MarkerBatchRemoval removeAll(List<ServerMarker> toRemove, MarkerRemovalReason reason) {
		List<ServerMarker> removedSorted = toRemove.stream()
			.sorted(Comparator.comparing(ServerMarker::id))
			.toList();

		if (removedSorted.isEmpty()) {
			return new MarkerBatchRemoval(List.of(), List.of());
		}

		// Winners as seen before any removal of this batch; computed first so
		// every pair sees the same pre-removal state.
		Map<RecipientPair, Optional<MarkerId>> beforeWinners = beforeWinners(removedSorted);

		List<MarkerRemoval> removals = new ArrayList<>(removedSorted.size());

		for (ServerMarker removed : removedSorted) {
			markers.remove(removed.id());
			removals.add(new MarkerRemoval(removed, reason));
		}

		List<MarkerWinnerChange> changes = new ArrayList<>();
		Set<RecipientPair> seen = new LinkedHashSet<>();

		// After-winners are always computed against the final post-batch map,
		// so a pair affected by several removed markers yields a single,
		// non-transient change attributed to the first marker that touches it.
		for (ServerMarker removed : removedSorted) {
			TargetKey key = removed.targetKey();

			for (UUID recipient : removed.recipients()) {
				RecipientPair pair = new RecipientPair(key, recipient);

				if (!seen.add(pair)) {
					continue;
				}

				Optional<MarkerId> before = beforeWinners.get(pair);
				Optional<MarkerId> after =
					MarkerWinner.winnerFor(markers.values(), key, recipient).map(ServerMarker::id);

				if (!before.equals(after)) {
					changes.add(new MarkerWinnerChange(key, recipient, before, after));
				}
			}
		}

		return new MarkerBatchRemoval(List.copyOf(removals), List.copyOf(changes));
	}

	/**
	 * The pre-removal winner for every {@code (targetKey, recipient)} pair that
	 * any of {@code removedSorted} touches, computed against the untouched map.
	 */
	private Map<RecipientPair, Optional<MarkerId>> beforeWinners(List<ServerMarker> removedSorted) {
		Map<RecipientPair, Optional<MarkerId>> winners = new HashMap<>();

		for (ServerMarker removed : removedSorted) {
			TargetKey key = removed.targetKey();

			for (UUID recipient : removed.recipients()) {
				RecipientPair pair = new RecipientPair(key, recipient);

				if (winners.containsKey(pair)) {
					continue;
				}

				winners.put(pair, MarkerWinner.winnerFor(markers.values(), key, recipient).map(ServerMarker::id));
			}
		}

		return winners;
	}

	/**
	 * Rebuilds {@code marker} with a smaller recipient list. All other fields
	 * are preserved and revalidated by the {@link ServerMarker} constructor.
	 */
	private static ServerMarker rebuildWithRecipients(ServerMarker marker, List<UUID> recipients) {
		return new ServerMarker(
			marker.id(),
			marker.owner(),
			marker.target(),
			marker.targetType(),
			marker.pingType(),
			marker.anchor(),
			marker.arrivalTick(),
			marker.expiresAtTick(),
			recipients);
	}

	/**
	 * A {@code (targetKey, recipient)} pair identifying one visible-winner slot.
	 */
	private record RecipientPair(TargetKey key, UUID recipient) {
	}
}
