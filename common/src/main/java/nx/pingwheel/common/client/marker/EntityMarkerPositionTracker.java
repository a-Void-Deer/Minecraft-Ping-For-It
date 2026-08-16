package nx.pingwheel.common.client.marker;

import java.util.Objects;

/**
 * Keeps the last live point of an entity marker while the entity is not
 * currently resolvable on the client.
 *
 * <p>The authoritative anchor is used until the first live point is seen. A
 * later live point replaces the remembered point, so resolving the same entity
 * again resumes normal following.
 */
final class EntityMarkerPositionTracker {

	private Position lastLivePosition;

	Position resolve(Position authoritativeAnchor, Position livePosition) {
		Objects.requireNonNull(authoritativeAnchor, "authoritativeAnchor");

		if (livePosition != null) {
			this.lastLivePosition = livePosition;
			return livePosition;
		}

		return this.lastLivePosition == null ? authoritativeAnchor : this.lastLivePosition;
	}

	void reset() {
		this.lastLivePosition = null;
	}

	record Position(double x, double y, double z) {}
}
