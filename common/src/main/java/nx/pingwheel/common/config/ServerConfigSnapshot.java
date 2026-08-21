package nx.pingwheel.common.config;

/**
 * The small, server-authoritative view of the settings that are editable from
 * the client settings screen.  Ping duration and ping distance deliberately
 * do not belong to this snapshot: they remain JSON-only server settings.
 */
public record ServerConfigSnapshot(
	boolean canEdit,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit
) {
	public ServerConfigSnapshot {
		if (defaultChannelMode == null) {
			defaultChannelMode = ChannelMode.AUTO;
		}
	}

	public static ServerConfigSnapshot from(ServerConfig config, boolean canEdit) {
		return new ServerConfigSnapshot(
			canEdit,
			config.getDefaultChannelMode(),
			config.isPlayerTrackingEnabled(),
			safeNonNegative(config.getMsToRegenerate()),
			safeNonNegative(config.getRateLimit()));
	}

	public ServerConfigSnapshot withCanEdit(boolean canEdit) {
		return new ServerConfigSnapshot(
			canEdit,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit);
	}

	public boolean isSafe() {
		return defaultChannelMode != null && msToRegenerate >= 0 && rateLimit >= 0;
	}

	private static int safeNonNegative(int value) {
		return Math.max(0, value);
	}
}
