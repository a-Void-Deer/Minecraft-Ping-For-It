package nx.pingwheel.common.integration;

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

/** Real shared-backend capture, with independent limiter state for each test. */
@NotNullByDefault
public final class IntegrationDiagnosticCapture implements AutoCloseable {
	private final Logger logger = (Logger) Diagnostics.logger();
	private final LoggerContext context = logger.getContext();
	private final Configuration configuration = context.getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final List<LogEvent> events = new ArrayList<>();
	private final RateLimitedDiagnosticsTestScope scope;
	private final AbstractAppender appender = new AbstractAppender(
		"integration-diagnostics-test", null, null, true, Property.EMPTY_ARRAY) {
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	};

	public IntegrationDiagnosticCapture(Level level) {
		appender.start();
		LoggerConfig config = new LoggerConfig(logger.getName(), level, false);
		config.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), config);
		context.updateLoggers();
		scope = RateLimitedDiagnosticsTestScope.install();
	}

	public @Unmodifiable List<LogEvent> events() { return List.copyOf(events); }

	@Override public void close() {
		scope.close();
		configuration.removeLogger(logger.getName());
		if (previous != null) configuration.addLogger(logger.getName(), previous);
		context.updateLoggers();
		appender.stop();
	}
}
