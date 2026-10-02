package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;
import java.util.Optional;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.EntityLocalGeometryMetadata;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.cancel.WorldVector;

/** Frozen selector value. IDs are meaningful only inside the enclosing accepted capture. */
public record Candidate(int candidateId, ResolvedTarget resolvedTarget, WorldVector worldHit,
	double distance, Optional<EntityLocalGeometryMetadata> entityLocalGeometryMetadata,
	Optional<BlockFace> blockHitFace, CaptureEquivalenceKey equivalenceKey) {

	public Candidate {
		Objects.requireNonNull(resolvedTarget, "resolvedTarget");
		Objects.requireNonNull(worldHit, "worldHit");
		Objects.requireNonNull(entityLocalGeometryMetadata, "entityLocalGeometryMetadata");
		Objects.requireNonNull(blockHitFace, "blockHitFace");
		Objects.requireNonNull(equivalenceKey, "equivalenceKey");
		if (candidateId < 0 || !Double.isFinite(distance) || distance < 0
			|| !equivalenceKey.matches(resolvedTarget.target())) {
			throw new IllegalArgumentException("invalid candidate identity or distance");
		}
		if (entityLocalGeometryMetadata.isPresent() && !(resolvedTarget.target() instanceof Target.EntityTarget)
			|| blockHitFace.isPresent() && !(resolvedTarget.target() instanceof Target.BlockTarget)) {
			throw new IllegalArgumentException("candidate metadata belongs to a different target kind");
		}
	}
}
