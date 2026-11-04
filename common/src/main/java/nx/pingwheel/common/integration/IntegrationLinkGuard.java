package nx.pingwheel.common.integration;

import nx.pingwheel.common.diagnostics.DiagnosticLogger;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.Objects;

/**
 * Shared once-only {@link LinkageError} guard for optional-mod boundaries. A
 * link error disables the owning boundary for the rest of the session, warns
 * once with the integration id and original cause in one bounded report.
 */
@NotNullByDefault
public final class IntegrationLinkGuard {
	private final String integration;
	private final DiagnosticLogger diagnostics;
	private volatile boolean disabled;
	private boolean warned;

	public IntegrationLinkGuard(String integration, DiagnosticLogger diagnostics) {
		this.integration = Objects.requireNonNull(integration, "integration");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
	}

	public boolean disabled() {
		return disabled;
	}

	public void disable(LinkageError error) {
		disabled = true;
		DiagnosticLogger logger = diagnostics;
		if (!logger.atNormal().isEnabled(Level.WARN)) return;
		synchronized (this) {
			if (warned) return;
			warned = true;
		}
		logger.atNormal().failure(Level.WARN, "integration-link", "integration-disabled",
			() -> integration + " integration disabled", error);
	}

	/**
	 * Disables an optional boundary without emitting an exception report. This
	 * requires the caller to own the failure report at its diagnostic boundary.
	 */
	public synchronized void disableSilently() {
		disabled = true;
	}
}
