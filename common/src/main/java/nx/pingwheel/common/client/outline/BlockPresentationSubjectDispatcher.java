package nx.pingwheel.common.client.outline;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Dispatches the ordinary subjects of one resolved presentation for one frame.
 *
 * <p>This small render-loop seam owns the source-conditioned coverage decision
 * so it can be exercised without a client world. The caller owns live-state
 * validation, geometry routing, and exact fallback-success recording.</p>
 */
final class BlockPresentationSubjectDispatcher {

	@FunctionalInterface
	interface SubjectGeometryRenderer {
		boolean render(
			BlockRenderSubject subject,
			BiConsumer<String, EntityBlockGeometryOutcome> outcomeObserver
		);
	}

	private BlockPresentationSubjectDispatcher() {}

	static void dispatch(
		BlockPresentation presentation,
		Predicate<BlockRenderSubject> isLiveAndCurrent,
		Consumer<BlockRenderSubject> recordSuccess,
		SubjectGeometryRenderer geometryRenderer
	) {
		Objects.requireNonNull(presentation, "presentation");
		Objects.requireNonNull(isLiveAndCurrent, "isLiveAndCurrent");
		Objects.requireNonNull(recordSuccess, "recordSuccess");
		Objects.requireNonNull(geometryRenderer, "geometryRenderer");

		BlockPresentationCoverageTracker coverageTracker =
			BlockPresentationCoverageTracker.forPresentation(presentation);
		for (BlockRenderSubject subject : presentation.renderSubjects()) {
			if (!isLiveAndCurrent.test(subject)) {
				continue;
			}

			if (coverageTracker != null && coverageTracker.covers(subject.subjectId())) {
				recordSuccess.accept(subject);
				continue;
			}

			BiConsumer<String, EntityBlockGeometryOutcome> outcomeObserver = coverageTracker == null
				? null
				: coverageTracker.outcomeObserverIfRequired(subject.subjectId());
			if (geometryRenderer.render(subject, outcomeObserver)) {
				recordSuccess.accept(subject);
			}
		}
	}
}
