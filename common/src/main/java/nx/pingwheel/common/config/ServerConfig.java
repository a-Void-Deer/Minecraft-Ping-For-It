package nx.pingwheel.common.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.platform.IPlatformContextService;

import static nx.pingwheel.common.Global.MOD_ID;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ServerConfig implements IConfig {
	ChannelMode defaultChannelMode = ChannelMode.AUTO;
	boolean playerTrackingEnabled = true;
	int msToRegenerate = 1000;
	int rateLimit = 5;

	public void validate() {
		if (msToRegenerate < 0) {
			msToRegenerate = 1000;
		}

		if (rateLimit < 0) {
			rateLimit = 0;
		}
	}

	public void onUpdate() {
		ServerCore.init();
	}

	public static final ConfigHandler<ServerConfig> HANDLER = new ConfigHandler<>(ServerConfig.class, IPlatformContextService.INSTANCE.resolveConfigDir(MOD_ID + ".server.json"));
}
