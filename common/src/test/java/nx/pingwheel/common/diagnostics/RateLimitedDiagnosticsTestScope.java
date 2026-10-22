package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;

/**
 * Test-only scope that replaces the shared production diagnostics limiter with
 * a fresh, independently admitted instance and restores the previous instance
 * when closed.
 *
 * <p>Tests that capture the real {@code pingforit} Log4j logger still exercise
 * the public production API; only the limiter state is isolated, so a window or
 * capacity state left by earlier tests (including an already reported capacity
 * summary) can neither suppress this test's reports nor replace them with a
 * summary. The replacement keeps the production clock, bounded capacity,
 * interval and logger sink, and the restored production instance stays shared
 * and bounded.
 *
 * <p>JUnit executes these tests sequentially, so a scoped swap cannot race
 * another test. Nested scopes restore correctly, and tests must close the
 * scope with try-with-resources.
 */
@NotNullByDefault
public final class RateLimitedDiagnosticsTestScope implements AutoCloseable {

	private final DiagnosticReporter previous;

	private RateLimitedDiagnosticsTestScope(DiagnosticReporter previous) {
		this.previous = previous;
	}

	/** Installs fresh limiter state for the current test; close restores the previous state. */
	public static RateLimitedDiagnosticsTestScope install() {
		return new RateLimitedDiagnosticsTestScope(Diagnostics.swapProductionForTesting(
			new DiagnosticReporter(System::nanoTime, new LoggerSink())));
	}

	@Override
	public void close() {
		Diagnostics.swapProductionForTesting(previous);
	}

	@NotNullByDefault
	private static final class LoggerSink implements DiagnosticReporter.Sink {
		@Override
		public boolean isEnabled(Level level) {
			return Diagnostics.logger().isEnabled(level);
		}

		@Override
		public void log(Level level, String message) {
			Diagnostics.logger().log(level, message);
		}
	}
}
