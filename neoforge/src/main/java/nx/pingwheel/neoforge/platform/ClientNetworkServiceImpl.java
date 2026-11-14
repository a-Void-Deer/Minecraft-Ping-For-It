package nx.pingwheel.neoforge.platform;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.platform.IClientNetworkService;

public class ClientNetworkServiceImpl implements IClientNetworkService {

	@Override
	public void sendToServer(IPacket packet) {
		var connection = Minecraft.getInstance().getConnection();

		if (connection == null || !connection.hasChannel(packet)) {
			return;
		}

		ClientPacketDistributor.sendToServer(packet);
	}
}
