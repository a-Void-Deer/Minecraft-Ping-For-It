package nx.pingwheel.common.integration.sable.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import org.apache.logging.log4j.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SablePresentationLogGateTest {

	@AfterEach
	void restoreGlobalDiagnostics() {
		SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global());
	}

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

	@Test
	void debugDisabledPresentationStatusDoesNotSpendWarningAdmission() {
		SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global());
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			SableClientProvider.logPresentationFailure("resolve-name", "provider-unavailable", "target", "stable");
			SableClientProvider.logPresentationException("resolve-name", new IllegalStateException("original failure"),
				"target", "stable");
			SableClientProvider.logPresentationException("resolve-name", new IllegalStateException("changed failure"),
				"target", "stable");
			assertEquals(1, capture.events().size());
			assertEquals(Level.WARN, capture.events().getFirst().getLevel());
			assertTrue(capture.events().getFirst().getMessage().getFormattedMessage().contains("original failure"));
			org.junit.jupiter.api.Assertions.assertNull(capture.events().getFirst().getThrown());
		}
	}
}
