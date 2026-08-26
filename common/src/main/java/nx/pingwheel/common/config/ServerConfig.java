package nx.pingwheel.common.config;

import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import nx.pingwheel.common.core.ServerCore;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ServerConfig implements IConfig {
	ChannelMode defaultChannelMode = ChannelMode.AUTO;
	boolean playerTrackingEnabled = true;
	int msToRegenerate = 1000;
	int rateLimit = 5;
	@SerializedName(value = "syncDuration", alternate = {"pingDuration"})
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

	public static final ConfigHandler<ServerConfig> HANDLER = ConfigHandler.of(ServerConfig.class, ".server.json");
}
