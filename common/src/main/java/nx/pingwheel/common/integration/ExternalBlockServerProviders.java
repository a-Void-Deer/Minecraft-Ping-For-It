package nx.pingwheel.common.integration;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;
import nx.pingwheel.common.integration.sable.SableDiagnostics;

import net.minecraft.server.MinecraftServer;

/**
 * Optional external-block provider bootstrap.  The provider class is loaded
 * indirectly so a missing optional integration cannot link this common/server
 * class or any of its callers.
 */
public final class ExternalBlockServerProviders {

	private static final String SABLE_PROVIDER_CLASS =
		"nx.pingwheel.common.integration.sable.server.SableExternalBlockServerProvider";

	private static final ExternalBlockServerProviderRegistry REGISTRY =
		new ExternalBlockServerProviderRegistry();
	private static final SableDiagnostics DIAGNOSTICS = SableDiagnostics.global();

	private ExternalBlockServerProviders() {
	}

	/**
	 * Rebuilds the deterministic optional registry after mod indexing.  The
	 * Sable adapter is considered only while the corresponding loader flag is
	 * true; no optional class is initialized otherwise.
	 */
	public static synchronized void configure(boolean sableLoaded) {
		REGISTRY.clear();

		if (!sableLoaded) {
			DIAGNOSTICS.server(
				"provider-selection",
				"sable-not-loaded",
				"provider_class", SABLE_PROVIDER_CLASS);
			return;
		}

		DIAGNOSTICS.server(
			"provider-selection",
			"start",
			"provider_class", SABLE_PROVIDER_CLASS,
			"sable_loaded", true);

		try {
			ClassLoader loader = ExternalBlockServerProviders.class.getClassLoader();
			Class<?> providerClass = Class.forName(SABLE_PROVIDER_CLASS, true, loader);
			Object provider = providerClass.getMethod("create").invoke(null);

			if (provider instanceof ExternalBlockServerProvider externalProvider) {
				REGISTRY.register(externalProvider);
				DIAGNOSTICS.server(
					"provider-selection",
					"selected",
					"provider_class", providerClass.getName(),
					"provider_id", externalProvider.providerId(),
					"registered", true);
			} else {
				DIAGNOSTICS.server(
					"provider-selection",
					"type-mismatch",
					"provider_class", providerClass.getName(),
					"provider_value", provider);
			}
		} catch (ReflectiveOperationException | LinkageError failure) {
			DIAGNOSTICS.serverException(
				"provider-selection",
				"failure",
				failure,
				"provider_class", SABLE_PROVIDER_CLASS);
		} catch (RuntimeException failure) {
			DIAGNOSTICS.serverException(
				"provider-selection",
				"runtime-failure",
				failure,
				"provider_class", SABLE_PROVIDER_CLASS);
		}
	}

	public static ExternalBlockServerProviderRegistry registry() {
		return REGISTRY;
	}

	/** Closes provider-owned state before a server instance is replaced. */
	public static void close(MinecraftServer server) {
		REGISTRY.close(server);
	}
}
