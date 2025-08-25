package nx.pingwheel.common;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.core.ClientCore;
import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformClientEventService;

import static nx.pingwheel.common.ClientGlobal.*;
import static nx.pingwheel.common.Global.MOD_ID;

public class CommonClient {

	public static CommonClient INSTANCE = new CommonClient();
	private CommonClient() {}

	public void onInit() {
		ConfigHandler = new ConfigHandler<>(ClientConfig.class, IPlatformContextService.INSTANCE.resolveConfigDir(MOD_ID + ".json"));
		ConfigHandler.load();

		IPlatformClientEventService.INSTANCE.registerTickStartEvent(this::onTickStart);
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(this::onJoinServer);
		IPlatformClientEventService.INSTANCE.registerLeaveServerEvent(this::onLeaveServer);
		IPlatformClientEventService.INSTANCE.registerRenderWorldEvent(this::onRenderWorld);
		IPlatformClientEventService.INSTANCE.registerRenderGUIEvent(this::onRenderGUI);

		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_PING);
		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_SETTINGS);
		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_NAME_LABELS);
	}

	public void onJoinServer() {
		IPlatformNetworkService.INSTANCE.sendToServer(new UpdateChannelC2SPacket(ConfigHandler.getConfig().getChannel()));
	}

	public void onLeaveServer() {
		ClientCore.onDisconnect();
	}

	public void onTickStart() {
		ClientCore.onTick();
	}

	public void onRenderWorld(Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float tickDelta) {
		ClientCore.onRenderWorld(modelViewMatrix, projectionMatrix, tickDelta);
	}

	public void onRenderGUI(PoseStack poseStack, float tickDelta) {
		ClientCore.onRenderGUI(poseStack, tickDelta);
	}

	public void onPingLocationPacket(PingLocationS2CPacket packet) {
		ClientCore.onPingLocation(packet);
	}
}
