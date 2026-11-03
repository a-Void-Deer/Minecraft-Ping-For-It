package nx.pingwheel.common.interaction.state;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.util.SafeExceptionReport;

/**
 * A tiny, injectable debug logger for the interaction state machine.
 *
 * <p>Only safe fields are ever logged by the state machine: token sequence,
 * ping type id, target kind, {@link TargetGoneReason}, wheel ping type count,
 * cancellation candidate count, and marker id value. UUIDs, world positions,
 * dimension/registry ids, names, item data, and chat text are never logged.
 * The M6.5 INFO select line additionally carries the client GUI scale factor,
 * the cursor position in GUI-scaled screen coordinates, and the committed
 * sector index - presentation/viewport data only, never a world position or
 * any player identity. Tests use {@link #noop()} or an in-memory recording
 * implementation, so the game logger ({@link Global}) is never initialized in
 * tests unless {@link #global()} is explicitly requested.
 *
 * <p>{@link #info(String)} is the separate INFO seam used by the wheel
 * lifecycle lines (open / select / timeout / cancel) that a normal launch
 * needs to see without enabling debug logging. It still carries only the
 * same safe fields, and it defaults to a no-op so every existing test logger
 * keeps compiling and staying silent.
 */
@FunctionalInterface
public interface PingInteractionLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, Object... args);

	/**
	 * Emits one already-formatted INFO line.
	 *
	 * <p>Implementations must receive a pre-formatted message and hand it to
	 * the single-argument logger call so the mod's {@code [PingForIt] } prefix
	 * is applied. Only the same safe fields the debug seam allows may appear.
	 * The default is a no-op so test loggers stay silent.
	 */
	default void info(String message) {
		// intentionally empty: debug-only loggers do not emit INFO lines
	}

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
	 * A logger backed by the mod's global Log4j logger.
	 *
	 * <p>The reference to {@link Global#LOGGER} is deferred into the returned
	 * lambda body, so calling this factory does not initialize {@link Global};
	 * only the first {@code debug(...)} invocation does.
	 */
	static PingInteractionLogger global() {
		return new PingInteractionLogger() {
			@Override
			public void debug(String message, Object... args) {
				Global.LOGGER.debug(message, args);
			}

			@Override
			public void info(String message) {
				// Single-argument overload: this is the only form that runs
				// FormattedMessageFactory#newMessage(String) and therefore
				// carries the "[PingForIt] " prefix.
				Global.LOGGER.info(message);
			}

			@Override
			public void debugException(String constantContext, Throwable throwable) {
				Global.debugException(constantContext, throwable);
			}
		};
	}
}
