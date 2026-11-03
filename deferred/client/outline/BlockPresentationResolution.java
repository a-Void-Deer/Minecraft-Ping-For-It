package nx.pingwheel.common.client.outline;

import java.util.List;
import java.util.Objects;

/**
 * Result of one resolver attempt.
 *
 * <p>{@link #UNHANDLED} is distinct from a handled result with no subjects.
 * The latter is an intentional presentation decision and must not fall back
 * to direct rendering.</p>
 */
public final class BlockPresentationResolution {
	public static final BlockPresentationResolution UNHANDLED =
		new BlockPresentationResolution(false, List.of(), List.of());

	private final boolean handled;
	private final List<BlockRenderSubject> subjects;
	private final List<BlockPresentationCoverageRelation> coverageRelations;

	private BlockPresentationResolution(
		boolean handled,
		List<BlockRenderSubject> subjects,
		List<BlockPresentationCoverageRelation> coverageRelations
	) {
		this.handled = handled;
		this.subjects = List.copyOf(subjects);
		this.coverageRelations = BlockPresentationCoverageRelations.immutableAndValidated(
			this.subjects, coverageRelations);
		if (!handled && !this.coverageRelations.isEmpty()) {
			throw new IllegalArgumentException("unhandled resolutions cannot declare coverage");
		}
	}

	public static BlockPresentationResolution handled(List<BlockRenderSubject> subjects) {
		return handled(subjects, List.of());
	}

	public static BlockPresentationResolution handled(
		List<BlockRenderSubject> subjects,
		List<BlockPresentationCoverageRelation> coverageRelations
	) {
		return new BlockPresentationResolution(
			true,
			Objects.requireNonNull(subjects, "subjects"),
			Objects.requireNonNull(coverageRelations, "coverageRelations"));
	}

	public static BlockPresentationResolution handled(BlockRenderSubject... subjects) {
		Objects.requireNonNull(subjects, "subjects");
		return handled(List.of(subjects));
	}

	public static BlockPresentationResolution unhandled() {
		return UNHANDLED;
	}

	public boolean handled() {
		return handled;
	}

	public boolean isHandled() {
		return handled;
	}

	public boolean isUnhandled() {
		return !handled;
	}

	public List<BlockRenderSubject> subjects() {
		return subjects;
	}

	/** Source-conditioned coverage declarations for this handled result. */
	public List<BlockPresentationCoverageRelation> coverageRelations() {
		return coverageRelations;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (!(object instanceof BlockPresentationResolution other)) {
			return false;
		}

		return handled == other.handled
			&& subjects.equals(other.subjects)
			&& coverageRelations.equals(other.coverageRelations);
	}

	@Override
	public int hashCode() {
		return Objects.hash(handled, subjects, coverageRelations);
	}

	@Override
	public String toString() {
		return "BlockPresentationResolution[handled=%s, subjects=%s, coverageRelations=%s]"
			.formatted(handled, subjects, coverageRelations);
	}
}
