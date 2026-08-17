package nx.pingwheel.common;

import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.FormattedMessageFactory;
import org.apache.logging.log4j.message.Message;

public class Global {
	private Global() {}

	public static final String MOD_VERSION = IPlatformContextService.INSTANCE.getSelfModVersion();
	public static final String MOD_ID = "pingforit";
	public static final String MOD_PREFIX = "[PingForIt] ";

	/**
	 * Derived external identities. All of these are compile-time constants so
	 * referencing them never triggers this class's platform-context
	 * initialization ({@link #MOD_VERSION}), which keeps them usable from
	 * pure unit tests.
	 */
	public static final String C2S_NAMESPACE = MOD_ID + "-c2s";
	public static final String S2C_NAMESPACE = MOD_ID + "-s2c";
	public static final String CLIENT_COMMAND_ROOT = MOD_ID;
	public static final String SERVER_COMMAND_ROOT = MOD_ID + ":server";

	public static final Logger LOGGER = LogManager.getLogger(MOD_ID,
		new FormattedMessageFactory() {
			@Override
			public Message newMessage(String message) {
				return super.newMessage(MOD_PREFIX + message);
			}
		});

	/**
	 * Logs a bounded exception report at debug level. The context must be a
	 * caller-provided constant or safe scalar; the throwable is rendered before
	 * it reaches Log4j and is never attached to the log event.
	 */
	public static void debugException(String constantContext, Throwable throwable) {
		LOGGER.debug(SafeExceptionReport.formatWithContext(constantContext, throwable));
	}

	/**
	 * Logs a bounded exception report at warn level without attaching the
	 * throwable to the Log4j event.
	 */
	public static void warnException(String constantContext, Throwable throwable) {
		LOGGER.warn(SafeExceptionReport.formatWithContext(constantContext, throwable));
	}

	/**
	 * Logs a bounded exception report at error level without attaching the
	 * throwable to the Log4j event.
	 */
	public static void errorException(String constantContext, Throwable throwable) {
		LOGGER.error(SafeExceptionReport.formatWithContext(constantContext, throwable));
	}
}
