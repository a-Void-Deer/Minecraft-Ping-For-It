package nx.pingwheel.common.platform;

import net.minecraft.client.KeyMapping;
import net.minecraft.server.level.ServerLevel;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;

import java.nio.file.Path;
import java.util.ServiceLoader;

public interface IPlatformContextService {

	IPlatformContextService INSTANCE = ServiceLoader.load(IPlatformContextService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformContextService implementation found!"));

	String getSelfModVersion();
	Path resolveGameDir(String path);
	Path resolveConfigDir(String path);
	void registerKeyMapping(KeyMapping keyMapping);
	boolean isModLoaded(String modId);

	/**
	 * Creation-only, metadata-only optional Vault grouping. The common caller has
	 * checked the loaded original registry; implementations must verify loaded
	 * controller membership without inventory/capability access or chunk loading.
	 * Unsupported or unavailable integrations retain the exact physical key.
	 */
	default TargetKey groupVaultTarget(ServerLevel level, Target.BlockTarget target) {
		return TargetKey.from(target);
	}
}
