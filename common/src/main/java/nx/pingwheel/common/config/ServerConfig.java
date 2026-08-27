package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
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
	private static final String LEGACY_SYNC_DURATION_KEY = "pingDuration";
	private static final String SYNC_DURATION_KEY = "syncDuration";

	ChannelMode defaultChannelMode = ChannelMode.AUTO;
	boolean playerTrackingEnabled = true;
	int msToRegenerate = 1000;
	int rateLimit = 5;
	@SerializedName(value = "syncDuration", alternate = {"pingDuration"})
	int syncDuration = ServerConfigBounds.DEFAULT_SYNC_DURATION;
	int pingDistance = 2048;

	/**
	 * Renames the pre-split persisted duration key before Gson deserializes the
	 * config. This is needed even when the version marker is unchanged: the
	 * alias keeps the value readable, but without normalizing the JSON the old
	 * key would remain on disk indefinitely.
	 */
	static boolean migrateLegacyDurationKey(JsonObject root) {
		if (!root.has(LEGACY_SYNC_DURATION_KEY)) {
			return false;
		}

		if (!root.has(SYNC_DURATION_KEY)) {
			root.add(SYNC_DURATION_KEY, root.get(LEGACY_SYNC_DURATION_KEY).deepCopy());
		}

		root.remove(LEGACY_SYNC_DURATION_KEY);
		return true;
	}

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
