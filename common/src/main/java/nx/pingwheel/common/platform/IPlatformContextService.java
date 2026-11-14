package nx.pingwheel.common.platform;

import java.nio.file.Path;
import java.util.ServiceLoader;

public interface IPlatformContextService {

	IPlatformContextService INSTANCE = ServiceLoader.load(IPlatformContextService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformContextService implementation found!"));

	String getSelfModVersion();
	Path resolveGameDir(String path);
	Path resolveConfigDir(String path);
	boolean isModLoaded(String modId);
}
