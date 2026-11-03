package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import nx.pingwheel.common.platform.IClientPlatformContextService;

import java.nio.file.Path;

/**
 * Client-only half of the platform context service: delegates the shared members
 * to {@link PlatformContextServiceImpl} and adds key-mapping registration.
 */
public class PlatformClientContextServiceImpl implements IClientPlatformContextService {

	private static final PlatformContextServiceImpl DELEGATE = new PlatformContextServiceImpl();

	@Override
	public String getSelfModVersion() {
		return DELEGATE.getSelfModVersion();
	}

	@Override
	public Path resolveGameDir(String path) {
		return DELEGATE.resolveGameDir(path);
	}

	@Override
	public Path resolveConfigDir(String path) {
		return DELEGATE.resolveConfigDir(path);
	}

	@Override
	public boolean isModLoaded(String modId) {
		return DELEGATE.isModLoaded(modId);
	}

	@Override
	public void registerKeyMapping(KeyMapping keyMapping) {
		KeyMappingHelper.registerKeyMapping(keyMapping);
	}
}
