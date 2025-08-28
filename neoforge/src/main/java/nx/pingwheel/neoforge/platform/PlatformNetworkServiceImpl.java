package nx.pingwheel.neoforge.platform;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;

import static nx.pingwheel.common.CommonClient.Game;

public class PlatformNetworkServiceImpl implements IPlatformNetworkService {

	@Override
	public void sendToServer(IPacket packet) {
		var connection = Game.getConnection();

		if (connection == null) {
			return;
		}

		PacketDistributor.sendToServer(packet);
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		PacketDistributor.sendToPlayer(player, packet);
	}
}
