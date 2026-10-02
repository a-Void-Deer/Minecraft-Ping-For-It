package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;
import java.util.Optional;

public record PreciseSlot(PreciseTargetType type, Optional<Integer> candidateId, Availability availability) {
	public enum Availability { AVAILABLE, MISSING, INCOMPLETE }
	public PreciseSlot {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(candidateId, "candidateId");
		Objects.requireNonNull(availability, "availability");
		if (candidateId.isPresent() != (availability == Availability.AVAILABLE)) {
			throw new IllegalArgumentException("slot availability must agree with candidate membership");
		}
	}
}
