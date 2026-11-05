package nx.pingwheel.common.presentation.inventory.client;

import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class ClientInventoryDiagnosticsTest {

	@Test
	void belowMinimumProfilesDoNotEvaluateDetail() {
		throwingDetailIgnored(() -> new DiagnosticLogCapture(LoggingProfile.NORMAL));
		throwingDetailIgnored(() -> new DiagnosticLogCapture(LoggingProfile.DEEP));
	}

	@Test
	void disabledBackendDoesNotEvaluateDetail() {
		try (var logs = new IntegrationDiagnosticCapture(Level.OFF, LoggingProfile.DEBUG)) {
			ClientInventoryDiagnostics.debug("inventory/test", "lazy", () -> {
				throw new AssertionError("disabled backend evaluated detail");
			});
			assertTrue(logs.events().isEmpty());
		}
	}

	@Test
	void enabledDebugKeepsTheFixedSiteAndReason() {
		try (var logs = new DiagnosticLogCapture(LoggingProfile.DEBUG)) {
			ClientInventoryDiagnostics.debug("inventory/test", "lazy", () -> "value=1");
			assertEquals(1, logs.events().size());
			String message = logs.events().getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("site=inventory/test"));
			assertTrue(message.contains("handling=lazy"));
			assertTrue(message.contains("value=1"));
		}
	}

	private static void throwingDetailIgnored(java.util.function.Supplier<DiagnosticLogCapture> capture) {
		try (var logs = capture.get()) {
			ClientInventoryDiagnostics.debug("inventory/test", "lazy", () -> {
				throw new AssertionError("below-minimum profile evaluated detail");
			});
			assertTrue(logs.events().isEmpty());
		}
	}
}
