package nx.pingwheel.common.integration;

import static nx.pingwheel.common.Global.LOGGER;

/**
 * Shared once-only {@link LinkageError} guard for optional-mod boundaries. A
 * link error disables the owning boundary for the rest of the session, warns
 * once with the integration id and the exception class only, and debug-logs
 * the full stack.
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
		LOGGER.warn("{} integration disabled: linkErrorClass={}", integration, error.getClass().getName());
		LOGGER.debug("{} integration link error", integration, error);
	}
}
