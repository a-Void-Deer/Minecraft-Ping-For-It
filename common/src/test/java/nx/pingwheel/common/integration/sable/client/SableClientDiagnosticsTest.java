package nx.pingwheel.common.integration.sable.client;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.sable.SableDiagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SableClientDiagnosticsTest {

	private boolean previousSableLoaded;

	@BeforeEach
	void installRecordingDiagnostics() {
		previousSableLoaded = ModContext.HasSable;
		ModContext.HasSable = false;
	}

	@AfterEach
	void restoreDiagnosticsAndSableFlag() {
		SableClientProvider.setDiagnosticsForTests(SableDiagnostics.global());
		ModContext.HasSable = previousSableLoaded;
	}

	@Test
	void disabledCaptureStillEmitsAttemptAndExternalFallbackStages() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		SableClientProvider.setDiagnosticsForTests(diagnostics);

		assertTrue(SableClientProvider.capture(null, null, null, null).isEmpty());
		SableClientProvider.logCaptureFallback(
			"PROJECTED_LOCATION", "external-capture-failed", "projection", "projected_position", "1,2,3");
		SableClientProvider.logCaptureFallback(
			"VANILLA_TARGET_FACTORY", "external-and-projected-capture-failed",
			"vanilla-target-factory", "block_pos", "1,2,3");

		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.prefix().equals(SableDiagnostics.CAPTURE_PREFIX)
				&& event.stage().equals("attempt")
				&& event.reason().equals("start")));
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.reason().equals("EXTERNAL_CAPTURE_FAILED")));
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.reason().equals("PROJECTED_LOCATION")));
		assertTrue(diagnostics.events().stream().anyMatch(event ->
			event.reason().equals("VANILLA_TARGET_FACTORY")));
	}
}
