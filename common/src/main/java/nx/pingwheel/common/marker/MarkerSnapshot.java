package nx.pingwheel.common.marker;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;

/**
 * An immutable, server-authoritative snapshot of a {@link ServerMarker} as it
 * is encoded for the wire.
 *
 * <p>The snapshot carries the marker id, the authoritative owner, the frozen
 * target identity, the resolved classification and selected presentation by
 * stable string id, the world-space anchor, and the server-side lifetime
 * ticks. No colors, display names, channels, or other client-supplied
 * presentation data are carried: the receiving client resolves presentation
 * from {@code targetTypeId}/{@code pingTypeId} against its own catalogs.
 */
public record MarkerSnapshot(
	MarkerId id,
	UUID owner,
	Target target,
	String targetTypeId,
	String pingTypeId,
	MarkerAnchor anchor,
	long arrivalTick,
	long expiresAtTick
) {

	public MarkerSnapshot {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(anchor, "anchor");
		requireNonBlankId("targetTypeId", targetTypeId);
		requireNonBlankId("pingTypeId", pingTypeId);

		if (arrivalTick < 0L) {
			throw new IllegalArgumentException("arrivalTick must be non-negative: " + arrivalTick);
		}

		if (expiresAtTick <= arrivalTick) {
			throw new IllegalArgumentException(
				"expiresAtTick must be greater than arrivalTick: " + expiresAtTick + " <= " + arrivalTick);
		}
	}

	/**
	 * Derives the wire snapshot for an authoritative {@link ServerMarker}.
	 */
	public static MarkerSnapshot from(ServerMarker marker) {
		Objects.requireNonNull(marker, "marker");

		return new MarkerSnapshot(
			marker.id(),
			marker.owner(),
			marker.target(),
			marker.targetType().id(),
			marker.pingType().id(),
			marker.anchor(),
			marker.arrivalTick(),
			marker.expiresAtTick()
		);
	}

	private static String requireNonBlankId(String name, String value) {
		Objects.requireNonNull(value, name);

		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}

		return value;
	}
}
