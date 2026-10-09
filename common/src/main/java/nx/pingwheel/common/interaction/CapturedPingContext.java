package nx.pingwheel.common.interaction;

import java.util.Objects;

import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.EntityLocalGeometryMetadata;

/**
 * The frozen outcome of one interaction: the {@link InteractionToken} that owns
 * it, the {@link ResolvedTarget} resolved once at capture time, and the exact
 * press-time ray used for capture.
 *
 * <p>This value is deliberately minimal: it carries no hold timing, wheel
 * state, cancellation, or error data. Those concerns belong to the phase-5
 * interaction state machine. All fields are validated non-null and are
 * effectively immutable (the token is identity-compared and the resolved target
 * and ray are immutable records).
 *
 * <p>Capture-only metadata stays attached to its matching identity: entity-local
 * geometry detail is retained only for an entity target, and a native block
 * hit face only for an ordinary or external block's matching read binding.
 */
public record CapturedPingContext(
	InteractionToken token,
	ResolvedTarget resolvedTarget,
	CapturedRay ray,
	java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata,
	java.util.Optional<BlockFace> blockHitFace,
	java.util.Optional<nx.pingwheel.common.interaction.candidate.FrozenCandidateSet> selectorCandidates
) {

	public CapturedPingContext {
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(resolvedTarget, "resolvedTarget");
		Objects.requireNonNull(ray, "ray");
		Objects.requireNonNull(entityLocalGeometryMetadata, "entityLocalGeometryMetadata");
		Objects.requireNonNull(blockHitFace, "blockHitFace");
		Objects.requireNonNull(selectorCandidates, "selectorCandidates");
		if (selectorCandidates.isPresent()
			&& !selectorCandidates.orElseThrow().ordinary().resolvedTarget().equals(resolvedTarget)) {
			throw new IllegalArgumentException("selector ordinary target must be the accepted capture");
		}

		if (entityLocalGeometryMetadata.isPresent()
			&& !(resolvedTarget.target() instanceof nx.pingwheel.common.domain.Target.EntityTarget)) {
			throw new IllegalArgumentException("only an entity target can retain local geometry metadata");
		}

		if (blockHitFace.isPresent()
			&& !(resolvedTarget.target() instanceof nx.pingwheel.common.domain.Target.BlockTarget)
			&& !(resolvedTarget.target() instanceof nx.pingwheel.common.domain.Target.ExternalBlockTarget)) {
			throw new IllegalArgumentException("only a block target can retain a hit face");
		}
	}

	/**
	 * Compatibility constructor for pure interaction seams that predate the
	 * press-ray field. Client capture uses the three-argument constructor.
	 */
	public CapturedPingContext(InteractionToken token, ResolvedTarget resolvedTarget) {
		this(token, resolvedTarget, CapturedRay.defaultRay(), java.util.Optional.empty());
	}

	/** Compatibility constructor retained for callers that provide a press ray. */
	public CapturedPingContext(InteractionToken token, ResolvedTarget resolvedTarget, CapturedRay ray) {
		this(token, resolvedTarget, ray, java.util.Optional.empty());
	}

	/**
	 * Compatibility constructor retained for callers that provide entity-local
	 * geometry metadata without the later ordinary-block hit face.
	 */
	public CapturedPingContext(
		InteractionToken token,
		ResolvedTarget resolvedTarget,
		CapturedRay ray,
		java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata
	) {
		this(token, resolvedTarget, ray, entityLocalGeometryMetadata, java.util.Optional.empty());
	}

	/** Compatibility constructor retaining the completed face-capture API. */
	public CapturedPingContext(
		InteractionToken token,
		ResolvedTarget resolvedTarget,
		CapturedRay ray,
		java.util.Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata,
		java.util.Optional<BlockFace> blockHitFace
	) {
		this(token, resolvedTarget, ray, entityLocalGeometryMetadata, blockHitFace, java.util.Optional.empty());
	}

	/**
	 * Descriptive alias for callers that refer to the value as a press ray.
	 */
	public CapturedRay pressRay() {
		return ray;
	}
}
