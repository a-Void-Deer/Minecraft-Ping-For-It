package nx.pingwheel.common.resolve;

import nx.pingwheel.common.Global;

/**
 * A tiny, injectable debug logger used only at the resolver orchestration
 * boundary.
 *
 * <p>The pure domain values, {@link OptionalRegistryRef}-style values, and
 * matchers stay logger-free; only the resolver emits debug output. Tests use
 * {@link #noop()} or an in-memory recording implementation, so the game client
 * logger ({@link Global}) is never initialized in tests unless {@link #global()}
 * is explicitly requested.
 */
@FunctionalInterface
public interface TargetResolutionLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, Object... args);

	/**
	 * A logger that discards every message. Suitable for tests and for
	 * configurations that must not touch the game logger.
	 */
	static TargetResolutionLogger noop() {
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
	static TargetResolutionLogger global() {
		return (message, args) -> Global.LOGGER.debug(message, args);
	}
}
