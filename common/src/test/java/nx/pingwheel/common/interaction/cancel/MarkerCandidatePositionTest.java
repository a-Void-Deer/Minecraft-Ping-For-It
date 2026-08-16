package nx.pingwheel.common.interaction.cancel;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerCandidatePositionTest {

	private static final WorldVector ANCHOR = new WorldVector(1.0, 2.0, 3.0);
	private static final WorldVector FROZEN_PRESENTATION = new WorldVector(10.0, 20.0, 30.0);

	@Test
	void frozenPresentationPositionWinsOverAnchor() {
		assertEquals(FROZEN_PRESENTATION,
			MarkerCandidatePosition.resolve(ANCHOR, Optional.of(FROZEN_PRESENTATION)));
	}

	@Test
	void missingPresentationViewFallsBackToAnchor() {
		assertEquals(ANCHOR,
			MarkerCandidatePosition.resolve(ANCHOR, Optional.empty()));
	}
}
