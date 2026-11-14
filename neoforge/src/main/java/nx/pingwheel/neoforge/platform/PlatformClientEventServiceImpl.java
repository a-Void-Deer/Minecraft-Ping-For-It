package nx.pingwheel.neoforge.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import nx.pingwheel.common.platform.IPlatformClientEventService;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static nx.pingwheel.common.Global.MOD_ID;

public class PlatformClientEventServiceImpl implements IPlatformClientEventService {

	private static final AtomicInteger HUD_LAYER_COUNTER = new AtomicInteger();
	private static IEventBus modBus;

	public static void setModBus(IEventBus bus) {
		modBus = Objects.requireNonNull(bus, "modBus");
	}

	@Override
	public void registerTickStartEvent(Runnable callback) {
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> callback.run());
	}

	@Override
	public void registerJoinServerEvent(Runnable callback) {
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> callback.run());
	}

	@Override
	public void registerLeaveServerEvent(Runnable callback) {
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> callback.run());
	}

	@Override
	public void registerRenderFrameEvent(Runnable callback) {
		NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> callback.run());
	}

	@Override
	public void registerHudRenderEvent(HudRenderCallback callback) {
		Identifier layerId = Identifier.fromNamespaceAndPath(
			MOD_ID, "hud-layer-" + HUD_LAYER_COUNTER.incrementAndGet());
		modBus.addListener((RegisterGuiLayersEvent event) ->
			event.registerAboveAll(layerId, (graphics, delta) -> callback.render(graphics, delta)));
	}

	@Override
	public void registerWorldSubmitEvent(WorldSubmitCallback callback) {
		NeoForge.EVENT_BUS.addListener((SubmitCustomGeometryEvent event) ->
			callback.submit(new WorldSubmitContext(
				event.getPoseStack(),
				event.getSubmitNodeCollector(),
				Minecraft.getInstance().gameRenderer.mainCamera(),
				0.0f
			)));
	}
}
