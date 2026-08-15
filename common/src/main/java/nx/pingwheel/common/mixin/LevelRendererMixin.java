package nx.pingwheel.common.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.outline.EntityOutlineState;
import nx.pingwheel.common.render.WorldRenderContext;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;applyModelViewMatrix()V", ordinal = 0, shift = At.Shift.AFTER))
	private void onStartRenderLevel(DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		CommonClient.INSTANCE.onRenderWorld(WorldRenderContext.of(modelViewMatrix, projectionMatrix, deltaTracker.getGameTimeDeltaPartialTick(true), camera));
	}

	/**
	 * Routes entities that currently control a visible ping outline into the
	 * vanilla outline pass. The entity is additionally rendered to the
	 * {@link net.minecraft.client.renderer.OutlineBufferSource} and the
	 * entity-outline post-process flag is set by vanilla itself, exactly as
	 * for glowing entities; no buffer flushing, post-process trigger, or
	 * glowing/team mutation happens here.
	 */
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean pingForItShouldEntityAppearGlowing(Minecraft minecraft, Entity entity) {
		return minecraft.shouldEntityAppearGlowing(entity)
			|| EntityOutlineState.INSTANCE.shouldOutline(entity.getUUID());
	}

	/**
	 * Replaces the outline color of a marked winner with its ping type color;
	 * ordinary vanilla glowing entities keep their team color.
	 */
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
	private int pingForItGetTeamColor(Entity entity) {
		int outlineColor = EntityOutlineState.INSTANCE.colorFor(entity.getUUID());

		if (outlineColor != 0) {
			return outlineColor;
		}

		return entity.getTeamColor();
	}
}
