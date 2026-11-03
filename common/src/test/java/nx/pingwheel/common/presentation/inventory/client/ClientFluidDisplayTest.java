package nx.pingwheel.common.presentation.inventory.client;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.platform.IPlatformFluidDescriptionService;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientFluidDisplayTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void strictLiteralProjectionUsesBaseFluidNotBucketAndNeverExposesRawJson() {
		var display = ClientFluidDisplay.resolve("minecraft:water", "block.minecraft.water",
			"{\"version\":1,\"id\":\"minecraft:water\",\"name\":\"{literal name}\"}");
		assertSame(Fluids.WATER, display.fluid());
		assertTrue(display.name().getString().contains("{literal name}"));
		for (String bad : new String[] {"{\"text\":\"raw JSON\"}", "{\"version\":1,\"id\":\"minecraft:lava\"}"}) {
			var fallback = ClientFluidDisplay.resolve("minecraft:water", "block.minecraft.water", bad);
			assertNull(fallback.fluid()); assertFalse(fallback.name().getString().contains("raw JSON"));
		}
		var unknown = ClientFluidDisplay.resolve("missing:fluid", "{\"unsafe\":true}", "{\"version\":1,\"id\":\"missing:fluid\"}");
		assertNull(unknown.fluid()); assertFalse(unknown.name().getString().contains("unsafe"));
	}
	private static final String WATER = "{\"version\":1,\"id\":\"minecraft:water\"}";
	@Test void nativeBaseTranslationUsesLocalLanguageThenBoundedFallbackThenVerifiedLabelThenLiteralId() {
		Language previous = Language.getInstance();
		try {
			Language.inject(new Language() {
				@Override public String getOrDefault(String key, String fallback) { return switch (key) {
					case "fluid.test.tea" -> "建筑工茶饮"; case "server.verified" -> "Verified water"; default -> fallback;
				}; }
				@Override public boolean has(String key) { return key.equals("fluid.test.tea") || key.equals("server.verified"); }
				@Override public boolean isDefaultRightToLeft() { return false; }
				@Override public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) { return net.minecraft.util.FormattedCharSequence.EMPTY; }
			});
			var local = ClientFluidDisplay.resolve("minecraft:water", "server.verified", WATER,
				fluid -> new IPlatformFluidDescriptionService.Translation("fluid.test.tea", "Builder's Tea"));
			assertEquals("建筑工茶饮", local.name().getString()); assertSame(Fluids.WATER, local.fluid());
			assertInstanceOf(TranslatableContents.class, local.name().getContents());
			assertEquals("Native fallback", ClientFluidDisplay.resolve("minecraft:water", "server.verified", WATER,
				fluid -> new IPlatformFluidDescriptionService.Translation("fluid.missing.tea", "Native fallback")).name().getString());
			assertEquals("Verified water", ClientFluidDisplay.resolve("minecraft:water", "server.verified", WATER,
				fluid -> new IPlatformFluidDescriptionService.Translation("fluid.missing.tea", null)).name().getString());
			assertEquals("minecraft:water", ClientFluidDisplay.resolve("minecraft:water", "block.create.tea", WATER,
				fluid -> new IPlatformFluidDescriptionService.Translation("fluid.missing.tea", null)).name().getString());
			assertEquals("minecraft:water", ClientFluidDisplay.resolve("minecraft:water", "block.create.tea", WATER, fluid -> null).name().getString());
		} finally { Language.inject(previous); }
	}
	@Test void nativeBaseLiteralIsNotCustomNameAndCustomProjectionStaysLiteralWithExistingComposition() {
		IPlatformFluidDescriptionService service = fluid -> new IPlatformFluidDescriptionService.Literal("Native literal tea");
		assertEquals("Native literal tea", ClientFluidDisplay.resolve("minecraft:water", "block.create.tea", WATER, service).name().getString());
		var custom = ClientFluidDisplay.resolve("minecraft:water", "block.create.tea",
			"{\"version\":1,\"id\":\"minecraft:water\",\"name\":\"{literal name}\"}", service);
		assertTrue(custom.name().getString().contains("\"{literal name}\" (Native literal tea)"));
	}
	@Test void rejectedProjectionAndIdMismatchNeverCallNativeNameOrRestoreIcon() {
		for (String bad : new String[] {"{\"text\":\"raw JSON\"}", "{\"version\":1,\"id\":\"minecraft:lava\"}", "{\"version\":1,\"id\":\"minecraft:water\",\"name\":\"§cBad\"}"}) {
			var display = ClientFluidDisplay.resolve("minecraft:water", "missing.translation", bad, fluid -> { throw new AssertionError("native bypass"); });
			assertNull(display.fluid()); assertEquals("minecraft:water", display.name().getString());
		}
		var missing = ClientFluidDisplay.resolve("missing:fluid", "missing.translation", "{\"version\":1,\"id\":\"missing:fluid\"}", fluid -> { throw new AssertionError("unknown lookup"); });
		assertNull(missing.fluid()); assertEquals("missing:fluid", missing.name().getString());
	}
	@Test void missingMalformedAndThrowingNativeDescriptionsKeepSafeTextAndValidatedIconIdentity() {
		for (IPlatformFluidDescriptionService names : new IPlatformFluidDescriptionService[] {
			fluid -> null, fluid -> { throw new IllegalStateException("native name"); },
			fluid -> { throw new NoClassDefFoundError("optional name"); },
			fluid -> new IPlatformFluidDescriptionService.Literal("茶".repeat(342)),
			fluid -> new IPlatformFluidDescriptionService.Translation("invalid key", null)
		}) {
			var display = ClientFluidDisplay.resolve("minecraft:water", "block.missing.tea", WATER, names);
			assertEquals("minecraft:water", display.name().getString()); assertSame(Fluids.WATER, display.fluid());
		}
	}
}
