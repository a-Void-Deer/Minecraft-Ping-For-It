package nx.pingwheel.neoforge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.CommonServer;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.SyncDurationPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.neoforge.platform.PlatformContextServiceImpl;

import static nx.pingwheel.common.Global.MOD_ID;

@Mod(MOD_ID)
public class NeoMain {

	private static final StreamCodec<FriendlyByteBuf, PingLocationS2CPacket> PING_LOCATION_S2C_CODEC = StreamCodec.ofMember(PingLocationS2CPacket::write, PingLocationS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, PingLocationC2SPacket> PING_LOCATION_C2S_CODEC = StreamCodec.ofMember(PingLocationC2SPacket::write, PingLocationC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, UpdateChannelC2SPacket> UPDATE_CHANNEL_C2S_CODEC = StreamCodec.ofMember(UpdateChannelC2SPacket::write, UpdateChannelC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerCreateC2SPacket> MARKER_CREATE_C2S_CODEC = StreamCodec.ofMember(MarkerCreateC2SPacket::write, MarkerCreateC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerRemoveC2SPacket> MARKER_REMOVE_C2S_CODEC = StreamCodec.ofMember(MarkerRemoveC2SPacket::write, MarkerRemoveC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerCreatedS2CPacket> MARKER_CREATED_S2C_CODEC = StreamCodec.ofMember(MarkerCreatedS2CPacket::write, MarkerCreatedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerRemovedS2CPacket> MARKER_REMOVED_S2C_CODEC = StreamCodec.ofMember(MarkerRemovedS2CPacket::write, MarkerRemovedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerRejectedS2CPacket> MARKER_REJECTED_S2C_CODEC = StreamCodec.ofMember(MarkerRejectedS2CPacket::write, MarkerRejectedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, MarkerWinnerChangedS2CPacket> MARKER_WINNER_CHANGED_S2C_CODEC = StreamCodec.ofMember(MarkerWinnerChangedS2CPacket::write, MarkerWinnerChangedS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, RateLimitPolicyS2CPacket> RATE_LIMIT_POLICY_S2C_CODEC = StreamCodec.ofMember(RateLimitPolicyS2CPacket::write, RateLimitPolicyS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, SyncDurationPolicyS2CPacket> SYNC_DURATION_POLICY_S2C_CODEC = StreamCodec.ofMember(SyncDurationPolicyS2CPacket::write, SyncDurationPolicyS2CPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigRequestC2SPacket> SERVER_CONFIG_REQUEST_C2S_CODEC = StreamCodec.ofMember(ServerConfigRequestC2SPacket::write, ServerConfigRequestC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigUpdateC2SPacket> SERVER_CONFIG_UPDATE_C2S_CODEC = StreamCodec.ofMember(ServerConfigUpdateC2SPacket::write, ServerConfigUpdateC2SPacket::readSafe);
	private static final StreamCodec<FriendlyByteBuf, ServerConfigSnapshotS2CPacket> SERVER_CONFIG_SNAPSHOT_S2C_CODEC = StreamCodec.ofMember(ServerConfigSnapshotS2CPacket::write, ServerConfigSnapshotS2CPacket::readSafe);

	public NeoMain(IEventBus modBus) {
		CommonServer.INSTANCE.onInit();

		PlatformContextServiceImpl.modBus = modBus;

		modBus.addListener(this::onRegisterPackets);

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

		registrar.playToServer(MarkerCreateC2SPacket.PACKET_TYPE, MARKER_CREATE_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onMarkerCreatePacket(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});

		registrar.playToServer(MarkerRemoveC2SPacket.PACKET_TYPE, MARKER_REMOVE_C2S_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonServer.INSTANCE.onMarkerRemovePacket(context.player().getServer(), (ServerPlayer)context.player(), payload));
		});

		registrar.playToClient(MarkerCreatedS2CPacket.PACKET_TYPE, MARKER_CREATED_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onMarkerCreatedPacket(payload));
		});

		registrar.playToClient(MarkerRemovedS2CPacket.PACKET_TYPE, MARKER_REMOVED_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onMarkerRemovedPacket(payload));
		});

		registrar.playToClient(MarkerRejectedS2CPacket.PACKET_TYPE, MARKER_REJECTED_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onMarkerRejectedPacket(payload));
		});

		registrar.playToClient(MarkerWinnerChangedS2CPacket.PACKET_TYPE, MARKER_WINNER_CHANGED_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onMarkerWinnerChangedPacket(payload));
		});
		registrar.playToClient(RateLimitPolicyS2CPacket.PACKET_TYPE, RATE_LIMIT_POLICY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onRateLimitPolicyPacket(payload));
		});
		registrar.playToClient(SyncDurationPolicyS2CPacket.PACKET_TYPE, SYNC_DURATION_POLICY_S2C_CODEC, (payload, context) -> {
			context.enqueueWork(() -> CommonClient.INSTANCE.onSyncDurationPolicyPacket(payload));
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
	}
}
