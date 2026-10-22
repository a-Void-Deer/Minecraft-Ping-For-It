package nx.pingwheel.common.integration;

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.integration.sable.SableDiagnostics;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class IntegrationFailureDiagnosticsTest {
	@Test void guardEmitsOneWarningWithOriginalLinkageCauseAndDisablesOnce() {
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			var guard = new IntegrationLinkGuard("test-integration");
			var failure = new LinkageError("original linkage payload");
			failure.initCause(new IllegalStateException("original root cause"));
			guard.disable(failure);
			guard.disable(new LinkageError("later failure"));
			assertTrue(guard.disabled());
			assertEquals(1, capture.events().size());
			LogEvent event = capture.events().getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertTrue(message(event).contains("test-integration"));
			assertTrue(message(event).contains("original linkage payload"));
			assertTrue(message(event).contains("original root cause"));
			assertNull(event.getThrown());
		}
	}

	@Test void sableFailuresUseWarningBackendAndApprovedEventsHaveNoSecondWindow() {
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			SableDiagnostics diagnostics = SableDiagnostics.global();
			var cause = new IllegalStateException("original cause");
			cause.addSuppressed(new RuntimeException("cleanup detail"));
			var failure = new RuntimeException("original message " + "x".repeat(100_000), cause);
			diagnostics.server("observe-block", "unavailable", "target", "normal missing data");
			diagnostics.serverException("observe-block", "exception", failure, "target", "provider target");
			diagnostics.serverException("observe-block", "exception", failure, "target", "changed detail");
			assertEquals(1, capture.events().size(), "ordinary failures share controlled admission");
			diagnostics.approvedException(SableDiagnostics.CAPTURE_PREFIX, "presentation", "exception", failure);
			diagnostics.approvedException(SableDiagnostics.CAPTURE_PREFIX, "presentation", "exception", failure);
			assertEquals(3, capture.events().size(), "approved domain transitions have no second window");
			for (LogEvent event : capture.events()) {
				assertEquals(Level.WARN, event.getLevel());
				assertNull(event.getThrown());
				assertTrue(message(event).contains("original cause"));
				assertTrue(message(event).contains("cleanup detail"));
				assertTrue(message(event).length() <= SafeExceptionReport.MAX_CHARACTERS + Diagnostics.PREFIX.length());
			}
		}
	}

	@Test void distantAsyncFailureReportsRootAndKeepsEmptyCompletionAndCallbackPropagation() {
		try (var capture = new IntegrationDiagnosticCapture(Level.WARN)) {
			var failure = new IllegalStateException("async original cause");
			var wrapper = new CompletionException(failure);
			AtomicInteger calls = new AtomicInteger();
			DistantHorizonsIntegration.completeTrace(Vec3.ZERO, new Vec3(1, 0, 0), null, wrapper, result -> {
				assertTrue(result.isEmpty());
				calls.incrementAndGet();
			});
			assertEquals(1, calls.get());
			assertEquals(1, capture.events().size());
			assertTrue(message(capture.events().getFirst()).contains("async original cause"));
			assertNull(capture.events().getFirst().getThrown());
			var callbackFailure = new IllegalStateException("callback cause");
			assertSame(callbackFailure, assertThrows(IllegalStateException.class, () ->
				DistantHorizonsIntegration.completeTrace(Vec3.ZERO, new Vec3(1, 0, 0), null, wrapper,
					result -> { throw callbackFailure; })));
		}
	}

	private static String message(LogEvent event) { return event.getMessage().getFormattedMessage(); }
}
