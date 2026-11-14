package nx.pingwheel.common.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;

import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.client.outline.EntityBlockGlowState;
import nx.pingwheel.common.client.outline.EntityOutlineState;

/**
 * M7b: refreshes the entity-outline snapshot at the start of level extraction, i.e.
 * strictly before any {@code EntityRenderer.extractRenderState} runs for that frame.
 *
 * <p>Fabric exposes no per-entity extraction event, and the level extraction events it
 * does expose fire either too late or without entity identity, so the snapshot has to be
 * prepared here to avoid a one-frame delay.
 *
 * <p>{@code require = 0}: without this hook the outlines simply lag or never appear;
 * nothing throws into the render loop.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {

	@Inject(method = "extract", at = @At("HEAD"), require = 0)
	private void pingforit$prepareEntityOutlines(
		DeltaTracker deltaTracker,
		Camera camera,
		float partialTick,
		CallbackInfo ci
	) {
		try {
			// M7c C2: a dispatch that never reached its RETURN must not tint the next frame.
			EntityBlockGlowState.resetFrame();

			Minecraft game = Minecraft.getInstance();
			var runtime = CommonClient.INSTANCE.getPingRuntime();

			if (game == null || game.level == null || runtime == null) {
				EntityOutlineState.clear();
				return;
			}

			String dimensionId = game.level.dimension().identifier().toString();

			EntityOutlineState.prepare(runtime.store(), dimensionId);
			EntityBlockGlowState.diagnoseDispatchHook(runtime.store(), dimensionId);
		} catch (RuntimeException | LinkageError ignored) {
			// EntityOutlineState owns its own failure policy; never propagate into extraction.
		}
	}
}
