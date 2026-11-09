package nx.pingwheel.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.monitor.integration.MonitorPacketDispatch;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSubscribeC2SPacket;
import nx.pingwheel.common.network.InventoryMonitorStatusS2CPacket;
import nx.pingwheel.common.network.InventoryMonitorSampleS2CPacket;
import nx.pingwheel.common.network.EncodedPresentationPayload;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyC2SPacket;
import nx.pingwheel.common.network.ServerPresentationPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.Nullable;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class FabricMain implements ModInitializer {

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

	@Override
	public void onInitialize() {
		CommonServer.INSTANCE.onInit();
		ServerLifecycleEvents.SERVER_STOPPED.register(CommonServer.INSTANCE::onServerStopped);

		registerPayloadTypes();
		registerMonitorReceiver(CommonServer.INSTANCE::onInventoryMonitorSubscribe);

		ServerPlayNetworking.registerGlobalReceiver(
			UpdateChannelC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onChannelUpdatePacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			ServerConfigRequestC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onServerConfigRequestPacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			ServerConfigUpdateC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onServerConfigUpdatePacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			ServerPresentationPolicyC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onServerPresentationPolicyPacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			PresentationC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onPresentationPacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(
			InventoryC2SPacket.PACKET_TYPE,
			(packet, context) -> {
				final var player = context.player();
				final var server = context.server();
				server.execute(() -> CommonServer.INSTANCE.onInventoryPacket(server, player, packet));
			}
		);
		ServerPlayNetworking.registerGlobalReceiver(PresentationPreviewC2SPacket.PACKET_TYPE, (packet, context) -> {
			final var player = context.player();
			final var server = context.server();
			server.execute(() -> CommonServer.INSTANCE.onPresentationPreview(server, player, packet));
		});
	}

	static void registerPayloadTypes() {
		PayloadTypeRegistry.playC2S().register(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(RateLimitPolicyS2CPacket.PACKET_TYPE, RATE_LIMIT_POLICY_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(ServerConfigRequestC2SPacket.PACKET_TYPE, SERVER_CONFIG_REQUEST_C2S_CODEC);
		PayloadTypeRegistry.playC2S().register(ServerConfigUpdateC2SPacket.PACKET_TYPE, SERVER_CONFIG_UPDATE_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(ServerConfigSnapshotS2CPacket.PACKET_TYPE, SERVER_CONFIG_SNAPSHOT_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(ServerPresentationPolicyC2SPacket.PACKET_TYPE, SERVER_PRESENTATION_POLICY_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(ServerPresentationPolicyS2CPacket.PACKET_TYPE, SERVER_PRESENTATION_POLICY_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(PresentationC2SPacket.PACKET_TYPE, PRESENTATION_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(EncodedPresentationPayload.PACKET_TYPE, EncodedPresentationPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(InventoryC2SPacket.PACKET_TYPE, INVENTORY_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(InventoryS2CPacket.PACKET_TYPE, INVENTORY_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(InventoryMonitorSubscribeC2SPacket.PACKET_TYPE, INVENTORY_MONITOR_SUBSCRIBE_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(InventoryMonitorStatusS2CPacket.PACKET_TYPE, INVENTORY_MONITOR_STATUS_S2C_CODEC);
		PayloadTypeRegistry.playS2C().register(InventoryMonitorSampleS2CPacket.PACKET_TYPE, INVENTORY_MONITOR_SAMPLE_S2C_CODEC);
		PayloadTypeRegistry.playC2S().register(PresentationPreviewC2SPacket.PACKET_TYPE, PRESENTATION_PREVIEW_C2S_CODEC);
		PayloadTypeRegistry.playS2C().register(PresentationPreviewS2CPacket.PACKET_TYPE, PRESENTATION_PREVIEW_S2C_CODEC);
	}

	static void registerMonitorReceiver(TriConsumer<MinecraftServer, ServerPlayer, InventoryMonitorSubscribeC2SPacket> receiver) {
		ServerPlayNetworking.registerGlobalReceiver(InventoryMonitorSubscribeC2SPacket.PACKET_TYPE, (packet, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			@Nullable ServerGamePacketListenerImpl listener = player.connection;
			if (listener != null) MonitorPacketDispatch.server(server, server, player, listener, packet,
				current -> receiver.accept(server, current, packet));
		});
	}
}
