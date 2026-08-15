package nx.pingwheel.common.marker;

import nx.pingwheel.common.Global;

/**
 * A tiny, injectable debug logger used only at the marker creation/removal
 * orchestration boundary ({@link MarkerCreationService}).
 *
 * <p>The pure marker values ({@link ServerMarker}, {@link ValidatedMarkerTarget},
 * {@link MarkerCreateOutcome}, and the outcome/validation verdicts) stay
 * logger-free; only the service emits debug output, and only with safe fields
 * (target kind, dimension id, ping type ids, marker ids). Custom names, player
 * names, colors, and registry lookups are never logged. Tests use
 * {@link #noop()} or an in-memory recording implementation, so the game client
 * logger ({@link Global}) is never initialized in tests unless {@link #global()}
 * is explicitly requested.
 */
@FunctionalInterface
public interface MarkerCreationLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, Object... args);

	/**
	 * A logger that discards every message. Suitable for tests and for
	 * configurations that must not touch the game logger.
	 */
	static MarkerCreationLogger noop() {
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
	static MarkerCreationLogger global() {
		return (message, args) -> Global.LOGGER.debug(message, args);
	}
}
