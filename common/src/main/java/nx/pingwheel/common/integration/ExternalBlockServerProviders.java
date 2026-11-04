package nx.pingwheel.common.integration;

import nx.pingwheel.common.integration.externalblock.ExternalBlockServerProviderRegistry;

import net.minecraft.server.MinecraftServer;

/**
 * Optional external-block provider bootstrap.
 *
 * <p>The generic external-block protocol remains available, but the 1.20.1
 * branch does not ship a provider implementation. A future loader integration
 * can register one through the same registry without changing the protocol or
 * the marker identity layer.</p>
 */
public final class ExternalBlockServerProviders {

	private static final ExternalBlockServerProviderRegistry REGISTRY =
		new ExternalBlockServerProviderRegistry();

	private ExternalBlockServerProviders() {
	}

	public static ExternalBlockServerProviderRegistry registry() {
		return REGISTRY;
	}

	/** Closes provider-owned state before a server instance is replaced. */
	public static void close(MinecraftServer server) {
		REGISTRY.close(server);
	}
}
