package nx.pingwheel.neoforge.integration.create;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public final class CreateDiagnosticCapture implements AutoCloseable {

	public final List<LogEvent> events = new ArrayList<>();
	private final Logger logger = (Logger) Diagnostics.logger();
	private final Configuration configuration = logger.getContext().getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final @Nullable Filter previousFilter = configuration.getFilter();
	private final RecordingAppender appender = new RecordingAppender(events);

	public CreateDiagnosticCapture() {
		this(Level.WARN);
	}

	CreateDiagnosticCapture(Level level) {
		// The loader's global WARN/ACCEPT filter overrides even a logger set to OFF.
		if (previousFilter != null) {
			configuration.removeFilter(previousFilter);
		}
		appender.start();
		LoggerConfig capture = new LoggerConfig(logger.getName(), level, false);
		capture.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName());
		configuration.addLogger(logger.getName(), capture);
		logger.getContext().updateLoggers();
	}

	@Override
	public void close() {
		configuration.removeLogger(logger.getName());
		if (previous != null) {
			configuration.addLogger(logger.getName(), previous);
		}
		if (previousFilter != null) {
			configuration.addFilter(previousFilter);
		}
		logger.getContext().updateLoggers();
		appender.stop();
	}

	@NotNullByDefault
	private static final class RecordingAppender extends AbstractAppender {
		private final List<LogEvent> events;

		private RecordingAppender(List<LogEvent> events) {
			super("create-diagnostic-test", null, null, true, Property.EMPTY_ARRAY);
			this.events = events;
		}

		@Override
		public void append(LogEvent event) {
			events.add(event.toImmutable());
		}
	}
}
