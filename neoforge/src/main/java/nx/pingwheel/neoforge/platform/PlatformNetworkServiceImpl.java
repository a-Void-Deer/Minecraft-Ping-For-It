package nx.pingwheel.neoforge.platform;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;

public class PlatformNetworkServiceImpl implements IPlatformNetworkService {

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		if (!player.connection.hasChannel(packet)) {
			return;
		}

		PacketDistributor.sendToPlayer(player, packet);
	}
}
