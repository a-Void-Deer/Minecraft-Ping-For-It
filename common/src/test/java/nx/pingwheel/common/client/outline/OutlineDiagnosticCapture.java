package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.integration.IntegrationDiagnosticCapture;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.List;

@NotNullByDefault
final class OutlineDiagnosticCapture implements AutoCloseable {
	private final IntegrationDiagnosticCapture scope;
	final @UnmodifiableView List<LogEvent> events;

	OutlineDiagnosticCapture(Level level) {
		scope = new IntegrationDiagnosticCapture(level);
		events = scope.events();
	}

	void level(Level level) { scope.level(level); }

	@Override public void close() { scope.close(); }
}
