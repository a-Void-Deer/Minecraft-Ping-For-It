package nx.pingwheel.common.interaction.candidate;

import java.util.Objects;

import nx.pingwheel.common.interaction.cancel.WorldVector;

/** Detached surface evidence. A provider hit uses its actual transformed world point. */
public record CandidateHit(WorldVector worldHit, CaptureEquivalenceKey equivalenceKey) {
	public CandidateHit {
		Objects.requireNonNull(worldHit, "worldHit");
		Objects.requireNonNull(equivalenceKey, "equivalenceKey");
	}
}
