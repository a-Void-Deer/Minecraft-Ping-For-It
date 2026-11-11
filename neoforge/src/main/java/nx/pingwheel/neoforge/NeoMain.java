package nx.pingwheel.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.monitor.integration.MonitorPacketDispatch;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.EncodedPresentationPayload;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.neoforge.platform.PlatformContextServiceImpl;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.Nullable;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

import static nx.pingwheel.common.Global.MOD_ID;

@Mod(MOD_ID)
public class NeoMain {

	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, RateLimitPolicyS2CPacket> RATE_LIMIT_POLICY_S2C_CODEC = StreamCodec.ofMember(RateLimitPolicyS2CPacket::write, RateLimitPolicyS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigRequestC2SPacket> SERVER_CONFIG_REQUEST_C2S_CODEC = StreamCodec.ofMember(ServerConfigRequestC2SPacket::write, ServerConfigRequestC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigUpdateC2SPacket> SERVER_CONFIG_UPDATE_C2S_CODEC = StreamCodec.ofMember(ServerConfigUpdateC2SPacket::write, ServerConfigUpdateC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigSnapshotS2CPacket> SERVER_CONFIG_SNAPSHOT_S2C_CODEC = StreamCodec.ofMember(ServerConfigSnapshotS2CPacket::write, ServerConfigSnapshotS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerPresentationPolicyC2SPacket> SERVER_PRESENTATION_POLICY_C2S_CODEC = StreamCodec.ofMember(ServerPresentationPolicyC2SPacket::write, ServerPresentationPolicyC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerPresentationPolicyS2CPacket> SERVER_PRESENTATION_POLICY_S2C_CODEC = StreamCodec.ofMember(ServerPresentationPolicyS2CPacket::write, ServerPresentationPolicyS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationC2SPacket> PRESENTATION_C2S_CODEC = StreamCodec.ofMember(PresentationC2SPacket::write, PresentationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryC2SPacket> INVENTORY_C2S_CODEC = StreamCodec.ofMember(InventoryC2SPacket::write, InventoryC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryS2CPacket> INVENTORY_S2C_CODEC = StreamCodec.ofMember(InventoryS2CPacket::write, InventoryS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryMonitorSubscribeC2SPacket> INVENTORY_MONITOR_SUBSCRIBE_C2S_CODEC = StreamCodec.ofMember(InventoryMonitorSubscribeC2SPacket::write, InventoryMonitorSubscribeC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryMonitorStatusS2CPacket> INVENTORY_MONITOR_STATUS_S2C_CODEC = StreamCodec.ofMember(InventoryMonitorStatusS2CPacket::write, InventoryMonitorStatusS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, InventoryMonitorSampleS2CPacket> INVENTORY_MONITOR_SAMPLE_S2C_CODEC = StreamCodec.ofMember(InventoryMonitorSampleS2CPacket::write, InventoryMonitorSampleS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationPreviewC2SPacket> PRESENTATION_PREVIEW_C2S_CODEC = StreamCodec.ofMember(PresentationPreviewC2SPacket::write, PresentationPreviewC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PresentationPreviewS2CPacket> PRESENTATION_PREVIEW_S2C_CODEC = StreamCodec.ofMember(PresentationPreviewS2CPacket::write, PresentationPreviewS2CPacket::readSafe);

	public NeoMain(IEventBus modBus) {
		CommonServer.INSTANCE.onInit();
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> CommonServer.INSTANCE.onServerStopped(event.getServer()));

		PlatformContextServiceImpl.modBus = modBus;

		modBus.addListener(this::onRegisterPackets);

		if (FMLEnvironment.dist.isClient()) {
			new NeoClient(modBus);
		}
	}

	public void onRegisterPackets(RegisterPayloadHandlersEvent event) {
		registerPackets(event.registrar(MOD_ID).optional());
	}

	static void registerPackets(PayloadRegistrar registrar) {
		registerMonitorPackets(registrar, CommonServer.INSTANCE::onInventoryMonitorSubscribe,
			(token, packet) -> CommonClient.INSTANCE.onMonitorStatus(token, packet),
			(token, packet) -> CommonClient.INSTANCE.onMonitorSample(token, packet));
		registrar.playToServer(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onChannelUpdatePacket(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});

		registrar.playToClient(RateLimitPolicyS2CPacket.PACKET_TYPE, RATE_LIMIT_POLICY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onRateLimitPolicyPacket(payload));
		});

		registrar.playToServer(ServerConfigRequestC2SPacket.PACKET_TYPE, SERVER_CONFIG_REQUEST_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onServerConfigRequestPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToServer(ServerConfigUpdateC2SPacket.PACKET_TYPE, SERVER_CONFIG_UPDATE_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onServerConfigUpdatePacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(ServerConfigSnapshotS2CPacket.PACKET_TYPE, SERVER_CONFIG_SNAPSHOT_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onServerConfigSnapshotPacket(payload));
		});

		registrar.playToServer(ServerPresentationPolicyC2SPacket.PACKET_TYPE, SERVER_PRESENTATION_POLICY_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onServerPresentationPolicyPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(ServerPresentationPolicyS2CPacket.PACKET_TYPE, SERVER_PRESENTATION_POLICY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onServerPresentationPolicyPacket(payload));
		});

		registrar.playToServer(PresentationC2SPacket.PACKET_TYPE, PRESENTATION_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onPresentationPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(EncodedPresentationPayload.PACKET_TYPE, EncodedPresentationPayload.STREAM_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onPresentationPacket((PresentationS2CPacket) payload));
		});

		registrar.playToServer(InventoryC2SPacket.PACKET_TYPE, INVENTORY_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onInventoryPacket(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});

		registrar.playToClient(InventoryS2CPacket.PACKET_TYPE, INVENTORY_S2C_CODEC, (payload, context) -> {
			Connection origin = context.connection();
			context.enqueueWork(() -> {
				@Nullable ClientPacketListener current = Minecraft.getInstance().getConnection();
				if (current != null && current.getConnection() == origin && origin.isConnected()) CommonClient.INSTANCE.onInventoryPacket(payload);
			});
		});
		registrar.playToServer(PresentationPreviewC2SPacket.PACKET_TYPE, PRESENTATION_PREVIEW_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onPresentationPreview(context.player().getServer(), (ServerPlayer) context.player(), payload));
		});
		registrar.playToClient(PresentationPreviewS2CPacket.PACKET_TYPE, PRESENTATION_PREVIEW_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onPresentationPreview(payload));
		});
	}

	static void registerMonitorPackets(PayloadRegistrar registrar,
		TriConsumer<MinecraftServer, ServerPlayer, InventoryMonitorSubscribeC2SPacket> subscribe,
		BiConsumer<Long, InventoryMonitorStatusS2CPacket> status, BiConsumer<Long, InventoryMonitorSampleS2CPacket> sample) {
		registrar.playToServer(InventoryMonitorSubscribeC2SPacket.PACKET_TYPE, INVENTORY_MONITOR_SUBSCRIBE_C2S_CODEC,
			(packet, context) -> {
				if (!(context.player() instanceof ServerPlayer player)) return;
				@Nullable MinecraftServer server = player.getServer();
				@Nullable ServerGamePacketListenerImpl listener = player.connection;
				if (server != null && listener != null && listener == context.listener()) {
					MonitorPacketDispatch.server(action -> context.enqueueWork(action), server, player, listener, packet,
						current -> subscribe.accept(server, current, packet));
				}
			});
		registrar.playToClient(InventoryMonitorStatusS2CPacket.PACKET_TYPE, INVENTORY_MONITOR_STATUS_S2C_CODEC,
			(packet, context) -> dispatchMonitorClient(packet, context, token -> status.accept(token, packet)));
		registrar.playToClient(InventoryMonitorSampleS2CPacket.PACKET_TYPE, INVENTORY_MONITOR_SAMPLE_S2C_CODEC,
			(packet, context) -> dispatchMonitorClient(packet, context, token -> sample.accept(token, packet)));
	}

	private static void dispatchMonitorClient(IPacket packet, IPayloadContext context, LongConsumer receive) {
		Connection connection = context.connection();
		long token = CommonClient.INSTANCE.monitorConnectionToken();
		MonitorPacketDispatch.client(action -> context.enqueueWork(action), connection, () -> {
			@Nullable Minecraft client = Minecraft.getInstance();
			@Nullable ClientPacketListener current = client == null ? null : client.getConnection();
			return current == null ? null : current.getConnection();
		}, packet, () -> receive.accept(token));
	}
}
