package nx.pingwheel.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.EventNetworkChannel;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;

import java.util.function.Consumer;
import java.util.function.Function;

import static nx.pingwheel.forge.ForgeMain.MARKER_CREATED_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.MARKER_REJECTED_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.MARKER_REMOVED_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.MARKER_WINNER_CHANGED_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.PING_LOCATION_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.RATE_LIMIT_POLICY_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.SERVER_CONFIG_SNAPSHOT_CHANNEL_S2C;

public class ForgeClient {

	public ForgeClient() {
		CommonClient.INSTANCE.onInit();

		MinecraftForge.EVENT_BUS.register(this);

		// packets
		registerPacketHandler(PING_LOCATION_CHANNEL_S2C, PingLocationS2CPacket::readSafe, CommonClient.INSTANCE::onPingLocationPacket);
		registerPacketHandler(MARKER_CREATED_CHANNEL_S2C, MarkerCreatedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerCreatedPacket);
		registerPacketHandler(MARKER_REMOVED_CHANNEL_S2C, MarkerRemovedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerRemovedPacket);
		registerPacketHandler(MARKER_REJECTED_CHANNEL_S2C, MarkerRejectedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerRejectedPacket);
		registerPacketHandler(MARKER_WINNER_CHANGED_CHANNEL_S2C, MarkerWinnerChangedS2CPacket::readSafe, CommonClient.INSTANCE::onMarkerWinnerChangedPacket);
		registerPacketHandler(RATE_LIMIT_POLICY_CHANNEL_S2C, RateLimitPolicyS2CPacket::readSafe, CommonClient.INSTANCE::onRateLimitPolicyPacket);
		registerPacketHandler(SERVER_CONFIG_SNAPSHOT_CHANNEL_S2C, ServerConfigSnapshotS2CPacket::readSafe, CommonClient.INSTANCE::onServerConfigSnapshotPacket);

		// resource reload
		FMLJavaModLoadingContext
			.get()
			.getModEventBus()
			.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(new ResourceReloadListener()));

		// config screen
		ModLoadingContext.get().registerExtensionPoint(
			ConfigScreenHandler.ConfigScreenFactory.class,
			() -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> new SettingsScreen(parent))
		);
	}

	public static <T> void registerPacketHandler(EventNetworkChannel channel, Function<FriendlyByteBuf, T> packetReader, Consumer<T> packetHandler) {
		channel.addListener((event) -> {
			var ctx = event.getSource();
			var payload = event.getPayload();

			if (payload != null) {
				var packet = packetReader.apply(payload);
				ctx.enqueueWork(() -> packetHandler.accept(packet));
			}

			ctx.setPacketHandled(true);
		});
	}

	@SubscribeEvent
	public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
		event.getDispatcher().register(ClientCommandBuilder.build((context, success, response) -> {
			if (success) {
				context.getSource().sendSuccess(() -> LanguageUtils.withModPrefix(response), false);
			} else {
				context.getSource().sendFailure(LanguageUtils.withModPrefix(response));
			}
		}));
	}
}
