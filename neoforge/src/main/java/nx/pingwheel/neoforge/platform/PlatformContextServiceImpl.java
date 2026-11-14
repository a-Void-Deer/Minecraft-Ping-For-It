package nx.pingwheel.neoforge.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import nx.pingwheel.common.platform.IPlatformContextService;

import java.nio.file.Path;

import static nx.pingwheel.common.Global.MOD_ID;

public class PlatformContextServiceImpl implements IPlatformContextService {

	@Override
	public String getSelfModVersion() {
		return ModList.get().getModContainerById(MOD_ID)
			.map(container -> container.getModInfo().getVersion().toString())
			.orElse("Unknown");
	}

	@Override
	public Path resolveGameDir(String path) {
		return FMLPaths.GAMEDIR.get().resolve(path);
	}

	@Override
	public Path resolveConfigDir(String path) {
		return FMLPaths.CONFIGDIR.get().resolve(path);
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}
}
