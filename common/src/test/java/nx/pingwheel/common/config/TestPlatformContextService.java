package nx.pingwheel.common.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.client.KeyMapping;
import nx.pingwheel.common.platform.IPlatformContextService;

/** Minimal platform seam for config tests that exercise Gson validation. */
public final class TestPlatformContextService implements IPlatformContextService {

	@Override
	public String getSelfModVersion() {
		return "test";
	}

	@Override
	public Path resolveGameDir(String path) {
		return Paths.get("build", "test-game").resolve(path);
	}

	@Override
	public Path resolveConfigDir(String path) {
		return Paths.get("build", "test-config").resolve(path);
	}

	@Override
	public void registerKeyMapping(KeyMapping keyMapping) {}

	@Override
	public boolean isModLoaded(String modId) {
		return false;
	}
}
