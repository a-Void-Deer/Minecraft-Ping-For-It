package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import nx.pingwheel.common.util.InputUtils;
import nx.pingwheel.common.util.ToggleInputState;
import org.jetbrains.annotations.NotNullByDefault;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the physical action and screen origin before vanilla's GUI dispatch. */
@Mixin(KeyboardHandler.class)
@NotNullByDefault
public abstract class KeyboardHandlerMixin {
	@Shadow @Final private Minecraft minecraft;
	@Unique private final ToggleInputState pingforit$toggleInput = new ToggleInputState();

	@Inject(method = "keyPress(JIIII)V", at = @At("HEAD"))
	private void pingforit$beginToggleInput(long window, int key, int scancode, int action, int modifiers,
		CallbackInfo callback) {
		pingforit$toggleInput.beginCallback(action,
			window == minecraft.getWindow().getWindow() && minecraft.isWindowActive(), minecraft.screen != null);
	}

	@ModifyArg(method = "keyPress(JIIII)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/KeyMapping;click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V"))
	private InputConstants.Key pingforit$claimToggleInput(InputConstants.Key key) {
		if (pingforit$toggleInput.claimMappingClick(minecraft.screen != null)) InputUtils.handleTogglePress(key);
		return key;
	}
}
