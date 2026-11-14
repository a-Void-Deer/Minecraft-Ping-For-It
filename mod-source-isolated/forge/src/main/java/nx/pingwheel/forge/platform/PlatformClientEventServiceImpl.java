package nx.pingwheel.forge.platform;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import nx.pingwheel.common.platform.IPlatformClientEventService;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	@Override
	public void registerTickStartEvent(Runnable callback) {
		MinecraftForge.EVENT_BUS.register(new ClientTickEventEventHandler(callback));
	}
	private record ClientTickEventEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientTick(TickEvent.ClientTickEvent event) {
			if (event.phase.equals(TickEvent.Phase.START)) {
				callback.run();
			}
		}
	}

	@Override
	public void registerJoinServerEvent(Runnable callback) {
		MinecraftForge.EVENT_BUS.register(new JoinServerEventHandler(callback));
	}
	private record JoinServerEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientConnectedToServer(ClientPlayerNetworkEvent.LoggingIn event) {
			callback.run();
		}
	}

	@Override
	public void registerLeaveServerEvent(Runnable callback) {
		MinecraftForge.EVENT_BUS.register(new LeaveServerEventHandler(callback));
	}
	private record LeaveServerEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientDisconnectedFromServer(ClientPlayerNetworkEvent.LoggingOut event) {
			callback.run();
		}
	}
}
