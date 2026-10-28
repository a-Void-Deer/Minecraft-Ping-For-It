package nx.pingwheel.common.config;

import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.presentation.PresentationSettings;
import org.jetbrains.annotations.Nullable;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ServerConfig implements IConfig {
	@Nullable ChannelMode defaultChannelMode = ConfigDefaults.Server.DEFAULT_CHANNEL_MODE;
	boolean playerTrackingEnabled = ConfigDefaults.Server.PLAYER_TRACKING_ENABLED;
	int msToRegenerate = ConfigDefaults.Server.MS_TO_REGENERATE;
	int rateLimit = ConfigDefaults.Server.RATE_LIMIT;
	@SerializedName("syncDuration")
	int syncDuration = ConfigDefaults.Server.SYNC_DURATION;
	int pingDistance = ConfigDefaults.Server.PING_DISTANCE;
	/** Server-authoritative Basic/extension allow lists, sampling limits and permissions. */
	PresentationSettings presentation = PresentationSettings.serverDefaults();
	/** Server-authoritative inventory preview/tracking budgets; additive object with defaults. */
	InventorySettings inventory = InventorySettings.serverDefaults();
	/** Controls administrator budget-delay messages only; WARN diagnostics remain enabled. */
	boolean budgetDelayNotificationsEnabled = ConfigDefaults.Server.BUDGET_DELAY_NOTIFICATIONS_ENABLED;

	public PresentationSettings getPresentation() {
		return presentation;
	}

	@Override
	public void validate() {
		if (presentation == null) presentation = PresentationSettings.serverDefaults();
		presentation.validate();
		if (inventory == null) inventory = InventorySettings.serverDefaults();
		inventory.validate();
		if (defaultChannelMode == null) {
			defaultChannelMode = ConfigDefaults.Server.DEFAULT_CHANNEL_MODE;
		}

		if (msToRegenerate < 0) {
			msToRegenerate = ConfigDefaults.Server.MS_TO_REGENERATE;
		}

		if (rateLimit < 0) {
			rateLimit = 0;
		}

		syncDuration = ServerConfigBounds.clampSyncDuration(syncDuration);
		pingDistance = ServerConfigBounds.clampPingDistance(pingDistance);
	}

	@Override
	public void onUpdate() {
		ServerCore.init();
		ServerCore.broadcastRateLimitPolicy();
	}

	public static final ConfigHandler<ServerConfig> HANDLER = ConfigHandler.of(ServerConfig.class, ".server.json");
}
