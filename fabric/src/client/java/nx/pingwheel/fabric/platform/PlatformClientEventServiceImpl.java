package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import nx.pingwheel.common.platform.IPlatformClientEventService;
import nx.pingwheel.common.platform.IPlatformClientEventService.HudRenderCallback;
import nx.pingwheel.common.platform.IPlatformClientEventService.WorldSubmitCallback;
import nx.pingwheel.common.platform.IPlatformClientEventService.WorldSubmitContext;

import java.util.concurrent.atomic.AtomicInteger;

import static nx.pingwheel.common.Global.MOD_ID;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	private static final AtomicInteger HUD_LAYER_COUNTER = new AtomicInteger();

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
	public void registerRenderFrameEvent(Runnable callback) {
		LevelExtractionEvents.END_EXTRACTION.register(context -> callback.run());
	}

	@Override
	public void registerHudRenderEvent(HudRenderCallback callback) {
		Identifier layerId = Identifier.fromNamespaceAndPath(
			MOD_ID, "hud-layer-" + HUD_LAYER_COUNTER.incrementAndGet());
		HudElementRegistry.addLast(layerId, (graphics, deltaTracker) -> callback.render(graphics, deltaTracker));
	}

	@Override
	public void registerWorldSubmitEvent(WorldSubmitCallback callback) {
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> callback.submit(new WorldSubmitContext(
			context.poseStack(),
			context.submitNodeCollector(),
			Minecraft.getInstance().gameRenderer.mainCamera(),
			0.0f /* BlockOutlinePass does not use partialTick; camera position is current */)));
	}
}
