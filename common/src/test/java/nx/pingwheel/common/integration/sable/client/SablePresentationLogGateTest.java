package nx.pingwheel.common.integration.sable.client;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.sable.SableDiagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SablePresentationLogGateTest {

	@Test
	void flickeringFailureUsesCadenceInsteadOfSuccessReset() {
		long[] now = {0L};
		SablePresentationLogGate gate = new SablePresentationLogGate(
			() -> now[0], 100L, 8);

		assertTrue(gate.shouldLog("resolve-presentation|provider-unavailable|none|target"));
		assertFalse(gate.shouldLog("resolve-presentation|provider-unavailable|none|target"));

		// A successful frame no longer clears the failure baseline.
		now[0] = 99L;
		assertFalse(gate.shouldLog("resolve-presentation|provider-unavailable|none|target"));
		now[0] = 100L;
		assertTrue(gate.shouldLog("resolve-presentation|provider-unavailable|none|target"));
	}

	@Test
	void failureKeysAreBoundedAndMessagesDoNotNeedToBeKeys() {
		long[] now = {0L};
		SablePresentationLogGate gate = new SablePresentationLogGate(
			() -> now[0], 1_000L, 2);

		assertTrue(gate.shouldLog("resolve-name|exception|java.lang.IllegalStateException|target"));
		assertFalse(gate.shouldLog("resolve-name|exception|java.lang.IllegalStateException|target"));
		assertTrue(gate.shouldLog("resolve-name|exception|java.lang.IllegalStateException|other-target"));
		assertTrue(gate.shouldLog("resolve-name|provider-unavailable|none|third-target"));
		assertEquals(2, gate.size());
	}

	@Test
	void providerExceptionDedupeUsesFailureClassAndStableTargetInsteadOfMessage() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		SableClientProvider.setDiagnosticsForTests(diagnostics);
		IllegalStateException first = new IllegalStateException("first mutable message");

		SableClientProvider.logPresentationException(
			"resolve-name", first, "target", "stable-target");
		SableClientProvider.logPresentationException(
			"resolve-name", new IllegalStateException("second mutable message"),
			"target", "stable-target");

		assertEquals(1, diagnostics.events().size());
		assertSame(first, diagnostics.events().get(0).throwable());
	}
}
