package nx.pingwheel.common.integration;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.debugException;

/**
 * Shared once-only {@link LinkageError} guard for optional-mod boundaries. A
 * link error disables the owning boundary for the rest of the session, warns
 * once with the integration id, and debug-logs a bounded safe exception
 * report.
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
		LOGGER.warn("{} integration disabled", integration);
		debugException(integration + " integration link error", error);
	}

	/**
	 * Disables an optional boundary without emitting an exception report. This
	 * is used by privacy-sensitive reflective adapters whose invocation context
	 * must never reach logs.
	 */
	public synchronized void disableSilently() {
		disabled = true;
	}
}
