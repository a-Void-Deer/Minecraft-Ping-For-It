package nx.pingwheel.common.interaction.cancel;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure fallback policy for a marker cancellation candidate.
 *
 * <p>A rendered presentation position is preferred over the authoritative
 * anchor. An empty presentation position means the marker view is absent or
 * has not rendered yet, so the anchor remains the safe fallback.
 */
public final class MarkerCandidatePosition {

	private MarkerCandidatePosition() {}

	public static WorldVector resolve(WorldVector anchor, Optional<WorldVector> presentationPosition) {
		Objects.requireNonNull(anchor, "anchor");
		Objects.requireNonNull(presentationPosition, "presentationPosition");

		return presentationPosition.orElse(anchor);
	}
}
