package nx.pingwheel.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.command.ServerCommandBuilder;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.LanguageUtils;

public class FabricMain implements ModInitializer {

	private static final StreamCodec<FriendlyByteBuf, PingLocationS2CPacket> PING_LOCATION_S2C_CODEC = StreamCodec.ofMember(PingLocationS2CPacket::write, PingLocationS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PingLocationC2SPacket> PING_LOCATION_C2S_CODEC = StreamCodec.ofMember(PingLocationC2SPacket::write, PingLocationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);

	@Override
	public void onInitialize() {
		CommonServer.INSTANCE.onInit();

		PayloadTypeRegistry.playS2C().register(PingLocationS2CPacket.PACKET_TYPE, PING_LOCATION_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(PingLocationC2SPacket.PACKET_TYPE, PING_LOCATION_C2S_CODEC);
		PayloadTypeRegistry.playC2S().register(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(
			PingLocationC2SPacket.PACKET_TYPE,
			(packet, context)
				-> CommonServer.INSTANCE.onPingLocationPacket(context.server(), context.player(), packet)
		);
		ServerPlayNetworking.registerGlobalReceiver(
			UpdateChannelC2SPacket.PACKET_TYPE,
			(packet, context)
				-> CommonServer.INSTANCE.onChannelUpdatePacket(context.server(), context.player(), packet)
		);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(ServerCommandBuilder.build((context, success, response) -> {
				if (success) {
					context.getSource().sendSuccess(() -> LanguageUtils.withModPrefix(response), false);
				} else {
					context.getSource().sendFailure(LanguageUtils.withModPrefix(response));
				}
			}));
		});
	}
}
