package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.BlockFace;
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
 *
 * <p>Capture-only metadata is meaningful only for its matching captured
 * identity: entity metadata belongs to an entity target and a hit face belongs
 * to an ordinary {@link Target.BlockTarget}. None of it participates in target
 * identity or marker identity. Inventory protocols carry faces as read context.
 */
public record TargetSnapshot(
	Target target,
	TargetMatchContext matchContext,
	java.util.Optional<EntityCaptureMetadata> entityCaptureMetadata,
	java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata,
	java.util.Optional<BlockFace> blockHitFace,
	java.util.Optional<nx.pingwheel.common.interaction.candidate.CandidateHit> candidateHit
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

	/**
	 * Compatibility constructor retained for callers that predate the frozen
	 * ordinary-block hit face.
	 */
	public TargetSnapshot(
		Target target,
		TargetMatchContext matchContext,
		java.util.Optional<EntityCaptureMetadata> entityCaptureMetadata,
		java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata
	) {
		this(target, matchContext, entityCaptureMetadata, entityLocalGeometryMetadata, java.util.Optional.empty());
	}

	/** Compatibility constructor retaining the completed face-capture API. */
	public TargetSnapshot(
		Target target,
		TargetMatchContext matchContext,
		java.util.Optional<EntityCaptureMetadata> entityCaptureMetadata,
		java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata,
		java.util.Optional<BlockFace> blockHitFace
	) {
		this(target, matchContext, entityCaptureMetadata, entityLocalGeometryMetadata, blockHitFace,
			java.util.Optional.empty());
	}

	/** Add detached surface evidence without changing identity, classification or face. */
	public TargetSnapshot withCandidateHit(nx.pingwheel.common.interaction.candidate.CandidateHit hit) {
		return new TargetSnapshot(target, matchContext, entityCaptureMetadata, entityLocalGeometryMetadata,
			blockHitFace, java.util.Optional.of(hit));
	}

	public TargetSnapshot {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(matchContext, "matchContext");
		Objects.requireNonNull(entityCaptureMetadata, "entityCaptureMetadata");
		Objects.requireNonNull(entityLocalGeometryMetadata, "entityLocalGeometryMetadata");
		Objects.requireNonNull(blockHitFace, "blockHitFace");
		Objects.requireNonNull(candidateHit, "candidateHit");
		if (candidateHit.isPresent() && !candidateHit.orElseThrow().equivalenceKey().matches(target)) {
			throw new IllegalArgumentException("surface evidence belongs to a different target");
		}

		if (entityLocalGeometryMetadata.isPresent() && !(target instanceof Target.EntityTarget)) {
			throw new IllegalArgumentException("only an entity target can retain local geometry metadata");
		}

		if (blockHitFace.isPresent() && !(target instanceof Target.BlockTarget)) {
			throw new IllegalArgumentException("only an ordinary block target can retain a hit face");
		}
	}
}
