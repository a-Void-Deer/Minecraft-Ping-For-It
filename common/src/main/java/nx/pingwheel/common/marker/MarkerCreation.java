package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;

/**
 * The immutable result of adding one marker to a {@link ServerMarkerStore}.
 *
 * <p>{@link #winnerChanges()} contains exactly the per-recipient winner
 * transitions caused by the creation, in deterministic order; recipients whose
 * visible winner did not change are omitted entirely.
 */
public record MarkerCreation(ServerMarker marker, List<MarkerWinnerChange> winnerChanges) {

	public MarkerCreation {
		Objects.requireNonNull(marker, "marker");
		winnerChanges = List.copyOf(Objects.requireNonNull(winnerChanges, "winnerChanges"));
	}
}
