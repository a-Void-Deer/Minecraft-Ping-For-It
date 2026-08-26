package nx.pingwheel.common.integration.sable.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import nx.pingwheel.common.marker.MarkerAnchor;

/**
 * Keeps fixed-cadence external refreshes quiet when nothing observable has
 * changed.  It records state transitions only; the provider owns the actual
 * DEBUG emission and any throwable attached to it.
 */
final class SableRefreshLogGate {

	private final Map<String, Observation> observations = new LinkedHashMap<>();

	boolean available(String stableId, String locator, MarkerAnchor anchor) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(locator, "locator");
		Objects.requireNonNull(anchor, "anchor");

		Observation previous = observations.put(
			stableId, new Observation(Status.AVAILABLE, "available", locator, anchor));

		return previous != null && (previous.status() != Status.AVAILABLE
			|| !locator.equals(previous.locator()) || !anchor.equals(previous.anchor()));
	}

	boolean temporarilyUnavailable(String stableId, String reason) {
		return transition(stableId, Status.TEMPORARILY_UNAVAILABLE, reason, null, null);
	}

	boolean invalid(String stableId, String reason) {
		return transition(stableId, Status.INVALID, reason, null, null);
	}

	void remove(String stableId) {
		if (stableId != null) {
			observations.remove(stableId);
		}
	}

	private boolean transition(
		String stableId, Status status, String reason, String locator, MarkerAnchor anchor
	) {
		Objects.requireNonNull(stableId, "stableId");
		Objects.requireNonNull(reason, "reason");

		Observation previous = observations.put(
			stableId, new Observation(status, reason, locator, anchor));

		return previous == null || previous.status() != status || !reason.equals(previous.reason());
	}

	private enum Status {
		AVAILABLE,
		TEMPORARILY_UNAVAILABLE,
		INVALID
	}

	private record Observation(
		Status status, String reason, String locator, MarkerAnchor anchor
	) {
	}
}
