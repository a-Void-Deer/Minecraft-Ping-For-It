package nx.pingwheel.neoforge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;
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
import nx.pingwheel.common.resource.ResourceConstants;

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

	private static final DeferredRegister<SoundEvent> SOUND_EVENTS =
		DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, MOD_ID);

	static {
		SOUND_EVENTS.register("ping", () -> ResourceConstants.PING_SOUND_EVENT);
	}

	public NeoMain(IEventBus modBus) {
		SOUND_EVENTS.register(modBus);
		CommonServer.INSTANCE.onInit();

		modBus.addListener(this::onRegisterPackets);

		if (FMLEnvironment.getDist() == Dist.CLIENT) {
			new NeoClient(modBus);
		}
	}

	public void onRegisterPackets(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(MOD_ID).optional();

		// Clientbound payload types are registered here. Their handlers live in
		// NeoClient, so a dedicated server never loads client-only handler code.
		registrar.playToClient(PingLocationS2CPacket.PACKET_TYPE, PING_LOCATION_S2C_CODEC);
		registrar.playToClient(MarkerCreatedS2CPacket.PACKET_TYPE, MARKER_CREATED_S2C_CODEC);
		registrar.playToClient(MarkerRemovedS2CPacket.PACKET_TYPE, MARKER_REMOVED_S2C_CODEC);
		registrar.playToClient(MarkerRejectedS2CPacket.PACKET_TYPE, MARKER_REJECTED_S2C_CODEC);
		registrar.playToClient(MarkerWinnerChangedS2CPacket.PACKET_TYPE, MARKER_WINNER_CHANGED_S2C_CODEC);
		registrar.playToClient(RateLimitPolicyS2CPacket.PACKET_TYPE, RATE_LIMIT_POLICY_S2C_CODEC);
		registrar.playToClient(SyncDurationPolicyS2CPacket.PACKET_TYPE, SYNC_DURATION_POLICY_S2C_CODEC);
		registrar.playToClient(ServerConfigSnapshotS2CPacket.PACKET_TYPE, SERVER_CONFIG_SNAPSHOT_S2C_CODEC);

		registrar.playToServer(PingLocationC2SPacket.PACKET_TYPE, PING_LOCATION_C2S_CODEC, (payload, context) ->
			context.enqueueWork(() -> withServerPlayer(context.player(), (player, server) ->
				CommonServer.INSTANCE.onPingLocationPacket(server, player, payload))));
		registrar.playToServer(UpdateChannelC2SPacket.PACKET_TYPE, UPDATE_CHANNEL_C2S_CODEC, (payload, context) ->
			context.enqueueWork(() -> withServerPlayer(context.player(), (player, server) ->
				CommonServer.INSTANCE.onChannelUpdatePacket(server, player, payload))));
		registrar.playToServer(MarkerCreateC2SPacket.PACKET_TYPE, MARKER_CREATE_C2S_CODEC, (payload, context) ->
			context.enqueueWork(() -> withServerPlayer(context.player(), (player, server) ->
				CommonServer.INSTANCE.onMarkerCreatePacket(server, player, payload))));
		registrar.playToServer(MarkerRemoveC2SPacket.PACKET_TYPE, MARKER_REMOVE_C2S_CODEC, (payload, context) ->
			context.enqueueWork(() -> withServerPlayer(context.player(), (player, server) ->
				CommonServer.INSTANCE.onMarkerRemovePacket(server, player, payload))));
		registrar.playToServer(ServerConfigRequestC2SPacket.PACKET_TYPE, SERVER_CONFIG_REQUEST_C2S_CODEC, (payload, context) ->
			context.enqueueWork(() -> withServerPlayer(context.player(), (player, server) ->
				CommonServer.INSTANCE.onServerConfigRequestPacket(server, player, payload))));
		registrar.playToServer(ServerConfigUpdateC2SPacket.PACKET_TYPE, SERVER_CONFIG_UPDATE_C2S_CODEC, (payload, context) ->
			context.enqueueWork(() -> withServerPlayer(context.player(), (player, server) ->
				CommonServer.INSTANCE.onServerConfigUpdatePacket(server, player, payload))));
	}

	private static void withServerPlayer(net.minecraft.world.entity.player.Player player, java.util.function.BiConsumer<ServerPlayer, MinecraftServer> action) {
		if (player instanceof ServerPlayer serverPlayer) {
			MinecraftServer server = serverPlayer.level().getServer();
			if (server != null) {
				action.accept(serverPlayer, server);
			}
		}
	}
}
