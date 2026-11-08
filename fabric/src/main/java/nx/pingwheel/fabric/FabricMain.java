package nx.pingwheel.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.network.ChannelRegistrationPayload;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;

import java.util.function.Function;

public class FabricMain implements ModInitializer {

	/**
	 * Decodes a client packet inside the networking callback and runs the
	 * server handler on the main server thread.
	 *
	 * <p>The legacy receiver runs on a Netty event loop; the handler reads the
	 * world, block entities and player state, so it must never run there.</p>
	 */
	private static <T> void receive(
		ResourceLocation packetId,
		Function<FriendlyByteBuf, T> reader,
		PacketHandler<T> handler
	) {
		ServerPlayNetworking.registerGlobalReceiver(packetId, (server, player, networking, buf, responseSender) -> {
			T packet = reader.apply(buf);
			server.execute(() -> handler.handle(server, player, packet));
		});
	}

	@FunctionalInterface
	private interface PacketHandler<T> {
		void handle(MinecraftServer server, ServerPlayer player, T packet);
	}

	@Override
	public void onInitialize() {
		CommonServer.INSTANCE.onInit();

		// Forge 47.2.0 does not commit the final unterminated channel in a
		// Fabric register payload. Send a second, terminated announcement after
		// Fabric's initial one so a Forge client sees every C2S channel. The
		// delayed task runs after ServerPlayNetworkAddon's initial registration.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;

			server.execute(() -> {
				var channels = ServerPlayNetworking.getReceived(player);

				if (channels.isEmpty()) {
					return;
				}

				player.connection.send(new ClientboundCustomPayloadPacket(
					new ResourceLocation("minecraft", "register"),
					ChannelRegistrationPayload.encode(channels)));
			});
		});

		receive(PingLocationC2SPacket.PACKET_ID,
			PingLocationC2SPacket::readSafe, CommonServer.INSTANCE::onPingLocationPacket);
		receive(UpdateChannelC2SPacket.PACKET_ID,
			UpdateChannelC2SPacket::readSafe, CommonServer.INSTANCE::onChannelUpdatePacket);
		receive(MarkerCreateC2SPacket.PACKET_ID,
			MarkerCreateC2SPacket::readSafe, CommonServer.INSTANCE::onMarkerCreatePacket);
		receive(MarkerRemoveC2SPacket.PACKET_ID,
			MarkerRemoveC2SPacket::readSafe, CommonServer.INSTANCE::onMarkerRemovePacket);
		receive(ServerConfigRequestC2SPacket.PACKET_ID,
			ServerConfigRequestC2SPacket::readSafe, CommonServer.INSTANCE::onServerConfigRequestPacket);
		receive(ServerConfigUpdateC2SPacket.PACKET_ID,
			ServerConfigUpdateC2SPacket::readSafe, CommonServer.INSTANCE::onServerConfigUpdatePacket);
	}
}
