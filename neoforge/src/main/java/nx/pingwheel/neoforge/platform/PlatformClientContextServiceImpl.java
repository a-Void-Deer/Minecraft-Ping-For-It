package nx.pingwheel.neoforge.platform;

import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import nx.pingwheel.common.platform.IClientPlatformContextService;

import java.nio.file.Path;
import java.util.Objects;

public class PlatformClientContextServiceImpl implements IClientPlatformContextService {

	private static final PlatformContextServiceImpl DELEGATE = new PlatformContextServiceImpl();
	private static IEventBus modBus;

	public static void setModBus(IEventBus bus) {
		modBus = Objects.requireNonNull(bus, "modBus");
	}

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
		modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(keyMapping));
	}
}
