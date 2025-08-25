package nx.pingwheel.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.config.ServerConfig;
import nx.pingwheel.common.core.ServerCore;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.platform.IPlatformServerEventService;

import static nx.pingwheel.common.Global.*;

public class CommonServer {

	public static CommonServer INSTANCE = new CommonServer();
	private CommonServer() {}

	public void onInit() {
		LOGGER.info("Init");

		ServerConfigHandler = new ConfigHandler<>(ServerConfig.class, IPlatformContextService.INSTANCE.resolveConfigDir(MOD_ID + ".server.json"));
		ServerConfigHandler.load();

		ServerCore.init();

		IPlatformServerEventService.INSTANCE.registerPlayerLogoutEvent(this::onPlayerDisconnect);
	}

	public void onPingLocationPacket(MinecraftServer server, ServerPlayer player, PingLocationC2SPacket packet) {
		ServerCore.onPingLocation(server, player, packet);
	}

	public void onChannelUpdatePacket(MinecraftServer server, ServerPlayer player, UpdateChannelC2SPacket packet) {
		ServerCore.onChannelUpdate(player, packet);
	}

	public void onPlayerDisconnect(ServerPlayer player) {
		ServerCore.onPlayerDisconnect(player);
	}
}
