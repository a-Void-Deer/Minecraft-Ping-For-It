package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;

public class PlatformNetworkServiceImpl implements IPlatformNetworkService {

	@Override
	public void sendToServer(IPacket packet) {
		if (!ClientPlayNetworking.canSend(packet.getId())) {
			return;
		}

		ClientPlayNetworking.send(packet);
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, packet.getId())) {
			return;
		}

		ServerPlayNetworking.send(player, packet);
	}
}
