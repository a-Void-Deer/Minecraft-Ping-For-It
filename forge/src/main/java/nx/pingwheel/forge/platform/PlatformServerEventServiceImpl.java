package nx.pingwheel.forge.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import nx.pingwheel.common.platform.IPlatformServerEventService;

import java.util.function.Consumer;

public class PlatformServerEventServiceImpl implements IPlatformServerEventService {

	@Override
	public void registerPlayerLogoutEvent(Consumer<ServerPlayer> callback) {
		PlayerEvent.PlayerLoggedOutEvent.BUS.addListener((event) -> callback.accept((ServerPlayer)event.getEntity()));
	}
}
