package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import nx.pingwheel.common.CommonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static nx.pingwheel.common.Global.LOGGER;

/** Bridges raw client key edges before the normal client tick can quantize them. */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {

	/** {@code SDL_NUM_SCANCODES}: the size of the SDL keyboard-state buffer. */
	private static final int SDL_SCANCODE_LIMIT = 512;
	private static boolean warnedOutOfRangeKey;


	@Inject(method = "click", at = @At("TAIL"))
	private static void pingforit$onClick(InputConstants.Key key, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onKeyMappingClick(key);
	}

	@Inject(method = "set", at = @At("TAIL"))
	private static void pingforit$onSet(InputConstants.Key key, boolean isDown, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onKeyMappingState(key, isDown);
	}

	/**
	 * Guards {@code KeyMapping.setAll()} against a persisted key binding whose
	 * value falls outside the SDL scancode range.
	 *
	 * <p>26.3 implements {@code InputConstants.isKeyDown(int)} as
	 * {@code SDL_GetKeyboardState().get(key)}, so a corrupt binding such as
	 * {@code key.keyboard.-1} (written by an earlier build whose default key was
	 * {@code -1}) throws {@code IndexOutOfBoundsException} and crashes the client
	 * the moment a world finishes loading. Treating an out-of-range key as
	 * "not pressed" keeps the client alive and the bad binding harmless.
	 */
	@Redirect(
		method = "setAll",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/InputConstants;isKeyDown(I)Z"))
	private static boolean pingforit$safeIsKeyDown(int key) {
		if (key < 0 || key >= SDL_SCANCODE_LIMIT) {
			if (!warnedOutOfRangeKey) {
				warnedOutOfRangeKey = true;
				LOGGER.warn("ignoring a key binding outside the SDL scancode range: value={} (the binding is corrupt; rebind it in Controls)", key);
			}

			return false;
		}

		return InputConstants.isKeyDown(key);
	}

	// Clear the claim before vanilla emits any synthetic set(false) callbacks;
	// handling those releases first would turn focus loss into a real ping.
	@Inject(method = "releaseAll", at = @At("HEAD"))
	private static void pingforit$onReleaseAll(CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onInputReset();
	}
}
