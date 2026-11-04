package nx.pingwheel.common.integration.sable.server;

import nx.pingwheel.common.testing.Test;

import nx.pingwheel.common.marker.MarkerAnchor;

import static nx.pingwheel.common.testing.Assertions.assertFalse;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

class SableRefreshLogGateTest {

	@Test
	void unchangedSuccessfulRefreshDoesNotRepeat() {
		SableRefreshLogGate gate = new SableRefreshLogGate();
		MarkerAnchor anchor = new MarkerAnchor(1.0, 2.0, 3.0);

		assertFalse(gate.available("tracking", "locator-a", anchor));
		assertFalse(gate.available("tracking", "locator-a", anchor));
		assertTrue(gate.available("tracking", "locator-b", anchor));
		assertFalse(gate.available("tracking", "locator-b", anchor));
	}

	@Test
	void temporaryAndInvalidReasonsOnlyLogOnTransitions() {
		SableRefreshLogGate gate = new SableRefreshLogGate();

		assertTrue(gate.temporarilyUnavailable("tracking", "container-unavailable"));
		assertFalse(gate.temporarilyUnavailable("tracking", "container-unavailable"));
		assertTrue(gate.temporarilyUnavailable("tracking", "sublevel-removed"));
		assertTrue(gate.invalid("tracking", "registry-mismatch"));
		assertFalse(gate.invalid("tracking", "registry-mismatch"));
	}
}
