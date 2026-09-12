package nx.pingwheel.common.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.outline.BlockOutlineFrameState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances the interaction machine once per rendered frame, independent of HUD visibility. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "render", at = @At("HEAD"))
	private void pingforit$advanceInteraction(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callbackInfo) {
		CommonClient.INSTANCE.onRenderFrame();
	}

	/**
	 * A new GameRenderer world pass makes any unconsumed frame from an aborted
	 * prior pass ineligible. The matching post-world hook consumes the current
	 * frame before any later pass can see it.
	 */
	@Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 1)
	private void pingforit$clearBlockOutlineFrame(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
		BlockOutlineFrameState.INSTANCE.clear();
	}

	/**
	 * Submits the no-depth native VoxelShape batch only after the entire world
	 * renderer has returned. This intentionally places it after return hooks
	 * that finalize world composites, while still preceding hand and HUD work.
	 * The captured world transform is restored around the immediate batch flush.
	 */
	@Inject(
		method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel("
				+ "Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;"
				+ "Lnet/minecraft/client/renderer/GameRenderer;"
				+ "Lnet/minecraft/client/renderer/LightTexture;"
				+ "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			shift = At.Shift.AFTER),
		require = 1)
	private void pingforit$renderBlockOutlines(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
		BlockOutlineFrameState.FrameTransform frame = BlockOutlineFrameState.INSTANCE.consume();

		if (frame == null) {
			return;
		}

		CommonClient.INSTANCE.renderBlockOutlines(
			this.minecraft.gameRenderer.getMainCamera(),
			this.minecraft.renderBuffers().bufferSource(),
			frame);
	}
}
