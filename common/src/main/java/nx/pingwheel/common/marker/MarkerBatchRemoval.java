package nx.pingwheel.common.marker;

import java.util.List;
import java.util.Objects;

/**
 * The immutable result of a batch removal from a {@link ServerMarkerStore}
 * (expiry or owner-disconnect cleanup).
 *
 * <p>{@link #removals()} lists the removed markers in ascending
 * {@link MarkerId} order and {@link #winnerChanges()} carries one winner
 * transition per affected target/recipient pair, also in deterministic order.
 */
public record MarkerBatchRemoval(List<MarkerRemoval> removals, List<MarkerWinnerChange> winnerChanges) {

	public MarkerBatchRemoval {
		removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
		winnerChanges = List.copyOf(Objects.requireNonNull(winnerChanges, "winnerChanges"));
	}
}
