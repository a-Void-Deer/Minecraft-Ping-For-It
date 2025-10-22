package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.render.WorldRenderContext;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;minecraft:Lnet/minecraft/client/Minecraft;", ordinal = 7, shift = At.Shift.AFTER))
	private void onStartRenderLevel(GraphicsResourceAllocator $$0, DeltaTracker deltaTracker, boolean $$2, Camera camera, GameRenderer $$4, LightTexture $$5, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		CommonClient.INSTANCE.onRenderWorld(WorldRenderContext.of(modelViewMatrix, projectionMatrix, deltaTracker.getGameTimeDeltaPartialTick(true), camera));
	}
}
