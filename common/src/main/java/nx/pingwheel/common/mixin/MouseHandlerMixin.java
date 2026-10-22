package nx.pingwheel.common.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.WheelMouseCapture;
import nx.pingwheel.common.client.input.LookInputTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
	@Shadow private boolean ignoreFirstMove;
	@Shadow private boolean mouseGrabbed;
	@Unique private double pingforit$pendingYawDegrees;
	@Unique private double pingforit$pendingPitchDegrees;
	@Unique private boolean pingforit$pendingLookInput;
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
	/**
	 * Reads the pre-update {@code xpos/ypos} before vanilla accumulates this
	 * callback's delta. Only ordinary gameplay (focused, grabbed, no screen or
	 * overlay, no selector ownership) may contribute; the pending value is sent
	 * at TAIL so one callback is counted exactly once, never from the already
	 * updated fields or the per-frame accumulator.
	 */
	@Inject(method = "onMove(JDD)V", at = @At("HEAD"))
	private void pingforit$lookInputBegin(long window, double x, double y, CallbackInfo callback) {
		pingforit$pendingLookInput = false;

		if (!minecraft.isWindowActive()) {
			// Focus loss can move the virtual cursor without ordinary gameplay;
			// discard the baseline before the next focused callback.
			CommonClient.INSTANCE.onLookInputReset();
			return;
		}

		if (!pingforit$acceptsLookInput(window)) {
			return;
		}

		double deltaX = x - minecraft.mouseHandler.xpos();
		double deltaY = y - minecraft.mouseHandler.ypos();

		if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
			CommonClient.INSTANCE.onLookInputReset();
			return;
		}

		if (deltaX == 0.0 && deltaY == 0.0) {
			return;
		}

		boolean scoping = minecraft.options.getCameraType().isFirstPerson()
			&& minecraft.player.isScoping();
		double sensitivity = minecraft.options.sensitivity().get();
		double pitchDegrees = LookInputTracker.degreesFromPixels(deltaY, sensitivity, scoping);

		pingforit$pendingYawDegrees = LookInputTracker.degreesFromPixels(
			deltaX, sensitivity, scoping);
		pingforit$pendingPitchDegrees = minecraft.options.invertYMouse().get()
			? -pitchDegrees
			: pitchDegrees;
		pingforit$pendingLookInput = true;
	}

	@Inject(method = "onMove(JDD)V", at = @At("TAIL"))
	private void pingforit$lookInputEnd(long window, double x, double y, CallbackInfo callback) {
		if (!pingforit$pendingLookInput) {
			return;
		}

		pingforit$pendingLookInput = false;
		CommonClient.INSTANCE.onLookInput(
			pingforit$pendingYawDegrees, pingforit$pendingPitchDegrees);
	}

	private boolean pingforit$acceptsLookInput(long window) {
		return window == minecraft.getWindow().getWindow()
			&& minecraft.isWindowActive()
			&& minecraft.screen == null
			&& minecraft.getOverlay() == null
			&& minecraft.level != null
			&& minecraft.player != null
			&& mouseGrabbed
			&& !ignoreFirstMove
			&& !CommonClient.INSTANCE.isSelectorMouseTransition();
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
		CommonClient.INSTANCE.onLookInputReset();
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
	private void pingforit$reprime(CallbackInfo callback) {
		CommonClient.INSTANCE.onLookInputReset();
		CommonClient.INSTANCE.onMouseCaptureChanged();
	}
	@Inject(method = "cursorEntered", at = @At("HEAD"))
	private void pingforit$cursorEntered(CallbackInfo callback) {
		CommonClient.INSTANCE.onLookInputReset();
		var runtime = CommonClient.INSTANCE.getPingRuntime();
		if (runtime != null) runtime.onMouseCursorEntered();
	}
	@Override
	public void pingforit$clearAccumulatedMovement() { accumulatedDX = 0; accumulatedDY = 0; }
}
