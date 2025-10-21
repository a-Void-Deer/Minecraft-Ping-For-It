package nx.pingwheel.common.integration;

import nx.pingwheel.common.platform.IPlatformContextService;

public class ModContext {
	private ModContext() {}

	public static boolean HasDistantHorizons = false;
	public static boolean HasVoiceChat = false;

	public static void indexMods() {
		HasDistantHorizons = IPlatformContextService.INSTANCE.isModLoaded("distanthorizons");
		HasVoiceChat = IPlatformContextService.INSTANCE.isModLoaded("voicechat");
	}
}
