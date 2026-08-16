package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetMatchContext;

/**
 * An immutable pair of a captured {@link Target} and the transient
 * {@link TargetMatchContext} needed to resolve it.
 *
 * <p>The snapshot is the raw, unresolved input to
 * {@link PingCaptureCoordinator#complete(InteractionToken, TargetSnapshot)};
 * the resolved {@link nx.pingwheel.common.domain.ResolvedTarget} and the
 * press-time ray supplied to the coordinator are frozen into a
 * {@link CapturedPingContext}. The match context never participates in target
 * identity and is never serialized.
 */
public record TargetSnapshot(Target target, TargetMatchContext matchContext) {

	public TargetSnapshot {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(matchContext, "matchContext");
	}
}
