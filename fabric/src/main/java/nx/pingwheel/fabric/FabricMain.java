package nx.pingwheel.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;

public class FabricMain implements ModInitializer {

	@Override
	public void onInitialize() {
		CommonServer.INSTANCE.onInit();

		ServerPlayNetworking.registerGlobalReceiver(
			PingLocationC2SPacket.PACKET_ID,
			(server, player, handler, buf, responseSender)
				-> CommonServer.INSTANCE.onPingLocationPacket(server, player, PingLocationC2SPacket.readSafe(buf))
		);
		ServerPlayNetworking.registerGlobalReceiver(
			UpdateChannelC2SPacket.PACKET_ID,
			(server, player, handler, buf, responseSender)
				-> CommonServer.INSTANCE.onChannelUpdatePacket(server, player, UpdateChannelC2SPacket.readSafe(buf))
		);
		ServerPlayNetworking.registerGlobalReceiver(
			MarkerCreateC2SPacket.PACKET_ID,
			(server, player, handler, buf, responseSender)
				-> CommonServer.INSTANCE.onMarkerCreatePacket(server, player, MarkerCreateC2SPacket.readSafe(buf))
		);
		ServerPlayNetworking.registerGlobalReceiver(
			MarkerRemoveC2SPacket.PACKET_ID,
			(server, player, handler, buf, responseSender)
				-> CommonServer.INSTANCE.onMarkerRemovePacket(server, player, MarkerRemoveC2SPacket.readSafe(buf))
		);
		ServerPlayNetworking.registerGlobalReceiver(
			ServerConfigRequestC2SPacket.PACKET_ID,
			(server, player, handler, buf, responseSender)
				-> CommonServer.INSTANCE.onServerConfigRequestPacket(server, player, ServerConfigRequestC2SPacket.readSafe(buf))
		);
		ServerPlayNetworking.registerGlobalReceiver(
			ServerConfigUpdateC2SPacket.PACKET_ID,
			(server, player, handler, buf, responseSender)
				-> CommonServer.INSTANCE.onServerConfigUpdatePacket(server, player, ServerConfigUpdateC2SPacket.readSafe(buf))
		);
	}
}
