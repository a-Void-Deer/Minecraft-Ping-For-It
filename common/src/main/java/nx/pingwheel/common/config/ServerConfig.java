package nx.pingwheel.common.config;

import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.presentation.PresentationSettings;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ServerConfig implements IConfig {
	ChannelMode defaultChannelMode = ChannelMode.AUTO;
	boolean playerTrackingEnabled = true;
	int msToRegenerate = 1000;
	int rateLimit = 5;
	@SerializedName("syncDuration")
	int syncDuration = ServerConfigBounds.DEFAULT_SYNC_DURATION;
	int pingDistance = 2048;
	/** Server-authoritative Basic/extension allow lists, sampling limits and permissions. */
	PresentationSettings presentation = PresentationSettings.serverDefaults();

	public PresentationSettings getPresentation() {
		return presentation;
	}

	@Override
	public void validate() {
		if (presentation == null) presentation = PresentationSettings.serverDefaults();
		presentation.validate();
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
