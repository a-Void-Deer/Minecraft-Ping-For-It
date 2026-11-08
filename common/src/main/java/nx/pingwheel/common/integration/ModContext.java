package nx.pingwheel.common.integration;

import nx.pingwheel.common.platform.IPlatformContextService;

import static nx.pingwheel.common.Global.LOGGER;

public class ModContext {
	private ModContext() {}

	public static boolean HasDistantHorizons = false;
	public static boolean HasVoiceChat = false;
	public static boolean HasFTBTeams = false;

	public static void indexMods() {
		HasDistantHorizons = IPlatformContextService.INSTANCE.isModLoaded("distanthorizons");
		HasVoiceChat = IPlatformContextService.INSTANCE.isModLoaded("voicechat");
		HasFTBTeams = IPlatformContextService.INSTANCE.isModLoaded("ftbteams");

		LOGGER.debug("optional integrations: distantHorizons={} voiceChat={} ftbTeams={}",
			HasDistantHorizons, HasVoiceChat, HasFTBTeams);

		if (HasDistantHorizons) {
			DistantHorizonsIntegration.logVersionDiagnostics();
		}
	}
}
