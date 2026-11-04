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
		return available(stableId, locator, anchor, true);
	}

	boolean available(String stableId, String locator, MarkerAnchor anchor, boolean reportEnabled) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(locator, "locator");
		Objects.requireNonNull(anchor, "anchor");

		@Nullable Observation previous = observations.get(stableId);

		// The first AVAILABLE observation establishes the baseline; only a later
		// transition or observable change is worth emitting.
		boolean pending = previous != null && (previous.status() != Status.AVAILABLE
			|| !locator.equals(previous.locator()) || !anchor.equals(previous.anchor()) || !previous.statusReported());
		observations.put(stableId, new Observation(Status.AVAILABLE, "available", locator, anchor,
			false, !pending || reportEnabled));
		return reportEnabled && pending;
	}

	boolean temporarilyUnavailable(String stableId, String reason) {
		return temporarilyUnavailable(stableId, reason, true);
	}

	boolean temporarilyUnavailable(String stableId, String reason, boolean reportEnabled) {
		return transition(stableId, Status.TEMPORARILY_UNAVAILABLE, reason, reportEnabled);
	}

	boolean invalid(String stableId, String reason) {
		return invalid(stableId, reason, true);
	}

	boolean invalid(String stableId, String reason, boolean reportEnabled) {
		return transition(stableId, Status.INVALID, reason, reportEnabled);
	}

	boolean invalidFailure(String stableId, String reason, boolean reportEnabled) {
		@Nullable Observation previous = observations.get(stableId);
		boolean changed = previous == null || previous.status() != Status.INVALID || !reason.equals(previous.reason());
		boolean emit = changed || previous == null || !previous.failureReported();
		boolean reported = reportEnabled && emit || previous != null && !changed && previous.failureReported();
		boolean statusReported = reported || previous != null && !changed && previous.statusReported();
		observations.put(stableId, new Observation(Status.INVALID, reason, null, null, reported, statusReported));
		return reportEnabled && emit;
	}

	void remove(String stableId) {
		if (stableId != null) {
			observations.remove(stableId);
		}
	}

	private boolean transition(
		String stableId, Status status, String reason, boolean reportEnabled
	) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(reason, "reason");

		@Nullable Observation previous = observations.get(stableId);
		boolean changed = previous == null || previous.status() != status || !reason.equals(previous.reason());
		boolean pending = changed || previous != null && !previous.statusReported();
		boolean failureReported = previous != null && !changed && previous.failureReported();
		observations.put(stableId, new Observation(status, reason, null, null, failureReported,
			reportEnabled && pending || previous != null && !changed && previous.statusReported()));
		return reportEnabled && pending;
	}

	private enum Status {
		AVAILABLE,
		TEMPORARILY_UNAVAILABLE,
		INVALID
	}

	private record Observation(
		Status status, String reason, @Nullable String locator, @Nullable MarkerAnchor anchor,
		boolean failureReported, boolean statusReported
	) {}
}
