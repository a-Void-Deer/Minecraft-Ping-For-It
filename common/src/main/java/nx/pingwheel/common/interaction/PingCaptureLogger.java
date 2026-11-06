package nx.pingwheel.common.interaction;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A tiny, injectable logger used only at the capture orchestration
 * boundary.
 *
 * <p>Callers keep context restricted to token
 * sequence, {@link nx.pingwheel.common.domain.TargetKind}, dimension id, and
 * resolved target type id. UUIDs, block/entity registry ids, positions, names,
 * item data, and chat are never logged. Tests use {@link #noop()} or an
 * in-memory recording implementation, so the game logger is
 * never initialized in tests unless {@link #global()} is explicitly requested.
 * The production adapter applies profile disclosure to context and the original failure.
 */
@FunctionalInterface
public interface PingCaptureLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, @Nullable Object @Unmodifiable ... args);

	/**
	 * Keeps the original cause separate from the restricted scalar context. The
	 * default retains compatibility with scalar-only recording implementations.
	 */
	default void failure(String operation, String reason, Throwable originalFailure, String message,
		@Nullable Object @Unmodifiable ... args) {
		debug(message + " cause={}", appendCause(args, originalFailure));
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
	 * A logger backed by the explicit client diagnostic side.
	 *
	 * <p>Restricted failures retain the original throwable; NORMAL disclosure is
	 * restricted by the shared reporter and higher profiles may reveal bounded detail.
	 */
	static PingCaptureLogger global() {
		return new PingCaptureLogger() {
			@Override public void debug(String message, @Nullable Object @Unmodifiable ... args) {
				Diagnostics.client().atDebug().site("capture/status").debug(() -> format(message, args));
			}
			@Override public void failure(String operation, String reason, Throwable originalFailure, String message,
				@Nullable Object @Unmodifiable ... args) {
				Diagnostics.client().atNormal().failure(Level.WARN,
					operation, reason, () -> format(message, args), originalFailure);
			}
		};
	}

	private static @Nullable Object @Unmodifiable [] appendCause(@Nullable Object @Unmodifiable [] args, Throwable failure) {
		@Nullable Object @Unmodifiable [] values = java.util.Arrays.copyOf(args, args.length + 1);
		values[args.length] = failure.getClass().getSimpleName();
		return values;
	}

	private static String format(String message, @Nullable Object @Unmodifiable [] args) {
		StringBuilder formatted = new StringBuilder(message);
		for (@Nullable Object arg : args) {
			int placeholder = formatted.indexOf("{}");
			if (placeholder < 0) break;
			String value = arg instanceof Throwable failure
				? failure.getClass().getSimpleName() : String.valueOf(arg);
			formatted.replace(placeholder, placeholder + 2, value);
		}
		return formatted.toString();
	}
}
