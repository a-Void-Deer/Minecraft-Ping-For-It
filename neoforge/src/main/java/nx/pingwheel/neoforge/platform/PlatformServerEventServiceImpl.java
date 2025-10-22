package nx.pingwheel.neoforge.platform;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import nx.pingwheel.common.platform.IPlatformServerEventService;

import java.util.function.Consumer;

public class PlatformServerEventServiceImpl implements IPlatformServerEventService {

	@Override
	public void registerPlayerLogoutEvent(Consumer<ServerPlayer> callback) {
		NeoForge.EVENT_BUS.register(new PlayerLogoutEventHandler(callback));
	}
	private record PlayerLogoutEventHandler(Consumer<ServerPlayer> callback) {
		@SubscribeEvent
		public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
			callback.accept((ServerPlayer)event.getEntity());
		}
	}
}
