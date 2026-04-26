package nx.pingwheel.common.integration;

import com.seibel.distanthorizons.api.DhApi;
import nx.pingwheel.common.platform.IPlatformContextService;

import static nx.pingwheel.common.Global.LOGGER;

public class ModContext {
	private ModContext() {}

	public static boolean HasDistantHorizons = false;
	public static boolean HasVoiceChat = false;
	public static boolean HasFTBTeams = false;
	public static boolean HasSable = false;

	public static void indexMods() {
		HasDistantHorizons = IPlatformContextService.INSTANCE.isModLoaded("distanthorizons");
		HasVoiceChat = IPlatformContextService.INSTANCE.isModLoaded("voicechat");
		HasFTBTeams = IPlatformContextService.INSTANCE.isModLoaded("ftbteams");
		HasSable = IPlatformContextService.INSTANCE.isModLoaded("sable");

		if (HasDistantHorizons) {
			LOGGER.info("Distant Horizons API Version: %s.%s.%s".formatted(DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), DhApi.getApiPatchVersion()));
		}
	}
}
