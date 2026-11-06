package nx.pingwheel.common.diagnostics;

import nx.pingwheel.common.util.SafeExceptionReport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Shared, platform-independent diagnostics. Production callers select an
 * explicit logical side and supply a channel only when the domain needs admission.
 */
@NotNullByDefault
public final class Diagnostics {

	public static final String PREFIX = "[PingForIt] ";
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
}
