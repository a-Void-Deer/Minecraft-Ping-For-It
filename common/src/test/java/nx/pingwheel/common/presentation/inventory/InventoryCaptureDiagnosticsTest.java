package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.PresentationDiagnosticScope;
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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class InventoryCaptureDiagnosticsTest {
	@Test void providerLimitClassificationOnlyUnwrapsEachAuditedWrapperOnce() {
		try (var logs = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			var limit = nx.pingwheel.common.presentation.inventory.minecraft.InventoryProviderLimits.exceeded(
				nx.pingwheel.common.presentation.inventory.minecraft.ProviderLimitSite.REFLECTION_INHERITANCE, 33, 32);
			var wrapped = new java.lang.reflect.InvocationTargetException(new InventorySourceAccess.CursorlessReadFailure(1,
				new InventorySharedRead.ReadFailure(InventorySharedRead.Check.UNAVAILABLE, limit)));
			assertEquals(limit, InventoryCaptureDiagnostics.providerLimit(wrapped));
			assertEquals(InventoryFailureReason.NATIVE_DEPTH_LIMIT, InventoryCaptureDiagnostics.sourceReason(wrapped));
			assertTrue(InventoryCaptureDiagnostics.expectedFailure(wrapped));
			assertTrue(InventoryCaptureDiagnostics.expectedSourceUnavailable(wrapped));
			assertNull(InventoryCaptureDiagnostics.providerLimit(new java.lang.reflect.InvocationTargetException(wrapped)));
			var unknown = new IllegalStateException("opaque provider wrapper", limit) {
				@Override public synchronized @Nullable Throwable getCause() { throw new AssertionError("unknown cause accessor"); }
			};
			assertNull(InventoryCaptureDiagnostics.providerLimit(unknown));
			assertEquals(InventoryFailureReason.PROVIDER_FAILURE, InventoryCaptureDiagnostics.sourceReason(unknown));
			assertEquals(1, logs.events().size(), "classification must not repeat the native limit report");
		}
	}
	@Test void typedBoundsKeepIndependentReasonsAndEveryDebugRepeatWithoutPayloadDisclosure() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "private:block"), new UUID(51, 92), BlockFace.NORTH);
		var memory = assertThrows(InventoryItemBudget.CaptureLimitException.class, () ->
			new nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission(0, Long.MAX_VALUE).admitFluid(0, 15, 5, 0, 0));
		var work = assertThrows(InventoryItemBudget.CaptureLimitException.class, () ->
			new nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission(Long.MAX_VALUE, 0).admitFluid(0, 15, 5, 0, 0));
		var catalog = new InventoryFluidCodec.Catalog();
		for (int index = 0; index < 15; index++) {
			byte[] bytes = new byte[65536]; bytes[0] = (byte) index;
			catalog.intern("minecraft:water", 1, InventoryFluidCodec.Identity.exact(bytes), new InventoryFluidValue.Display("minecraft:water", "private-label", null));
		}
		byte[] tail = new byte[65536]; tail[0] = 16;
		var payload = assertThrows(InventoryFluidCodec.CapacityException.class, () -> catalog.intern("minecraft:water", 1,
			InventoryFluidCodec.Identity.exact(tail), new InventoryFluidValue.Display("minecraft:water", "private-label", null)));
		var definitions = new InventoryFluidCodec.Catalog();
		for (int index = 0; index < 1024; index++) {
			String id = "test:fluid_" + index;
			definitions.intern(id, 1, InventoryFluidCodec.Identity.refused(), new InventoryFluidValue.Display(id, "private-label", null));
		}
		var entries = assertThrows(InventoryFluidCodec.CapacityException.class, () -> definitions.intern("test:overflow", 1,
			InventoryFluidCodec.Identity.refused(), new InventoryFluidValue.Display("test:overflow", "private-label", null)));
		var failures = java.util.Map.of(InventoryFailureReason.MEMORY_LIMIT, memory, InventoryFailureReason.ITEM_WORK_LIMIT, work,
			InventoryFailureReason.VARIANT_LIMIT, entries, InventoryFailureReason.CATALOG_BYTES_LIMIT, payload, InventoryFailureReason.SNAPSHOT_LIMIT,
			new nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot.LimitException("private-payload"));
		try (var logs = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			failures.forEach((reason, failure) -> {
				int before = logs.events().size();
				for (int repeat = 0; repeat < 2; repeat++) InventoryCaptureDiagnostics.boundExceeded(input, "typed-bound", failure);
				assertEquals(before + 2, logs.events().size());
				assertTrue(logs.events().getLast().getMessage().getFormattedMessage().contains("reason=" + reason.code()));
			});
			assertTrue(logs.events().stream().allMatch(event -> event.getMessage().getFormattedMessage().contains("limitHit=true")));
			assertTrue(!logs.messages().contains("private-label")); assertTrue(!logs.messages().contains("private:block"));
		}
	}
	@Test void normalProfileDoesNotEvaluateUnexpectedFailureContext() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "private:block"), new UUID(51, 2), BlockFace.NORTH);
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
			var calls = new java.util.concurrent.atomic.AtomicInteger();
			InventoryCaptureDiagnostics.warn("test-profile-disabled", input, null, () -> { calls.incrementAndGet(); return "private-detail"; },
				new IllegalStateException("private-message"));
			assertEquals(0, calls.get()); assertEquals(1, logs.events().size());
			assertEquals(Level.WARN, logs.events().getFirst().getLevel());
			assertTrue(logs.messages().contains("handling=invalidate-read"));
			assertTrue(!logs.messages().contains("private-"));
		}
	}

	@Test void belowMinimumProfileSkipsDetailAndDebugReportsEveryLimitAttempt() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 4, 5, 6, "private:block"), new UUID(51, 4), BlockFace.NORTH);
		try (var logs = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
			InventoryCaptureDiagnostics.boundExceeded(input, () -> { throw new AssertionError("below-minimum detail"); });
			assertTrue(logs.events().isEmpty());
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			InventoryCaptureDiagnostics.boundExceeded(input, "first-eligible");
			assertEquals(1, logs.events().size());
			assertTrue(logs.messages().contains("stage=first-eligible"));
			InventoryCaptureDiagnostics.boundExceeded(input, "repeat");
			assertEquals(2, logs.events().size()); assertTrue(logs.messages().contains("limitHit=true"));
		}
	}

	@Test void backendDisabledAtEligibleProfileDoesNotFormatLimitDetail() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 4, 5, 6, "private:block"), new UUID(51, 5), BlockFace.NORTH);
		try (var logs = new InventoryDiagnosticsTestCapture(Level.OFF)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.DEEP);
			InventoryCaptureDiagnostics.boundExceeded(input, () -> { throw new AssertionError("backend-disabled detail"); });
			assertTrue(logs.events().isEmpty());
		}
	}

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
			InventoryCaptureDiagnostics.warn("test-budget", input, null, () -> { throw new AssertionError("expected certified budget has no log"); },
				new InventorySharedRead.ReadFailure(InventorySharedRead.Check.AUTHORITY_DENIED, exhausted));
			assertTrue(logs.events.isEmpty());
		}
	}
	@Test void normalFailureHandlingDistinguishesCleanupFromSelectionRejection() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "private:block"), new UUID(51, 7), BlockFace.NORTH);
		try (var logs = new InventoryDiagnosticsTestCapture()) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.NORMAL);
			InventoryCaptureDiagnostics.warn("source-close", input, null, () -> { throw new AssertionError("NORMAL cleanup context"); }, new IllegalStateException("private-cleanup"));
			InventoryCaptureDiagnostics.warn("selection-witness", input, null, () -> { throw new AssertionError("NORMAL selection context"); }, new IllegalArgumentException("private-selection"));
			assertEquals(2, logs.events().size()); assertTrue(logs.messages().contains("handling=cleanup-continued"));
			assertTrue(logs.messages().contains("handling=reject-selection")); assertTrue(!logs.messages().contains("private-"));
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

	@Test void debugLimitReportsBypassOtherReportVolumeAndKeepIndependentReasons() {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 4, 5, 6, "minecraft:chest"), new UUID(51, 4), BlockFace.NORTH);
		try (var logs = new InventoryDiagnosticsTestCapture(Level.DEBUG)) {
			for (int index = 0; index < 65; index++) nx.pingwheel.common.diagnostics.Diagnostics.server().atDetailed().event(Level.WARN,
				"inventory/test-window", "filled", () -> "unrelated");
			int before = logs.events().size();
			InventoryCaptureDiagnostics.boundExceeded(input, "native-refusal");
			InventoryCaptureDiagnostics.boundExceeded(input, "repeat-refusal");
			InventoryCaptureDiagnostics.memoryDeferred(input);
			assertEquals(before + 3, logs.events().size());
			assertTrue(logs.messages().contains("stage=native-refusal"));
			assertTrue(logs.messages().contains("reason=memory-limit")); assertTrue(logs.messages().contains("reason=native-work-limit"));
			assertTrue(logs.events().getLast().getMessage().getFormattedMessage().startsWith("[PingForIt] "));
			assertNull(logs.events().getLast().getThrown());
		}
	}

	/** Captures the production lazy logger without touching Global or platform services. */
	@NotNullByDefault
	static final class Capture implements AutoCloseable {
		private final Logger logger = (Logger) LogManager.getLogger("pingforit");
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final @Nullable LoggerConfig previousConfig = configuration.getLoggers().get(logger.getName());
		final List<LogEvent> events = new ArrayList<>();
		private final PresentationDiagnosticScope diagnostics;
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
			diagnostics = PresentationDiagnosticScope.install();
		}
		void report(String operation, Level level, String... details) {
			var matching = events.stream().filter(e -> e.getMessage().getFormattedMessage().contains("site=inventory/" + operation + " ")).toList();
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
