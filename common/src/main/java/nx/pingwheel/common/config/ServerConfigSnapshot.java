package nx.pingwheel.common.config;

import org.jetbrains.annotations.Nullable;

/**
 * The small, server-authoritative view of the settings that are editable from
 * the client settings screen.  Ping distance deliberately remains a JSON-only
 * server setting; sync duration is included because it is both editable and a
 * server-authoritative client policy.
 */
public record ServerConfigSnapshot(
	boolean canEdit,
	@Nullable ChannelMode defaultChannelMode,
	boolean playerTrackingEnabled,
	int msToRegenerate,
	int rateLimit,
	int syncDuration,
	@Nullable InventoryConfigValues inventory,
	boolean budgetDelayNotificationsEnabled
) {
	public ServerConfigSnapshot(
		boolean canEdit,
		@Nullable ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit) {
		this(canEdit, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			ConfigDefaults.Server.SYNC_DURATION);
	}

	/** Source-compatible constructor; old Java callers get independent inventory defaults. */
	public ServerConfigSnapshot(
		boolean canEdit,
		ChannelMode defaultChannelMode,
		boolean playerTrackingEnabled,
		int msToRegenerate,
		int rateLimit,
		int syncDuration) {
		this(canEdit, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			syncDuration, InventoryConfigValues.defaults());
	}

	/** Source-compatible constructor; existing Java callers enable administrator notices. */
	public ServerConfigSnapshot(
		boolean canEdit, @Nullable ChannelMode defaultChannelMode, boolean playerTrackingEnabled,
		int msToRegenerate, int rateLimit, int syncDuration, @Nullable InventoryConfigValues inventory) {
		this(canEdit, defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit,
			syncDuration, inventory, ConfigDefaults.Server.BUDGET_DELAY_NOTIFICATIONS_ENABLED);
	}

	public static ServerConfigSnapshot from(ServerConfig config, boolean canEdit) {
		return new ServerConfigSnapshot(
			canEdit,
			config.getDefaultChannelMode(),
			config.isPlayerTrackingEnabled(),
			safeNonNegative(config.getMsToRegenerate()),
			safeNonNegative(config.getRateLimit()),
			ServerConfigBounds.clampSyncDuration(config.getSyncDuration()),
			InventoryConfigValues.from(config.getInventory()),
			config.isBudgetDelayNotificationsEnabled());
	}

	public ServerConfigSnapshot withCanEdit(boolean canEdit) {
		return new ServerConfigSnapshot(
			canEdit,
			defaultChannelMode,
			playerTrackingEnabled,
			msToRegenerate,
			rateLimit,
			syncDuration,
			inventory,
			budgetDelayNotificationsEnabled);
	}

	public boolean isSafe() {
		return defaultChannelMode != null
			&& msToRegenerate >= 0
			&& rateLimit >= 0
			&& syncDuration >= ServerConfigBounds.MIN_PING_DURATION
			&& syncDuration <= ServerConfigBounds.MAX_PING_DURATION
			&& inventory != null && inventory.isSafe();
	}

	private static int safeNonNegative(int value) {
		return Math.max(0, value);
	}
}
