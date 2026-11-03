package nx.pingwheel.common.platform.client;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/** Stateless draw-time discovery. Never caches atlas sprites or failed lookups across resource reloads. */
@NotNullByDefault
public final class FluidSprites {
	private FluidSprites() {}
	public static @Nullable TextureAtlasSprite stillSprite(Fluid fluid) {
		return stillSprite(fluid, ServiceHolder.service());
	}
	public static @Nullable TextureAtlasSprite stillSprite(Fluid fluid, @Nullable IPlatformFluidRenderService service) {
		if (service == null) return null;
		try {
			@Nullable var sprite = service.stillSprite(fluid);
			return usable(sprite) ? sprite : null;
		} catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) { return null; }
	}
	public static boolean usable(@Nullable TextureAtlasSprite sprite) {
		return sprite != null && TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation()) && sprite.contents() != null
			&& sprite.contents().name() != null && !MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name())
			&& sprite.contents().width() > 0 && sprite.contents().height() > 0;
	}
	@NotNullByDefault
	private static final class ServiceHolder {
		private static @Nullable IPlatformFluidRenderService cached;
		private static @Nullable IPlatformFluidRenderService service() {
			if (cached != null) return cached;
			try {
				cached = ServiceLoader.load(IPlatformFluidRenderService.class).findFirst().orElse(null);
				return cached;
			} catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) { return null; }
		}
	}
}
