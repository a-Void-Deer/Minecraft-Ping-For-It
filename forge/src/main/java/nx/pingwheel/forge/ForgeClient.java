package nx.pingwheel.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.EventNetworkChannel;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;

import java.util.function.Consumer;
import java.util.function.Function;

import static nx.pingwheel.forge.ForgeMain.PING_LOCATION_CHANNEL_S2C;

public class ForgeClient {

	protected FMLJavaModLoadingContext context;

	public ForgeClient(FMLJavaModLoadingContext context) {
		CommonClient.INSTANCE.onInit();

		this.context = context;

		// packets
		registerPacketHandler(PING_LOCATION_CHANNEL_S2C, PingLocationS2CPacket::readSafe, CommonClient.INSTANCE::onPingLocationPacket);

		// resource reload
		RegisterClientReloadListenersEvent
			.getBus(this.context.getModBusGroup())
			.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(new ResourceReloadListener()));

		// commands
		RegisterClientCommandsEvent.BUS.addListener((RegisterClientCommandsEvent event) -> {
			event.getDispatcher().register(ClientCommandBuilder.build((ctx, success, response) -> {
				if (success) {
					ctx.getSource().sendSuccess(() -> LanguageUtils.withModPrefix(response), false);
				} else {
					ctx.getSource().sendFailure(LanguageUtils.withModPrefix(response));
				}
			}));
		});

		// config screen
		this.context.registerExtensionPoint(
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
}
