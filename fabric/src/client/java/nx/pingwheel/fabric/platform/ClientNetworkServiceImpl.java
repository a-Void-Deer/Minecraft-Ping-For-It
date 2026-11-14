package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IClientNetworkService;

public class ClientNetworkServiceImpl implements IClientNetworkService {

	@Override
	public void sendToServer(IPacket packet) {
		if (!ClientPlayNetworking.canSend(packet.getId())) {
			return;
		}

		ClientPlayNetworking.send(packet);
	}
}
