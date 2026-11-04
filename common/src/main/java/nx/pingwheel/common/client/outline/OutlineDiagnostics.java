package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.DiagnosticFaultReporter.Stage;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded delivery for the outline sources' existing domain warning gates. */
@NotNullByDefault
final class OutlineDiagnostics {

	private OutlineDiagnostics() {}

	static boolean enabled() {
		return Diagnostics.client().atNormal().isEnabled(Level.WARN);
	}

	static void approved(String operation, String scope, Supplier<String> details, @Nullable Throwable failure) {
		if (failure == null) {
			Diagnostics.client().atNormal().event(Level.WARN, operation, "contract-or-source-failure", details);
		} else {
			Diagnostics.client().atNormal().failure(Level.WARN, operation, "source-failure", details, failure);
		}
	}

	static void deliver(Consumer<String> sink, Supplier<String> details, @Nullable Throwable failure) {
		LoggingProfile profile = Diagnostics.profile(DiagnosticSide.CLIENT);
		String context;
		try { context = details.get(); }
		catch (RuntimeException | LinkageError contextFailure) {
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "outline/custom-sink", Stage.CONTEXT, profile, contextFailure);
			context = "[context unavailable]";
		}
		Stage stage = Stage.FORMAT;
		try {
			String message = failure == null ? context : SafeExceptionReport.formatWithContext(context, failure,
				(accessor, accessorFailure) -> Diagnostics.faults().report(DiagnosticSide.CLIENT,
					"outline/custom-sink", Stage.ACCESSOR, profile, accessorFailure));
			stage = Stage.SINK;
			sink.accept(message.length() <= SafeExceptionReport.MAX_CHARACTERS ? message
				: message.substring(0, SafeExceptionReport.MAX_CHARACTERS - 11) + "[truncated]");
		} catch (RuntimeException | LinkageError reportingFailure) {
			// Admission was already consumed. Do not recursively diagnose a sink.
			Diagnostics.faults().report(DiagnosticSide.CLIENT, "outline/custom-sink", stage, profile, reportingFailure);
		}
	}

	static void rejected(String operation, String scope, String reason, @Nullable Throwable failure) {
		if (failure == null) {
			Diagnostics.client().atNormal().event(Level.WARN, operation, reason, () -> "scope=" + scope);
		} else {
			Diagnostics.client().atNormal().failure(Level.WARN, operation, "source-rejected", () -> reason, failure);
		}
	}
}
