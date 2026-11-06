package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class Log4jDiagnosticBackendTest {
	@Test
	void installedChildrenAreNotReadyUntilTheRequestingLoggerHasPublishedThem() throws Exception {
		try (PausedPublicationContext context = new PausedPublicationContext()) {
			DefaultConfiguration configuration = new DefaultConfiguration();
			configuration.getRootLogger().setLevel(Level.ERROR);
			context.start(configuration);
			var server = context.getLogger("pingforit.server");
			assertFalse(server.isWarnEnabled());
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
			context.pause.set(true);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var first = executor.submit(() -> backend.prepare(DiagnosticSide.CLIENT));
				try {
					assertTrue(context.entered.await(5, TimeUnit.SECONDS));
					assertNotNull(configuration.getLoggers().get("pingforit.server"));
					assertFalse(server.isWarnEnabled(), "Child installed, cached logger still inherits root ERROR");
					executor.submit(() -> {
						backend.prepare(DiagnosticSide.SERVER);
						assertSame(configuration.getLoggers().get("pingforit.server"), server.get());
						assertTrue(server.isWarnEnabled());
					}).get(5, TimeUnit.SECONDS);
				} finally {
					context.release.countDown();
				}
				first.get(5, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void concurrentTerminalCannotMistakeSetupInProgressForReadyUnderErrorRoot() throws Exception {
		try (LoggerContext context = new LoggerContext("diagnostic-concurrent-setup")) {
			PausedConfiguration configuration = new PausedConfiguration();
			configuration.getRootLogger().setLevel(Level.ERROR);
			RecordingAppender appender = new RecordingAppender();
			appender.start();
			configuration.getRootLogger().addAppender(appender, Level.ALL, null);
			context.start(configuration);
			context.getLogger("pingforit.client");
			context.getLogger("pingforit.server");
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
			DiagnosticBackend previous = Diagnostics.swapBackendForTesting(backend);
			LoggingProfile profile = Diagnostics.profile(DiagnosticSide.SERVER);
			try (var executor = Executors.newFixedThreadPool(2)) {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.NORMAL);
				configuration.pause.set(true);
				var first = executor.submit(() -> backend.prepare(DiagnosticSide.CLIENT));
				try {
					assertTrue(configuration.entered.await(5, TimeUnit.SECONDS));
					CountDownLatch secondStarted = new CountDownLatch(1);
					var second = executor.submit(() -> {
						secondStarted.countDown();
						Diagnostics.server().warn("normal warning during concurrent setup");
					});
					assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
					assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS),
						"A terminal cannot finish against root ERROR while exact children are not installed");
					configuration.release.countDown();
					second.get(5, TimeUnit.SECONDS);
					first.get(5, TimeUnit.SECONDS);
					assertEquals(1, appender.events.size());
					assertEquals(Level.WARN, appender.events.getFirst().getLevel());
					assertEquals("pingforit.server", appender.events.getFirst().getLoggerName());
					assertEquals(Level.ERROR, configuration.getRootLogger().getLevel());
				} finally {
					configuration.release.countDown();
				}
			} finally {
				Diagnostics.swapBackendForTesting(previous);
				Diagnostics.setProfile(DiagnosticSide.SERVER, profile);
			}
		}
	}

	@Test
	void foreignListenerCanWaitForConcurrentPrepareAndReconfigureWithoutBackendLockInversion() throws Exception {
		try (LoggerContext context = new LoggerContext("diagnostic-listener-concurrency")) {
			DefaultConfiguration first = new DefaultConfiguration();
			first.getRootLogger().setLevel(Level.ERROR);
			context.start(first);
			CountDownLatch listenerEntered = new CountDownLatch(1);
			CountDownLatch releaseListener = new CountDownLatch(1);
			AtomicBoolean blockOnce = new AtomicBoolean(true);
			context.addPropertyChangeListener(event -> {
				if (blockOnce.compareAndSet(true, false)) {
					listenerEntered.countDown();
					await(releaseListener);
				}
			});
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
			try (var executor = Executors.newFixedThreadPool(2)) {
				var publishing = executor.submit(() -> backend.prepare(DiagnosticSide.CLIENT));
				try {
					assertTrue(listenerEntered.await(5, TimeUnit.SECONDS));
					// This must complete while the first publisher is inside a foreign
					// listener. A global setup mutex held across updateLoggers deadlocks.
					executor.submit(() -> {
						backend.prepare(DiagnosticSide.SERVER);
						assertTrue(backend.isEnabled(DiagnosticSide.SERVER, Level.WARN));
						DefaultConfiguration second = new DefaultConfiguration();
						second.getRootLogger().setLevel(Level.FATAL);
						context.setConfiguration(second);
						backend.prepare(DiagnosticSide.SERVER);
						assertSame(second.getLoggers().get("pingforit.server"), context.getLogger("pingforit.server").get());
						assertTrue(backend.isEnabled(DiagnosticSide.SERVER, Level.WARN));
						assertEquals(Level.FATAL, second.getRootLogger().getLevel());
					}).get(5, TimeUnit.SECONDS);
				} finally {
					releaseListener.countDown();
				}
				publishing.get(5, TimeUnit.SECONDS);
				assertSame(context.getConfiguration().getLoggers().get("pingforit.client"), context.getLogger("pingforit.client").get());
			}
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	@NotNullByDefault
	private static final class PausedConfiguration extends DefaultConfiguration {
		private final AtomicBoolean pause = new AtomicBoolean();
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		@Override public synchronized void addLogger(String name, LoggerConfig logger) {
			if (pause.compareAndSet(true, false)) {
				entered.countDown();
				await(release);
			}
			super.addLogger(name, logger);
		}
	}

	@NotNullByDefault
	private static final class PausedPublicationContext extends LoggerContext {
		private final AtomicBoolean pause = new AtomicBoolean();
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		private PausedPublicationContext() { super("diagnostic-paused-publication"); }

		@Override public void updateLoggers() {
			if (pause.compareAndSet(true, false)) {
				entered.countDown();
				await(release);
			}
			super.updateLoggers();
		}
	}

	@Test
	void exactOwnedLoggersRemainDebugAcrossReconfigureWithoutChangingRootOtherModOrAppenderPolicies() {
		try (LoggerContext context = new LoggerContext("diagnostic-backend-test")) {
			DefaultConfiguration first = new DefaultConfiguration();
			first.getRootLogger().setLevel(Level.ERROR);
			LoggerConfig other = new LoggerConfig("othermod", Level.WARN, false);
			first.addLogger("othermod", other);
			LoggerConfig existing = new LoggerConfig("pingforit.client", Level.INFO, false);
			RecordingAppender appender = new RecordingAppender();
			appender.start();
			existing.addAppender(appender, Level.WARN, null);
			first.addLogger(existing.getName(), existing);
			context.start(first);
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
			backend.prepare(DiagnosticSide.CLIENT);
			assertEquals(Level.ERROR, first.getRootLogger().getLevel());
			assertSame(other, first.getLoggers().get("othermod"));
			assertEquals(Level.WARN, other.getLevel());
			assertSame(existing, first.getLoggers().get("pingforit.client"));
			assertFalse(existing.isAdditive());
			assertSame(appender, existing.getAppenders().get(appender.getName()));
			backend.log(DiagnosticSide.CLIENT, Level.DEBUG, "filtered-by-existing-appender-threshold");
			backend.log(DiagnosticSide.CLIENT, Level.WARN, "permitted");
			assertEquals(1, appender.events.size());
			for (DiagnosticSide side : DiagnosticSide.values()) {
				assertEquals(Level.DEBUG, first.getLoggers().get(Log4jDiagnosticBackend.name(side)).getExplicitLevel());
				assertTrue(backend.isEnabled(side, Level.DEBUG));
			}
			DefaultConfiguration second = new DefaultConfiguration();
			second.getRootLogger().setLevel(Level.FATAL);
			AtomicInteger changes = new AtomicInteger();
			context.addPropertyChangeListener(event -> changes.incrementAndGet());
			context.setConfiguration(second);
			for (DiagnosticSide side : DiagnosticSide.values()) {
				assertEquals(Level.DEBUG, second.getLoggers().get(Log4jDiagnosticBackend.name(side)).getExplicitLevel());
			}
			assertEquals(Level.FATAL, second.getRootLogger().getLevel());
			assertTrue(changes.get() < 10, "Listener updateLoggers recursion terminates");
			int before = changes.get();
			backend.prepare(DiagnosticSide.CLIENT);
			backend.prepare(DiagnosticSide.SERVER);
			assertEquals(before, changes.get(), "Repeated setup is idempotent");
		}
	}

	@Test
	void initialLoggerLookupFailureCanRecoverWithoutPoisoningClassInitialization() {
		try (LoggerContext context = new LoggerContext("diagnostic-recovery-test")) {
			context.start(new DefaultConfiguration());
			AtomicBoolean broken = new AtomicBoolean(true);
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(name -> {
				if (broken.getAndSet(false)) throw new IllegalStateException("initial lookup");
				return context.getLogger(name);
			});
			assertThrows(IllegalStateException.class, () -> backend.prepare(DiagnosticSide.SERVER));
			assertDoesNotThrow(() -> backend.prepare(DiagnosticSide.SERVER));
			assertTrue(backend.isEnabled(DiagnosticSide.SERVER, Level.DEBUG));
		}
	}

	@Test
	void realAppenderReceivesCorrectSideSeverityOneBoundedStringAndNoAttachedThrowable() {
		try (LoggerContext context = new LoggerContext("diagnostic-message-test")) {
			DefaultConfiguration configuration = new DefaultConfiguration();
			RecordingAppender appender = new RecordingAppender();
			appender.start();
			for (DiagnosticSide side : DiagnosticSide.values()) {
				LoggerConfig exact = new LoggerConfig(Log4jDiagnosticBackend.name(side), Level.DEBUG, false);
				exact.addAppender(appender, Level.ALL, null);
				configuration.addLogger(exact.getName(), exact);
			}
			context.start(configuration);
			DiagnosticBackend previous = Diagnostics.swapBackendForTesting(new Log4jDiagnosticBackend(context::getLogger));
			LoggingProfile profile = Diagnostics.profile(DiagnosticSide.SERVER);
			try {
				Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
				Diagnostics.server().warn("provider-read", "fallback", new RuntimeException("original message"), () -> "permitted context");
				Diagnostics.server().atDetailed().debug(() -> "large=" + "x".repeat(30_000));
				assertEquals(2, appender.events.size());
				assertEquals(List.of(Level.WARN, Level.DEBUG), appender.events.stream().map(LogEvent::getLevel).toList());
				assertTrue(appender.events.getFirst().getMessage().getFormattedMessage().contains("original message"));
				for (LogEvent event : appender.events) {
					assertEquals("pingforit.server", event.getLoggerName());
					assertNull(event.getThrown());
					assertTrue(event.getMessage().getFormattedMessage().startsWith(Diagnostics.PREFIX));
					assertTrue(event.getMessage().getFormattedMessage().length() <= SafeExceptionReport.MAX_CHARACTERS);
				}
			} finally {
				Diagnostics.swapBackendForTesting(previous);
				Diagnostics.setProfile(DiagnosticSide.SERVER, profile);
			}
		}
	}

	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events = new ArrayList<>();
		private RecordingAppender() { super("diagnostic-recording", null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}
}
