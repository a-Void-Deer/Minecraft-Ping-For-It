package nx.pingwheel.common.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nx.pingwheel.common.CommonClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Aborts claimed ping input at the exact client screen transition boundary. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "setScreen", at = @At("HEAD"))
	private void pingforit$onSetScreen(Screen nextScreen, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onScreenChanged(nextScreen);
	}
}
