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
 * client-local synchronization/display bookkeeping:
 * <ul>
 *   <li>{@link #receivedAtLocalTick()} — the client tick on which the snapshot
 *       was applied to the local store;</li>
 *   <li>{@link #fallbackExpiresAtLocalTick()} — the client tick at which the
 *       synchronized state ends as a loss-recovery fallback when no
 *       authoritative removal has arrived (see
 *       {@link ClientMarkerStore#expireFallback});</li>
 *   <li>{@link #displayExpiresAtLocalTick()} — the independent visual
 *       deadline; and</li>
 *   <li>{@link #state()} — whether authoritative synchronization is still
 *       current or stale.</li>
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
	long fallbackExpiresAtLocalTick,
	long displayExpiresAtLocalTick,
	ClientMarkerState state
) {

	/**
	 * Compatibility constructor for callers that only supplied the original
	 * synchronization fields.  Such records use the synchronization fallback
	 * as their visual deadline, which is the least surprising legacy behavior.
	 */
	public ClientMarker(
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
		this(
			id,
			owner,
			target,
			targetTypeId,
			pingTypeId,
			anchor,
			arrivalTick,
			expiresAtTick,
			receivedAtLocalTick,
			fallbackExpiresAtLocalTick,
			fallbackExpiresAtLocalTick,
			ClientMarkerState.SYNCHRONIZED);
	}

	public ClientMarker {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(anchor, "anchor");
		Objects.requireNonNull(state, "state");
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

		if (displayExpiresAtLocalTick < 0L) {
			throw new IllegalArgumentException(
				"displayExpiresAtLocalTick must be non-negative: " + displayExpiresAtLocalTick);
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

		return from(snapshot, receivedAtLocalTick, graceTicks, serverDurationTicks(snapshot));
	}

	/**
	 * Derives a client marker with an explicitly supplied visual duration.  The
	 * synchronization fallback remains tied to the frozen server duration; only
	 * the independent visual deadline is customized.
	 *
	 * @param displayDurationTicks visual lifetime from receipt, in client ticks;
	 *                             must be non-negative
	 */
	public static ClientMarker from(
		MarkerSnapshot snapshot,
		long receivedAtLocalTick,
		long graceTicks,
		long displayDurationTicks
	) {
		Objects.requireNonNull(snapshot, "snapshot");

		if (receivedAtLocalTick < 0L) {
			throw new IllegalArgumentException("receivedAtLocalTick must be non-negative: " + receivedAtLocalTick);
		}

		if (graceTicks < 0L) {
			throw new IllegalArgumentException("graceTicks must be non-negative: " + graceTicks);
		}

		if (displayDurationTicks < 0L) {
			throw new IllegalArgumentException(
				"displayDurationTicks must be non-negative: " + displayDurationTicks);
		}

		long fallbackDuration = saturatedAdd(
			serverDurationTicks(snapshot), graceTicks);

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
			saturatedAdd(receivedAtLocalTick, fallbackDuration),
			saturatedAdd(receivedAtLocalTick, displayDurationTicks),
			ClientMarkerState.SYNCHRONIZED);
	}

	/**
	 * The frozen server-side lifetime represented by a marker snapshot.  The
	 * minimum is defensive for callers handling a corrupt-but-constructed value.
	 */
	public static long serverDurationTicks(MarkerSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		return Math.max(1L, snapshot.expiresAtTick() - snapshot.arrivalTick());
	}

	/** Returns whether authoritative synchronization has ended for this record. */
	public boolean isStale() {
		return state == ClientMarkerState.STALE;
	}

	/** Short alias useful to lifecycle callers and tests. */
	public boolean stale() {
		return isStale();
	}

	/** Returns whether the record still represents synchronized server state. */
	public boolean isSynchronized() {
		return state == ClientMarkerState.SYNCHRONIZED;
	}

	/**
	 * Returns whether this marker is still allowed to contribute a visual at the
	 * supplied client tick.  The marker record itself may remain synchronized
	 * after this returns false so late authoritative packets can be processed
	 * without resurrecting the visual.
	 */
	public boolean isVisuallyActiveAt(long localTick) {
		if (localTick < 0L) {
			throw new IllegalArgumentException("localTick must be non-negative: " + localTick);
		}

		return localTick < displayExpiresAtLocalTick;
	}

	/** Returns a copy whose synchronization state is stale. */
	public ClientMarker asStale() {
		if (isStale()) {
			return this;
		}

		return new ClientMarker(
			id,
			owner,
			target,
			targetTypeId,
			pingTypeId,
			anchor,
			arrivalTick,
			expiresAtTick,

			receivedAtLocalTick,
			fallbackExpiresAtLocalTick,
			displayExpiresAtLocalTick,
			ClientMarkerState.STALE);
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
