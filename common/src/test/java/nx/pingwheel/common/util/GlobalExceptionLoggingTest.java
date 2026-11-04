package nx.pingwheel.common.util;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.diagnostics.RateLimitedDiagnosticsTestScope;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class GlobalExceptionLoggingTest {

	@Test
	void explicitSideGlobalHelperUsesNormalDisclosureEvenAtErrorSeverity() {
		Logger logger = (Logger) org.apache.logging.log4j.LogManager.getLogger("pingforit.server");
		LoggerContext context = logger.getContext();
		Configuration configuration = context.getConfiguration();
		@Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
		LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.SERVER);
		List<LogEvent> events = new ArrayList<>();
		AbstractAppender appender = new AbstractAppender("global-side-test", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
		};
		appender.start();
		LoggerConfig exact = new LoggerConfig(logger.getName(), Level.DEBUG, false);
		exact.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), exact);
		context.updateLoggers();
		try {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
			Global.exception(DiagnosticSide.SERVER, Level.ERROR, "global-side", "fallback", new RuntimeException() {
				@Override public String getMessage() { throw new AssertionError("NORMAL message accessor"); }
				@Override public StackTraceElement @Unmodifiable [] getStackTrace() { throw new AssertionError("NORMAL stack accessor"); }
				@Override public @Nullable Throwable getCause() { throw new AssertionError("NORMAL cause accessor"); }
			});
			assertEquals(1, events.size());
			assertEquals(Level.ERROR, events.getFirst().getLevel());
			assertEquals("pingforit.server", events.getFirst().getLoggerName());
			assertTrue(events.getFirst().getMessage().getFormattedMessage().contains("site=global-side handling=fallback"));
			assertNull(events.getFirst().getThrown());
		} finally {
			Diagnostics.setProfile(DiagnosticSide.SERVER, previousProfile);
			configuration.removeLogger(logger.getName());
			if (previous != null) configuration.addLogger(logger.getName(), previous);
			context.updateLoggers();
			appender.stop();
		}
	}

	@Test
	void disabledExceptionHelpersNeverReadThrowableAccessors() {
		try (Capture capture = new Capture(Level.OFF)) {
			AtomicInteger reads = new AtomicInteger();
			Throwable failure = new Throwable() {
				@Override
				public String getMessage() {
					reads.incrementAndGet();
					return "detail";
				}

				@Override
				public StackTraceElement[] getStackTrace() {
					reads.incrementAndGet();
					return new StackTraceElement[0];
				}

				@Override
				public @Nullable Throwable getCause() {
					reads.incrementAndGet();
					return null;
				}
			};

			Global.debugException("debug", failure);
			Global.warnException("warn", failure);
			Global.errorException("error", failure);
			RateLimitedDiagnostics.debug("public-api-disabled-debug-test", "provider.Inventory", () -> {
				reads.incrementAndGet();
				return "disabled context";
			}, failure);

			assertEquals(0, reads.get());
			assertTrue(capture.events.isEmpty());
		}
	}

	@Test
	void enabledHelpersAndPublicRateLimitedApiEmitCorrectLevelsAndOneBoundedReport() {
		try (Capture capture = new Capture(Level.ALL)) {
			assertSame(Diagnostics.logger(), Global.LOGGER);
			RuntimeException cause = failure("cause registry=othermod:machine", "resolve");
			RuntimeException failure = new RuntimeException("original payload={name:Alice}", cause);
			failure.setStackTrace(new StackTraceElement[] {
				new StackTraceElement("provider.Inventory", "read", "Inventory.java", 18)
			});
			failure.addSuppressed(failure("suppressed material=othermod:copper", "close"));

			Global.debugException("debug position=(1,64,-2)", failure);
			Global.warnException("warn position=(1,64,-2)", failure);
			Global.errorException("error position=(1,64,-2)", failure);
			RateLimitedDiagnostics.debug("public-api-debug-test", "provider.Inventory",
				() -> "position=(1,64,-2)", failure);
			RateLimitedDiagnostics.warn("public-api-warn-test", "provider.Inventory",
				() -> "position=(1,64,-2)", failure);
			RateLimitedDiagnostics.error("public-api-error-test", "provider.Inventory",
				() -> "position=(1,64,-2)", failure);

			assertEquals(List.of(Level.DEBUG, Level.WARN, Level.ERROR, Level.DEBUG, Level.WARN, Level.ERROR),
				capture.events.stream().map(LogEvent::getLevel).toList());
			for (LogEvent event : capture.events) {
				String message = event.getMessage().getFormattedMessage();
				assertTrue(message.contains("position=(1,64,-2)"));
				assertTrue(message.contains("original payload={name:Alice}"));
				assertTrue(message.contains("cause registry=othermod:machine"));
				assertTrue(message.contains("suppressed material=othermod:copper"));
				assertTrue(message.contains("java.lang.RuntimeException"));
				assertTrue(message.contains("provider.Inventory.read (Inventory.java:18)"));
				assertTrue(message.contains("provider.Inventory.resolve"));
				assertTrue(message.startsWith(Diagnostics.PREFIX));
				assertNull(event.getThrown(), "No second, unbounded logger throwable rendering");
				assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS + Global.MOD_PREFIX.length());
			}
		}
	}

	@Test
	void legacyExceptionHelpersRetainTheirNoWindowBehavior() {
		try (Capture capture = new Capture(Level.ALL)) {
			RuntimeException failure = failure("original", "read");
			Global.warnException("same context", failure);
			Global.warnException("same context", failure);
			assertEquals(2, capture.events.size());
		}
	}

	private static RuntimeException failure(String message, String method) {
		RuntimeException failure = new RuntimeException(message);
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Inventory", method, "Inventory.java", 18)
		});
		return failure;
	}

	@NotNullByDefault
	private static final class Capture implements AutoCloseable {
		private final Logger logger = (Logger) Global.LOGGER;
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final @Nullable LoggerConfig previousConfig = configuration.getLoggers().get(logger.getName());
		private final List<LogEvent> events = new ArrayList<>();
		private final RateLimitedDiagnosticsTestScope diagnostics;
		private final AbstractAppender appender = new AbstractAppender(
			"exception-helper-test", null, null, true, Property.EMPTY_ARRAY
		) {
			@Override
			public void append(LogEvent event) {
				events.add(event.toImmutable());
			}
		};

		private Capture(Level level) {
			appender.start();
			LoggerConfig captureConfig = new LoggerConfig(logger.getName(), level, false);
			captureConfig.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName());
			configuration.addLogger(logger.getName(), captureConfig);
			// The Global and diagnostics routes share one logger instance.
			context.updateLoggers();
			diagnostics = RateLimitedDiagnosticsTestScope.install();
		}

		@Override
		public void close() {
			diagnostics.close();
			configuration.removeLogger(logger.getName());
			if (previousConfig != null) {
				configuration.addLogger(logger.getName(), previousConfig);
			}
			context.updateLoggers();
			appender.stop();
		}
	}
}
