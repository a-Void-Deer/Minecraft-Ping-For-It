package nx.pingwheel.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.command.ServerCommandBuilder;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.neoforge.platform.PlatformContextServiceImpl;

import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.neoforge.NeoMain.NEOFORGE_ID;

@Mod(NEOFORGE_ID)
public class NeoMain {

	public static final String NEOFORGE_ID = "pingwheel";

	private static final StreamCodec<FriendlyByteBuf, PingLocationS2CPacket> PING_LOCATION_S2C_CODEC = StreamCodec.ofMember(PingLocationS2CPacket::write, PingLocationS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PingLocationC2SPacket> PING_LOCATION_C2S_CODEC = StreamCodec.ofMember(PingLocationC2SPacket::write, PingLocationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);

	public NeoMain(IEventBus modBus) {
		CommonServer.INSTANCE.onInit();

		PlatformContextServiceImpl.modBus = modBus;

		modBus.addListener(this::onRegisterPackets);
		NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

		if (FMLEnvironment.dist.isClient()) {
			new NeoClient(modBus);
		}
	}

	public void onRegisterPackets(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(MOD_ID).optional();

		registrar.playToClient(PingLocationS2CPacket.PACKET_TYPE, PING_LOCATION_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onPingLocationPacket(payload));
		});

		registrar.playToServer(PingLocationC2SPacket.PACKET_TYPE, PING_LOCATION_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onPingLocationPacket(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});

		registrar.playToServer(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onChannelUpdatePacket(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});
	}

	public void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(ServerCommandBuilder.build((context, success, response) -> {
			if (success) {
				context.getSource().sendSuccess(() -> LanguageUtils.withModPrefix(response), false);
			} else {
				context.getSource().sendFailure(LanguageUtils.withModPrefix(response));
			}
		}));
	}
}
