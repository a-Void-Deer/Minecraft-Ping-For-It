package nx.pingwheel.common.diagnostics;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.FormattedMessageFactory;
import org.apache.logging.log4j.message.Message;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared, platform-independent diagnostics. No entry point initializes platform
 * services. Only DEBUG, WARN and ERROR are diagnostic levels; other levels are
 * ignored. Ordinary status messages may use {@link #logger()} directly.
 */
@NotNullByDefault
public final class Diagnostics {

	public static final String PREFIX = "[PingForIt] ";
	private static volatile DiagnosticReporter production = new DiagnosticReporter(System::nanoTime, new Log4jSink());

	private Diagnostics() {}

	/** The same prefixed Log4j backend used by Global's ordinary status logger. */
	public static Logger logger() {
		return LoggerHolder.LOGGER;
	}

	/**
	 * Rate-limited failure reports. Operation and scope are stable stage and
	 * provider/target-class/registry names, never request or round identifiers.
	 * Context supplies detached scalar detail and is evaluated at most once,
	 * only after level gating and admission. Each level has its own shared window.
	 */
	public static void debug(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		production.report(Level.DEBUG, operation, scope, context, failure);
	}

	/** @see #debug */
	public static void warn(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		production.report(Level.WARN, operation, scope, context, failure);
	}

	/** @see #debug */
	public static void error(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		production.report(Level.ERROR, operation, scope, context, failure);
	}

	/**
	 * A rate-limited event without an exception. The stable reason participates in
	 * the key; changing detail does not. No synthetic or null throwable is rendered.
	 * This also supports restricted capture sites that may disclose only scalars.
	 */
	public static void event(Level level, String operation, String scope, String reason,
		@Nullable Supplier<@Nullable String> context) {
		production.event(level, operation, scope, reason, context);
	}

	/**
	 * Emits a failure already approved by the caller's domain frequency gate.
	 * There is deliberately NO additional ten-second window or capacity admission.
	 * Level gating, lazy context, bounded formatting and sink isolation still apply.
	 * Do not use this to bypass frequency control at an ungated retry site.
	 */
	public static void domainApprovedFailure(Level level, String operation, String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		production.domainApprovedFailure(level, operation, scope, context, failure);
	}

	/** As {@link #domainApprovedFailure}, but for a scalar-only event with a stable reason. */
	public static void domainApprovedEvent(Level level, String operation, String scope, String reason,
		@Nullable Supplier<@Nullable String> context) {
		production.domainApprovedEvent(level, operation, scope, reason, context);
	}

	/** Test-only scoped state replacement; never a production reset API. */
	static DiagnosticReporter swapProductionForTesting(DiagnosticReporter replacement) {
		DiagnosticReporter previous = production;
		production = Objects.requireNonNull(replacement, "replacement");
		return previous;
	}

	@NotNullByDefault
	private static final class PrefixedMessageFactory extends FormattedMessageFactory {
		@Override
		public Message newMessage(@Nullable String message) {
			return super.newMessage(PREFIX + message);
		}
	}

	@NotNullByDefault
	private static final class Log4jSink implements DiagnosticReporter.Sink {
		@Override
		public boolean isEnabled(Level level) {
			return logger().isEnabled(level);
		}

		@Override
		public void log(Level level, String message) {
			logger().log(level, message);
		}
	}

	@NotNullByDefault
	private static final class LoggerHolder {
		// Initialization occurs inside the reporter's guarded sink boundary, not
		// while initializing Diagnostics. Logger initialization failures stay local.
		private static final Logger LOGGER = LogManager.getLogger("pingforit", new PrefixedMessageFactory());
	}
}
