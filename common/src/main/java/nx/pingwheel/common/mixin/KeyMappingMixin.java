package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import nx.pingwheel.common.CommonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges raw client key edges before the normal client tick can quantize them. */
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

	// Clear the claim before vanilla emits any synthetic set(false) callbacks;
	// handling those releases first would turn focus loss into a real ping.
	@Inject(method = "releaseAll", at = @At("HEAD"))
	private static void pingforit$onReleaseAll(CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onInputReset();
	}
}
