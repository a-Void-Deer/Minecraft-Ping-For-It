package nx.pingwheel.common.resolve;

import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A tiny, injectable debug logger used only at the resolver orchestration
 * boundary.
 *
 * <p>The pure domain values, {@link nx.pingwheel.common.registry.OptionalRegistryRef}-style values, and
 * matchers stay logger-free; only the resolver emits debug output. Tests use
 * {@link #noop()} or an in-memory recording implementation, so the game client
 * logger is never initialized in tests unless {@link #global(DiagnosticLogger)}
 * is explicitly requested.
 */
@FunctionalInterface
public interface TargetResolutionLogger {

	/**
	 * Emits a debug message with {@code {}} placeholder arguments.
	 */
	void debug(String message, @Nullable Object @Unmodifiable ... args);

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
	 * A logger backed by an explicitly selected diagnostic side.
	 *
	 * <p>The caller must choose the logical side; physical distribution and
	 * callback thread are not reliable indicators of diagnostic ownership.
	 */
	static TargetResolutionLogger global(DiagnosticLogger diagnostics) {
		return (message, args) -> diagnostics.atDebug().site("target-resolution").debug(() -> format(message, args));
	}

	/** @deprecated Callers must select CLIENT or SERVER explicitly. */
	@Deprecated
	static TargetResolutionLogger global() {
		return noop();
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
