package nx.pingwheel.fabric.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import nx.pingwheel.common.platform.IPlatformClientEventService;
import nx.pingwheel.fabric.event.GuiRenderCallback;
import nx.pingwheel.fabric.event.WorldRenderCallback;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.function.BiConsumer;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	@Override
	public void registerTickStartEvent(Runnable callback) {
		ClientTickEvents.START_CLIENT_TICK.register(client -> callback.run());
	}

	@Override
	public void registerJoinServerEvent(Runnable callback) {
		ClientPlayConnectionEvents.JOIN.register((a, b, c) -> callback.run());
	}

	@Override
	public void registerLeaveServerEvent(Runnable callback) {
		ClientPlayConnectionEvents.DISCONNECT.register((a, b) -> callback.run());
	}

	@Override
	public void registerRenderWorldEvent(TriConsumer<Matrix4f, Matrix4f, Float> callback) {
		WorldRenderCallback.START.register(callback::accept);
	}

	@Override
	public void registerRenderGUIEvent(BiConsumer<PoseStack, Float> callback) {
		GuiRenderCallback.START.register(callback::accept);
	}
}
