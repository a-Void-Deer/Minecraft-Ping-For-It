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

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class Log4jDiagnosticBackendTest {
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
