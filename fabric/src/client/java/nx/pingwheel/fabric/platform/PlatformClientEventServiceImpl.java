package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import nx.pingwheel.common.platform.IPlatformClientEventService;

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

	/**
	 * Fires once per frame from {@code LevelExtractor#extract} (RETURN), which
	 * 26.3 calls once per frame from {@code GameRenderer#extract} whenever a level
	 * is extracted. That path is not gated by HUD visibility, so it keeps running
	 * while {@code F1} hides the HUD — the reason this hook is not a
	 * {@code HudElement}. It is also not gated on terrain being drawn, so unlike
	 * {@code LevelRenderEvents.START_MAIN} it cannot fire once per render pass.
	 */
	@Override
	public void registerRenderFrameEvent(Runnable callback) {
		LevelExtractionEvents.END_EXTRACTION.register(context -> callback.run());
	}
}
