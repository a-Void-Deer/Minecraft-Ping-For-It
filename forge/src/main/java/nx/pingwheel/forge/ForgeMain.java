package nx.pingwheel.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
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
import nx.pingwheel.forge.platform.PlatformNetworkServiceImpl;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.function.Function;

import static nx.pingwheel.common.Global.MOD_ID;

@Mod(MOD_ID)
public class ForgeMain {

	private static final String PROTOCOL_VERSION = "1";

	private static EventNetworkChannel channel(ResourceLocation id) {
		return NetworkRegistry.newEventChannel(
			id,
			() -> PROTOCOL_VERSION,
			client -> true,
			server -> true);
	}

	public static final EventNetworkChannel PING_LOCATION_CHANNEL_C2S = channel(PingLocationC2SPacket.PACKET_ID);
	public static final EventNetworkChannel PING_LOCATION_CHANNEL_S2C = channel(PingLocationS2CPacket.PACKET_ID);
	public static final EventNetworkChannel UPDATE_CHANNEL_C2S = channel(UpdateChannelC2SPacket.PACKET_ID);
	public static final EventNetworkChannel MARKER_CREATE_CHANNEL_C2S = channel(MarkerCreateC2SPacket.PACKET_ID);
	public static final EventNetworkChannel RATE_LIMIT_POLICY_CHANNEL_S2C = channel(RateLimitPolicyS2CPacket.PACKET_ID);
	public static final EventNetworkChannel SYNC_DURATION_POLICY_CHANNEL_S2C = channel(SyncDurationPolicyS2CPacket.PACKET_ID);
	public static final EventNetworkChannel SERVER_CONFIG_REQUEST_CHANNEL_C2S = channel(ServerConfigRequestC2SPacket.PACKET_ID);
	public static final EventNetworkChannel SERVER_CONFIG_UPDATE_CHANNEL_C2S = channel(ServerConfigUpdateC2SPacket.PACKET_ID);
	public static final EventNetworkChannel SERVER_CONFIG_SNAPSHOT_CHANNEL_S2C = channel(ServerConfigSnapshotS2CPacket.PACKET_ID);
	public static final EventNetworkChannel MARKER_REMOVE_CHANNEL_C2S = channel(MarkerRemoveC2SPacket.PACKET_ID);
	public static final EventNetworkChannel MARKER_CREATED_CHANNEL_S2C = channel(MarkerCreatedS2CPacket.PACKET_ID);
	public static final EventNetworkChannel MARKER_REMOVED_CHANNEL_S2C = channel(MarkerRemovedS2CPacket.PACKET_ID);
	public static final EventNetworkChannel MARKER_REJECTED_CHANNEL_S2C = channel(MarkerRejectedS2CPacket.PACKET_ID);
	public static final EventNetworkChannel MARKER_WINNER_CHANGED_CHANNEL_S2C = channel(MarkerWinnerChangedS2CPacket.PACKET_ID);

	@SuppressWarnings({"java:S1118", "the public constructor is required by forge"})
	public ForgeMain() {
		CommonServer.INSTANCE.onInit();

		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ForgeClient::new);

		PlatformNetworkServiceImpl.CHANNEL_MAP.put(PingLocationC2SPacket.PACKET_ID, PING_LOCATION_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(PingLocationS2CPacket.PACKET_ID, PING_LOCATION_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(UpdateChannelC2SPacket.PACKET_ID, UPDATE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerCreateC2SPacket.PACKET_ID, MARKER_CREATE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(RateLimitPolicyS2CPacket.PACKET_ID, RATE_LIMIT_POLICY_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(SyncDurationPolicyS2CPacket.PACKET_ID, SYNC_DURATION_POLICY_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(ServerConfigRequestC2SPacket.PACKET_ID, SERVER_CONFIG_REQUEST_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(ServerConfigUpdateC2SPacket.PACKET_ID, SERVER_CONFIG_UPDATE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(ServerConfigSnapshotS2CPacket.PACKET_ID, SERVER_CONFIG_SNAPSHOT_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerRemoveC2SPacket.PACKET_ID, MARKER_REMOVE_CHANNEL_C2S);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerCreatedS2CPacket.PACKET_ID, MARKER_CREATED_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerRemovedS2CPacket.PACKET_ID, MARKER_REMOVED_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerRejectedS2CPacket.PACKET_ID, MARKER_REJECTED_CHANNEL_S2C);
		PlatformNetworkServiceImpl.CHANNEL_MAP.put(MarkerWinnerChangedS2CPacket.PACKET_ID, MARKER_WINNER_CHANGED_CHANNEL_S2C);
		registerPacketHandler(PING_LOCATION_CHANNEL_C2S, PingLocationC2SPacket::readSafe, CommonServer.INSTANCE::onPingLocationPacket);
		registerPacketHandler(UPDATE_CHANNEL_C2S, UpdateChannelC2SPacket::readSafe, CommonServer.INSTANCE::onChannelUpdatePacket);
		registerPacketHandler(MARKER_CREATE_CHANNEL_C2S, MarkerCreateC2SPacket::readSafe, CommonServer.INSTANCE::onMarkerCreatePacket);
		registerPacketHandler(MARKER_REMOVE_CHANNEL_C2S, MarkerRemoveC2SPacket::readSafe, CommonServer.INSTANCE::onMarkerRemovePacket);
		registerPacketHandler(SERVER_CONFIG_REQUEST_CHANNEL_C2S, ServerConfigRequestC2SPacket::readSafe, CommonServer.INSTANCE::onServerConfigRequestPacket);
		registerPacketHandler(SERVER_CONFIG_UPDATE_CHANNEL_C2S, ServerConfigUpdateC2SPacket::readSafe, CommonServer.INSTANCE::onServerConfigUpdatePacket);
	}

	public static <T> void registerPacketHandler(EventNetworkChannel channel, Function<FriendlyByteBuf, T> packetReader, TriConsumer<MinecraftServer, ServerPlayer, T> packetHandler) {
		channel.addListener((event) -> {
			var ctx = event.getSource().get();
			var payload = event.getPayload();
			var sender = ctx.getSender();

			if (payload != null && sender != null) {
				var packet = packetReader.apply(payload);
				ctx.enqueueWork(() -> packetHandler.accept(sender.getServer(), sender, packet));
			}

			ctx.setPacketHandled(true);
		});
	}

}
