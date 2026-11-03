package nx.pingwheel.common.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

import nx.pingwheel.common.client.outline.EntityOutlineDiagnostics;
import nx.pingwheel.common.client.outline.EntityOutlineState;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.interaction.MinecraftEntityTargetAdapter;

/**
 * M7b: writes the ping colour into the native entity-glow channel.
 *
 * <p>{@code EntityRenderer.extractRenderState} is the only public point that receives
 * both the live {@link Entity} and the {@link EntityRenderState} being filled, so it is
 * the only place where a marker could be resolved to a render state. Vanilla has
 * already written {@code glowing ? ARGB.opaque(teamColor) : NO_OUTLINE} by the time TAIL
 * runs, so overwriting the field here is exactly equivalent to upstream's
 * {@code Entity.getTeamColor()} redirect — entities without a ping marker keep their
 * vanilla glowing / team colour untouched.
 *
 * <p>{@code require = 0}: if the descriptor changes the mixin silently does nothing and
 * {@link EntityOutlineState} reports it once instead of crashing.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void pingforit$applyPingOutline(
		Entity entity,
		EntityRenderState state,
		float partialTick,
		CallbackInfo ci
	) {
		if (entity == null || state == null) {
			return;
		}

		EntityOutlineDiagnostics.markExtractionApplied();

		EntityLocator locator;

		try {
			locator = MinecraftEntityTargetAdapter.locatorFor(entity);
		} catch (RuntimeException | LinkageError ignored) {
			return;
		}

		Integer color = EntityOutlineState.colorFor(locator);

		if (color != null) {
			state.outlineColor = color;
		}
	}
}
