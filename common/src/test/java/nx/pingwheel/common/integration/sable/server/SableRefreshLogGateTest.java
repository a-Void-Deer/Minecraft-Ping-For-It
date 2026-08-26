package nx.pingwheel.common.integration.sable.server;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.marker.MarkerAnchor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
