package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.EntityCaptureMetadata;
import nx.pingwheel.common.domain.EntityLocalGeometryMetadata;
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
public record TargetSnapshot(
	Target target,
	TargetMatchContext matchContext,
	java.util.Optional<EntityCaptureMetadata> entityCaptureMetadata,
	java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata
) {

	public TargetSnapshot(Target target, TargetMatchContext matchContext) {
		this(target, matchContext, java.util.Optional.empty(), java.util.Optional.empty());
	}

	/** Compatibility constructor retained for existing capture metadata callers. */
	public TargetSnapshot(
		Target target,
		TargetMatchContext matchContext,
		java.util.Optional<EntityCaptureMetadata> entityCaptureMetadata
	) {
		this(target, matchContext, entityCaptureMetadata, java.util.Optional.empty());
	}

	public TargetSnapshot {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(matchContext, "matchContext");
		Objects.requireNonNull(entityCaptureMetadata, "entityCaptureMetadata");
		Objects.requireNonNull(entityLocalGeometryMetadata, "entityLocalGeometryMetadata");

		if (entityLocalGeometryMetadata.isPresent() && !(target instanceof Target.EntityTarget)) {
			throw new IllegalArgumentException("only an entity target can retain local geometry metadata");
		}
	}
}
