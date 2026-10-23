package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.RateLimitedDiagnostics;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class DiagnosticsTest {

	@Test
	void compatibilityAndNewFailureRoutesShareAllLevelWindowsAndOnePrefixedBackend() {
		try (Capture capture = new Capture(Level.ALL)) {
			RuntimeException failure = failure("original payload", null);
			AtomicInteger reads = new AtomicInteger();
			RateLimitedDiagnostics.debug("read", "provider", () -> "old-debug", failure);
			Diagnostics.debug("read", "provider", () -> "suppressed=" + reads.incrementAndGet(), failure);
			Diagnostics.warn("read", "provider", () -> "new-warn", failure);
			RateLimitedDiagnostics.warn("read", "provider", () -> "suppressed=" + reads.incrementAndGet(), failure);
			RateLimitedDiagnostics.error("read", "provider", () -> "old-error", failure);
			Diagnostics.error("read", "provider", () -> "suppressed=" + reads.incrementAndGet(), failure);

			assertEquals(0, reads.get());
			assertEquals(List.of(Level.DEBUG, Level.WARN, Level.ERROR), capture.events.stream().map(LogEvent::getLevel).toList());
			for (LogEvent event : capture.events) {
				String message = event.getMessage().getFormattedMessage();
				assertTrue(message.startsWith(Diagnostics.PREFIX + "operation=read"));
				assertFalse(message.contains(Diagnostics.PREFIX + Diagnostics.PREFIX));
				assertTrue(message.contains("original payload"));
				assertNull(event.getThrown());
			}
		}
	}

	@Test
	void importantScalarEventsAndRepeatedDomainApprovalsEmitWithoutAnyThrowable() {
		try (Capture capture = new Capture(Level.ALL)) {
			AtomicInteger reads = new AtomicInteger();
			Diagnostics.event(Level.ERROR, "capture", "provider", "invalid-target", () -> "position=(1,64,-2)");
			Diagnostics.event(Level.ERROR, "capture", "provider", "invalid-target", () -> "unused=" + reads.incrementAndGet());
			for (int attempt = 0; attempt < 2; attempt++) {
				Diagnostics.domainApprovedEvent(Level.ERROR, "capture", "provider", "invalid-target",
					() -> "position=(1,64,-2)");
				Diagnostics.domainApprovedFailure(Level.WARN, "refresh", "provider", () -> "approved",
					failure("original failure", null));
			}
			assertEquals(0, reads.get());
			assertEquals(5, capture.events.size());
			for (LogEvent event : capture.events) {
				String message = event.getMessage().getFormattedMessage();
				assertNull(event.getThrown());
				assertTrue(message.startsWith(Diagnostics.PREFIX));
				assertFalse(message.contains("null throwable"));
				if (event.getLevel() == Level.ERROR) {
					assertTrue(message.contains("operation=capture scope=provider reason=invalid-target"));
					assertTrue(message.contains("position=(1,64,-2)"));
					assertFalse(message.contains("exception:"));
				}
			}
		}
	}

	@Test
	void prefixedFailureAndScalarEventsStayInsideTheTotalBudgetWithStableHeaderAndEscapedContext() {
		try (Capture capture = new Capture(Level.ALL)) {
			@Nullable Throwable chain = null;
			for (int depth = 0; depth < 14; depth++) {
				chain = failure("payload=" + "x".repeat(1000), chain);
			}
			String huge = "provider\n" + "y".repeat(20_000);
			Diagnostics.error("read", "provider", () -> huge, chain);
			Diagnostics.event(Level.ERROR, huge, huge, huge, () -> huge);
			Diagnostics.domainApprovedFailure(Level.ERROR, "read", "provider", () -> huge, chain);
			Diagnostics.domainApprovedEvent(Level.ERROR, huge, huge, huge, () -> huge);
			assertEquals(4, capture.events.size());
			for (LogEvent event : capture.events) {
				String message = event.getMessage().getFormattedMessage();
				assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS);
				assertTrue(message.startsWith(Diagnostics.PREFIX + "operation="));
				assertTrue(message.contains("scope="));
				assertTrue(message.contains("provider\\n"));
				assertTrue(message.contains("truncated"));
				assertNull(event.getThrown());
				if (message.contains("exception:")) {
					assertTrue(message.contains("provider.Inventory.read (Inventory.java:17)"));
				}
			}
		}
	}

	@Test
	void disabledPublicEventAndDomainApprovedEntrypointsRemainLazy() {
		try (Capture capture = new Capture(Level.OFF)) {
			for (Level level : List.of(Level.DEBUG, Level.WARN, Level.ERROR)) {
				Diagnostics.event(level, "read", "provider", "missing", () -> {
					throw new AssertionError("disabled event context");
				});
				Diagnostics.domainApprovedEvent(level, "read", "provider", "missing", () -> {
					throw new AssertionError("disabled approved context");
				});
				Diagnostics.domainApprovedFailure(level, "read", "provider", () -> {
					throw new AssertionError("disabled approved context");
				}, new UnreadableFailure());
			}
			assertTrue(capture.events.isEmpty());
		}
	}

	@Test
	void publicDiagnosticsInitializeAndRunWithoutGlobalOrPlatformServices() throws Exception {
		try (Capture capture = new Capture(Level.ALL);
			PlatformFreeLoader loader = new PlatformFreeLoader()) {
			Class<?> diagnostics = loader.loadClass(Diagnostics.class.getName());
			diagnostics.getMethod("event", Level.class, String.class, String.class, String.class,
				java.util.function.Supplier.class).invoke(null, Level.WARN, "read", "provider", "missing",
				(java.util.function.Supplier<String>) () -> "without platform services");
			assertEquals(1, capture.events.size());
			assertTrue(capture.events.getFirst().getMessage().getFormattedMessage().contains("without platform services"));
		}
	}

	private static RuntimeException failure(String message, @Nullable Throwable cause) {
		RuntimeException failure = new RuntimeException(message, cause);
		failure.setStackTrace(new StackTraceElement @Unmodifiable [] {
			new StackTraceElement("provider.Inventory", "read", "Inventory.java", 17),
			new StackTraceElement("provider." + "y".repeat(1400), "caller", "Caller.java", 18)
		});
		return failure;
	}

	/** A fresh copy of the production classes, with platform initialization unavailable. */
	@NotNullByDefault
	private static final class PlatformFreeLoader extends URLClassLoader {
		private PlatformFreeLoader() {
			super(new URL @Unmodifiable [] { Diagnostics.class.getProtectionDomain().getCodeSource().getLocation() },
				Diagnostics.class.getClassLoader());
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.equals("nx.pingwheel.common.Global") || name.startsWith("nx.pingwheel.common.platform.")) {
				throw new ClassNotFoundException("Platform services are intentionally unavailable: " + name);
			}
			if (!name.startsWith("nx.pingwheel.common.diagnostics.")
				&& !name.startsWith("nx.pingwheel.common.util.SafeExceptionReport")) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				@Nullable Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					loaded = findClass(name);
				}
				if (resolve) {
					resolveClass(loaded);
				}
				return loaded;
			}
		}
	}

	@NotNullByDefault
	private static final class UnreadableFailure extends RuntimeException {
		@Override
		public String getMessage() {
			throw new AssertionError("disabled message accessor");
		}

		@Override
		public StackTraceElement[] getStackTrace() {
			throw new AssertionError("disabled stack accessor");
		}
	}

	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events = new ArrayList<>();

		private RecordingAppender() {
			super("unified-diagnostics-test", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}
	}

	@NotNullByDefault
	private static final class Capture implements AutoCloseable {
		private final Logger logger = (Logger) Diagnostics.logger();
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final @Nullable LoggerConfig previousConfig = configuration.getLoggers().get(logger.getName());
		private final RecordingAppender appender = new RecordingAppender();
		private final List<LogEvent> events = appender.events;
		private final RateLimitedDiagnosticsTestScope diagnostics;

		private Capture(Level level) {
			appender.start();
			LoggerConfig captureConfig = new LoggerConfig(logger.getName(), level, false);
			captureConfig.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(logger.getName());
			configuration.addLogger(logger.getName(), captureConfig);
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
