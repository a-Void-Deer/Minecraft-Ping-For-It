package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
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
 * Shared, platform-independent diagnostics. Production callers select an
 * explicit logical side. Legacy static routes have no safe implicit side and
 * are inert unless an old reporter is explicitly installed by a test scope.
 */
@NotNullByDefault
public final class Diagnostics {

	public static final String PREFIX = "[PingForIt] ";
	private static volatile @Nullable DiagnosticReporter production;
	private static volatile DiagnosticBackend backend = new Log4jDiagnosticBackend();
	private static volatile ExceptionFormatter formatter = SafeExceptionReport::formatWithContext;
	private static volatile DiagnosticFaultReporter faults = new DiagnosticFaultReporter(DiagnosticEmergencyWriter.process());
	private static volatile LoggingProfile clientProfile = LoggingProfile.NORMAL;
	private static volatile LoggingProfile serverProfile = LoggingProfile.NORMAL;
	private static volatile LoggingProfile bootstrapProfile = LoggingProfile.NORMAL;
	private static final DiagnosticLogger CLIENT = new DiagnosticLogger(DiagnosticSide.CLIENT, LoggingProfile.NORMAL, null, "log");
	private static final DiagnosticLogger SERVER = new DiagnosticLogger(DiagnosticSide.SERVER, LoggingProfile.NORMAL, null, "log");
	private static final DiagnosticLogger BOOTSTRAP = new DiagnosticLogger(DiagnosticSide.BOOTSTRAP, LoggingProfile.NORMAL, null, "log");

	private Diagnostics() {}

	public static DiagnosticLogger client() { return CLIENT; }
	public static DiagnosticLogger server() { return SERVER; }
	public static DiagnosticLogger bootstrap() { return BOOTSTRAP; }

	public static void setProfile(DiagnosticSide side, LoggingProfile profile) {
		Objects.requireNonNull(profile, "profile");
		switch (side) {
			case CLIENT -> clientProfile = profile;
			case SERVER -> serverProfile = profile;
			case BOOTSTRAP -> bootstrapProfile = profile;
		}
	}

	public static LoggingProfile profile(DiagnosticSide side) {
		return switch (side) {
			case CLIENT -> clientProfile;
			case SERVER -> serverProfile;
			case BOOTSTRAP -> bootstrapProfile;
		};
	}

	public static DiagnosticFaultReporter faults() { return faults; }
	static DiagnosticBackend backend() { return backend; }
	static ExceptionFormatter formatter() { return formatter; }

	static ExceptionFormatter swapFormatterForTesting(ExceptionFormatter replacement) {
		ExceptionFormatter previous = formatter;
		formatter = Objects.requireNonNull(replacement, "replacement");
		return previous;
	}

	@NotNullByDefault
	@FunctionalInterface
	interface ExceptionFormatter {
		String format(@Nullable String context, Throwable failure, SafeExceptionReport.FailureObserver observer);
	}

	static DiagnosticBackend swapBackendForTesting(DiagnosticBackend replacement) {
		DiagnosticBackend previous = backend;
		backend = Objects.requireNonNull(replacement, "replacement");
		return previous;
	}

	static DiagnosticFaultReporter swapFaultsForTesting(DiagnosticFaultReporter replacement) {
		DiagnosticFaultReporter previous = faults;
		faults = Objects.requireNonNull(replacement, "replacement");
		return previous;
	}

	/** Migration/test compatibility only. Production reports use an explicit side. */
	@Deprecated
	public static Logger logger() {
		return LogManager.getLogger("pingforit", new PrefixedMessageFactory());
	}

	/** Legacy test adapter. No implicit production side; migrate to client/server/bootstrap. */
	@Deprecated
	public static void debug(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		@Nullable DiagnosticReporter reporter = production;
		if (reporter != null) reporter.report(Level.DEBUG, operation, scope, context, failure);
	}

	/** @see #debug */
	@Deprecated
	public static void warn(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		@Nullable DiagnosticReporter reporter = production;
		if (reporter != null) reporter.report(Level.WARN, operation, scope, context, failure);
	}

	/** @see #debug */
	@Deprecated
	public static void error(@Nullable String operation, @Nullable String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		@Nullable DiagnosticReporter reporter = production;
		if (reporter != null) reporter.report(Level.ERROR, operation, scope, context, failure);
	}

	/** Legacy test adapter; see {@link #debug}. */
	@Deprecated
	public static void event(Level level, String operation, String scope, String reason,
		@Nullable Supplier<@Nullable String> context) {
		@Nullable DiagnosticReporter reporter = production;
		if (reporter != null) reporter.event(level, operation, scope, reason, context);
	}

	/** Legacy test adapter; explicit-side loggers have no default general window. */
	@Deprecated
	public static void domainApprovedFailure(Level level, String operation, String scope,
		@Nullable Supplier<@Nullable String> context, @Nullable Throwable failure) {
		@Nullable DiagnosticReporter reporter = production;
		if (reporter != null) reporter.domainApprovedFailure(level, operation, scope, context, failure);
	}

	/** As {@link #domainApprovedFailure}, but for a scalar-only event with a stable reason. */
	@Deprecated
	public static void domainApprovedEvent(Level level, String operation, String scope, String reason,
		@Nullable Supplier<@Nullable String> context) {
		@Nullable DiagnosticReporter reporter = production;
		if (reporter != null) reporter.domainApprovedEvent(level, operation, scope, reason, context);
	}

	/** Test-only scoped state replacement; never a production reset API. */
	static @Nullable DiagnosticReporter swapProductionForTesting(@Nullable DiagnosticReporter replacement) {
		@Nullable DiagnosticReporter previous = production;
		production = replacement;
		return previous;
	}

	@NotNullByDefault
	private static final class PrefixedMessageFactory extends FormattedMessageFactory {
		@Override
		public Message newMessage(@Nullable String message) {
			return super.newMessage(PREFIX + message);
		}
	}

}
