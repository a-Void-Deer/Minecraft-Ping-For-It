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
	/** Compatibility lookup: recoverable discovery and sprite failures remain unavailable to old callers. */
	public static @Nullable TextureAtlasSprite stillSprite(Fluid fluid) {
		try {
			return lookupStillSprite(fluid);
		} catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) { return null; }
	}
	public static @Nullable TextureAtlasSprite stillSprite(Fluid fluid, @Nullable IPlatformFluidRenderService service) {
		try {
			return lookupStillSprite(fluid, service);
		} catch (RuntimeException | LinkageError | ServiceConfigurationError unavailable) { return null; }
	}

	/**
	 * One lookup for the consumer that owns fallback and diagnostics. Absence or an unusable sprite returns null;
	 * original discovery and native lookup failures propagate unchanged.
	 */
	public static @Nullable TextureAtlasSprite lookupStillSprite(Fluid fluid) {
		return lookupStillSprite(fluid, ServiceHolder.service());
	}

	/** The same lookup with an already selected service; never performs discovery. */
	public static @Nullable TextureAtlasSprite lookupStillSprite(Fluid fluid, @Nullable IPlatformFluidRenderService service) {
		if (service == null) return null;
		@Nullable var sprite = service.stillSprite(fluid);
		return usable(sprite) ? sprite : null;
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
			cached = ServiceLoader.load(IPlatformFluidRenderService.class).findFirst().orElse(null);
			return cached;
		}
	}
}
