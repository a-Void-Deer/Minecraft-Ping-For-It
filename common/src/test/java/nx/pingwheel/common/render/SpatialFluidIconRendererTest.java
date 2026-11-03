package nx.pingwheel.common.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import nx.pingwheel.common.platform.client.FluidSprites;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production sprite dispatch with only final quad submission replaced; no GPU or Minecraft singleton. */
@NotNullByDefault
class SpatialFluidIconRendererTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void nonPlaceableFluidStillAttemptsNativeSpriteAndPaintsWithExistingAlpha() throws Exception {
		assertTrue(Fluids.EMPTY.defaultFluidState().createLegacyBlock().isAir());
		try (var contents = contents("test:tea_still")) {
			var sprite = new Sprite(contents);
			var graphics = allocate(RecordingGraphics.class);
			NativeSprites.sprite = sprite; NativeSprites.calls = 0;
			try {
				SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 4, 7, .65f, .37f, graphics::record);
				assertEquals(1, NativeSprites.calls, "legacy AIR cannot bypass the native base-fluid renderer");
				assertSame(sprite, graphics.painted); assertEquals(.37f, graphics.alpha); assertEquals(.65f, graphics.red);
			} finally { NativeSprites.sprite = null; }
		}
	}
	@Test void nullMissingWrongAtlasAndThrowingNativeRoutesTryOnlyNonAirLegacyThenTextOnly() throws Exception {
		try (var valid = contents("test:legacy"); var missing = contents("minecraft:missingno")) {
			var legacy = new Sprite(valid);
			for (SpatialFluidIconRenderer.NativeSprite nativeRoute : new SpatialFluidIconRenderer.NativeSprite[] {
				fluid -> null, fluid -> new Sprite(missing), fluid -> new Sprite(ResourceLocation.parse("test:wrong_atlas"), valid),
				fluid -> { throw new IllegalStateException("native"); }, fluid -> { throw new NoClassDefFoundError("native"); },
				fluid -> { throw new java.util.ServiceConfigurationError("native"); }
			}) {
				int[] attempts = {0};
				assertSame(legacy, SpatialFluidIconRenderer.resolve(Fluids.WATER, nativeRoute, state -> { attempts[0]++; assertFalse(state.isAir()); return legacy; }));
				assertEquals(1, attempts[0]);
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.EMPTY, nativeRoute, state -> { throw new AssertionError("AIR fallback"); }));
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, nativeRoute, state -> new Sprite(missing)));
				assertNull(SpatialFluidIconRenderer.resolve(Fluids.WATER, nativeRoute, state -> { throw new IllegalStateException("legacy"); }));
			}
			assertFalse(FluidSprites.usable(new Sprite(missing)), "a non-null atlas lookup can still be Minecraft's missing sprite");
		}
	}
	@Test void everyDrawRequeriesSpriteAndFailedDrawRecoversAfterResourceReload() throws Exception {
		try (var oldContents = contents("test:old"); var newContents = contents("test:reloaded")) {
			var first = new Sprite(oldContents); var replacement = new Sprite(newContents);
			var graphics = allocate(RecordingGraphics.class); NativeSprites.calls = 0;
			try {
				NativeSprites.sprite = null; SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record); assertNull(graphics.painted);
				NativeSprites.sprite = first; SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record); assertSame(first, graphics.painted);
				NativeSprites.sprite = replacement; SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 1, graphics::record); assertSame(replacement, graphics.painted);
				assertEquals(3, NativeSprites.calls);
				SpatialFluidIconRenderer.draw(graphics, null, 0, 0, 1, 1); SpatialFluidIconRenderer.draw(graphics, Fluids.EMPTY, 0, 0, 1, 0);
				assertEquals(3, NativeSprites.calls, "hidden icon has no native callback");
			} finally { NativeSprites.sprite = null; }
		}
	}
	@Test void waterKeepsExistingTintAndFailedSubmissionLeavesTextOnlyThenRecovers() throws Exception {
		var holder = Fluids.WATER.builtInRegistryHolder();
		var tags = holder.getClass().getDeclaredField("tags"); tags.setAccessible(true);
		@Nullable Object previousTags = tags.get(holder);
		try (var contents = contents("test:water")) {
			tags.set(holder, java.util.Set.of(net.minecraft.tags.FluidTags.WATER));
			var graphics = allocate(RecordingGraphics.class);
			NativeSprites.sprite = new Sprite(contents);
			try {
				SpatialFluidIconRenderer.draw(graphics, Fluids.WATER, 2, 3, .65f, .05f, (gui, sprite, x, y, r, g, b, a) -> {
					throw new IllegalStateException("submission unavailable");
				});
				assertNull(graphics.painted);
				SpatialFluidIconRenderer.draw(graphics, Fluids.WATER, 2, 3, .65f, .05f, graphics::record);
				assertSame(NativeSprites.sprite, graphics.painted);
				assertEquals(63 / 255f * .65f, graphics.red);
				assertEquals(.05f, graphics.alpha);
			} finally { NativeSprites.sprite = null; }
		} finally { tags.set(holder, previousTags); }
	}

	private static SpriteContents contents(String id) {
		return new SpriteContents(ResourceLocation.parse(id), new FrameSize(16, 16), new NativeImage(16, 16, false), ResourceMetadata.EMPTY);
	}
	@NotNullByDefault
	private static final class Sprite extends TextureAtlasSprite {
		private Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0, 0); }
		private Sprite(ResourceLocation atlas, SpriteContents contents) { super(atlas, contents, 16, 16, 0, 0); }
	}
	@NotNullByDefault
	public static final class NativeSprites implements IPlatformFluidRenderService {
		static @Nullable TextureAtlasSprite sprite;
		static int calls;
		@Override public @Nullable TextureAtlasSprite stillSprite(Fluid fluid) { calls++; return sprite; }
	}
	@NotNullByDefault
	private static final class RecordingGraphics extends GuiGraphics {
		private @Nullable TextureAtlasSprite painted;
		private float red, alpha;
		private RecordingGraphics() { super(null, null); }
		private void record(GuiGraphics graphics, TextureAtlasSprite sprite, int x, int y, float red, float green, float blue, float alpha) {
			this.painted = sprite; this.red = red; this.alpha = alpha;
		}
	}
	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		var unsafeType = Class.forName("sun.misc.Unsafe"); var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (T) unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
	}
}
