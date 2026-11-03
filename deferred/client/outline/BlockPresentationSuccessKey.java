package nx.pingwheel.common.client.outline;

import java.util.Objects;

import nx.pingwheel.common.marker.TargetKey;

/**
 * Stable per-frame success identity for one rendered presentation subject.
 *
 * <p>The source block key remains the marker identity. The subject identity is
 * deliberately separate so composite presentations can report success for
 * each half without suppressing another half.</p>
 */
public record BlockPresentationSuccessKey(
	TargetKey.BlockKey sourceKey,
	String subjectId
) {

	public BlockPresentationSuccessKey {
		Objects.requireNonNull(sourceKey, "sourceKey");
		Objects.requireNonNull(subjectId, "subjectId");

		if (subjectId.isBlank()) {
			throw new IllegalArgumentException("subjectId must not be blank");
		}
	}
}
