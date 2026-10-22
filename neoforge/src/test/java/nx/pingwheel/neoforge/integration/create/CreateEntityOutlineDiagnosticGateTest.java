package nx.pingwheel.neoforge.integration.create;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class CreateEntityOutlineDiagnosticGateTest {

	@Test
	void debugDisabledSuccessStillAllowsTheSameFailureToTransitionAfterTheExistingDebounce() {
		long[] now = {0};
		CreateEntityOutlineDiagnosticGate gate = new CreateEntityOutlineDiagnosticGate(() -> now[0]);
		assertTrue(gate.admit("target", "failed", "FAILED", true, false));
		now[0] += 2_000_000_000L;
		assertFalse(gate.admit("target", "rendered", "RENDERED", false, true));
		assertFalse(gate.admit("target", "failed", "FAILED", true, false));
		now[0] += CreateEntityOutlineDiagnosticGate.MIN_INTERVAL_NANOS;
		assertTrue(gate.admit("target", "failed", "FAILED", true, false));
		assertFalse(gate.admit("target", "failed", "FAILED", true, false));
		now[0] += CreateEntityOutlineDiagnosticGate.HEARTBEAT_NANOS;
		assertTrue(gate.admit("target", "failed", "FAILED", true, false));
	}

	@Test
	void disabledWarningSpendsNoOpportunityAndLevelChangesDoNotResetAnAdmittedWarning() {
		CreateEntityOutlineDiagnosticGate gate = new CreateEntityOutlineDiagnosticGate(() -> 0);
		assertFalse(gate.admit("target", "failed", "FAILED", false, false));
		assertTrue(gate.admit("target", "failed", "FAILED", true, false));
		assertFalse(gate.admit("target", "failed", "FAILED", false, false));
		assertFalse(gate.admit("target", "failed", "FAILED", true, false));
	}

	@Test
	void retainedStateStaysBoundedAndTeardownClearsIt() {
		CreateEntityOutlineDiagnosticGate gate = new CreateEntityOutlineDiagnosticGate(() -> 0);
		for (int index = 0; index <= CreateEntityOutlineDiagnosticGate.MAX_STATES; index++) {
			assertTrue(gate.admit("target-" + index, "failed", "FAILED", true, false));
		}
		assertEquals(CreateEntityOutlineDiagnosticGate.MAX_STATES, gate.trackedCount());
		gate.clear();
		assertEquals(0, gate.trackedCount());
	}
}
