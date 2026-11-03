package nx.pingwheel.common.client.outline;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validation shared by immutable presentation and resolver results. */
final class BlockPresentationCoverageRelations {

    private BlockPresentationCoverageRelations() {}

    static List<BlockPresentationCoverageRelation> immutableAndValidated(
        List<BlockRenderSubject> subjects,
        List<BlockPresentationCoverageRelation> coverageRelations
    ) {
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(coverageRelations, "coverageRelations");

        List<BlockRenderSubject> immutableSubjects = List.copyOf(subjects);
        List<BlockPresentationCoverageRelation> immutableRelations = List.copyOf(coverageRelations);
        Map<String, Integer> subjectIndexes = new HashMap<>();
        for (int index = 0; index < immutableSubjects.size(); index++) {
            BlockRenderSubject subject = immutableSubjects.get(index);
            Integer previous = subjectIndexes.putIfAbsent(subject.subjectId(), index);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "presentation subject ids must be unambiguous: " + subject.subjectId());
            }
        }

		Set<CoverageEdge> seenRelations = new HashSet<>();
		for (BlockPresentationCoverageRelation relation : immutableRelations) {
            Integer ownerIndex = subjectIndexes.get(relation.ownerSubjectId());
            Integer coveredIndex = subjectIndexes.get(relation.coveredSubjectId());
            if (ownerIndex == null || coveredIndex == null) {
                throw new IllegalArgumentException(
                    "coverage relation subjects must belong to this presentation");
            }

            if (ownerIndex.equals(coveredIndex)) {
                throw new IllegalArgumentException("coverage relation cannot cover its owner");
            }

			if (ownerIndex >= coveredIndex) {
                throw new IllegalArgumentException(
                    "coverage relation owner must precede its covered subject");
            }

			if (!seenRelations.add(new CoverageEdge(
				relation.ownerSubjectId(), relation.coveredSubjectId()))) {
				throw new IllegalArgumentException("duplicate presentation coverage relation");
            }
        }

		return immutableRelations;
	}

	private record CoverageEdge(String ownerSubjectId, String coveredSubjectId) {}
}
