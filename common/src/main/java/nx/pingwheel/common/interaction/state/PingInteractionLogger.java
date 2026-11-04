package nx.pingwheel.common.interaction.state;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A tiny, injectable debug logger for the interaction state machine.
 *
 * <p>Only safe fields are ever logged by the state machine: token sequence,
 * ping type id, target kind, {@link TargetGoneReason}, wheel ping type count,
 * cancellation candidate count, and marker id value. UUIDs, positions,
 * dimension/registry ids, names, item data, and chat text are never logged.
 * Tests use {@link #noop()} or an in-memory recording implementation, so the
	 * game logger is never initialized in tests unless
 * {@link #global()} is explicitly requested.
 */
@FunctionalInterface
public interface PingInteractionLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, @Nullable Object @Unmodifiable ... args);

	/**
	 * Emits a complete bounded exception report without passing the throwable
	 * to the underlying logger.
	 */
	default void debugException(String constantContext, Throwable throwable) {
		debug(SafeExceptionReport.formatWithContext(constantContext, throwable));
	}

	/**
	 * Records one client-side create action dropped by the send limiter.
	 * Only the request id and policy values are permitted here; the callback
	 * must never be used for target, player, identity, position, or name data.
	 */
	default void debugCreateThrottled(long requestId, int rateLimit, int msToRegenerate) {
		debug("dispatch create throttled: requestId={} rateLimit={} msToRegenerate={}",
			requestId, rateLimit, msToRegenerate);
	}

	/**
	 * A logger that discards every message.
	 */
	static PingInteractionLogger noop() {
		return (message, args) -> {
			// intentionally empty
		};
	}

	/**
	 * A logger backed by the explicit client diagnostic side.
	 *
	 * <p>Details and throwable access are deferred until DEBUG is enabled.
	 */
	static PingInteractionLogger global() {
		return new PingInteractionLogger() {
			@Override
			public void debug(String message, @Nullable Object @Unmodifiable ... args) {
				Diagnostics.client().atDebug().site("interaction/state").debug(() -> format(message, args));
			}

			@Override
			public void debugException(String constantContext, Throwable throwable) {
				Diagnostics.client().atDebug().failure(Level.DEBUG, "interaction/state", "state-failure",
					() -> constantContext, throwable);
			}
		};
	}

	private static String format(String message, @Nullable Object @Unmodifiable [] args) {
		StringBuilder formatted = new StringBuilder(message);
		for (@Nullable Object arg : args) {
			int placeholder = formatted.indexOf("{}");
			if (placeholder < 0) break;
			formatted.replace(placeholder, placeholder + 2, String.valueOf(arg));
		}
		return formatted.toString();
	}
}
