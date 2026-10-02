package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;

/** Pure allocation. Unordered/truncated scans are never promoted to certified nearest results. */
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
			Candidate selected = null;
			if (type.matches(ordinary.resolvedTarget()) && !consumed.contains(ordinary.equivalenceKey())) {
				selected = ordinary;
			} else if (type == PreciseTargetType.LOCATION) {
				selected = location;
			} else if (certifiedTypes.contains(type)) {
				for (Candidate candidate : supplements) {
					if (type.matches(candidate.resolvedTarget()) && !consumed.contains(candidate.equivalenceKey())
						&& (selected == null || candidate.distance() < selected.distance())) {
						selected = candidate;
					}
				}
			}
			if (selected == null) {
				slots.add(new PreciseSlot(type, Optional.empty(), certifiedTypes.contains(type)
					? PreciseSlot.Availability.MISSING : PreciseSlot.Availability.INCOMPLETE));
			} else {
				consumed.add(selected.equivalenceKey());
				if (selected != ordinary) retained.add(selected);
				slots.add(new PreciseSlot(type, Optional.of(selected.candidateId()), PreciseSlot.Availability.AVAILABLE));
			}
		}
		return new FrozenCandidateSet(ordinary, retained, slots);
	}
}
