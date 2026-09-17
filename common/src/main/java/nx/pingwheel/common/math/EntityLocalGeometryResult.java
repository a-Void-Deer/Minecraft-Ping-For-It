package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;

/**
 * The recoverable outcome of an owned entity narrowphase attempt.
 */
public record EntityLocalGeometryResult(Outcome outcome, Optional<LocalGeometryHit> localHit) {

	public EntityLocalGeometryResult {
		Objects.requireNonNull(outcome, "outcome");
		Objects.requireNonNull(localHit, "localHit");

		if ((outcome == Outcome.HIT) != localHit.isPresent()) {
			throw new IllegalArgumentException("HIT must carry one local hit and every other outcome must not");
		}
	}

	public static EntityLocalGeometryResult hit(LocalGeometryHit localHit) {
		return new EntityLocalGeometryResult(Outcome.HIT, Optional.of(Objects.requireNonNull(localHit, "localHit")));
	}

	public static EntityLocalGeometryResult miss() {
		return withoutHit(Outcome.MISS);
	}

	public static EntityLocalGeometryResult unavailable() {
		return withoutHit(Outcome.UNAVAILABLE);
	}

	public static EntityLocalGeometryResult failed() {
		return withoutHit(Outcome.FAILED);
	}

	private static EntityLocalGeometryResult withoutHit(Outcome outcome) {
		return new EntityLocalGeometryResult(outcome, Optional.empty());
	}

	public enum Outcome {
		HIT,
		MISS,
		UNAVAILABLE,
		FAILED
	}
}
