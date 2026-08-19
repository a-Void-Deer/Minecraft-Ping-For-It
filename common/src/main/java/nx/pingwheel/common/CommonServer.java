package nx.pingwheel.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformServerEventService;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.common.Global.MOD_VERSION;

public class CommonServer {

	public static final CommonServer INSTANCE = new CommonServer();
	private CommonServer() {}

	private boolean serverEventsRegistered;

	public void onInit() {
		LOGGER.debug("server init: mod_id={} mod_version={}", MOD_ID, MOD_VERSION);

		LOGGER.info("Init");

		ServerConfig.HANDLER.load();

		ModContext.indexMods();
		ServerCore.init();

		if (!serverEventsRegistered) {
			serverEventsRegistered = true;
			ServerCore.initMarkers();
			IPlatformServerEventService.INSTANCE.registerPlayerLogoutEvent(this::onPlayerDisconnect);
			IPlatformServerEventService.INSTANCE.registerServerTickEvent(this::onServerTick);
		}
	}

	public void onPingLocationPacket(MinecraftServer server, ServerPlayer player, PingLocationC2SPacket packet) {
		ServerCore.onPingLocation(server, player, packet);
	}

	public void onChannelUpdatePacket(MinecraftServer server, ServerPlayer player, UpdateChannelC2SPacket packet) {
		ServerCore.onChannelUpdate(player, packet);
	}

	public void onMarkerCreatePacket(MinecraftServer server, ServerPlayer player, MarkerCreateC2SPacket packet) {
		ServerCore.onMarkerCreate(server, player, packet);
	}

	public void onMarkerRemovePacket(MinecraftServer server, ServerPlayer player, MarkerRemoveC2SPacket packet) {
		ServerCore.onMarkerRemove(server, player, packet);
	}

	public void onServerConfigRequestPacket(MinecraftServer server, ServerPlayer player, ServerConfigRequestC2SPacket packet) {
		ServerCore.onServerConfigRequest(server, player, packet);
	}

	public void onServerConfigUpdatePacket(MinecraftServer server, ServerPlayer player, ServerConfigUpdateC2SPacket packet) {
		ServerCore.onServerConfigUpdate(server, player, packet);
	}

	public void onServerTick(MinecraftServer server) {
		ServerCore.onServerTick(server);
	}

	public void onPlayerDisconnect(ServerPlayer player) {
		ServerCore.onPlayerDisconnect(player);
	}
}
