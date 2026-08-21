package nx.pingwheel.common.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.world.entity.Entity;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.outline.EntityOutlineFrameState;
import nx.pingwheel.common.client.outline.EntityOutlineRenderDecision;
import nx.pingwheel.common.client.outline.EntityOutlineSourceRegistry;
import nx.pingwheel.common.client.outline.EntityOutlineState;
import nx.pingwheel.common.interaction.MinecraftEntityTargetAdapter;
import nx.pingwheel.common.render.WorldRenderContext;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Shadow
	@Final
	private RenderBuffers renderBuffers;

	@Shadow
	@Final
	private Minecraft minecraft;

	// The target field is lazily (re)assigned during renderLevel, not final.
	@Shadow
	private PostChain entityEffect;

	/**
	 * Whether the model-outline pass already ran the vanilla entity-outline
	 * post-process this frame, so the vanilla ordinal-0 {@code process} call
	 * is skipped exactly once.
	 */
	private boolean modelOutlinesProcessed;

	@Shadow
	protected abstract boolean shouldShowEntityOutlines();

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;applyModelViewMatrix()V", ordinal = 0, shift = At.Shift.AFTER))
	private void onStartRenderLevel(DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		// Prepares the overlay, entity outline, and block outline state for
		// this frame. Runs right after the ordinal-0 applyModelViewMatrix
		// anchor: the camera-relative model-view matrix is applied and the
		// call happens before the entity loop below, so the entity outline
		// state is already prepared when the outline redirects fire. Also
		// resets the per-frame model-outline processed flag and the model
		// outline success record.
		this.modelOutlinesProcessed = false;
		CommonClient.INSTANCE.onRenderWorld(WorldRenderContext.of(modelViewMatrix, projectionMatrix, deltaTracker.getGameTimeDeltaPartialTick(true), camera));
	}

	/**
	 * Runs the model-outline pass (actual {@code BlockEntity} geometry,
	 * virtual {@code BlockDisplay} glow, and the optional Flywheel silhouette
	 * mask) and the registered entity-outline sources immediately before the
	 * vanilla {@code OutlineBufferSource.endOutlineBatch()} call. Built-in
	 * block attempts use transient local buffers; the Flywheel source writes
	 * the vanilla outline buffer but never flushes it, and the entity-outline
	 * sources write into the shared vanilla outline buffer (flushed by that
	 * same vanilla call). The model and entity-outline passes run when either
	 * vanilla's {@code shouldShowEntityOutlines()} gate or the reflection
	 * {@code requestOutlineEffect()} probe succeeded this frame, so a block
	 * model source can establish the outline pipeline too.
	 */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OutlineBufferSource;endOutlineBatch()V"))
	private void pingForItRenderBlockModels(DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		boolean outlinePipelineAvailable = this.shouldShowEntityOutlines()
			|| CommonClient.INSTANCE.entityOutlineRequestSucceededThisFrame();

		if (outlinePipelineAvailable) {
			CommonClient.INSTANCE.renderModelOutlines(
				camera,
				deltaTracker.getGameTimeDeltaPartialTick(true),
				deltaTracker.getGameTimeDeltaPartialTick(false));
		}

		if (outlinePipelineAvailable) {
			CommonClient.INSTANCE.renderEntityOutlines(
				camera,
				deltaTracker.getGameTimeDeltaPartialTick(true),
				this.renderBuffers.outlineBufferSource());
		}
	}

	/**
	 * Immediately after the vanilla {@code endOutlineBatch()} call: when the
	 * model-outline pass emitted geometry, the vanilla entity-outline
	 * post-process must run even if no vanilla entity glowed this frame. If
	 * the post chain is unavailable after all, the per-frame success record
	 * is dropped so all blocks fall back to the VoxelShape outline instead of
	 * silently disappearing.
	 */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OutlineBufferSource;endOutlineBatch()V", shift = At.Shift.AFTER))
	private void pingForItProcessBlockModelOutlines(DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		if (!CommonClient.INSTANCE.modelOutlinesEmittedThisFrame()) {
			return;
		}

		if (this.entityEffect == null) {
			CommonClient.INSTANCE.resetModelOutlinesForFrame();
			return;
		}

		this.entityEffect.process(deltaTracker.getGameTimeDeltaTicks());
		this.minecraft.getMainRenderTarget().bindWrite(false);
		this.modelOutlinesProcessed = true;
	}

	/**
	 * Routes the vanilla ordinal-0 {@code PostChain.process} call in
	 * {@code renderLevel} (the entity-outline post-process): when the
	 * model-outline pass already processed the entity outline this frame, the
	 * vanilla call is skipped so the post-process runs exactly once. The
	 * later Fabulous transparency {@code PostChain.process} call (ordinal 1)
	 * is never touched. Vanilla's own {@code bindWrite(false)} after the
	 * redirected call still runs.
	 */
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;process(F)V", ordinal = 0))
	private void pingForItProcessEntityOutlines(PostChain postChain, float tickDelta) {
		if (!this.modelOutlinesProcessed) {
			postChain.process(tickDelta);
		}
	}

	/**
	 * Draws and flushes the prepared block outlines at the very end of the
	 * world render pass: after all 3D batches and composites have been
	 * flushed, immediately before the world model-view matrix is popped.
	 * The camera-relative model-view matrix is still applied at this point,
	 * so the vertices can be camera-relative. The custom block outline
	 * batch is acquired and flushed explicitly; the vanilla
	 * {@code RenderType.lines()} batch and the entity outline redirects
	 * above are untouched.
	 */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", ordinal = 0))
	private void onEndRenderLevel(DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
		CommonClient.INSTANCE.renderBlockOutlines(camera, this.renderBuffers.bufferSource());
	}

	/**
	 * Routes entities that currently control a visible ping outline into the
	 * vanilla outline pass. The entity is additionally rendered to the
	 * {@link net.minecraft.client.renderer.OutlineBufferSource} and the
	 * entity-outline post-process flag is set by vanilla itself, exactly as
	 * for glowing entities; no buffer flushing, post-process trigger, or
	 * glowing/team mutation happens here. Vanilla glowing entities are always
	 * preserved. The ping-only outline route is suppressed for an entity a
	 * registered {@link EntityOutlineSourceRegistry} source claims, because
	 * that source owns the outline for the entity.
	 */
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean pingForItShouldEntityAppearGlowing(Minecraft minecraft, Entity entity) {
		if (minecraft.shouldEntityAppearGlowing(entity)) {
			return true;
		}

		boolean pingOutline = EntityOutlineState.INSTANCE.shouldOutline(
			MinecraftEntityTargetAdapter.locatorFor(entity));
		boolean sourceHandlesEntity = pingOutline
			&& EntityOutlineSourceRegistry.INSTANCE.handlesAny(entity);

		return EntityOutlineRenderDecision.shouldShowPingOutline(
			pingOutline,
			EntityOutlineFrameState.INSTANCE.requestSucceeded(),
			sourceHandlesEntity);
	}

	/**
	 * Replaces the outline color of a marked winner with its ping type color;
	 * ordinary vanilla glowing entities keep their team color.
	 */
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
	private int pingForItGetTeamColor(Entity entity) {
		int outlineColor = EntityOutlineState.INSTANCE.colorFor(MinecraftEntityTargetAdapter.locatorFor(entity));

		if (outlineColor != 0) {
			return outlineColor;
		}

		return entity.getTeamColor();
	}
}
