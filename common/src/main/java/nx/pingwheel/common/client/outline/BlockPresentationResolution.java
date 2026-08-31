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
		new BlockPresentationResolution(false, List.of());

	private final boolean handled;
	private final List<BlockRenderSubject> subjects;

	private BlockPresentationResolution(boolean handled, List<BlockRenderSubject> subjects) {
		this.handled = handled;
		this.subjects = List.copyOf(subjects);
	}

	public static BlockPresentationResolution handled(List<BlockRenderSubject> subjects) {
		return new BlockPresentationResolution(true, Objects.requireNonNull(subjects, "subjects"));
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

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (!(object instanceof BlockPresentationResolution other)) {
			return false;
		}

		return handled == other.handled && subjects.equals(other.subjects);
	}

	@Override
	public int hashCode() {
		return Objects.hash(handled, subjects);
	}

	@Override
	public String toString() {
		return "BlockPresentationResolution[handled=%s, subjects=%s]".formatted(handled, subjects);
	}
}
