package nx.pingwheel.fabric.platform;

import net.fabricmc.loader.api.FabricLoader;
import nx.pingwheel.common.platform.IPlatformContextService;

import java.nio.file.Path;

import static nx.pingwheel.common.Global.MOD_ID;

public class PlatformContextServiceImpl implements IPlatformContextService {

	@Override
	public String getSelfModVersion() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("Unknown");
	}

	@Override
	public Path resolveGameDir(String path) {
		return FabricLoader.getInstance().getGameDir().resolve(path);
	}

	@Override
	public Path resolveConfigDir(String path) {
		return FabricLoader.getInstance().getConfigDir().resolve(path);
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}
}
