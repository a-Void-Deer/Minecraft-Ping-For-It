package nx.pingwheel.common.util;

import nx.pingwheel.common.Global;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionLoggingTest {

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
				public Throwable getCause() {
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
				assertNull(event.getThrown(), "No second, unbounded logger throwable rendering");
				assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS + Global.MOD_PREFIX.length());
			}
		}
	}

	private static RuntimeException failure(String message, String method) {
		RuntimeException failure = new RuntimeException(message);
		failure.setStackTrace(new StackTraceElement[] {
			new StackTraceElement("provider.Inventory", method, "Inventory.java", 18)
		});
		return failure;
	}

	private static final class Capture implements AutoCloseable {
		private final Logger logger = (Logger) Global.LOGGER;
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final LoggerConfig previousConfig = configuration.getLoggers().get(logger.getName());
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
			// Apply to both Global's custom-message-factory logger and the lazy
			// logger used by RateLimitedDiagnostics, whichever initializes first.
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
