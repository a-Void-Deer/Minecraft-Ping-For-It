package nx.pingwheel.common.client.outline;

import nx.pingwheel.common.diagnostics.Diagnostics;
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
		try {
			return Diagnostics.logger().isEnabled(Level.WARN);
		} catch (RuntimeException | LinkageError ignored) {
			return false;
		}
	}

	static void approved(String operation, String scope, Supplier<String> details, @Nullable Throwable failure) {
		if (failure == null) {
			Diagnostics.domainApprovedEvent(Level.WARN, operation, scope, "contract-or-source-failure", details);
		} else {
			Diagnostics.domainApprovedFailure(Level.WARN, operation, scope, details, failure);
		}
	}

	static void deliver(Consumer<String> sink, Supplier<String> details, @Nullable Throwable failure) {
		try {
			String message = failure == null ? details.get() : SafeExceptionReport.formatWithContext(details.get(), failure);
			sink.accept(message.length() <= SafeExceptionReport.MAX_CHARACTERS ? message
				: message.substring(0, SafeExceptionReport.MAX_CHARACTERS - 11) + "[truncated]");
		} catch (RuntimeException | LinkageError ignored) {
			// Admission was already consumed. Do not recursively diagnose a sink.
		}
	}

	static void rejected(String operation, String scope, String reason, @Nullable Throwable failure) {
		if (failure == null) {
			Diagnostics.event(Level.WARN, operation, scope, reason, () -> reason);
		} else {
			Diagnostics.warn(operation, scope, () -> reason, failure);
		}
	}
}
