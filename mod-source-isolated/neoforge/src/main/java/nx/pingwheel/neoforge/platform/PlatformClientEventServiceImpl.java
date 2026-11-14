package nx.pingwheel.neoforge.platform;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import nx.pingwheel.common.platform.IPlatformClientEventService;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	@Override
	public void registerTickStartEvent(Runnable callback) {
		NeoForge.EVENT_BUS.register(new ClientTickEventEventHandler(callback));
	}
	private record ClientTickEventEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientTick(ClientTickEvent.Pre event) {
			callback.run();
		}
	}

	@Override
	public void registerJoinServerEvent(Runnable callback) {
		NeoForge.EVENT_BUS.register(new JoinServerEventHandler(callback));
	}
	private record JoinServerEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientConnectedToServer(ClientPlayerNetworkEvent.LoggingIn event) {
			callback.run();
		}
	}

	@Override
	public void registerLeaveServerEvent(Runnable callback) {
		NeoForge.EVENT_BUS.register(new LeaveServerEventHandler(callback));
	}
	private record LeaveServerEventHandler(Runnable callback) {
		@SubscribeEvent
		public void onClientDisconnectedFromServer(ClientPlayerNetworkEvent.LoggingOut event) {
			callback.run();
		}
	}
}
