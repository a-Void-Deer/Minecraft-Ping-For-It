package nx.pingwheel.common.presentation;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.RateLimitedDiagnosticsTestScope;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Real backend capture shared by the presentation call-site tests. */
@NotNullByDefault
public final class PresentationDiagnosticCapture implements AutoCloseable {
	private final Logger logger = (Logger) Diagnostics.logger();
	private final Configuration configuration = logger.getContext().getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final RecordingAppender appender = new RecordingAppender();
	private final RateLimitedDiagnosticsTestScope diagnostics;

	public PresentationDiagnosticCapture() { this(Level.WARN); }
	public PresentationDiagnosticCapture(Level level) {
		appender.start();
		var config = new LoggerConfig(logger.getName(), level, false);
		config.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), config);
		logger.getContext().updateLoggers();
		diagnostics = RateLimitedDiagnosticsTestScope.install();
	}
	public @Unmodifiable List<LogEvent> events() { return List.copyOf(appender.events); }
	public @Unmodifiable List<String> messages() {
		return appender.events.stream().map(event -> event.getMessage().getFormattedMessage()).toList();
	}
	@Override public void close() {
		diagnostics.close();
		configuration.removeLogger(logger.getName());
		if (previous != null) configuration.addLogger(logger.getName(), previous);
		logger.getContext().updateLoggers();
		appender.stop();
	}
	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events = new ArrayList<>();
		private RecordingAppender() { super("presentation-diagnostics-test", null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}
}
