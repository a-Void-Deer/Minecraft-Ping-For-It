package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;

/**
 * Client-render-only presentation of one captured block outline.
 *
 * <p>The source specification is retained unchanged. Its render subjects may
 * be empty, singular, or composite.</p>
 */
public record BlockPresentation(
	BlockOutlineSpec sourceSpec,
	List<BlockRenderSubject> renderSubjects,
	List<BlockPresentationCoverageRelation> coverageRelations
) {

	public BlockPresentation {
		Objects.requireNonNull(sourceSpec, "sourceSpec");
		Objects.requireNonNull(renderSubjects, "renderSubjects");
		renderSubjects = List.copyOf(renderSubjects);
		coverageRelations = BlockPresentationCoverageRelations.immutableAndValidated(
			renderSubjects, coverageRelations);
	}

	/** Creates a presentation without source-conditioned coverage declarations. */
	public BlockPresentation(BlockOutlineSpec sourceSpec, List<BlockRenderSubject> renderSubjects) {
		this(sourceSpec, renderSubjects, List.of());
	}

	/** Alias for the source name used by outline callers. */
	public BlockOutlineSpec source() {
		return sourceSpec;
	}

	public boolean isEmpty() {
		return renderSubjects.isEmpty();
	}
}
