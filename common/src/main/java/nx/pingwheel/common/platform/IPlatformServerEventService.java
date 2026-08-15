package nx.pingwheel.common.platform;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ServiceLoader;
import java.util.function.Consumer;

public interface IPlatformServerEventService {

	IPlatformServerEventService INSTANCE = ServiceLoader.load(IPlatformServerEventService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformServerEventService implementation found!"));

	void registerPlayerLogoutEvent(Consumer<ServerPlayer> callback);

	/**
	 * Registers a callback that is invoked once at the end of every server
	 * tick, receiving the current server instance. Implementations must fire
	 * on the server thread.
	 */
	void registerServerTickEvent(Consumer<MinecraftServer> callback);
}
