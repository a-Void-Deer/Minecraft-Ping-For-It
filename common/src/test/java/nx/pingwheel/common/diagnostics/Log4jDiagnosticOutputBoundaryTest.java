package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.filter.LevelMatchFilter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** External output policy remains authoritative even when the owned logger is enabled. */
@NotNullByDefault
class Log4jDiagnosticOutputBoundaryTest {
	@Test
	void ownedDebugReachesRootAppenderDespiteRootInfoButInfoReferenceStillRejectsDebug() {
		try (LoggerContext context = new LoggerContext("diagnostic-reference-boundary")) {
			DefaultConfiguration configuration = isolatedConfiguration();
			LoggerConfig root = configuration.getRootLogger();
			LoggerConfig other = Objects.requireNonNull(configuration.getLoggers().get("othermod"));
			RecordingAppender debugOutput = new RecordingAppender("debug-output");
			RecordingAppender infoOutput = new RecordingAppender("info-output");
			configuration.addAppender(debugOutput);
			configuration.addAppender(infoOutput);
			// LoggerConfig.addAppender creates the same AppenderControl used for
			// configured AppenderRef levels (AbstractConfiguration.doConfigure).
			root.addAppender(debugOutput, Level.DEBUG, null);
			root.addAppender(infoOutput, Level.INFO, null);
			context.start(configuration);
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
			assertFalse(backend.isEnabled(DiagnosticSide.CLIENT, Level.DEBUG));
			backend.prepare(DiagnosticSide.CLIENT);
			assertTrue(backend.isEnabled(DiagnosticSide.CLIENT, Level.DEBUG));
			backend.log(DiagnosticSide.CLIENT, Level.DEBUG, "controlled-debug-record");
			backend.log(DiagnosticSide.CLIENT, Level.INFO, "controlled-info-record");
			// Log4j 2.22.1 LoggerConfig.logParent forwards the admitted event;
			// it does not reapply the parent's logger level. References still gate.
			assertEquals(List.of(Level.DEBUG, Level.INFO), debugOutput.events.stream().map(LogEvent::getLevel).toList());
			assertEquals(List.of(Level.INFO), infoOutput.events.stream().map(LogEvent::getLevel).toList());
			assertTrue(debugOutput.events.stream().allMatch(event -> event.getLoggerName().equals("pingforit.client")));
			assertSame(root, configuration.getRootLogger());
			assertEquals(Level.INFO, root.getLevel());
			assertSame(other, configuration.getLoggers().get("othermod"));
			assertEquals(Level.WARN, other.getLevel());
			assertFalse(context.getLogger("othermod").isInfoEnabled());
			context.getLogger("othermod").warn("controlled-other-record");
			assertEquals("othermod", debugOutput.events.getLast().getLoggerName());
			assertEquals("othermod", infoOutput.events.getLast().getLoggerName());
		}
	}

	@Test
	void ownedLoggerSetupPreservesExternalDenyFilterDespiteEnabledDebugAndWorkingInfoOutput() {
		try (LoggerContext context = new LoggerContext("diagnostic-deny-boundary")) {
			DefaultConfiguration configuration = isolatedConfiguration();
			RecordingAppender output = new RecordingAppender("deny-test-output");
			configuration.addAppender(output);
			configuration.getRootLogger().addAppender(output, Level.ALL, null);
			LoggerConfig owned = new LoggerConfig("pingforit.client", Level.INFO, true);
			LevelMatchFilter denyDebug = LevelMatchFilter.newBuilder().setLevel(Level.DEBUG)
				.setOnMatch(Filter.Result.DENY).setOnMismatch(Filter.Result.NEUTRAL).build();
			owned.addFilter(denyDebug);
			configuration.addLogger(owned.getName(), owned);
			LoggerConfig other = Objects.requireNonNull(configuration.getLoggers().get("othermod"));
			context.start(configuration);
			Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
			backend.prepare(DiagnosticSide.CLIENT);
			assertTrue(backend.isEnabled(DiagnosticSide.CLIENT, Level.DEBUG), "Logger enablement is not delivery acknowledgement");
			backend.log(DiagnosticSide.CLIENT, Level.DEBUG, "controlled-denied-record");
			assertTrue(output.events.isEmpty());
			backend.log(DiagnosticSide.CLIENT, Level.INFO, "controlled-permitted-record");
			assertEquals(List.of(Level.INFO), output.events.stream().map(LogEvent::getLevel).toList());
			assertSame(owned, configuration.getLoggers().get("pingforit.client"));
			assertSame(denyDebug, owned.getFilter());
			assertTrue(owned.isAdditive());
			assertEquals(Level.INFO, configuration.getRootLogger().getLevel());
			assertSame(other, configuration.getLoggers().get("othermod"));
			assertEquals(Level.WARN, other.getLevel());
		}
	}

