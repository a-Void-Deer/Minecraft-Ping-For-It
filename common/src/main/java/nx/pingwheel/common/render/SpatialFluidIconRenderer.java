package nx.pingwheel.common.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Base-fluid sprite route used by vanilla's liquid renderer, with no item or network texture. */
@NotNullByDefault
final class SpatialFluidIconRenderer {

	private SpatialFluidIconRenderer() {}
	static void draw(GuiGraphics graphics, @Nullable Fluid fluid, int x, int y, float tint, float alpha) {
		if (fluid == null || alpha <= 0) return;
		try {
			var state = fluid.defaultFluidState().createLegacyBlock();
			if (state.isAir()) return; // A fluid with no common base sprite retains its safe text-only display.
			var sprite = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state).getParticleIcon();
			int color = fluid.defaultFluidState().is(FluidTags.WATER) ? 0x3F76E4 : 0xFFFFFF;
			graphics.blit(x, y, 0, 16, 16, sprite, ((color >> 16) & 255) / 255f * tint,
				((color >> 8) & 255) / 255f * tint, (color & 255) / 255f * tint, alpha);
		} catch (RuntimeException | LinkageError unavailable) {
			nx.pingwheel.common.diagnostics.Diagnostics.warn("selector/fluid-icon", "client",
				() -> "base fluid sprite unavailable; retaining text display", unavailable);
		}
	}
}
