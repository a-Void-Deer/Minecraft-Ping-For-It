package nx.pingwheel.common.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.render.WorldRenderContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;minecraft:Lnet/minecraft/client/Minecraft;", ordinal = 7, shift = At.Shift.AFTER))
	private void onStartRenderLevel(GraphicsResourceAllocator $$0, DeltaTracker deltaTracker, boolean $$2, Camera camera, Matrix4f modelViewMatrix, Matrix4f $$5, Matrix4f projectionMatrix, GpuBufferSlice $$7, Vector4f $$8, boolean $$9, CallbackInfo ci) {
		CommonClient.INSTANCE.onRenderWorld(WorldRenderContext.of(modelViewMatrix, projectionMatrix, deltaTracker.getGameTimeDeltaPartialTick(true), camera));
	}
}