	@Test
	void realAppenderIgnoreExceptionsControlsPropagationAndOnlyEscapingFailuresBecomeSelfFaults() {
		for (boolean ignoreExceptions : List.of(true, false)) {
			try (LoggerContext context = new LoggerContext("diagnostic-appender-failure-" + ignoreExceptions)) {
				DefaultConfiguration configuration = isolatedConfiguration();
				ThrowingAppender output = new ThrowingAppender(ignoreExceptions);
				configuration.addAppender(output);
				LoggerConfig owned = new LoggerConfig("pingforit.client", Level.INFO, false);
				owned.addAppender(output, Level.ALL, null);
				configuration.addLogger(owned.getName(), owned);
				context.start(configuration);
				Log4jDiagnosticBackend backend = new Log4jDiagnosticBackend(context::getLogger);
				backend.prepare(DiagnosticSide.CLIENT);
				assertTrue(backend.isEnabled(DiagnosticSide.CLIENT, Level.WARN));
				// 2.22.1 AppenderControl + DefaultErrorHandler: true swallows;
				// false wraps an ordinary RuntimeException and propagates it.
				if (ignoreExceptions) {
					assertDoesNotThrow(() -> backend.log(DiagnosticSide.CLIENT, Level.WARN, "controlled-appender-record"));
				} else {
					AppenderLoggingException escaped = assertThrows(AppenderLoggingException.class,
						() -> backend.log(DiagnosticSide.CLIENT, Level.WARN, "controlled-appender-record"));
					assertSame(output.failure, escaped.getCause());
				}
				assertEquals(1, output.attempts);
				ByteArrayOutputStream emergencyOutput = new ByteArrayOutputStream();
				DiagnosticFaultReporter faults = new DiagnosticFaultReporter(new DiagnosticEmergencyWriter(() -> emergencyOutput));
				DiagnosticBackend previousBackend = Diagnostics.swapBackendForTesting(backend);
				DiagnosticFaultReporter previousFaults = Diagnostics.swapFaultsForTesting(faults);
				LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.CLIENT);
				try {
					Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
					assertDoesNotThrow(() -> Diagnostics.client().site("appender-failure-smoke").warn("controlled-appender-record"));
					assertEquals(2, output.attempts);
					assertEquals(ignoreExceptions ? 0 : 1, faults.observations().size());
					if (ignoreExceptions) {
						assertEquals(0, emergencyOutput.size(), "A swallowed appender failure is not observable at the terminal");
					} else {
						assertEquals(DiagnosticFaultReporter.Stage.SINK, faults.observations().getFirst().key().stage());
						assertEquals("appender-failure-smoke", faults.observations().getFirst().key().site());
						assertTrue(emergencyOutput.size() > 0);
					}
				} finally {
					Diagnostics.swapBackendForTesting(previousBackend);
					Diagnostics.swapFaultsForTesting(previousFaults);
					Diagnostics.setProfile(DiagnosticSide.CLIENT, previousProfile);
				}
			}
		}
	}

	private static DefaultConfiguration isolatedConfiguration() {
		DefaultConfiguration configuration = new DefaultConfiguration();
		// Remove only this isolated configuration's default console route. No
		// global configuration, status listener or System.err interception.
		@Unmodifiable List<String> defaultAppenders = List.copyOf(configuration.getAppenders().keySet());
		for (String name : defaultAppenders) {
			configuration.getRootLogger().removeAppender(name);
			configuration.removeAppender(name);
		}
		configuration.getRootLogger().setLevel(Level.INFO);
		configuration.addLogger("othermod", new LoggerConfig("othermod", Level.WARN, true));
		return configuration;
	}

	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events = new ArrayList<>();
		private RecordingAppender(String name) { super(name, null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@NotNullByDefault
	private static final class ThrowingAppender extends AbstractAppender {
		private final IllegalStateException failure = new IllegalStateException("controlled-test-appender-failure");
		private int attempts;
		private ThrowingAppender(boolean ignoreExceptions) {
			super("controlled-throwing-appender", null, null, ignoreExceptions, Property.EMPTY_ARRAY);
		}
		@Override public void append(LogEvent event) {
			attempts++;
			throw failure;
		}
	}
}
