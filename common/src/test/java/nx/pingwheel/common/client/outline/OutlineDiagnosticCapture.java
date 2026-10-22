package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.RateLimitedDiagnosticsTestScope;
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

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
final class OutlineDiagnosticCapture implements AutoCloseable {

	final List<LogEvent> events = new ArrayList<>();
	private final Logger logger = (Logger) Diagnostics.logger();
	private final LoggerContext context = logger.getContext();
	private final Configuration configuration = context.getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final RecordingAppender appender = new RecordingAppender(events);
	private final RateLimitedDiagnosticsTestScope scope;

	OutlineDiagnosticCapture(Level level) {
		appender.start();
		LoggerConfig capture = new LoggerConfig(logger.getName(), level, false);
		capture.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), capture);
		context.updateLoggers();
		scope = RateLimitedDiagnosticsTestScope.install();
	}

	void level(Level level) {
		configuration.getLoggerConfig(logger.getName()).setLevel(level);
		context.updateLoggers();
	}

	@Override
	public void close() {
		scope.close();
		configuration.removeLogger(logger.getName());
		if (previous != null) {
			configuration.addLogger(logger.getName(), previous);
		}
		context.updateLoggers();
		appender.stop();
	}

	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events;

		private RecordingAppender(List<LogEvent> events) {
			super("outline-diagnostics-test", null, null, true, Property.EMPTY_ARRAY);
			this.events = events;
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}
	}
}
