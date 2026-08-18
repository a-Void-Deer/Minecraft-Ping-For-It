package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;

/**
 * A per-recipient change of the visible winner for one concrete target.
 *
 * <p>Both sides are carried as {@link MarkerId}s rather than whole markers, so
 * the change is stable and cheap to validate. A change always represents a
 * real transition: {@link #previousWinner()} and {@link #currentWinner()} must
 * differ (including the empty-to-empty case, which is rejected).
 */
public record MarkerWinnerChange(
	TargetKey targetKey,
	UUID recipientId,
	Optional<MarkerId> previousWinner,
	Optional<MarkerId> currentWinner
) {

	public MarkerWinnerChange {
		Objects.requireNonNull(targetKey, "targetKey");
		Objects.requireNonNull(recipientId, "recipientId");
		previousWinner = Objects.requireNonNull(previousWinner, "previousWinner");
		currentWinner = Objects.requireNonNull(currentWinner, "currentWinner");

		if (previousWinner.equals(currentWinner)) {
			throw new IllegalArgumentException("previousWinner must differ from currentWinner");
		}
	}
}
