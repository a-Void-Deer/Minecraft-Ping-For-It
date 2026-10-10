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
public abstract class MouseHandlerMixin implements WheelMouseCapture.MouseState {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;
	@Redirect(method = "releaseMouse", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(JIDD)V"))
	private void pingforit$releaseCursor(long window, int mode, double x, double y) {
		WheelMouseCapture.releaseCursor(window, mode, x, y);
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
	@Inject(method = "grabMouse", at = @At("HEAD"))
	private void pingforit$beforeGrab(CallbackInfo callback) { pingforit$beforeCapture(true); }
	@Inject(method = "releaseMouse", at = @At("HEAD"))
	private void pingforit$beforeRelease(CallbackInfo callback) {
		WheelMouseCapture.beforeVanillaRelease();
		pingforit$beforeCapture(false);
	}
	private void pingforit$beforeCapture(boolean grabbing) {
		var runtime = CommonClient.INSTANCE.getPingRuntime();
		if (runtime != null) runtime.onVanillaMouseCaptureChanged(grabbing);
		if (CommonClient.INSTANCE.isSelectorMouseTransition()) {
			pingforit$clearAccumulatedMovement();
		}
		CommonClient.INSTANCE.onMouseCaptureChanged();
	}
	@Inject(method = "grabMouse", at = @At("TAIL"))
	private void pingforit$afterWarp(CallbackInfo callback) { CommonClient.INSTANCE.onMouseCaptureChanged(); }
	@Inject(method = "releaseMouse", at = @At("TAIL"))
	private void pingforit$afterRelease(CallbackInfo callback) {
		WheelMouseCapture.afterVanillaRelease();
		CommonClient.INSTANCE.onMouseCaptureChanged();
	}
	@Inject(method = "setIgnoreFirstMove", at = @At("HEAD"))
	private void pingforit$reprime(CallbackInfo callback) { CommonClient.INSTANCE.onMouseCaptureChanged(); }
	@Inject(method = "cursorEntered", at = @At("HEAD"))
	private void pingforit$cursorEntered(CallbackInfo callback) {
		var runtime = CommonClient.INSTANCE.getPingRuntime();
		if (runtime != null) runtime.onMouseCursorEntered();
	}
	@Override
	public void pingforit$clearAccumulatedMovement() { accumulatedDX = 0; accumulatedDY = 0; }
}
