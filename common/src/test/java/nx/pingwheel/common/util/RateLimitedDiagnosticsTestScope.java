package nx.pingwheel.common.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
public final class RateLimitedDiagnosticsTestScope implements AutoCloseable {

	private final RateLimitedDiagnostics previous;

	private RateLimitedDiagnosticsTestScope(RateLimitedDiagnostics previous) {
		this.previous = previous;
	}

	/** Installs fresh limiter state for the current test; close restores the previous state. */
	public static RateLimitedDiagnosticsTestScope install() {
		Logger logger = LogManager.getLogger("pingforit");
		RateLimitedDiagnostics isolated = new RateLimitedDiagnostics(System::nanoTime,
			new RateLimitedDiagnostics.Sink() {
				@Override
				public boolean isEnabled(Level level) {
					return logger.isEnabled(level);
				}

				@Override
				public void log(Level level, String message) {
					logger.log(level, message);
				}
			});
		return new RateLimitedDiagnosticsTestScope(RateLimitedDiagnostics.swapProductionForTesting(isolated));
	}

	@Override
	public void close() {
		RateLimitedDiagnostics.swapProductionForTesting(previous);
	}
}
