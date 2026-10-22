package nx.pingwheel.common.integration;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;

/**
 * Shared once-only {@link LinkageError} guard for optional-mod boundaries. A
 * link error disables the owning boundary for the rest of the session, warns
 * once with the integration id and original cause in one bounded report.
 */
public final class IntegrationLinkGuard {
	private final String integration;
	private volatile boolean disabled;
	private boolean warned;

	public IntegrationLinkGuard(String integration) {
		this.integration = integration;
	}

	public boolean disabled() {
		return disabled;
	}

	public synchronized void disable(LinkageError error) {
		disabled = true;

		if (warned) {
			return;
		}

		warned = true;
		Diagnostics.domainApprovedFailure(Level.WARN, "integration-link", integration,
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
