package nx.pingwheel.common.render;

import java.time.Duration;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.platform.client.FluidSprites;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Base-fluid sprite route used by vanilla's liquid renderer, with no item or network texture. */
@NotNullByDefault
final class SpatialFluidIconRenderer {

	private static final DiagnosticChannel FLUID_ICON_FAILURE =
		DiagnosticChannel.interval("selector-fluid-icon", Duration.ofSeconds(10));
	private static final DiagnosticChannel FLUID_ICON_RESOLUTION_FAILURE =
		DiagnosticChannel.interval("selector-fluid-icon-resolution", Duration.ofSeconds(10));
	private static final DiagnosticChannel FLUID_ICON_LEGACY_FAILURE =
		DiagnosticChannel.interval("selector-fluid-icon-legacy", Duration.ofSeconds(10));

	private SpatialFluidIconRenderer() {}
	static void draw(GuiGraphics graphics, @Nullable Fluid fluid, int x, int y, float tint, float alpha) {
		draw(graphics, fluid, x, y, tint, alpha, SpatialFluidIconQuad::draw);
	}

	/** Production dispatch with only the final GPU submission replaceable for headless tests. */
	static void draw(GuiGraphics graphics, @Nullable Fluid fluid, int x, int y, float tint, float alpha, SpriteDraw draw) {
		draw(graphics, fluid, x, y, tint, alpha, draw, FluidSprites::lookupStillSprite, state ->
			Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state).getParticleIcon());
	}

	static void draw(GuiGraphics graphics, @Nullable Fluid fluid, int x, int y, float tint, float alpha,
		SpriteDraw draw, NativeSprite nativeSprite, LegacySprite legacySprite) {
		if (fluid == null || alpha <= 0) return;
		@Nullable var sprite = resolve(fluid, nativeSprite, legacySprite);
		if (sprite == null) return;
		try {
			int color = fluid.defaultFluidState().is(FluidTags.WATER) ? 0x3F76E4 : 0xFFFFFF;
			draw.draw(graphics, sprite, x, y, ((color >> 16) & 255) / 255f * tint,
				((color >> 8) & 255) / 255f * tint, (color & 255) / 255f * tint, alpha);
		} catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError unavailable) {
			Diagnostics.client().atNormal().rateLimited(FLUID_ICON_FAILURE).failure(Level.ERROR,
				"selector/fluid-icon/draw-failed", "text-only",
				() -> "base fluid sprite draw failed; x=" + x + "; y=" + y + "; tint=" + tint + "; alpha=" + alpha, unavailable);
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
		@Nullable Throwable nativeFailure = null;
		try {
			@Nullable var sprite = nativeSprite.get(fluid);
			if (FluidSprites.usable(sprite)) return sprite;
		} catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError unavailable) { nativeFailure = unavailable; }
		@Nullable TextureAtlasSprite legacy = null;
		@Nullable Throwable legacyFailure = null;
		try {
			var state = fluid.defaultFluidState().createLegacyBlock();
			if (!state.isAir()) {
				@Nullable var sprite = legacySprite.get(state);
				if (FluidSprites.usable(sprite)) legacy = sprite;
			}
		} catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError unavailable) { legacyFailure = unavailable; }
		if (nativeFailure != null || legacyFailure != null) {
			Throwable failure = nativeFailure == null ? java.util.Objects.requireNonNull(legacyFailure)
				: legacyFailure == null ? nativeFailure : new ResolutionFailure(nativeFailure, legacyFailure);
			String handling = legacy == null ? "text-only" : "legacy-sprite";
			reportResolution(handling, () -> "base fluid sprite resolution failed; replacement=" + handling, failure);
		}
		return legacy;
	}

	private static void reportResolution(String handling, Supplier<String> detail, Throwable failure) {
		Diagnostics.client().atNormal().rateLimited(handling.equals("legacy-sprite") ? FLUID_ICON_LEGACY_FAILURE
			: FLUID_ICON_RESOLUTION_FAILURE).failure(Level.WARN,
			"selector/fluid-icon/resolution-failed", handling, detail, failure);
	}

	@NotNullByDefault
	private static final class ResolutionFailure extends RuntimeException {
		private ResolutionFailure(Throwable nativeFailure, Throwable legacyFailure) {
			super("Fluid sprite resolution failed in both routes", nativeFailure, true, false);
			addSuppressed(legacyFailure);
		}
	}
}
