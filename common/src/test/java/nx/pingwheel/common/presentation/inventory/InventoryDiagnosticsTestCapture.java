package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.diagnostics.RateLimitedDiagnosticsTestScope;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** Scoped real backend capture shared by the inventory failure-path fixtures. */
@NotNullByDefault
public final class InventoryDiagnosticsTestCapture implements AutoCloseable {

	private final Logger logger = (Logger) LogManager.getLogger("pingforit");
	private final org.apache.logging.log4j.core.config.Configuration configuration = logger.getContext().getConfiguration();
	private final @Nullable LoggerConfig previous = configuration.getLoggers().get(logger.getName());
	private final List<LogEvent> events = new ArrayList<>();
	private final AbstractAppender appender = new AbstractAppender("inventory-failure-paths", null, null, true, Property.EMPTY_ARRAY) {
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	};
	private final RateLimitedDiagnosticsTestScope diagnostics;

	public InventoryDiagnosticsTestCapture() { this(Level.WARN); }
	public InventoryDiagnosticsTestCapture(Level level) {
		appender.start();
		var config = new LoggerConfig(logger.getName(), level, false);
		config.addAppender(appender, Level.ALL, null);
		configuration.removeLogger(logger.getName()); configuration.addLogger(logger.getName(), config);
		logger.getContext().updateLoggers(); diagnostics = RateLimitedDiagnosticsTestScope.install();
	}

	public @Unmodifiable List<LogEvent> events() { return List.copyOf(events); }
	public String messages() { return events.stream().map(event -> event.getMessage().getFormattedMessage()).collect(java.util.stream.Collectors.joining("\n")); }

	@Override public void close() {
		diagnostics.close(); configuration.removeLogger(logger.getName());
		if (previous != null) configuration.addLogger(logger.getName(), previous);
		logger.getContext().updateLoggers(); appender.stop();
	}
}
