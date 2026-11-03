package nx.pingwheel.common.config;

import com.google.gson.annotations.SerializedName;
import nx.pingwheel.common.core.ServerCore;

import java.util.Objects;

public class ServerConfig implements IConfig {
	ChannelMode defaultChannelMode = ChannelMode.AUTO;
	boolean playerTrackingEnabled = true;
	int msToRegenerate = 1000;
	int rateLimit = 5;
	@SerializedName("syncDuration")
	int syncDuration = ServerConfigBounds.DEFAULT_SYNC_DURATION;
	int pingDistance = 2048;

	@Override
	public void validate() {
		if (defaultChannelMode == null) {
			defaultChannelMode = ChannelMode.AUTO;
		}

		if (msToRegenerate < 0) {
			msToRegenerate = 1000;
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
		ServerCore.broadcastSyncDurationPolicy();
	}

	public ChannelMode getDefaultChannelMode() {
		return defaultChannelMode;
	}

	public void setDefaultChannelMode(ChannelMode defaultChannelMode) {
		this.defaultChannelMode = defaultChannelMode;
	}

	public boolean isPlayerTrackingEnabled() {
		return playerTrackingEnabled;
	}

	public void setPlayerTrackingEnabled(boolean playerTrackingEnabled) {
		this.playerTrackingEnabled = playerTrackingEnabled;
	}

	public int getMsToRegenerate() {
		return msToRegenerate;
	}

	public void setMsToRegenerate(int msToRegenerate) {
		this.msToRegenerate = msToRegenerate;
	}

	public int getRateLimit() {
		return rateLimit;
	}

	public void setRateLimit(int rateLimit) {
		this.rateLimit = rateLimit;
	}

	public int getSyncDuration() {
		return syncDuration;
	}

	public void setSyncDuration(int syncDuration) {
		this.syncDuration = syncDuration;
	}

	public int getPingDistance() {
		return pingDistance;
	}

	public void setPingDistance(int pingDistance) {
		this.pingDistance = pingDistance;
	}

	@Override
	public String toString() {
		return "ServerConfig{defaultChannelMode=" + defaultChannelMode
			+ ", playerTrackingEnabled=" + playerTrackingEnabled
			+ ", msToRegenerate=" + msToRegenerate
			+ ", rateLimit=" + rateLimit
			+ ", syncDuration=" + syncDuration
			+ ", pingDistance=" + pingDistance + "}";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ServerConfig other)) return false;
		return playerTrackingEnabled == other.playerTrackingEnabled
			&& msToRegenerate == other.msToRegenerate
			&& rateLimit == other.rateLimit
			&& syncDuration == other.syncDuration
			&& pingDistance == other.pingDistance
			&& defaultChannelMode == other.defaultChannelMode;
	}

	@Override
	public int hashCode() {
		return Objects.hash(defaultChannelMode, playerTrackingEnabled, msToRegenerate, rateLimit, syncDuration, pingDistance);
	}

	public static final ConfigHandler<ServerConfig> HANDLER = ConfigHandler.of(ServerConfig.class, ".server.json");
}
