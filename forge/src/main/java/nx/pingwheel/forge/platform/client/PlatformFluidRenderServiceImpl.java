package nx.pingwheel.forge.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

@NotNullByDefault
public final class PlatformFluidRenderServiceImpl implements IPlatformFluidRenderService {
	@Override public @Nullable TextureAtlasSprite stillSprite(Fluid fluid) {
		@Nullable ResourceLocation texture = IClientFluidTypeExtensions.of(fluid).getStillTexture();
		return texture == null ? null : Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(texture);
	}
}
