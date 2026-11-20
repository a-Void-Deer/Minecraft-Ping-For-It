package nx.pingwheel.common.client.marker;

import java.util.Objects;
import java.util.UUID;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * An immutable client record whose lifetime predicates read the applicable clocks.
 *
 * <p>Derives every field of an authoritative {@link MarkerSnapshot} and adds
 * client-local synchronization/display bookkeeping:
 * <ul>
 *   <li>{@link #receivedAtLocalTick()} — the client tick on which the snapshot
 *       was applied to the local store;</li>
 *   <li>{@link #fallbackExpiresAtLocalTick()} and {@link #displayExpiresAtLocalTick()}
 *       — the deadlines for a custom local duration (unused in follow-server mode);</li>
 *   <li>{@link #followServerTiming()} — the follow-server timing instead of
 *       local deadlines, or {@code null} for a custom local duration; and</li>
 *   <li>{@link #state()} — whether authoritative synchronization is still
 *       current or stale.</li>
 * </ul>
 *
 * <p>A custom visual and its synchronization fallback use client ticks. A
 * follow-server visual uses observed server progress; its fallback uses
 * observed progress followed by local grace.
 */
@NotNullByDefault
public record ClientMarker(
	MarkerId id,
	UUID owner,
	Target target,
	TargetKey targetKey,
	String targetTypeId,
	String pingTypeId,
	MarkerAnchor anchor,
	long arrivalTick,
	long expiresAtTick,
	long receivedAtLocalTick,
	long fallbackExpiresAtLocalTick,
	long displayExpiresAtLocalTick,
	ClientMarkerState state,
	@Nullable FollowServerTiming followServerTiming
) {
	/** The visual origin and span are frozen; each upsert starts a new synchronization fallback. */
	@NotNullByDefault
	public record FollowServerTiming(
		long durationTicks,
		ObservedServerClock.Origin visualOrigin,
		ObservedServerClock.Origin fallbackOrigin,
		long graceEndsAtLocalTick
	) {
		private static final long GRACE_NOT_STARTED = -1L;

		public FollowServerTiming {
			Objects.requireNonNull(visualOrigin, "visualOrigin");
			Objects.requireNonNull(fallbackOrigin, "fallbackOrigin");
			if (durationTicks <= 0L || graceEndsAtLocalTick < GRACE_NOT_STARTED) {
				throw new IllegalArgumentException("invalid follow-server timing");
			}
		}
	}

	public ClientMarker {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(target, "target");
		TargetKey.requireCompatible(target, targetKey);
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
	 * Creates a marker with an independent visual deadline in client ticks.
	 *
	 * <p>The synchronization fallback counts the snapshot's server tick span
	 * plus grace as client ticks from receipt. Both deadlines saturate at
	 * {@link Long#MAX_VALUE} rather than overflowing.
	 *
	 * @param receivedAtLocalTick client tick on which the snapshot is applied
	 * @param graceTicks synchronization fallback grace in client ticks
	 * @param displayDurationTicks visual lifetime from receipt in client ticks
	 */
	public static ClientMarker withLocalDuration(
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

		return from(snapshot, receivedAtLocalTick, localFallbackDeadline(snapshot, receivedAtLocalTick, graceTicks),
			saturatedAdd(receivedAtLocalTick, displayDurationTicks), null);
	}

	/** Uses the same receipt origin for both lifetimes on the first insertion. */
	static ClientMarker followingServer(MarkerSnapshot snapshot, long localTick, ObservedServerClock.Origin receipt) {
		return from(snapshot, localTick, Long.MAX_VALUE, Long.MAX_VALUE, new FollowServerTiming(
			serverDurationTicks(snapshot), receipt, receipt, FollowServerTiming.GRACE_NOT_STARTED));
	}

	/** Refreshes payload and fallback, never the first insertion's visual deadline or time domain. */
	ClientMarker refreshed(MarkerSnapshot snapshot, long localTick, long graceTicks, ObservedServerClock clock) {
		if (followServerTiming == null) {
			return from(snapshot, localTick, localFallbackDeadline(snapshot, localTick, graceTicks),
				displayExpiresAtLocalTick, null);
		}
		return from(snapshot, localTick, Long.MAX_VALUE, Long.MAX_VALUE, new FollowServerTiming(
			followServerTiming.durationTicks(), followServerTiming.visualOrigin(), clock.receiptOrigin(),
			FollowServerTiming.GRACE_NOT_STARTED));
	}

	private static ClientMarker from(MarkerSnapshot snapshot, long localTick, long fallbackDeadline,
		long visualDeadline, @Nullable FollowServerTiming followServerTiming) {
		return new ClientMarker(
			snapshot.id(),
			snapshot.owner(),
			snapshot.target(),
			snapshot.targetKey(),
			snapshot.targetTypeId(),
			snapshot.pingTypeId(),
			snapshot.anchor(),
			snapshot.arrivalTick(),
			snapshot.expiresAtTick(),
			localTick,
			fallbackDeadline,
			visualDeadline,
			ClientMarkerState.SYNCHRONIZED,
			followServerTiming);
	}

	private static long localFallbackDeadline(MarkerSnapshot snapshot, long localTick, long graceTicks) {
		return saturatedAdd(localTick, saturatedAdd(serverDurationTicks(snapshot), graceTicks));
	}

	/**
	 * The frozen server-side lifetime represented by a marker snapshot.  The
	 * minimum is defensive for callers handling a corrupt-but-constructed value.
	 */
	private static long serverDurationTicks(MarkerSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		return Math.max(1L, snapshot.expiresAtTick() - snapshot.arrivalTick());
	}

	/** Returns whether expiry or local loss recovery marked this record stale. */
	public boolean isStale() {
		return state == ClientMarkerState.STALE;
	}

	/** Returns whether the record still represents synchronized server state. */
	public boolean isSynchronized() {
		return state == ClientMarkerState.SYNCHRONIZED;
	}

	/**
	 * Tests the visual deadline using the supplied client tick for custom
	 * durations or observed server progress in follow-server mode. The record
	 * can remain synchronized after the visual deadline so late packets can be
	 * processed without resurrecting the visual.
	 */
	public boolean isVisuallyActiveAt(long localTick) {
		if (localTick < 0L) {
			throw new IllegalArgumentException("localTick must be non-negative: " + localTick);
		}

		return followServerTiming == null ? localTick < displayExpiresAtLocalTick
			: followServerTiming.visualOrigin().elapsedTicks() < followServerTiming.durationTicks();
	}

	public boolean followsServer() {
		return followServerTiming != null;
	}

	/** Starts local grace when packet evidence first reaches the synchronization span. */
	ClientMarker observeFallbackGrace(long localTick, long graceTicks) {
		if (followServerTiming == null || !isSynchronized()
			|| followServerTiming.graceEndsAtLocalTick() != FollowServerTiming.GRACE_NOT_STARTED
			|| followServerTiming.fallbackOrigin().elapsedTicks() < followServerTiming.durationTicks()) return this;

		return copyWith(state, new FollowServerTiming(followServerTiming.durationTicks(),
			followServerTiming.visualOrigin(), followServerTiming.fallbackOrigin(), saturatedAdd(localTick, graceTicks)));
	}

	boolean isFallbackDueAt(long localTick) {
		if (followServerTiming == null) return fallbackExpiresAtLocalTick <= localTick;
		long graceEnd = followServerTiming.graceEndsAtLocalTick();
		return graceEnd != FollowServerTiming.GRACE_NOT_STARTED && graceEnd <= localTick;
	}

	/** Returns a copy whose synchronization state is stale. */
	public ClientMarker asStale() {
		return isStale() ? this : copyWith(ClientMarkerState.STALE, followServerTiming);
	}

	private ClientMarker copyWith(ClientMarkerState nextState, @Nullable FollowServerTiming nextTiming) {
		return new ClientMarker(
			id,
			owner,
			target,
			targetKey,
			targetTypeId,
			pingTypeId,
			anchor,
			arrivalTick,
			expiresAtTick,

			receivedAtLocalTick,
			fallbackExpiresAtLocalTick,
			displayExpiresAtLocalTick,
			nextState,
			nextTiming);
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
