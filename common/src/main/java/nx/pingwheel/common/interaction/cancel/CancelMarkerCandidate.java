package nx.pingwheel.common.interaction.cancel;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;

/**
 * A marker that is a candidate for cancellation.
 *
 * <p>All fields are validated non-null (and the dimension id non-blank), making
 * the value safe to filter on. Only the marker id, owner, dimension, and world
 * position are needed to apply the phase-5 cancellation rules: local ownership
 * + current dimension + 5-degree half-angle cone + nearest world distance.
 */
public record CancelMarkerCandidate(
	MarkerId markerId,
	UUID ownerId,
	String dimensionId,
	WorldVector position
) {

	public CancelMarkerCandidate {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(ownerId, "ownerId");
		requireDimensionId(dimensionId);
		Objects.requireNonNull(position, "position");
	}

	/**
	 * Validates a dimension resource identifier: non-null and non-blank.
	 */
	static String requireDimensionId(String dimensionId) {
		Objects.requireNonNull(dimensionId, "dimensionId");

		if (dimensionId.isBlank()) {
			throw new IllegalArgumentException("dimensionId must not be blank");
		}

		return dimensionId;
	}
}
