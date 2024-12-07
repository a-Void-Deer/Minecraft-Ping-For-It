package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import nx.pingwheel.common.core.ClientCore;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;minecraft:Lnet/minecraft/client/Minecraft;", ordinal = 7, shift = At.Shift.AFTER))
	private void onStartRenderLevel(GraphicsResourceAllocator $$0, DeltaTracker deltaTracker, boolean $$2, Camera $$3, GameRenderer $$4, Matrix4f $$5, Matrix4f $$6, CallbackInfo ci) {
		ClientCore.onRenderWorld(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), deltaTracker.getGameTimeDeltaPartialTick(true));
	}
}
