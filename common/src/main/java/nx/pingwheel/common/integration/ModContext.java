package nx.pingwheel.common.integration;

import nx.pingwheel.common.platform.IPlatformContextService;

import static nx.pingwheel.common.Global.LOGGER;

public class ModContext {
	private ModContext() {}

	public static boolean HasDistantHorizons = false;
	public static boolean HasSable = false;

	public static void indexMods() {
		HasDistantHorizons = IPlatformContextService.INSTANCE.isModLoaded("distanthorizons");
		HasSable = IPlatformContextService.INSTANCE.isModLoaded("sable");
		ExternalBlockServerProviders.configure(HasSable);

		LOGGER.debug("optional integrations: distantHorizons={} sable={}",
			HasDistantHorizons, HasSable);

		if (HasDistantHorizons) {
			DistantHorizonsIntegration.logVersionDiagnostics();
		}
	}
}
