package nx.pingwheel.common.interaction.cancel;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The immutable snapshot of everything needed to resolve a cancellation.
 *
 * <p>Captures the local player's identity, the dimension the local player is
 * currently in, the eye position, the (non-zero) look direction, and the
 * candidate markers to choose from. The candidate list is defensively copied
 * via {@link List#copyOf}, so mutating the caller's list afterwards has
 * no effect, and the returned list cannot be modified.
 */
public record CancellationContext(
	UUID localOwnerId,
	String currentDimensionId,
	WorldVector eyePosition,
	WorldVector lookDirection,
	List<CancelMarkerCandidate> candidates
) {

	public CancellationContext {
		Objects.requireNonNull(localOwnerId, "localOwnerId");
		CancelMarkerCandidate.requireDimensionId(currentDimensionId);
		Objects.requireNonNull(eyePosition, "eyePosition");
		Objects.requireNonNull(lookDirection, "lookDirection");
		candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));

		if (lookDirection.lengthSquared() == 0.0) {
			throw new IllegalArgumentException("lookDirection must not be the zero vector");
		}
	}
}
