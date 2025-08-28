package nx.pingwheel.fabric.platform;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
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

		connection.send(new ServerboundCustomPayloadPacket(packet));
	}

	@Override
	public void sendToClient(IPacket packet, ServerPlayer player) {
		player.connection.send(new ClientboundCustomPayloadPacket(packet));
	}
}
