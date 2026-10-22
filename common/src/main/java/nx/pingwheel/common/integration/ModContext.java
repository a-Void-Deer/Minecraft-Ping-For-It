package nx.pingwheel.common.integration;

import nx.pingwheel.common.platform.IPlatformContextService;

import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;

public class ModContext {
	private ModContext() {}

	public static boolean HasDistantHorizons = false;
	public static boolean HasSable = false;

	public static void indexMods() {
		HasDistantHorizons = IPlatformContextService.INSTANCE.isModLoaded("distanthorizons");
		HasSable = IPlatformContextService.INSTANCE.isModLoaded("sable");
		ExternalBlockServerProviders.configure(HasSable);

		Diagnostics.event(Level.DEBUG, "integration-index", "optional-mods", "indexed",
			() -> "optional integrations: distantHorizons=" + HasDistantHorizons + " sable=" + HasSable);

		if (HasDistantHorizons) {
			DistantHorizonsIntegration.logVersionDiagnostics();
		}
	}
}
