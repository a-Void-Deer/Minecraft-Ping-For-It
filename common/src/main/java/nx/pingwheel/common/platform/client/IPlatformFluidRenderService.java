package nx.pingwheel.common.platform.client;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Client draw-time base still sprite only; no variant, item, tint or world context. */
@NotNullByDefault
public interface IPlatformFluidRenderService {
	@Nullable TextureAtlasSprite stillSprite(Fluid fluid);
}
