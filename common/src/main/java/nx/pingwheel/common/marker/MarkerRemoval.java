package nx.pingwheel.common.marker;

import java.util.Objects;

/**
 * The immutable result of removing one active marker from a
 * {@link ServerMarkerStore}, carrying the removed marker and the authoritative
 * {@link MarkerRemovalReason} for why it was removed.
 */
public record MarkerRemoval(ServerMarker marker, MarkerRemovalReason reason) {

	public MarkerRemoval {
		Objects.requireNonNull(marker, "marker");
		Objects.requireNonNull(reason, "reason");
	}
}
