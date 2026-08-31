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
	List<BlockRenderSubject> renderSubjects
) {

	public BlockPresentation {
		Objects.requireNonNull(sourceSpec, "sourceSpec");
		Objects.requireNonNull(renderSubjects, "renderSubjects");
		renderSubjects = List.copyOf(renderSubjects);
}

	/** Alias for the source name used by outline callers. */
	public BlockOutlineSpec source() {
		return sourceSpec;
	}

	public boolean isEmpty() {
		return renderSubjects.isEmpty();
	}
}
