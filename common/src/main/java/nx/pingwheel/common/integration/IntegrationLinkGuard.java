package nx.pingwheel.common.integration;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.debugException;

/**
 * Shared once-only {@link LinkageError} guard for optional-mod boundaries. A
 * link error disables the owning boundary for the rest of the session, warns
 * once with the integration id, and debug-logs a bounded safe exception
 * report.
 */
final class IntegrationLinkGuard {
	private final String integration;
	private volatile boolean disabled;
	private boolean warned;

	IntegrationLinkGuard(String integration) {
		this.integration = integration;
	}

	boolean disabled() {
		return disabled;
	}

	synchronized void disable(LinkageError error) {
		disabled = true;

		if (warned) {
			return;
		}

		warned = true;
		LOGGER.warn("{} integration disabled", integration);
		debugException(integration + " integration link error", error);
	}
}
