package nx.pingwheel.forge.platform;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import nx.pingwheel.common.platform.IPlatformClientEventService;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	@Override
	public void registerTickStartEvent(Runnable callback) {
		TickEvent.ClientTickEvent.Pre.BUS.addListener((event) -> callback.run());
	}

	@Override
	public void registerJoinServerEvent(Runnable callback) {
		ClientPlayerNetworkEvent.LoggingIn.BUS.addListener((event) -> callback.run());
	}

	@Override
	public void registerLeaveServerEvent(Runnable callback) {
		ClientPlayerNetworkEvent.LoggingOut.BUS.addListener((event) -> callback.run());
	}
}
