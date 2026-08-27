package nx.pingwheel.common.config;

/**
 * The small, server-authoritative view of the settings that are editable from
 * the client settings screen.  Ping distance deliberately remains a JSON-only
 * server setting; sync duration is included because it is both editable and a
 * server-authoritative client policy.
 */
public record ServerConfigSnapshot(
	boolean canEdit,
	ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit,
	int syncDuration
) {
	public ServerConfigSnapshot(
		boolean canEdit,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		this(canEdit, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			ServerConfigBounds.DEFAULT_SYNC_DURATION);
	}

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
			safeNonNegative(config.getRateLimit()),
			ServerConfigBounds.clampSyncDuration(config.getSyncDuration()));
	}

	public ServerConfigSnapshot withCanEdit(boolean canEdit) {
		return new ServerConfigSnapshot(
			canEdit,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration);
	}

	public boolean isSafe() {
		return defaultChannelMode != null
			&& msToRegenerate >= 0
			&& rateLimit >= 0
			&& syncDuration >= ServerConfigBounds.MIN_PING_DURATION
			&& syncDuration <= ServerConfigBounds.MAX_PING_DURATION;
	}

	private static int safeNonNegative(int value) {
		return Math.max(0, value);
	}
}
