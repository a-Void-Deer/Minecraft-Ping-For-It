package nx.pingwheel.common.integration.sable.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.Level;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter.Stage;
import org.jetbrains.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SablePresentationLogGateTest {

	@AfterEach
	void restoreGlobalDiagnostics() {
		SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global(Diagnostics.client()));
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
		SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global(Diagnostics.client()));
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
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

	@Test
	void normalProfileDoesNotConsumeDebugPresentationOpportunityWithDebugBackendEnabled() {
		try (var capture = new IntegrationDiagnosticCapture(Level.DEBUG, LoggingProfile.NORMAL)) {
			SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global(Diagnostics.client()));
			SableClientProvider.logPresentationFailure("resolve-name", "provider-unavailable", "target", "stable");
			assertTrue(capture.events().isEmpty());
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			SableClientProvider.logPresentationFailure("resolve-name", "provider-unavailable", "target", "stable");
			assertEquals(1, capture.events().size());
			SableClientProvider.logPresentationFailure("resolve-name", "provider-unavailable", "target", "stable");
			assertEquals(1, capture.events().size(), "same domain cadence remains consumed once enabled");
		}
	}

	@Test
	void injectedPresentationSinkFailureIsASelfFaultAndStaysConsumed() {
		long before = faults(Stage.SINK);
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		SableClientProvider.setDiagnosticsForTests(new SableDiagnostics() {
			@Override public void debug(String prefix, String stage, String reason, @Nullable Object... fields) {}
			@Override public void debugException(String prefix, String stage, String handling, String reason,
				Throwable failure, @Nullable Object... fields) {
				calls.incrementAndGet();
				throw new IllegalStateException("diagnostic sink fault");
			}
		});
		SableClientProvider.logPresentationException("resolve-name", new IllegalStateException("business original"));
		SableClientProvider.logPresentationException("resolve-name", new IllegalStateException("business next"));
		assertEquals(1, calls.get());
		assertEquals(before + 1, faults(Stage.SINK));
	}

	@Test
	void gateClockRunsOutsideTheMonitorAndClockFailurePreservesOpportunity() {
		long before = faults(Stage.CHANNEL_CLOCK);
		java.util.concurrent.atomic.AtomicBoolean failClock = new java.util.concurrent.atomic.AtomicBoolean(true);
		@Nullable SablePresentationLogGate[] holder = new SablePresentationLogGate[1];
		holder[0] = new SablePresentationLogGate(() -> {
			assertFalse(Thread.holdsLock(holder[0]));
			if (failClock.get()) throw new IllegalStateException("gate clock fault");
			return 1L;
		}, 100L, 8);
		assertFalse(holder[0].shouldLog("target"));
		assertEquals(before + 1, faults(Stage.CHANNEL_CLOCK));
		failClock.set(false);
		assertTrue(holder[0].shouldLog("target"));
		assertFalse(holder[0].shouldLog("target"));
	}

	private static long faults(Stage stage) {
		return Diagnostics.faults().observations().stream()
			.filter(observation -> observation.key().side() == DiagnosticSide.CLIENT
				&& observation.key().site().equals("sable/presentation") && observation.key().stage() == stage)
			.mapToLong(observation -> observation.occurrences()).sum();
	}
}
