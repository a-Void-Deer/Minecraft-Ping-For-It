package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;

import nx.pingwheel.common.interaction.TargetSnapshot;

/** Unresolved but detached press-time evidence; only the final accepted set has public candidate IDs. */
public record CandidateEvidence(TargetSnapshot snapshot, double distance) {
	public CandidateEvidence {
		Objects.requireNonNull(snapshot, "snapshot");
		if (snapshot.candidateHit().isEmpty() || !Double.isFinite(distance) || distance < 0) {
			throw new IllegalArgumentException("surface evidence and finite distance required");
		}
	}
	public CandidateHit hit() { return snapshot.candidateHit().orElseThrow(); }
}
