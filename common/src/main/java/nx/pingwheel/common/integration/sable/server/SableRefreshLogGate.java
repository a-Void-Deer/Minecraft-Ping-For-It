package nx.pingwheel.common.integration.sable.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import nx.pingwheel.common.marker.MarkerAnchor;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps fixed-cadence external refreshes quiet when nothing observable has
 * changed. The provider owns emission; disabled reporting still observes
 * state without spending a WARN.
 */
final class SableRefreshLogGate {

	private final Map<String, Observation> observations = new LinkedHashMap<>();

	boolean available(String stableId, String locator, MarkerAnchor anchor) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(locator, "locator");
		Objects.requireNonNull(anchor, "anchor");

		@Nullable Observation previous = observations.put(
			stableId, new Observation(Status.AVAILABLE, "available", locator, anchor));

		// The first AVAILABLE observation establishes the baseline; only a later
		// transition or observable change is worth emitting.
		return previous != null && (previous.status() != Status.AVAILABLE
			|| !locator.equals(previous.locator()) || !anchor.equals(previous.anchor()));
	}

	boolean temporarilyUnavailable(String stableId, String reason) {
		return transition(stableId, Status.TEMPORARILY_UNAVAILABLE, reason, null, null);
	}

	boolean invalid(String stableId, String reason) {
		return transition(stableId, Status.INVALID, reason, null, null);
	}

	boolean invalidFailure(String stableId, String reason, boolean reportEnabled) {
		@Nullable Observation previous = observations.get(stableId);
		boolean changed = invalid(stableId, reason);
		boolean emit = changed || previous == null || !previous.failureReported();
		boolean reported = reportEnabled && emit || previous != null && !changed && previous.failureReported();
		observations.put(stableId, new Observation(Status.INVALID, reason, null, null, reported));
		return reportEnabled && emit;
	}

	void remove(String stableId) {
		if (stableId != null) {
			observations.remove(stableId);
		}
	}

	private boolean transition(
		String stableId, Status status, String reason, @Nullable String locator, @Nullable MarkerAnchor anchor
	) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(reason, "reason");

		@Nullable Observation previous = observations.get(stableId);
		boolean changed = previous == null || previous.status() != status || !reason.equals(previous.reason());
		if (changed) observations.put(stableId, new Observation(status, reason, locator, anchor));
		return changed;
	}

	private enum Status {
		AVAILABLE,
		TEMPORARILY_UNAVAILABLE,
		INVALID
	}

	private record Observation(
		Status status, String reason, @Nullable String locator, @Nullable MarkerAnchor anchor,
		boolean failureReported
	) {
		private Observation(Status status, String reason, @Nullable String locator, @Nullable MarkerAnchor anchor) {
			this(status, reason, locator, anchor, false);
		}
	}
}
