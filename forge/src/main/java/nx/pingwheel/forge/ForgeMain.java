package nx.pingwheel.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.EventNetworkChannel;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.command.ServerCommandBuilder;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.forge.platform.PlatformContextServiceImpl;
import nx.pingwheel.forge.platform.PlatformNetworkServiceImpl;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.function.Function;

import static nx.pingwheel.forge.ForgeMain.FORGE_ID;

@Mod(FORGE_ID)
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeMain {

	public static final String FORGE_ID = "pingwheel";

	public static final EventNetworkChannel PING_LOCATION_CHANNEL_C2S = ChannelBuilder.named(PingLocationC2SPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel PING_LOCATION_CHANNEL_S2C = ChannelBuilder.named(PingLocationS2CPacket.PACKET_ID).optional().eventNetworkChannel();
	public static final EventNetworkChannel UPDATE_CHANNEL_C2S = ChannelBuilder.named(UpdateChannelC2SPacket.PACKET_ID).optional().eventNetworkChannel();

	@SuppressWarnings({"java:S1118", "the public constructor is required by forge"})
	public ForgeMain(FMLJavaModLoadingContext context) {
		CommonServer.INSTANCE.onInit();

		PlatformContextServiceImpl.context = context;

		if (FMLEnvironment.dist.isClient()) {
			new ForgeClient(context);
		}

		PlatformNetworkServiceImpl.CHANNEL_MAP.put(PingLocationC2SPacket.PACKET_ID, PING_LOCATION_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(PingLocationS2CPacket.PACKET_ID, PING_LOCATION_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(UpdateChannelC2SPacket.PACKET_ID, UPDATE_CHANNEL_C2S);
		registerPacketHandler(PING_LOCATION_CHANNEL_C2S, PingLocationC2SPacket::readSafe, CommonServer.INSTANCE::onPingLocationPacket);
		registerPacketHandler(UPDATE_CHANNEL_C2S, UpdateChannelC2SPacket::readSafe, CommonServer.INSTANCE::onChannelUpdatePacket);
	}

	public static <T> void registerPacketHandler(EventNetworkChannel channel, Function<FriendlyByteBuf, T> packetReader, TriConsumer<MinecraftServer, ServerPlayer, T> packetHandler) {
		channel.addListener((event) -> {
			var ctx = event.getSource();
			var payload = event.getPayload();
			var sender = ctx.getSender();

			if (payload != null && sender != null) {
				var packet = packetReader.apply(payload);
				ctx.enqueueWork(() -> packetHandler.accept(sender.level().getServer(), sender, packet));
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
