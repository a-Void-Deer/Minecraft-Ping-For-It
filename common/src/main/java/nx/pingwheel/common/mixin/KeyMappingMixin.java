package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import nx.pingwheel.common.CommonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridges raw client key edges before the normal client tick can quantize them.
 *
 * <p>{@code click}/{@code set} remain the primary vanilla path. They are not
 * sufficient on their own: AMEcs (and potentially other input mods) cancels
 * both methods at {@code HEAD} to implement key modifiers and re-dispatches
 * physical edges through {@link KeyMapping#setDown(boolean)}. The
 * {@code setDown} anchor therefore runs in both environments; all three
 * callbacks funnel into the same claimed-input state, so one physical edge is
 * processed exactly once.</p>
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {

	@Inject(method = "click", at = @At("TAIL"))
	private static void pingforit$onClick(InputConstants.Key key, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onKeyMappingClick(key);
	}

	@Inject(method = "set", at = @At("TAIL"))
	private static void pingforit$onSet(InputConstants.Key key, boolean isDown, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onKeyMappingState(key, isDown);
	}

	/**
	 * Compatibility anchor for mods that cancel {@code click}/{@code set}.
	 * Vanilla also calls this from {@link KeyMapping#set}, so the shared claimed
	 * state is what prevents a duplicate press or release.
	 */
	@Inject(method = "setDown", at = @At("TAIL"))
	private void pingforit$onSetDown(boolean isDown, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onKeyMappingSetDown((KeyMapping) (Object) this, isDown);
	}

	// Clear the claim before vanilla emits any synthetic set(false) callbacks;
	// handling those releases first would turn focus loss into a real ping.
	@Inject(method = "releaseAll", at = @At("HEAD"))
	private static void pingforit$onReleaseAll(CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onInputReset();
	}
}
