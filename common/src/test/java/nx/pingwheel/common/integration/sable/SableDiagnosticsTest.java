package nx.pingwheel.common.integration.sable;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import org.apache.logging.log4j.Level;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableDiagnosticsTest {

	@Test
	void recordingKeepsDetailedCaptureServerAndRefreshEvents() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		UUID subLevel = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

		diagnostics.capture(
			"attempt", "start",
			"sable_loaded", true,
			"sublevel_uuid", subLevel,
			"position", "12,64,-4");
		diagnostics.capture(
			"candidate-rejection", "containment-mismatch",
			"sublevel_uuid", subLevel,
			"local_block_pos", "12,64,-4");
		diagnostics.capture(
			"candidate-rejection", "unloaded-or-missing-state",
			"sublevel_uuid", subLevel);
		diagnostics.capture("capture", "success", "provider", "sable", "block_entity", true);
		diagnostics.server(
			"candidate-validation", "accepted",
			"provider_locator", subLevel + "/12,64,-4",
			"distance", 8.5D,
			"classification", "entity_block");
		diagnostics.refresh(
			"refresh", "locator-or-anchor-changed",
			"stable_target_uuid", subLevel,
			"current_locator", subLevel + "/13,64,-4");

		assertEquals(6, diagnostics.events().size());
		assertEquals(SableDiagnostics.CAPTURE_PREFIX, diagnostics.events().get(0).prefix());
		assertEquals("attempt", diagnostics.events().get(0).stage());
		assertEquals("start", diagnostics.events().get(0).reason());
		assertEquals(SableDiagnostics.SERVER_PREFIX, diagnostics.events().get(4).prefix());
		assertEquals("accepted", diagnostics.events().get(4).reason());
		assertEquals(SableDiagnostics.REFRESH_PREFIX, diagnostics.events().get(5).prefix());
		assertTrue(diagnostics.events().get(5).fields().contains(subLevel + "/13,64,-4"));
	}

	@Test
	void exceptionEventRetainsTheOriginalThrowable() {
		SableDiagnostics.Recording diagnostics = SableDiagnostics.recording();
		RuntimeException failure = new RuntimeException("provider payload and coordinates");

		diagnostics.serverException(
			"reflection-discovery", "failure", failure,
			"class_name", "dev.ryanhcode.sable.sublevel.SubLevel");

		assertEquals(1, diagnostics.events().size());
		assertSame(failure, diagnostics.events().get(0).throwable());
		assertEquals("reflection-discovery", diagnostics.events().get(0).stage());
		assertEquals("failure", diagnostics.events().get(0).reason());
	}

	@Test
	void rejectedNormalAndSuppressedFailureDoNotReadDeferredProviderFields() {
		try (IntegrationDiagnosticCapture capture = new IntegrationDiagnosticCapture(Level.DEBUG, LoggingProfile.NORMAL)) {
			SableDiagnostics diagnostics = SableDiagnostics.global(Diagnostics.server());
			AtomicInteger reads = new AtomicInteger();
			Object field = SableDiagnostics.lazy(() -> { reads.incrementAndGet(); return "private-locator"; });
			diagnostics.server("candidate-query", "start", "locator", field);
			diagnostics.serverException("observe-block", "failed", new IllegalStateException("private-cause"), "locator", field);
			assertEquals(0, reads.get());
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			diagnostics.serverException("observe-block", "failed", new IllegalStateException("later-cause"), "locator", field);
			assertEquals(0, reads.get(), "suppressed failure fields remain lazy after a profile change");
			diagnostics.approvedException(SableDiagnostics.SERVER_PREFIX, "observe-block", "failed",
				new IllegalStateException("original-cause"), "locator", field);
			assertEquals(1, reads.get());
			assertTrue(capture.events().getLast().getMessage().getFormattedMessage().contains("original-cause"));
			assertTrue(capture.events().getLast().getMessage().getFormattedMessage().contains("private-locator"));
		}
	}
}
