package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.platform.IPlatformServerEventService;

import java.util.function.Consumer;

public class PlatformServerEventServiceImpl implements IPlatformServerEventService {

	@Override
	public void registerPlayerLogoutEvent(Consumer<ServerPlayer> callback) {
		ServerPlayConnectionEvents.DISCONNECT.register((networkHandler, a) -> callback.accept(networkHandler.player));
	}

	@Override
	public void registerServerTickEvent(Consumer<MinecraftServer> callback) {
		ServerTickEvents.END_SERVER_TICK.register(callback::accept);
	}
}
