package nx.pingwheel.common.domain;

import java.util.Objects;

/**
 * The frozen result of capturing a {@link Target} and selecting its winning
 * {@link TargetType}. Produced once at key-down and held for the whole
 * interaction.
 */
public record ResolvedTarget(Target target, TargetType targetType) {

	public ResolvedTarget {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(targetType, "targetType");
	}
}
