package nx.pingwheel.common.testing;

import java.util.ArrayList;
import java.util.List;
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
import org.jetbrains.annotations.Unmodifiable;

/** Captures the public diagnostics backend with isolated admission state. */
@NotNullByDefault
public final class DiagnosticLogCapture implements AutoCloseable {

	private final Logger logger = (Logger) Diagnostics.logger();
	private final LoggerContext context = logger.getContext();
	private final Configuration configuration = context.getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final List<LogEvent> events = new ArrayList<>();
	private final RateLimitedDiagnosticsTestScope diagnostics;
	private final AbstractAppender appender = new RecordingAppender();

	public DiagnosticLogCapture() {
		appender.start();
		LoggerConfig capture = new LoggerConfig(logger.getName(), Level.ALL, false);
		capture.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), capture);
		context.updateLoggers();
		diagnostics = RateLimitedDiagnosticsTestScope.install();
	}

	public @Unmodifiable List<LogEvent> events() { return List.copyOf(events); }

	public @Unmodifiable List<LogEvent> operation(String operation) {
		return events.stream().filter(event -> event.getMessage().getFormattedMessage().contains(operation)).toList();
	}

	@Override public void close() {
		diagnostics.close();
		configuration.removeLogger(logger.getName());
		if (previous != null) configuration.addLogger(logger.getName(), previous);
		context.updateLoggers();
		appender.stop();
	}

	@NotNullByDefault
	private final class RecordingAppender extends AbstractAppender {
		private RecordingAppender() { super("client-business-diagnostics-test", null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}
}
