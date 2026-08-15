package nx.pingwheel.common.client.marker;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;

/**
 * An immutable, client-side view of an active marker.
 *
 * <p>Derives every field of an authoritative {@link MarkerSnapshot} and adds
 * two client-local bookkeeping ticks:
 * <ul>
 *   <li>{@link #receivedAtLocalTick()} — the client tick on which the snapshot
 *       was applied to the local store;</li>
 *   <li>{@link #fallbackExpiresAtLocalTick()} — the client tick at which the
 *       marker is dropped as a loss-recovery fallback when no authoritative
 *       removal has arrived (see {@link ClientMarkerStore#expireFallback}).</li>
 * </ul>
 *
 * <p>Only JDK types are used here; there are no {@code net.minecraft}
 * references, so this value can be constructed and validated in tests.
 */
public record ClientMarker(
	MarkerId id,
	UUID owner,
	Target target,
	String targetTypeId,
	String pingTypeId,
	MarkerAnchor anchor,
	long arrivalTick,
	long expiresAtTick,
	long receivedAtLocalTick,
	long fallbackExpiresAtLocalTick
) {

	public ClientMarker {
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

		if (receivedAtLocalTick < 0L) {
			throw new IllegalArgumentException("receivedAtLocalTick must be non-negative: " + receivedAtLocalTick);
		}

		if (fallbackExpiresAtLocalTick < receivedAtLocalTick) {
			throw new IllegalArgumentException(
				"fallbackExpiresAtLocalTick must not be before receivedAtLocalTick: "
					+ fallbackExpiresAtLocalTick + " < " + receivedAtLocalTick);
		}
	}

	/**
	 * Derives the client marker for a wire {@link MarkerSnapshot} applied on
	 * client tick {@code receivedAtLocalTick}.
	 *
	 * <p>The fallback expiry is {@code receivedAtLocalTick + fallbackDuration}
	 * where {@code fallbackDuration = max(1, expiresAtTick - arrivalTick) +
	 * graceTicks}. Both additions saturate at {@link Long#MAX_VALUE} instead
	 * of overflowing, so a huge server lifetime or grace can never produce a
	 * negative expiry that would instantly drop the marker.
	 *
	 * @param receivedAtLocalTick the client tick on which the snapshot is
	 *                            applied; must be non-negative
	 * @param graceTicks          extra client ticks the marker may outlive its
	 *                            server expiry before the fallback drops it;
	 *                            must be non-negative
	 */
	public static ClientMarker from(MarkerSnapshot snapshot, long receivedAtLocalTick, long graceTicks) {
		Objects.requireNonNull(snapshot, "snapshot");

		if (receivedAtLocalTick < 0L) {
			throw new IllegalArgumentException("receivedAtLocalTick must be non-negative: " + receivedAtLocalTick);
		}

		if (graceTicks < 0L) {
			throw new IllegalArgumentException("graceTicks must be non-negative: " + graceTicks);
		}

		long fallbackDuration = saturatedAdd(
			Math.max(1L, snapshot.expiresAtTick() - snapshot.arrivalTick()), graceTicks);

		return new ClientMarker(
			snapshot.id(),
			snapshot.owner(),
			snapshot.target(),
			snapshot.targetTypeId(),
			snapshot.pingTypeId(),
			snapshot.anchor(),
			snapshot.arrivalTick(),
			snapshot.expiresAtTick(),
			receivedAtLocalTick,
			saturatedAdd(receivedAtLocalTick, fallbackDuration));
	}

	/**
	 * The frozen {@link TargetKey} identity of this marker's target.
	 */
	public TargetKey targetKey() {
		return TargetKey.from(target);
	}

	/**
	 * Adds two non-negative ticks, saturating at {@link Long#MAX_VALUE} instead
	 * of overflowing.
	 */
	private static long saturatedAdd(long a, long b) {
		if (b > 0L && a > Long.MAX_VALUE - b) {
			return Long.MAX_VALUE;
		}

		return a + b;
	}

	private static String requireNonBlankId(String name, String value) {
		Objects.requireNonNull(value, name);

		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}

		return value;
	}
}
