package nx.pingwheel.common.interaction;

import nx.pingwheel.common.Global;

/**
 * A tiny, injectable debug logger used only at the capture orchestration
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
	 * <p>The reference to {@link Global#LOGGER} is deferred into the returned
	 * lambda body, so calling this factory does not initialize {@link Global};
	 * only the first {@code debug(...)} invocation does.
	 */
	static PingCaptureLogger global() {
		return (message, args) -> Global.LOGGER.debug(message, args);
	}
}
