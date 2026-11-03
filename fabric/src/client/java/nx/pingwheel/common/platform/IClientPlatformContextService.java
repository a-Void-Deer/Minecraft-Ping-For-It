package nx.pingwheel.common.platform;

import net.minecraft.client.KeyMapping;

import java.util.ServiceLoader;

/**
 * Client-only extension of {@link IPlatformContextService}.
 *
 * <p>Key-mapping registration is the only member that needs a client class, so it
 * lives in the client source set instead of polluting the shared interface.</p>
 */
public interface IClientPlatformContextService extends IPlatformContextService {

	IClientPlatformContextService INSTANCE = ServiceLoader.load(IClientPlatformContextService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IClientPlatformContextService implementation found!"));

	void registerKeyMapping(KeyMapping keyMapping);
}
