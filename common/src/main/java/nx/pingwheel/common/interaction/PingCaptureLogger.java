package nx.pingwheel.common.interaction;

import nx.pingwheel.common.Global;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.message.ParameterizedMessage;

/**
 * A tiny, injectable logger used only at the capture orchestration
 * boundary.
 *
 * <p>Only safe fields are ever logged by callers of this logger: token
 * sequence, {@link nx.pingwheel.common.domain.TargetKind}, dimension id, and
 * resolved target type id. UUIDs, block/entity registry ids, positions, names,
 * item data, and chat are never logged. Tests use {@link #noop()} or an
 * in-memory recording implementation, so the game logger ({@link Global}) is
 * never initialized in tests unless {@link #global()} is explicitly requested.
 */
@FunctionalInterface
public interface PingCaptureLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, Object... args);

	/**
	 * Scalar-only failure port: never pass a Throwable or target identity as an
	 * argument. The default keeps existing injectable debug recorders compatible;
	 * the production adapter emits a controlled WARN event.
	 */
	default void warnRestricted(String operation, String reason, String message, Object... args) {
		debug(message, args);
	}

	/**
	 * A logger that discards every message. Suitable for tests and for
	 * configurations that must not touch the game logger.
	 */
	static PingCaptureLogger noop() {
		return (message, args) -> {
			// intentionally empty
		};
	}

	/**
	 * A logger backed by the mod's global Log4j logger.
	 *
	 * <p>Backend lookup is deferred until emission. Restricted failures use the
	 * shared event limiter without attaching an exception or rendering its details.
	 */
	static PingCaptureLogger global() {
		return new PingCaptureLogger() {
			@Override public void debug(String message, Object... args) {
				Diagnostics.logger().debug(message, args);
			}
			@Override public void warnRestricted(String operation, String reason, String message, Object... args) {
				Diagnostics.event(Level.WARN, operation, "capture", reason,
					() -> ParameterizedMessage.format(message, args));
			}
		};
	}
}
