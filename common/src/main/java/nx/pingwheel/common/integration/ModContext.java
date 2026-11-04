package nx.pingwheel.common.integration;

import nx.pingwheel.common.platform.IPlatformContextService;

import nx.pingwheel.common.diagnostics.Diagnostics;

public class ModContext {
	private ModContext() {}

	public static boolean HasDistantHorizons = false;
	public static boolean HasSable = false;

	public static void indexMods() {
		HasDistantHorizons = IPlatformContextService.INSTANCE.isModLoaded("distanthorizons");
		HasSable = IPlatformContextService.INSTANCE.isModLoaded("sable");
		ExternalBlockServerProviders.configure(HasSable);

		Diagnostics.bootstrap().atNormal().site("integration-index").debug(
			"optional integrations: distantHorizons={} sable={}",
			nx.pingwheel.common.diagnostics.SafeDiagnosticValue.bool(HasDistantHorizons),
			nx.pingwheel.common.diagnostics.SafeDiagnosticValue.bool(HasSable));

		if (HasDistantHorizons) {
			DistantHorizonsIntegration.logVersionDiagnostics();
		}
	}
}
