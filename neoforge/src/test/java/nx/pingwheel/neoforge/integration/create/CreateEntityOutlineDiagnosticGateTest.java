package nx.pingwheel.neoforge.integration.create;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

	@Test
	void clockFailureRunsOutsideTheMonitorReportsSelfFaultAndDoesNotSpendTheFirstEvent() {
		var owner = new AtomicReference<CreateEntityOutlineDiagnosticGate>();
		var broken = new AtomicBoolean(true);
		var failure = new IllegalStateException("clock original");
		var gate = new CreateEntityOutlineDiagnosticGate(() -> {
			assertFalse(Thread.holdsLock(owner.get()));
			if (broken.get()) throw failure;
			return 0;
		});
		owner.set(gate);
		long before = clockFaults();
		LoggingProfile previous = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			assertFalse(gate.admit("target", "failed", "FAILED", true, false));
			assertEquals(0, gate.trackedCount()); assertEquals(before + 1, clockFaults());
			broken.set(false);
			assertTrue(gate.admit("target", "failed", "FAILED", true, false));
			assertFalse(gate.admit("target", "failed", "FAILED", true, false));
		} finally { Diagnostics.setProfile(DiagnosticSide.CLIENT, previous); }
	}

	private static long clockFaults() {
		return Diagnostics.faults().observations().stream().filter(observation ->
			observation.key().site().equals("create-entity-outline-gate")
				&& observation.key().stage() == DiagnosticFaultReporter.Stage.CHANNEL_CLOCK)
			.mapToLong(DiagnosticFaultReporter.Observation::occurrences).sum();
	}
}
