package nx.pingwheel.common;

import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
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

	public static void exception(DiagnosticSide side, Level severity, String stableSite, String handling,
		@Nullable Throwable throwable) {
		var logger = switch (side) {
			case CLIENT -> Diagnostics.client();
			case SERVER -> Diagnostics.server();
			case BOOTSTRAP -> Diagnostics.bootstrap();
		};
		logger.atNormal().failure(severity, stableSite, handling, null, throwable);
	}
}
