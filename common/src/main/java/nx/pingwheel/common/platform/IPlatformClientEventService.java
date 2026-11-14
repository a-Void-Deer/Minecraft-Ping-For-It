package nx.pingwheel.common.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;

import java.util.ServiceLoader;

public interface IPlatformClientEventService {

	IPlatformClientEventService INSTANCE = ServiceLoader.load(IPlatformClientEventService.class)
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No IPlatformClientEventService implementation found!"));

	void registerTickStartEvent(Runnable callback);
	void registerJoinServerEvent(Runnable callback);
	void registerLeaveServerEvent(Runnable callback);

	/** Registers a callback invoked once per rendered frame while a level is present. */
	void registerRenderFrameEvent(Runnable callback);

	/** Registers a HUD-layer callback; the loader maps its native HUD registry to this. */
	void registerHudRenderEvent(HudRenderCallback callback);

	/** Registers a world-submit callback; the loader maps its native render event to this. */
	void registerWorldSubmitEvent(WorldSubmitCallback callback);

	@FunctionalInterface
	interface HudRenderCallback {
		void render(GuiGraphicsExtractor graphics, DeltaTracker delta);
	}

	@FunctionalInterface
	interface WorldSubmitCallback {
		void submit(WorldSubmitContext context);
	}

	record WorldSubmitContext(
		PoseStack poseStack,
		SubmitNodeCollector collector,
		Camera camera,
		float partialTick
	) {}
}
