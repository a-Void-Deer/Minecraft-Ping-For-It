package nx.pingwheel.fabric.platform;

import java.util.ServiceLoader;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributeHandler;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.fabric.impl.transfer.fluid.FluidVariantCache;
import net.fabricmc.fabric.impl.transfer.fluid.FluidVariantImpl;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.EmptyFluid;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import nx.pingwheel.common.platform.client.IPlatformFluidRenderService;
import nx.pingwheel.common.platform.client.FluidSprites;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real Fabric APIs with a cache-bearing fluid (the cache is normally installed by Fabric's mixin). */
@NotNullByDefault
class FluidPresentationServiceTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	@NotNullByDefault
	private static final class TestFluid extends EmptyFluid implements FluidVariantCache {
		@Override public boolean isSource(net.minecraft.world.level.material.FluidState state) { return true; }
		@Override public FluidVariant fabric_getCachedFluidVariant() { return new FluidVariantImpl(this, DataComponentPatch.EMPTY); }
	}
	@Test void baseNameUsesNativeAttributesWithoutAStackAmountOrPatchAndKeepsLiteralOverride() {
		var fluid = fluid(); int[] calls = {0}; Component[] name = {Component.translatable("fluid.test.native")};
		FluidVariantAttributes.register(fluid, new FluidVariantAttributeHandler() {
			@Override public Component getName(FluidVariant variant) {
				calls[0]++; assertSame(fluid, variant.getFluid()); assertTrue(variant.getComponents().isEmpty()); return name[0];
			}
		});
		var service = new PlatformFluidDescriptionServiceImpl();
		assertEquals(new IPlatformFluidDescriptionService.Translation("fluid.test.native", null), service.describeBase(fluid));
		name[0] = Component.literal("Native literal");
		assertEquals(new IPlatformFluidDescriptionService.Literal("Native literal"), service.describeBase(fluid)); assertEquals(2, calls[0]);
		assertTrue(ServiceLoader.load(IPlatformFluidDescriptionService.class).stream().anyMatch(p -> p.type() == service.getClass()));
	}
	@Test void renderServiceIsConstructibleBeforeAtlasAndNativeHandlerReceivesNullViewAndPosition() {
		var service = new nx.pingwheel.fabric.platform.client.PlatformFluidRenderServiceImpl();
		var fluid = fluid(); assertNull(service.stillSprite(fluid));
		int[] calls = {0};
		FluidRenderHandlerRegistry.INSTANCE.register(fluid, (view, pos, state) -> {
			assertNull(view); assertNull(pos); assertSame(fluid.defaultFluidState(), state); calls[0]++; return new net.minecraft.client.renderer.texture.TextureAtlasSprite[0];
		});
		assertNull(service.stillSprite(fluid)); assertNull(service.stillSprite(fluid)); assertEquals(2, calls[0]);
		assertTrue(ServiceLoader.load(IPlatformFluidRenderService.class).stream().anyMatch(p -> p.type() == service.getClass()));
		FluidRenderHandlerRegistry.INSTANCE.register(fluid, (view, pos, state) -> null);
		assertNull(service.stillSprite(fluid));
		FluidRenderHandlerRegistry.INSTANCE.register(fluid, (view, pos, state) -> { throw new IllegalStateException("native"); });
		assertNull(FluidSprites.stillSprite(fluid, service));
	}
	@Test void nativeNonPlaceableSpriteSurvivesProductionPortAndFreshHandlerIsObservedAfterReload() {
		var fluid = fluid(); var service = new nx.pingwheel.fabric.platform.client.PlatformFluidRenderServiceImpl();
		try (var old = contents("test:still"); var reloaded = contents("test:reloaded")) {
			var first = new Sprite(old); var replacement = new Sprite(reloaded); int[] calls = {0};
			FluidRenderHandlerRegistry.INSTANCE.register(fluid, (view, pos, state) -> { calls[0]++; return new TextureAtlasSprite[] {first}; });
			assertSame(first, FluidSprites.stillSprite(fluid, service));
			FluidRenderHandlerRegistry.INSTANCE.register(fluid, (view, pos, state) -> { calls[0]++; return new TextureAtlasSprite[] {replacement}; });
			assertSame(replacement, FluidSprites.stillSprite(fluid, service)); assertEquals(2, calls[0]);
			FluidRenderHandlerRegistry.INSTANCE.register(fluid, (view, pos, state) -> new @Nullable TextureAtlasSprite[] {null});
			assertNull(FluidSprites.stillSprite(fluid, service));
		}
	}
	private static SpriteContents contents(String name) {
		return new SpriteContents(ResourceLocation.parse(name), new net.minecraft.client.resources.metadata.animation.FrameSize(16, 16),
			new com.mojang.blaze3d.platform.NativeImage(16, 16, false), net.minecraft.server.packs.resources.ResourceMetadata.EMPTY);
	}
	@NotNullByDefault
	private static final class Sprite extends TextureAtlasSprite {
		private Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0, 0); }
	}
	private static TestFluid fluid() {
		try {
			var unsafeType = Class.forName("sun.misc.Unsafe"); var field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
			var fluid = (TestFluid) unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), TestFluid.class);
			var definition = new net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.material.Fluid, net.minecraft.world.level.material.FluidState>(fluid)
				.create(net.minecraft.world.level.material.Fluid::defaultFluidState, net.minecraft.world.level.material.FluidState::new);
			var state = net.minecraft.world.level.material.Fluid.class.getDeclaredField("defaultFluidState"); state.setAccessible(true); state.set(fluid, definition.any());
			return fluid;
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
}
