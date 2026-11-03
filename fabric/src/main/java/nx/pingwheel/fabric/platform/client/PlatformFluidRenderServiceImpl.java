package nx.pingwheel.fabric.platform.client;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.material.Fluid;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
public final class PlatformFluidRenderServiceImpl implements IPlatformFluidRenderService {
	@Override public @Nullable TextureAtlasSprite stillSprite(Fluid fluid) {
		@Nullable var handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
		if (handler == null) return null;
		@Nullable TextureAtlasSprite @Nullable [] sprites = handler.getFluidSprites(null, null, fluid.defaultFluidState());
		return sprites == null || sprites.length == 0 ? null : sprites[0];
	}
}
