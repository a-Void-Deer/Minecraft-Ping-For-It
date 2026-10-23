package nx.pingwheel.common;

import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class Global {
	private Global() {}

	public static final String MOD_VERSION = IPlatformContextService.INSTANCE.getSelfModVersion();
	public static final String MOD_ID = "pingforit";
	public static final String MOD_PREFIX = Diagnostics.PREFIX;

	/**
	 * Derived external identities. All of these are compile-time constants so
	 * referencing them never triggers this class's platform-context
	 * initialization ({@link #MOD_VERSION}), which keeps them usable from
	 * pure unit tests.
	 */
	public static final String C2S_NAMESPACE = MOD_ID + "-c2s";
	public static final String S2C_NAMESPACE = MOD_ID + "-s2c";
	public static final String CLIENT_COMMAND_ROOT = MOD_ID;

	public static final Logger LOGGER = Diagnostics.logger();

	/**
	 * Logs a bounded exception report at debug level. The context must be a
	 * caller-provided detached scalar. Formatting, including original exception
	 * messages and stacks, occurs only when DEBUG is enabled; the throwable is
	 * never also attached to the log event for unbounded rendering.
	 */
	public static void debugException(@Nullable String constantContext, @Nullable Throwable throwable) {
		Diagnostics.domainApprovedFailure(Level.DEBUG, "legacy-exception", "global", () -> constantContext, throwable);
	}

	/**
	 * Logs original messages and stacks as one bounded report only when WARN is
	 * enabled, without also attaching the throwable to the Log4j event.
	 */
	public static void warnException(@Nullable String constantContext, @Nullable Throwable throwable) {
		Diagnostics.domainApprovedFailure(Level.WARN, "legacy-exception", "global", () -> constantContext, throwable);
	}

	/**
	 * Logs original messages and stacks as one bounded report only when ERROR is
	 * enabled, without also attaching the throwable to the Log4j event.
	 */
	public static void errorException(@Nullable String constantContext, @Nullable Throwable throwable) {
		Diagnostics.domainApprovedFailure(Level.ERROR, "legacy-exception", "global", () -> constantContext, throwable);
	}
}
