package nx.pingwheel.common.client.outline;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Per-presentation, per-frame state for source-conditioned subject coverage.
 *
 * <p>This object retains only immutable subject/source identifiers. It is
 * deliberately created only for presentations with explicit coverage
 * declarations and never derives one relation from another.</p>
 */
final class BlockPresentationCoverageTracker {

	private final Map<OwnerSource, List<String>> coveredSubjectsByOwnerSource;
	private final Set<String> ownerSubjectIds;
	private final Set<String> coveredSubjectIds = new LinkedHashSet<>();

	private BlockPresentationCoverageTracker(
		List<BlockPresentationCoverageRelation> coverageRelations
	) {
		Map<OwnerSource, List<String>> mutable = new LinkedHashMap<>();
		for (BlockPresentationCoverageRelation relation : coverageRelations) {
			OwnerSource ownerSource = new OwnerSource(
				relation.ownerSubjectId(), relation.requiredRenderedSourceId());
			List<String> coveredSubjects = mutable.computeIfAbsent(
				ownerSource, ignored -> new java.util.ArrayList<>());
			coveredSubjects.add(relation.coveredSubjectId());
		}
		Map<OwnerSource, List<String>> immutable = new LinkedHashMap<>();
		for (Map.Entry<OwnerSource, List<String>> entry : mutable.entrySet()) {
			immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		coveredSubjectsByOwnerSource = Map.copyOf(immutable);
		ownerSubjectIds = Set.copyOf(coveredSubjectsByOwnerSource.keySet().stream()
			.map(OwnerSource::subjectId)
			.collect(java.util.stream.Collectors.toSet()));
	}

	/** Returns null when no tracking work is needed for this presentation. */
	static BlockPresentationCoverageTracker forPresentation(BlockPresentation presentation) {
		Objects.requireNonNull(presentation, "presentation");
		return presentation.coverageRelations().isEmpty()
			? null
			: new BlockPresentationCoverageTracker(presentation.coverageRelations());
	}

	/**
	 * Returns a per-owner source observer, or {@code null} when this owner has
	 * no declared relation. Callers can pass the result directly to the runner
	 * only when the source route is entity-block geometry.
	 */
	BiConsumer<String, EntityBlockGeometryOutcome> outcomeObserverIfRequired(String subjectId) {
		return requiresOutcomeFor(subjectId) ? outcomeObserverFor(subjectId) : null;
	}

	boolean requiresOutcomeFor(String subjectId) {
		Objects.requireNonNull(subjectId, "subjectId");
		return ownerSubjectIds.contains(subjectId);
	}

	boolean covers(String subjectId) {
		return coveredSubjectIds.contains(subjectId);
	}

	/** Records exactly one final source outcome without aggregate or transitive inference. */
	void recordOutcome(
		String ownerSubjectId,
		String sourceId,
		EntityBlockGeometryOutcome outcome
	) {
		Objects.requireNonNull(ownerSubjectId, "ownerSubjectId");
		Objects.requireNonNull(sourceId, "sourceId");
		Objects.requireNonNull(outcome, "outcome");

		if (outcome != EntityBlockGeometryOutcome.RENDERED) {
			return;
		}

		List<String> newlyCovered = coveredSubjectsByOwnerSource.get(
			new OwnerSource(ownerSubjectId, sourceId));
		if (newlyCovered != null) {
			coveredSubjectIds.addAll(newlyCovered);
		}
	}

	BiConsumer<String, EntityBlockGeometryOutcome> outcomeObserverFor(String ownerSubjectId) {
		Objects.requireNonNull(ownerSubjectId, "ownerSubjectId");
		return (sourceId, outcome) -> recordOutcome(ownerSubjectId, sourceId, outcome);
	}

	private record OwnerSource(String subjectId, String sourceId) {}
}
