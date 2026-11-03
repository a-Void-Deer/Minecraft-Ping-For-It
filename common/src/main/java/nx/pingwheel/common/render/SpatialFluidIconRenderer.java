package nx.pingwheel.common.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.platform.client.FluidSprites;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Base-fluid sprite route used by vanilla's liquid renderer, with no item or network texture. */
@NotNullByDefault
final class SpatialFluidIconRenderer {

	private SpatialFluidIconRenderer() {}
	static void draw(GuiGraphics graphics, @Nullable Fluid fluid, int x, int y, float tint, float alpha) {
		draw(graphics, fluid, x, y, tint, alpha, SpatialFluidIconQuad::draw);
	}

	/** Production dispatch with only the final GPU submission replaceable for headless tests. */
	static void draw(GuiGraphics graphics, @Nullable Fluid fluid, int x, int y, float tint, float alpha, SpriteDraw draw) {
		if (fluid == null || alpha <= 0) return;
		try {
			@Nullable var sprite = resolve(fluid, FluidSprites::stillSprite, state ->
				Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state).getParticleIcon());
			if (sprite == null) return;
			int color = fluid.defaultFluidState().is(FluidTags.WATER) ? 0x3F76E4 : 0xFFFFFF;
			draw.draw(graphics, sprite, x, y, ((color >> 16) & 255) / 255f * tint,
				((color >> 8) & 255) / 255f * tint, (color & 255) / 255f * tint, alpha);
		} catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError unavailable) {
			nx.pingwheel.common.diagnostics.Diagnostics.warn("selector/fluid-icon", "client",
				() -> "base fluid sprite unavailable; retaining text display", unavailable);
		}
	}

	@NotNullByDefault
	interface SpriteDraw {
		void draw(GuiGraphics graphics, TextureAtlasSprite sprite, int x, int y, float red, float green, float blue, float alpha);
	}

	@NotNullByDefault
	interface NativeSprite { @Nullable TextureAtlasSprite get(Fluid fluid); }
	@NotNullByDefault
	interface LegacySprite { @Nullable TextureAtlasSprite get(BlockState state); }

	/** The legacy AIR check fences only legacy particle lookup, never the loader's native still route. */
	static @Nullable TextureAtlasSprite resolve(Fluid fluid, NativeSprite nativeSprite, LegacySprite legacySprite) {
		try {
			@Nullable var sprite = nativeSprite.get(fluid);
			if (FluidSprites.usable(sprite)) return sprite;
		} catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError unavailable) { /* try legacy */ }
		try {
			var state = fluid.defaultFluidState().createLegacyBlock();
			if (state.isAir()) return null;
			@Nullable var sprite = legacySprite.get(state);
			return FluidSprites.usable(sprite) ? sprite : null;
		} catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError unavailable) { return null; }
	}
}
