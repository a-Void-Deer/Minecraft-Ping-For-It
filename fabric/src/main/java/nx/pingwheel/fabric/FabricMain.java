package nx.pingwheel.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.command.ServerCommandBuilder;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.LanguageUtils;

public class FabricMain implements ModInitializer {

	private static final StreamCodec<FriendlyByteBuf, PingLocationS2CPacket> PING_LOCATION_S2C_CODEC = StreamCodec.ofMember(PingLocationS2CPacket::write, PingLocationS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PingLocationC2SPacket> PING_LOCATION_C2S_CODEC = StreamCodec.ofMember(PingLocationC2SPacket::write, PingLocationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerCreateC2SPacket> MARKER_CREATE_C2S_CODEC = StreamCodec.ofMember(MarkerCreateC2SPacket::write, MarkerCreateC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerRemoveC2SPacket> MARKER_REMOVE_C2S_CODEC = StreamCodec.ofMember(MarkerRemoveC2SPacket::write, MarkerRemoveC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerCreatedS2CPacket> MARKER_CREATED_S2C_CODEC = StreamCodec.ofMember(MarkerCreatedS2CPacket::write, MarkerCreatedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerRemovedS2CPacket> MARKER_REMOVED_S2C_CODEC = StreamCodec.ofMember(MarkerRemovedS2CPacket::write, MarkerRemovedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerRejectedS2CPacket> MARKER_REJECTED_S2C_CODEC = StreamCodec.ofMember(MarkerRejectedS2CPacket::write, MarkerRejectedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerWinnerChangedS2CPacket> MARKER_WINNER_CHANGED_S2C_CODEC = StreamCodec.ofMember(MarkerWinnerChangedS2CPacket::write, MarkerWinnerChangedS2CPacket::readSafe);

	@Override
	public void onInitialize() {
		CommonServer.INSTANCE.onInit();

		PayloadTypeRegistry.playS2C().register(PingLocationS2CPacket.PACKET_TYPE, PING_LOCATION_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(PingLocationC2SPacket.PACKET_TYPE, PING_LOCATION_C2S_CODEC);
		PayloadTypeRegistry.playC2S().register(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC);
		PayloadTypeRegistry.playC2S().register(MarkerCreateC2SPacket.PACKET_TYPE, MARKER_CREATE_C2S_CODEC);
		PayloadTypeRegistry.playC2S().register(MarkerRemoveC2SPacket.PACKET_TYPE, MARKER_REMOVE_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(MarkerCreatedS2CPacket.PACKET_TYPE, MARKER_CREATED_S2C_CODEC);
		PayloadTypeRegistry.playS2C().register(MarkerRemovedS2CPacket.PACKET_TYPE, MARKER_REMOVED_S2C_CODEC);
		PayloadTypeRegistry.playS2C().register(MarkerRejectedS2CPacket.PACKET_TYPE, MARKER_REJECTED_S2C_CODEC);
		PayloadTypeRegistry.playS2C().register(MarkerWinnerChangedS2CPacket.PACKET_TYPE, MARKER_WINNER_CHANGED_S2C_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(
			PingLocationC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onPingLocationPacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			UpdateChannelC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onChannelUpdatePacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			MarkerCreateC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onMarkerCreatePacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			MarkerRemoveC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onMarkerRemovePacket(server, player, packet));
			}
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
