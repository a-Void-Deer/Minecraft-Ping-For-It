package nx.pingwheel.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nx.pingwheel.common.commands.ServerCommandBuilder;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.core.ClientCore;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.helper.LanguageUtils;
import nx.pingwheel.common.networking.PingLocationC2SPacket;
import nx.pingwheel.common.networking.PingLocationS2CPacket;
import nx.pingwheel.common.networking.UpdateChannelC2SPacket;
import nx.pingwheel.neoforge.networking.NetworkHandler;

import static nx.pingwheel.common.Global.*;
import static nx.pingwheel.neoforge.Main.NEOFORGE_ID;

@Mod(NEOFORGE_ID)
public class Main {

	public static final String NEOFORGE_ID = "pingwheel";

	private static final StreamCodec<FriendlyByteBuf, PingLocationS2CPacket> PING_LOCATION_S2C_CODEC = StreamCodec.ofMember(PingLocationS2CPacket::write, PingLocationS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PingLocationC2SPacket> PING_LOCATION_C2S_CODEC = StreamCodec.ofMember(PingLocationC2SPacket::write, PingLocationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);

	public Main(IEventBus modBus) {
		LOGGER.info("Init");

		// Initialize networking
		NetHandler = new NetworkHandler();

		// Config setup
		ServerConfigHandler = new ConfigHandler<>(ServerConfig.class, FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".server.json"));
		ServerConfigHandler.load();

		// Mod Version setup
		ModVersion = ModList.get().getModContainerById(NEOFORGE_ID)
			.map(container -> container.getModInfo().getVersion().toString())
			.orElse("Unknown");

		modBus.addListener(this::onRegisterPackets);
		NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
		NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);

		if (FMLEnvironment.dist.isClient()) {
			new Client(modBus);
		}

		ServerCore.init();
	}

	public void onRegisterPackets(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(MOD_ID).optional();

		registrar.playToClient(PingLocationS2CPacket.PACKET_TYPE, PING_LOCATION_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> ClientCore.onPingLocation(payload));
		});

		registrar.playToServer(PingLocationC2SPacket.PACKET_TYPE, PING_LOCATION_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> ServerCore.onPingLocation(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});

		registrar.playToServer(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> ServerCore.onChannelUpdate((ServerPlayer)context.player(), payload));
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

	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		ServerCore.onPlayerDisconnect((ServerPlayer) event.getEntity());
	}
}
