package nx.pingwheel.common.marker;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic same-target visible-winner selection.
 *
 * <p>The winner is the active marker with the latest server arrival tick;
 * equal arrival ticks resolve to the larger {@link MarkerId}. The comparison is
 * total and independent of collection iteration order, so the result is
 * deterministic across clients.
 */
public final class MarkerWinner {

	/**
	 * Orders markers by ascending arrival tick, then ascending marker id.
	 */
	public static final Comparator<ServerMarker> ARRIVAL_THEN_ID =
		Comparator.comparingLong(ServerMarker::arrivalTick).thenComparing(ServerMarker::id);

	private MarkerWinner() {
	}

	/**
	 * Selects the winning marker among the given markers that share {@code key}
	 * and are visible to {@code recipient}.
	 *
	 * <p>Markers with a different {@link TargetKey} are ignored, so a collection
	 * containing mixed target keys is handled without ambiguity.
	 *
	 * @throws NullPointerException if {@code markers}, {@code key}, or
	 *                              {@code recipient} is {@code null}, or if
	 *                              {@code markers} contains a {@code null}
	 *                              element
	 */
	public static Optional<ServerMarker> winnerFor(
		Collection<? extends ServerMarker> markers,
		TargetKey key,
		UUID recipient
	) {
		Objects.requireNonNull(markers, "markers");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(recipient, "recipient");

		ServerMarker winner = null;

		for (ServerMarker marker : markers) {
			if (marker == null) {
				throw new NullPointerException("markers must not contain null elements");
			}

			if (!key.equals(marker.targetKey())) {
				continue;
			}

			if (!marker.recipients().contains(recipient)) {
				continue;
			}

			if (winner == null || ARRIVAL_THEN_ID.compare(marker, winner) > 0) {
				winner = marker;
			}
		}

		return Optional.ofNullable(winner);
	}
}
