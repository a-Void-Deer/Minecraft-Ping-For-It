package nx.pingwheel.common.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.WheelMouseCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Raw callback bridge; vanilla absolute-position bookkeeping is never cancelled. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;
	@Shadow private double xpos;
	@Shadow private double ypos;
	@Redirect(method = "releaseMouse", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(JIDD)V"))
	private void pingforit$releaseCursor(long window, int mode, double x, double y) {
		var position = WheelMouseCapture.releaseCursor(window, mode, x, y);
		if (position != null) { xpos = position.x(); ypos = position.y(); }
	}
	@Redirect(method = "onPress(JIII)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/MouseHandler;grabMouse()V"))
	private void pingforit$keepSelectorMouseFree(MouseHandler mouse) {
		var runtime = CommonClient.INSTANCE.getPingRuntime();
		if (runtime == null || !WheelMouseCapture.preventVanillaGrab(runtime.phase(),
			minecraft.screen != null, minecraft.isWindowActive())) mouse.grabMouse();
	}
	@Inject(method = "onMove(JDD)V", at = @At("TAIL"))
	private void pingforit$selectorMove(long window, double x, double y, CallbackInfo callback) {
		CommonClient.INSTANCE.onMouseMove(window, x, y);
	}
	@Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
	private void pingforit$selectorScroll(long window, double horizontal, double vertical, CallbackInfo callback) {
		if (CommonClient.INSTANCE.onMouseScroll(window, horizontal, vertical)) callback.cancel();
	}
	@Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"))
	private void pingforit$beforeWarp(CallbackInfo callback) {
		if (CommonClient.INSTANCE.isSelectorMouseTransition()) {
			accumulatedDX = 0;
			accumulatedDY = 0;
		}
		CommonClient.INSTANCE.onMouseCaptureChanged();
	}
	@Inject(method = {"grabMouse", "releaseMouse"}, at = @At("TAIL"))
	private void pingforit$afterWarp(CallbackInfo callback) { CommonClient.INSTANCE.onMouseCaptureChanged(); }
	@Inject(method = {"setIgnoreFirstMove", "cursorEntered"}, at = @At("HEAD"))
	private void pingforit$reprime(CallbackInfo callback) { CommonClient.INSTANCE.onMouseCaptureChanged(); }
}
