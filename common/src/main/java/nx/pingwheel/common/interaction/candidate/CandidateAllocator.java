package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;

/**
 * Pure allocation. A scanned class is unavailable unless its bounded traversal
 * completed, even when the ordinary candidate belongs to that class. Every
 * completed class installs the nearest eligible identity by the real world-hit
 * distance to the frozen ray origin; a more specific class consumes its
 * identity first and a generic class installs the next nearest. An exact
 * distance tie keeps the established stable order (the ordinary candidate,
 * then the supplied evidence order).
 */
public final class CandidateAllocator {
	private CandidateAllocator() {}

	public static FrozenCandidateSet allocate(Candidate ordinary, List<Candidate> supplements,
		Set<PreciseTargetType> certifiedTypes, Candidate location) {
		Objects.requireNonNull(ordinary, "ordinary");
		supplements = List.copyOf(supplements);
		certifiedTypes = Set.copyOf(certifiedTypes);
		Objects.requireNonNull(location, "location");
		if (!PreciseTargetType.LOCATION.matches(location.resolvedTarget())) {
			throw new IllegalArgumentException("location slot requires a location candidate");
		}
		List<Candidate> retained = new ArrayList<>();
		List<PreciseSlot> slots = new ArrayList<>();
		Set<CaptureEquivalenceKey> consumed = new HashSet<>();
		retained.add(ordinary);
		for (PreciseTargetType type : PreciseTargetType.values()) {
			Candidate selected;
			if (type == PreciseTargetType.LOCATION) {
				// The location class is derived, not scanned: it is always available and
				// prefers the ordinary candidate when the ordinary itself is a location.
				selected = type.matches(ordinary.resolvedTarget()) && !consumed.contains(ordinary.equivalenceKey())
					? ordinary : location;
			} else if (!certifiedTypes.contains(type)) {
				slots.add(new PreciseSlot(type, Optional.empty(), PreciseSlot.Availability.INCOMPLETE));
				continue;
			} else {
				selected = nearest(type, ordinary, supplements, consumed);
			}
			if (selected == null) {
				slots.add(new PreciseSlot(type, Optional.empty(), PreciseSlot.Availability.MISSING));
				continue;
			}
			consumed.add(selected.equivalenceKey());
			if (selected != ordinary) retained.add(selected);
			slots.add(new PreciseSlot(type, Optional.of(selected.candidateId()), PreciseSlot.Availability.AVAILABLE));
		}
		return new FrozenCandidateSet(ordinary, retained, slots);
	}

	/** Nearest eligible identity; the first eligible candidate wins an exact tie. */
	private static Candidate nearest(PreciseTargetType type, Candidate ordinary, List<Candidate> supplements,
		Set<CaptureEquivalenceKey> consumed) {
		Candidate selected = null;
		if (type.matches(ordinary.resolvedTarget()) && !consumed.contains(ordinary.equivalenceKey())) {
			selected = ordinary;
		}
		for (Candidate candidate : supplements) {
			if (!type.matches(candidate.resolvedTarget()) || consumed.contains(candidate.equivalenceKey())) continue;
			if (selected == null || candidate.distance() < selected.distance()) selected = candidate;
		}
		return selected;
	}
}
