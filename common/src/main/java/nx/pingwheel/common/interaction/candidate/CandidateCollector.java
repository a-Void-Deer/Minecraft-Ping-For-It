package nx.pingwheel.common.interaction.candidate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.IdentityHashMap;

/** Bounded nearest-two reduction per concrete class; generic slots can skip one consumed identity. */
public final class CandidateCollector {
	private final EnumMap<PreciseTargetType, List<CandidateEvidence>> nearest = new EnumMap<>(PreciseTargetType.class);
	private final IdentityHashMap<CandidateEvidence, Long> encounterOrder = new IdentityHashMap<>();
	private long sequence;

	public CandidateCollector() {
		for (PreciseTargetType type : PreciseTargetType.values()) {
			if (type != PreciseTargetType.LOCATION) nearest.put(type, new ArrayList<>(2));
		}
	}

	public void add(CandidateEvidence evidence) {
		encounterOrder.putIfAbsent(evidence, sequence++);
		for (var entry : nearest.entrySet()) {
			if (!entry.getKey().matches(evidence.snapshot())) continue;
			List<CandidateEvidence> values = entry.getValue();
			int same = -1;
			for (int i = 0; i < values.size(); i++) {
				if (values.get(i).hit().equivalenceKey().equals(evidence.hit().equivalenceKey())) same = i;
			}
			if (same >= 0) {
				if (values.get(same).distance() <= evidence.distance()) continue;
				values.remove(same);
			}
			int position = 0;
			while (position < values.size() && values.get(position).distance() <= evidence.distance()) position++;
			values.add(position, evidence);
			if (values.size() > 2) values.remove(2);
		}
		encounterOrder.keySet().removeIf(value -> nearest.values().stream()
			.noneMatch(values -> values.stream().anyMatch(retained -> retained == value)));
	}

	public List<CandidateEvidence> evidence() {
		List<CandidateEvidence> result = new ArrayList<>();
		for (List<CandidateEvidence> values : nearest.values()) {
			for (CandidateEvidence value : values) if (!result.contains(value)) result.add(value);
		}
		result.sort(java.util.Comparator.comparingDouble(CandidateEvidence::distance)
			.thenComparingLong(encounterOrder::get));
		return List.copyOf(result);
	}
}
