package nx.pingwheel.neoforge.networking;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import nx.pingwheel.common.networking.INetworkHandler;
import nx.pingwheel.common.networking.IPacket;

import static nx.pingwheel.common.ClientGlobal.Game;

public class NetworkHandler implements INetworkHandler {

	@Override
	public void sendToServer(IPacket packet) {
		var connection = Game.getConnection();

		if (connection == null) {
			return;
		}

		ClientPacketDistributor.sendToServer(packet);
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		PacketDistributor.sendToPlayer(player, packet);
	}
}
