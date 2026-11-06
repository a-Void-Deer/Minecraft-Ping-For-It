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

	enum Category {
		CONTEXT_CREATION("context-creation-failed", "next-source", Level.WARN),
		NULL_CONTEXT("null-context", "next-source", Level.WARN),
		OBSERVER("observer-failed", "observer-ignored", Level.WARN),
		NULL_OUTCOME("null-outcome", "next-source", Level.ERROR),
		ATTEMPT("source-render-failed", "next-source-fallback-eligible", Level.ERROR),
		HANDLES("source-probe-failed", "next-source", Level.WARN),
		INVALID_ID("invalid-source-id", "source-id-unavailable", Level.WARN),
		SOURCE_ID("source-id-failed", "source-id-unavailable", Level.WARN),
		REGISTRATION("registration-failed", "registration-rejected", Level.WARN);

		final String code;
		final String handling;
		final Level level;
		Category(String code, String handling, Level level) {
			this.code = code;
			this.handling = handling;
			this.level = level;
		}
	}

	static boolean enabled(Category category) {
		return Diagnostics.client().atNormal().isEnabled(category.level);
	}

	static void approved(String operation, Category category, Supplier<String> details, @Nullable Throwable failure) {
		String site = operation + "/" + category.code;
		if (failure == null) {
			Diagnostics.client().atNormal().event(category.level, site, category.handling, details);
		} else {
			Diagnostics.client().atNormal().failure(category.level, site, category.handling, details, failure);
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
		// Only this finite caller-owned vocabulary may enter a NORMAL envelope.
		String category = switch (reason) {
			case "null-source", "source-id-lookup-failed", "invalid-source-id", "duplicate-id-first-registration-kept",
				"duplicate-id", "missing-anchor-or-duplicate", "null-resolver", "resolver-id-failed", "invalid-id",
				"null-adapter", "adapter-id-failed" -> reason;
			default -> "registration-rejected";
		};
		Supplier<String> detail = () -> "scope=" + scope + "; category=" + reason;
		if (failure == null) {
			Diagnostics.client().atNormal().event(Level.WARN, operation + "/" + category, "registration-rejected", detail);
		} else {
			Diagnostics.client().atNormal().failure(Level.WARN, operation + "/" + category, "registration-rejected", detail, failure);
		}
	}
}
