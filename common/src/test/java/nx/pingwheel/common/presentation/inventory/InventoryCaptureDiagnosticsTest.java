package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.diagnostics.RateLimitedDiagnosticsTestScope;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryCaptureDiagnosticsTest {

	@Test void externalCaptureFailureKeepsProviderIdentityAndOriginalDetailsWithoutPhysicalCoordinates() {
		var target = new Target.ExternalBlockTarget("minecraft:overworld", "test:provider", "stable-machine",
			"test:inventory", "opaque-locator");
		var input = new InventorySourceInput(target, new UUID(51, 1), BlockFace.EAST);
		var failure = new ClassCastException("external capture cast: test.NativeInventory cannot be cast to test.Container");
		try (var logs = new Capture()) {
			InventoryCaptureDiagnostics.captureFailed(input, failure);
			logs.report("capture-failed", Level.WARN, failure.getMessage(), "at " + getClass().getName() + ".externalCaptureFailureKeepsProviderIdentity",
				"provider=test:provider", "stableId=stable-machine", "registry=test:inventory", "locator=opaque-locator",
				"face=EAST", "readOwner=" + input.readOwner());
			assertTrue(logs.events.stream().noneMatch(e -> e.getMessage().getFormattedMessage().contains("position=")),
				"detached external placeholder coordinates are not a physical source position");
		}
	}

	@Test void expectedBudgetRefusalAndCursorlessWrapperDoNotEvaluateDiagnosticContext() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			new UUID(51, 2), BlockFace.NORTH);
		var allowance = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(0, 0, 0));
		var exhausted = assertThrows(InventoryItemBudget.CaptureLimitException.class,
			() -> allowance.debit(new InventoryValidationBudget.Cost(0, 0, 1)));
		try (var logs = new Capture()) {
			InventoryCaptureDiagnostics.warn("test-budget", input, null, () -> { throw new AssertionError("expected refusal has no log"); }, exhausted);
			InventoryCaptureDiagnostics.warn("test-budget", input, null, () -> { throw new AssertionError("expected wrapper has no log"); },
				new InventorySourceAccess.CursorlessReadFailure(1, exhausted));
			assertTrue(logs.events.isEmpty());
		}
	}

	@Test void expectedUnavailableAndCursorlessCauseStaySilentButIdenticalProviderFailureMessagesWarn() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "diagnostic:expected-source"),
			new UUID(51, 3), BlockFace.NORTH);
		var denied = new InventorySourceUnavailableException("inventory access outside the read scope");
		var broken = new IllegalStateException(denied.getMessage(), new RuntimeException("real provider callback failure"));
		try (var logs = new Capture()) {
			InventoryCaptureDiagnostics.warn("test-unavailable", input, null,
				() -> { throw new AssertionError("expected denial has no log"); }, denied);
			InventoryCaptureDiagnostics.warn("test-unavailable", input, null,
				() -> { throw new AssertionError("expected cursorless denial has no log"); }, new InventorySourceAccess.CursorlessReadFailure(1, denied));
			assertTrue(logs.events.isEmpty());
			InventoryCaptureDiagnostics.warn("test-unexpected-direct", input, null, () -> "direct callback", broken);
			InventoryCaptureDiagnostics.warn("test-unexpected-cursorless", input, null, () -> "cursorless callback",
				new InventorySourceAccess.CursorlessReadFailure(1, broken));
			logs.report("test-unexpected-direct", Level.WARN, broken.getMessage(), "real provider callback failure", "direct callback");
			logs.report("test-unexpected-cursorless", Level.WARN, broken.getMessage(), "real provider callback failure", "cursorless callback");
			assertEquals(2, logs.events.size(), "classification uses the explicit type, never an exception message");
		}
	}

	/** Captures the production lazy logger without touching Global or platform services. */
	static final class Capture implements AutoCloseable {
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final LoggerConfig previousConfig = configuration.getLoggers().get(logger.getName());
		final List<LogEvent> events = new ArrayList<>();
		private final RateLimitedDiagnosticsTestScope diagnostics;
		private final AbstractAppender appender = new AbstractAppender("inventory-diagnostic-test", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};
		Capture() {
			appender.start();
			var captureConfig = new LoggerConfig(logger.getName(), Level.WARN, false);
			captureConfig.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName()); configuration.addLogger(logger.getName(), captureConfig);
			context.updateLoggers();
			// Shared with InventorySourceSnapshotTest: each capture starts with
			// independent limiter state and restores the shared one on close.
			diagnostics = RateLimitedDiagnosticsTestScope.install();
		}
		void report(String operation, Level level, String... details) {
			var matching = events.stream().filter(e -> e.getMessage().getFormattedMessage().contains("operation=inventory/" + operation + " ")).toList();
			assertEquals(1, matching.size(), () -> "Expected one " + operation + " report; got " + events);
			var event = matching.getFirst();
			assertEquals(level, event.getLevel());
			assertNull(event.getThrown(), "the logger must not render a second unbounded throwable");
			String message = event.getMessage().getFormattedMessage();
			for (String detail : details) assertTrue(message.contains(detail), () -> "Missing " + detail + " in " + message);
		}
		@Override public void close() {
			diagnostics.close();
			configuration.removeLogger(logger.getName());
			if (previousConfig != null) configuration.addLogger(logger.getName(), previousConfig);
			context.updateLoggers(); appender.stop();
		}
	}
}
