package nx.pingwheel.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.EventNetworkChannel;
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
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.forge.platform.PlatformNetworkServiceImpl;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.function.Function;

import static nx.pingwheel.common.Global.MOD_ID;

@Mod(MOD_ID)
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeMain {

	public static final EventNetworkChannel PING_LOCATION_CHANNEL_C2S = ChannelBuilder.named(PingLocationC2SPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel PING_LOCATION_CHANNEL_S2C = ChannelBuilder.named(PingLocationS2CPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel UPDATE_CHANNEL_C2S = ChannelBuilder.named(UpdateChannelC2SPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel MARKER_CREATE_CHANNEL_C2S = ChannelBuilder.named(MarkerCreateC2SPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel RATE_LIMIT_POLICY_CHANNEL_S2C = ChannelBuilder.named(RateLimitPolicyS2CPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel MARKER_REMOVE_CHANNEL_C2S = ChannelBuilder.named(MarkerRemoveC2SPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel MARKER_CREATED_CHANNEL_S2C = ChannelBuilder.named(MarkerCreatedS2CPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel MARKER_REMOVED_CHANNEL_S2C = ChannelBuilder.named(MarkerRemovedS2CPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel MARKER_REJECTED_CHANNEL_S2C = ChannelBuilder.named(MarkerRejectedS2CPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel MARKER_WINNER_CHANGED_CHANNEL_S2C = ChannelBuilder.named(MarkerWinnerChangedS2CPacket.PACKET_ID).optional().eventNetworkChannel();

	@SuppressWarnings({"java:S1118", "the public constructor is required by forge"})
	public ForgeMain() {
		CommonServer.INSTANCE.onInit();

		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ForgeClient::new);

		PlatformNetworkServiceImpl.CHANNEL_MAP.put(PingLocationC2SPacket.PACKET_ID, PING_LOCATION_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(PingLocationS2CPacket.PACKET_ID, PING_LOCATION_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(UpdateChannelC2SPacket.PACKET_ID, UPDATE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerCreateC2SPacket.PACKET_ID, MARKER_CREATE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(RateLimitPolicyS2CPacket.PACKET_ID, RATE_LIMIT_POLICY_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerRemoveC2SPacket.PACKET_ID, MARKER_REMOVE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerCreatedS2CPacket.PACKET_ID, MARKER_CREATED_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerRemovedS2CPacket.PACKET_ID, MARKER_REMOVED_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerRejectedS2CPacket.PACKET_ID, MARKER_REJECTED_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerWinnerChangedS2CPacket.PACKET_ID, MARKER_WINNER_CHANGED_CHANNEL_S2C);
		registerPacketHandler(PING_LOCATION_CHANNEL_C2S, PingLocationC2SPacket::readSafe, CommonServer.INSTANCE::onPingLocationPacket);
		registerPacketHandler(UPDATE_CHANNEL_C2S, UpdateChannelC2SPacket::readSafe, CommonServer.INSTANCE::onChannelUpdatePacket);
		registerPacketHandler(MARKER_CREATE_CHANNEL_C2S, MarkerCreateC2SPacket::readSafe, CommonServer.INSTANCE::onMarkerCreatePacket);
		registerPacketHandler(MARKER_REMOVE_CHANNEL_C2S, MarkerRemoveC2SPacket::readSafe, CommonServer.INSTANCE::onMarkerRemovePacket);
	}

	public static <T> void registerPacketHandler(EventNetworkChannel channel, Function<FriendlyByteBuf, T> packetReader, TriConsumer<MinecraftServer, ServerPlayer, T> packetHandler) {
		channel.addListener((event) -> {
			var ctx = event.getSource();
			var payload = event.getPayload();
			var sender = ctx.getSender();

			if (payload != null && sender != null) {
				var packet = packetReader.apply(payload);
				ctx.enqueueWork(() -> packetHandler.accept(sender.getServer(), sender, packet));
			}

			ctx.setPacketHandled(true);
		});
	}

	@SubscribeEvent
	public static void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(ServerCommandBuilder.build((context, success, response) -> {
			if (success) {
				context.getSource().sendSuccess(() -> LanguageUtils.withModPrefix(response), false);
			} else {
				context.getSource().sendFailure(LanguageUtils.withModPrefix(response));
			}
		}));
	}
}
