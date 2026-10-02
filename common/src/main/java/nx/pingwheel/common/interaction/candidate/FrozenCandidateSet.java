package nx.pingwheel.common.interaction.candidate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ordinary plus at most four concrete supplements and one derived location. No live game objects. */
public record FrozenCandidateSet(Candidate ordinary, List<Candidate> candidates, List<PreciseSlot> preciseSlots) {
	public static final int MAX_CANDIDATES = 6;

	public FrozenCandidateSet {
		Objects.requireNonNull(ordinary, "ordinary");
		candidates = List.copyOf(candidates);
		preciseSlots = List.copyOf(preciseSlots);
		if (candidates.size() > MAX_CANDIDATES || !candidates.contains(ordinary)
			|| preciseSlots.size() != PreciseTargetType.values().length) {
			throw new IllegalArgumentException("invalid frozen candidate membership");
		}
		var ids = new HashSet<Integer>();
		var keys = new HashSet<CaptureEquivalenceKey>();
		for (Candidate candidate : candidates) {
			if (!ids.add(candidate.candidateId()) || !keys.add(candidate.equivalenceKey())) {
				throw new IllegalArgumentException("duplicate frozen candidate identity");
			}
		}
		var consumed = new HashSet<Integer>();
		for (int i = 0; i < preciseSlots.size(); i++) {
			PreciseSlot slot = preciseSlots.get(i);
			if (slot.type() != PreciseTargetType.values()[i]) {
				throw new IllegalArgumentException("precise slots must follow catalog priority");
			}
			if (slot.candidateId().isPresent()) {
				int id = slot.candidateId().orElseThrow();
				Candidate candidate = candidates.stream().filter(value -> value.candidateId() == id)
					.findFirst().orElseThrow(() -> new IllegalArgumentException("unknown slot candidate"));
				if (!consumed.add(id) || !slot.type().matches(candidate.resolvedTarget())) {
					throw new IllegalArgumentException("consumed or ineligible slot candidate");
				}
			}
		}
	}

	public Optional<Candidate> candidate(int candidateId) {
		return candidates.stream().filter(value -> value.candidateId() == candidateId).findFirst();
	}

	public PreciseSlot slot(PreciseTargetType type) { return preciseSlots.get(type.ordinal()); }
}
