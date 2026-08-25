package nx.pingwheel.common.integration;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProvider;
import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;

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
			return;
		}

		try {
			ClassLoader loader = ExternalBlockServerProviders.class.getClassLoader();
			Class<?> providerClass = Class.forName(SABLE_PROVIDER_CLASS, true, loader);
			Object provider = providerClass.getMethod("create").invoke(null);

			if (provider instanceof ExternalBlockServerProvider externalProvider) {
				REGISTRY.register(externalProvider);
			}
		} catch (ReflectiveOperationException | SecurityException | LinkageError ignored) {
			// An absent, incompatible, or partially installed optional provider is
			// simply not registered.  No provider payload is safe to log here.
		} catch (RuntimeException ignored) {
			// Keep optional bootstrap fail-soft for malformed adapter construction.
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
