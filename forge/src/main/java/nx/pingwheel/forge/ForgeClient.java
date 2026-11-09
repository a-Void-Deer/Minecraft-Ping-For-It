package nx.pingwheel.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.EventNetworkChannel;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.monitor.integration.MonitorPacketDispatch;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.resource.ResourceReloadListener;
import nx.pingwheel.common.screen.SettingsScreen;
import nx.pingwheel.forge.integration.ForgeWorldAwareBlockModelOutlineAdapter;

import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

import static nx.pingwheel.forge.ForgeMain.INVENTORY_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.PRESENTATION_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.PRESENTATION_PREVIEW_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.RATE_LIMIT_POLICY_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.SERVER_CONFIG_SNAPSHOT_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.SERVER_PRESENTATION_POLICY_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.INVENTORY_MONITOR_STATUS_CHANNEL_S2C;
import static nx.pingwheel.forge.ForgeMain.INVENTORY_MONITOR_SAMPLE_CHANNEL_S2C;

public class ForgeClient {

	public ForgeClient() {
		CommonClient.INSTANCE.onInit();
		ForgeWorldAwareBlockModelOutlineAdapter.register();

		MinecraftForge.EVENT_BUS.register(this);

		// packets
		registerPacketHandler(RATE_LIMIT_POLICY_CHANNEL_S2C, RateLimitPolicyS2CPacket::readSafe, CommonClient.INSTANCE::onRateLimitPolicyPacket);
		registerPacketHandler(SERVER_CONFIG_SNAPSHOT_CHANNEL_S2C, ServerConfigSnapshotS2CPacket::readSafe, CommonClient.INSTANCE::onServerConfigSnapshotPacket);
		registerPacketHandler(SERVER_PRESENTATION_POLICY_CHANNEL_S2C, ServerPresentationPolicyS2CPacket::readSafe, CommonClient.INSTANCE::onServerPresentationPolicyPacket);
		registerPacketHandler(PRESENTATION_CHANNEL_S2C, PresentationS2CPacket::readSafe, CommonClient.INSTANCE::onPresentationPacket);
		registerPacketHandler(INVENTORY_CHANNEL_S2C, InventoryS2CPacket::readSafe, CommonClient.INSTANCE::onInventoryPacket);
		registerMonitorReceiver(INVENTORY_MONITOR_STATUS_CHANNEL_S2C, InventoryMonitorStatusS2CPacket::readSafe, CommonClient.INSTANCE::onMonitorStatus);
		registerMonitorReceiver(INVENTORY_MONITOR_SAMPLE_CHANNEL_S2C, InventoryMonitorSampleS2CPacket::readSafe, CommonClient.INSTANCE::onMonitorSample);
		registerPacketHandler(PRESENTATION_PREVIEW_CHANNEL_S2C, PresentationPreviewS2CPacket::readSafe, CommonClient.INSTANCE::onPresentationPreview);

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

	static <T extends IPacket> void registerMonitorReceiver(EventNetworkChannel channel,
		Function<FriendlyByteBuf, T> reader, BiConsumer<Long, T> receiver) {
		channel.addListener(event -> {
			var context = event.getSource();
			var payload = event.getPayload();
			if (context.isClientSide() && payload != null) {
				T packet = reader.apply(payload);
				Connection connection = context.getConnection();
				long token = CommonClient.INSTANCE.monitorConnectionToken();
				MonitorPacketDispatch.client(action -> context.enqueueWork(action), connection, () -> {
					@Nullable Minecraft client = Minecraft.getInstance();
					@Nullable ClientPacketListener current = client == null ? null : client.getConnection();
					return current == null ? null : current.getConnection();
				}, packet, () -> receiver.accept(token, packet));
			}
			context.setPacketHandled(true);
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
