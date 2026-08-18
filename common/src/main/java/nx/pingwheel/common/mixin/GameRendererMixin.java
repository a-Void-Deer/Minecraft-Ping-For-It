package nx.pingwheel.common.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import nx.pingwheel.common.CommonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances the interaction machine once per rendered frame, independent of HUD visibility. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void pingforit$advanceInteraction(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onRenderFrame();
	}
}
