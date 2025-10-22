package nx.pingwheel.common.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.CommonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Gui.class)
public abstract class GuiMixin {

	@Inject(method = "render", at = @At(value = "HEAD"))
	public void render(GuiGraphics guiGraphics, float tickDelta, CallbackInfo callbackInfo) {
		final var matrixStack = guiGraphics.pose();
		matrixStack.pushPose();

		/** the hotbar is rendered at Z -90 {@link Gui#renderItemHotbar(GuiGraphics, float)} */
		matrixStack.translate(0, 0, -90);
		CommonClient.INSTANCE.onRenderGUI(guiGraphics, tickDelta);

		matrixStack.popPose();
	}
}
