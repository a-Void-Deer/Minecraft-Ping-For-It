package nx.pingwheel.common.chat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.PresentationBasic;
import nx.pingwheel.common.presentation.PresentationLimits;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.PresentationView;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentChatComposerTest {

	private static final String BASIC = PresentationBasic.ID;
	private static final String CREATE = "create:presentation";
	private static final PresentationPropertyRef CUSTOM_NAME =
		PresentationPropertyRef.root(BASIC, PresentationBasic.CUSTOM_NAME);
	private static final PresentationPropertyRef NAME =
		PresentationPropertyRef.root(BASIC, PresentationBasic.NAME);
	private static final PresentationPropertyRef STRESS =
		PresentationPropertyRef.root(CREATE, "create:kinetic.stress");

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	private static PingType type(String id) {
		return PingTypeCatalog.builtIn().findById(id).orElseThrow();
	}

	private static ContentChatComposer.TemplateSource templates(String template) {
		return ignored -> template;
	}

	@Test
	void allExplicitRefsRenderIncludingTheNameTheHudPlanExcludes() throws IOException {
		withTranslations("en_us", () -> {
			var section = new PresentationSection(BASIC, 1, Map.of(
				PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Named Cat"),
				PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Named\"}")), false,
				Map.of(CUSTOM_NAME, "attention", NAME, "attention"));
			var view = new PresentationView("entity", null, Map.of(BASIC, section));
			Component decoded = Component.literal("Named");

			Component message = ContentChatComposer.propertyMessage(
				Component.literal("Steve"), Component.literal("Chest"), view, List.of(NAME, CUSTOM_NAME),
				json -> Optional.of(decoded), templates("{author}|{type}|{target}|{content}")).orElseThrow();

			String text = message.getString();
			assertTrue(text.contains("Attention"), "the annotation phrase is retained");
			assertTrue(text.contains("Target name: Named"), "an explicit name ref is not skipped");
			assertTrue(text.contains("Custom name: \"Named Cat\""), "the second explicit ref stays in order");
			assertTrue(text.contains("Named, Attention Custom name:"), "each entry keeps its own annotation");
			assertEquals(2, occurrences(text, "Attention"), "same-type selections each retain their own phrase once");
			assertFalse(text.contains("{\"text\""), "the raw name JSON never reaches the message");
			assertTrue(containsIdentity(message, decoded), "the decoded authoritative name is retained");
		});
	}

	@Test
	void mixedAnnotationsEachRenderOnceInOrderWithTheirOwnPhraseOnlyColor() throws IOException {
		withTranslations("zh_cn", () -> {
			var section = new PresentationSection(BASIC, 1, Map.of(
				PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("猫"),
				PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"名字\"}")), false,
				Map.of(CUSTOM_NAME, "attention", NAME, "danger"));
			var view = new PresentationView("entity", null, Map.of(BASIC, section));
			Component message = ContentChatComposer.propertyMessage(Component.literal("player"), Component.literal("target"),
				view, List.of(CUSTOM_NAME, NAME), json -> Optional.of(Component.literal("名字")),
				ContentChatComposer.localized(key -> false, key -> Language.getInstance().getOrDefault(key))).orElseThrow();
			String text = message.getString();
			assertEquals(1, occurrences(text, "注意"));
			assertEquals(1, occurrences(text, "危险"));
			assertTrue(text.indexOf("注意") < text.indexOf("危险"), "reference order is preserved, not grouped by type");
			assertTrue(text.contains("注意 自定义名称: \"猫\""));
			assertTrue(text.contains("危险 目标名称: 名字"));
			assertEquals(1, occurrences(text, "player"));
			assertEquals(1, occurrences(text, "target"));
			assertOnlyTypesColored(message, null, Map.of("attention", type("attention"), "danger", type("danger")));
		});
	}

	@Test
	void missingSecondaryAnnotationFailsClosedEvenWhenEveryValueIsPresent() {
		var section = new PresentationSection(BASIC, 1, Map.of(
			PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Cat"),
			PresentationBasic.NAME, new PresentationValue.Text("{\"text\":\"Named\"}")), false,
			Map.of(CUSTOM_NAME, "attention"));
		var view = new PresentationView("entity", null, Map.of(BASIC, section));
		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"), view,
			List.of(CUSTOM_NAME, NAME), json -> Optional.of(Component.literal("Named")), templates("{content}")).isEmpty());
		assertTrue(ContentChatComposer.annotation(view, List.of(CUSTOM_NAME, NAME)).isEmpty(),
			"the compatibility summary must not conceal a missing later annotation");
		assertThrows(IllegalArgumentException.class, () -> new PresentationSection(BASIC, 1, section.fields(), false,
			Map.of(CUSTOM_NAME, "attention", NAME, "not_a_type")), "invalid annotations cannot enter the detached view");
	}

	@Test
	void missingOrIncompatibleSelectedRefMakesTheWholeMessageUnavailable() {
		var partial = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Named Cat")), false,
			Map.of(CUSTOM_NAME, "attention"));
		var partialView = new PresentationView("entity", null, Map.of(BASIC, partial));

		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
			partialView, List.of(NAME, CUSTOM_NAME), json -> Optional.of(Component.literal("Named")),
			templates("{content}")).isEmpty(), "one missing ref value must not produce a partial line");
		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
			partialView, List.of(), json -> Optional.of(Component.literal("Named")),
			templates("{content}")).isEmpty(), "an empty selection is unavailable");
		var unannotated = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Named Cat")), false);
		var unannotatedView = new PresentationView("entity", null, Map.of(BASIC, unannotated));
		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
			unannotatedView, List.of(CUSTOM_NAME), json -> Optional.of(Component.literal("Named")),
			templates("{content}")).isEmpty(), "a selected ref without an authorized annotation is unavailable");
		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
			partialView, java.util.Arrays.asList(CUSTOM_NAME, null), json -> Optional.of(Component.literal("Named")),
			templates("{content}")).isEmpty(), "a null ref is unavailable");
	}

	@Test
	void customNameTextStaysLiteralAndQuotedExactlyOnce() throws IOException {
		withTranslations("en_us", () -> {
			String raw = "a{b}%s'cat'";
			var section = new PresentationSection(BASIC, 1,
				Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text(raw)), false,
				Map.of(CUSTOM_NAME, "attention"));
			var view = new PresentationView("entity", null, Map.of(BASIC, section));

			Component message = ContentChatComposer.propertyMessage(
				Component.literal("Steve"), Component.literal("Chest"), view, List.of(CUSTOM_NAME),
				json -> Optional.empty(), templates("{author}: {type} {target} {content}")).orElseThrow();

			String text = message.getString();
			assertTrue(text.contains("\"" + raw + "\""),
				"braces, percent-s and punctuation stay literal inside one quote pair");
			assertEquals(2, text.chars().filter(c -> c == '"').count(),
				"the custom name is quoted exactly once");
		});
	}

	@Test
	void nameJsonUsesTheInjectedAuthoritativeDecoderAndNeverRawJson() {
		String json = "{\"text\":\"Named\"}";
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.NAME, new PresentationValue.Text(json)), false,
			Map.of(NAME, "attention"));
		var view = new PresentationView("entity", null, Map.of(BASIC, section));
		AtomicReference<String> seen = new AtomicReference<>();
		Component decoded = Component.literal("Named");

		Component present = ContentChatComposer.propertyMessage(
			Component.literal("Steve"), Component.literal("Chest"), view, List.of(NAME),
			value -> {
				seen.set(value);
				return Optional.of(decoded);
			}, templates("{content}")).orElseThrow();

		assertEquals(json, seen.get(), "the authoritative codec receives the stored JSON value");
		assertTrue(containsIdentity(present, decoded), "the decoded component is retained, not its JSON");

		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
			view, List.of(NAME), value -> Optional.empty(), templates("{content}")).isEmpty(),
			"an undecodable name JSON is unavailable");
		assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
			view, List.of(NAME), value -> {
				throw new IllegalArgumentException("malformed");
			}, templates("{content}")).isEmpty(),
			"a throwing decoder stays a controlled unavailability");
	}

	@Test
	void onlyTheAnnotationTypeWordIsColored() {
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Named Cat")), false,
			Map.of(CUSTOM_NAME, "take"));
		var view = new PresentationView("entity", null, Map.of(BASIC, section));
		PingType annotation = type("take");

		Component message = ContentChatComposer.propertyMessage(
			Component.literal("Steve"), Component.literal("Chest"), view, List.of(CUSTOM_NAME),
			json -> Optional.empty(), templates("{author}|{type}|{target}|{content}")).orElseThrow();

		assertOnlyTypeColored(message, null, annotation);
	}

	@Test
	void kineticStressReusesTheAuthorizedCapacityAndOmitsZeroCapacity() throws IOException {
		withTranslations("en_us", () -> {
			var withCapacity = new PresentationSection(CREATE, 1, Map.of(
				"create:kinetic.stress", new PresentationValue.NumberValue(12),
				"create:kinetic.capacity", new PresentationValue.NumberValue(10)), false,
				Map.of(STRESS, "attention"));
			String percent = ContentChatComposer.propertyMessage(
				Component.literal("Steve"), Component.literal("Chest"),
				new PresentationView("block", STRESS, Map.of(CREATE, withCapacity)), List.of(STRESS),
				json -> Optional.empty(), templates("{content}")).orElseThrow().getString();
			assertTrue(percent.contains("Used stress: 12 SU (120%)"),
				"the authorized capacity in the same projection supplies the percentage");

			var zeroCapacity = new PresentationSection(CREATE, 1, Map.of(
				"create:kinetic.stress", new PresentationValue.NumberValue(12),
				"create:kinetic.capacity", new PresentationValue.NumberValue(0)), false,
				Map.of(STRESS, "attention"));
			String plain = ContentChatComposer.propertyMessage(
				Component.literal("Steve"), Component.literal("Chest"),
				new PresentationView("block", STRESS, Map.of(CREATE, zeroCapacity)), List.of(STRESS),
				json -> Optional.empty(), templates("{content}")).orElseThrow().getString();
			assertTrue(plain.contains("12 SU"), "the stress amount is retained");
			assertFalse(plain.contains("%"), "a zero capacity never invents a percentage");
		});
	}

	@Test
	void localizedTemplateSourceReusesTheSelectedLocaleOverridePolicy() {
		PingType request = type("request");
		Map<String, String> selectedLocale = Map.of(
			ContentChatTemplate.TEMPLATE_KEY, "base",
			ContentChatTemplate.templateOverrideKey(request), "override",
			ContentChatTemplate.MULTIPLE_TEMPLATE_KEY, "outer",
			ContentChatTemplate.ENTRY_TEMPLATE_KEY, "entry",
			ContentChatTemplate.entryOverrideKey(request), "entry override");

		assertEquals("override",
			ContentChatComposer.localized(selectedLocale::containsKey, selectedLocale::get).template(request));
		assertEquals("base",
			ContentChatComposer.localized(key -> false, key -> "base").template(request));
		assertNull(ContentChatComposer.localized(key -> false, key -> {
			throw new IllegalStateException("lookup failed");
		}).template(request), "a failed template lookup stays a controlled default fallback");
		var source = ContentChatComposer.localized(selectedLocale::containsKey, selectedLocale::get);
		assertEquals("outer", source.multipleTemplate());
		assertEquals("entry override", source.entryTemplate(request));
		assertEquals("entry", ContentChatComposer.localized(key -> false, selectedLocale::get).entryTemplate(request),
			"a fallback-only entry override is not selected");
		var failed = ContentChatComposer.localized(key -> { throw new IllegalStateException("presence failed"); },
			key -> { throw new IllegalStateException("lookup failed"); });
		assertNull(failed.multipleTemplate());
		assertNull(failed.entryTemplate(request));
	}

	@Test
	void selectedRootAndNestedTextRetainWireBoundedLengthAndLiteralPunctuation() throws IOException {
		withTranslations("en_us", () -> {
			String raw = "example:" + "long_registry_id_".repeat(6) + "{literal}[%s]";
			var root = PresentationPropertyRef.root(BASIC, PresentationBasic.ENTITY_TYPE);
			var nested = new PresentationPropertyRef(BASIC, PresentationBasic.BLOCK_STATE, List.of("value"));
			var section = new PresentationSection(BASIC, 1, Map.of(
				PresentationBasic.ENTITY_TYPE, new PresentationValue.Text(raw),
				PresentationBasic.BLOCK_STATE, new PresentationValue.RecordValue(Map.of("value", new PresentationValue.Text(raw)))),
				false, Map.of(root, "attention", nested, "danger"));
			var view = new PresentationView("entity", null, Map.of(BASIC, section));
			Component message = ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
				view, List.of(root, nested), json -> Optional.empty(), templates("{content}")).orElseThrow();
			assertTrue(raw.length() > 80);
			assertEquals(2, occurrences(message.getString(), raw), "both selected text payloads remain exact literal text");
			String atLimit = "x".repeat(PresentationLimits.MAX_TEXT_BYTES);
			var max = new PresentationSection(BASIC, 1, Map.of(PresentationBasic.ENTITY_TYPE, new PresentationValue.Text(atLimit)),
				false, Map.of(root, "attention"));
			assertTrue(ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
				new PresentationView("entity", null, Map.of(BASIC, max)), List.of(root), json -> Optional.empty(),
				templates("{content}")).orElseThrow().getString().contains(atLimit), "chat adds no HUD truncation");
			assertThrows(IllegalArgumentException.class, () -> new PresentationSection(BASIC, 1,
				Map.of(PresentationBasic.ENTITY_TYPE, new PresentationValue.Text(atLimit + "x")), false),
				"the existing wire input bound is still enforced before composition");
			Component summary = ContentChatComposer.propertyMessage(Component.literal("Steve"), Component.literal("Chest"),
				new PresentationView("entity", null, Map.of(BASIC, new PresentationSection(BASIC, 1, section.fields(), false,
					Map.of(PresentationPropertyRef.root(BASIC, PresentationBasic.BLOCK_STATE), "attention")))),
				List.of(PresentationPropertyRef.root(BASIC, PresentationBasic.BLOCK_STATE)), json -> Optional.empty(),
				templates("{content}")).orElseThrow();
			assertFalse(summary.getString().contains(raw), "selected record roots retain their established size summary, not a dump");
		});
	}

	@Test
	void productionItemDecoderFlattensNestedCustomStylesAndQuotesOnlyCustomText() throws IOException {
		withTranslations("en_us", () -> {
			Component custom = Component.literal("named{root}").withColor(0xAA0000).withStyle(style -> style.withItalic(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/say unsafe")))
				.append(Component.literal("[%s]child").withColor(0x00AA00).withStyle(style -> style.withBold(true)
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("private")))));
			ItemStack stack = new ItemStack(Items.GUNPOWDER);
			stack.set(DataComponents.CUSTOM_NAME, custom);
			String json = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE),
				stack).result().orElseThrow().toString();
			var decoder = ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY);
			Component decoded = decoder.apply(json).orElseThrow();
			assertEquals("\"named{root}[%s]child\" (item.minecraft.gunpowder)", decoded.getString());
			assertEquals(2, decoded.getString().chars().filter(c -> c == '"').count(), "only the custom text is quoted, once");
			Component composed = findTranslatable(decoded, ContentChatTemplate.CUSTOM_ITEM_NAME_KEY);
			assertEquals("item.minecraft.gunpowder", translatableKey(argComponent(composed, 1)),
				"trusted base remains translatable, never flattened to English");
			assertPlainEffectiveTree(decoded, Style.EMPTY);
			Component message = ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
				entry(json, 9007199254740993L, false, null), decoder, templates("{content}")).orElseThrow();
			assertTrue(message.getString().contains(decoded.getString()));
			assertPlainEffectiveTree(argComponent(findTranslatable(message, ContentChatTemplate.ITEM_COUNT_KEY), 0), Style.EMPTY);
			assertEquals("9007199254740993", argText(findTranslatable(message, ContentChatTemplate.ITEM_COUNT_KEY), 1));

			stack.remove(DataComponents.CUSTOM_NAME);
			String normalJson = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE),
				stack).result().orElseThrow().toString();
			assertEquals("item.minecraft.gunpowder", translatableKey(decoder.apply(normalJson).orElseThrow()),
				"an unrenamed item keeps its localized base without quotes");
			stack.set(DataComponents.ITEM_NAME, custom);
			String itemNameJson = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE),
				stack).result().orElseThrow().toString();
			assertPlainEffectiveTree(decoder.apply(itemNameJson).orElseThrow(), Style.EMPTY);
			for (String malformed : List.of("{not json", json.substring(0, json.length() - 2))) {
				assertTrue(decoder.apply(malformed).isEmpty());
				Component fallback = ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
					entry(malformed, 5L, false, null), decoder, templates("{content}")).orElseThrow();
				assertEquals("item.minecraft.gunpowder",
					translatableKey(argComponent(findTranslatable(fallback, ContentChatTemplate.ITEM_COUNT_KEY), 0)));
			}
		});
	}

	@Test
	void productionItemDecoderKeepsANormalVariantsLocalizedBaseName() {
		ItemStack stack = new ItemStack(Items.POTION);
		stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
		String json = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE),
			stack).result().orElseThrow().toString();
		Component decoded = ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(json).orElseThrow();
		assertEquals("item.minecraft.potion.effect.water", translatableKey(decoded));
		assertNull(findTranslatable(decoded, ContentChatTemplate.QUOTE_KEY));
	}

	@Test
	void chinesePropertyExampleMatchesTheConfirmedContract() throws IOException {
		withTranslations("zh_cn", () -> {
			var section = new PresentationSection(BASIC, 1,
				Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("命名牌猫")), false,
				Map.of(CUSTOM_NAME, "attention"));
			var view = new PresentationView("entity", null, Map.of(BASIC, section));

			Component message = ContentChatComposer.propertyMessage(
				Component.literal("player"), Component.literal("target"), view, List.of(CUSTOM_NAME),
				json -> Optional.empty(), templates("{author}: 请求 {type} {target} 的 {content}")).orElseThrow();

			assertEquals("player: 请求 注意 target 的 自定义名称: \"命名牌猫\"", message.getString());
		});
	}

	@Test
	void chineseInventoryExampleMatchesTheConfirmedContract() throws IOException {
		withTranslations("zh_cn", () -> {
			var entry = new ClientInventory.EntryView("key", "minecraft:gunpowder", "item.minecraft.gunpowder",
				"{\"id\":\"minecraft:gunpowder\"}", 96L, false, InventoryS2CPacket.Status.READY, "take");

			Component message = ContentChatComposer.inventoryMessage(
				Component.literal("player"), Component.literal("箱子"), entry,
				json -> Optional.of(Component.literal("火药")),
				templates("{author}: 请求 {type} {target} 的 {content}")).orElseThrow();

			assertEquals("player: 请求 拿走 箱子 的 火药 × 96", message.getString());
		});
	}

	@Test
	void inventoryCountIsExactAndExplicitZeroIsAccepted() {
		Component big = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 9007199254740993L, false, null),
			json -> Optional.empty(), templates("{content}")).orElseThrow();
		Component count = findTranslatable(big, ContentChatTemplate.ITEM_COUNT_KEY);
		assertNotNull(count);
		assertEquals("9007199254740993", argText(count, 1),
			"a long count beyond double precision stays exact");

		Component zero = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 0L, false, null),
			json -> Optional.empty(), templates("{content}")).orElseThrow();
		assertEquals("0", argText(findTranslatable(zero, ContentChatTemplate.ITEM_COUNT_KEY), 1),
			"a complete authoritative zero is a real count");
	}

	@Test
	void inventoryFallbackUsesTheRegistryLocalizedNameWithoutRawLabelLiteral() {
		Component malformed = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{not json", 5L, false, null), json -> Optional.empty(), templates("{content}")).orElseThrow();
		Component itemCount = findTranslatable(malformed, ContentChatTemplate.ITEM_COUNT_KEY);
		assertEquals("item.minecraft.gunpowder", translatableKey(argComponent(itemCount, 0)),
			"the fallback is the registry localized name, not the raw description id as literal text");
		assertFalse(collectText(malformed).contains("{not json"),
			"malformed server display metadata never reaches the message");

		Component throwing = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 5L, false, null), json -> {
				throw new IllegalStateException("malformed");
			}, templates("{content}")).orElseThrow();
		assertEquals("item.minecraft.gunpowder",
			translatableKey(argComponent(findTranslatable(throwing, ContentChatTemplate.ITEM_COUNT_KEY), 0)));
	}

	@Test
	void inventoryQualityIsPreservedWithoutUpgrading() {
		Component uncertain = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 5L, false, InventoryS2CPacket.Status.UNCERTAIN),
			json -> Optional.empty(), templates("{content}")).orElseThrow();
		assertEquals("pingforit.spatial.inventory.uncertain",
			translatableKey(argComponent(findTranslatable(uncertain, ContentChatTemplate.QUALITY_KEY), 1)),
			"an uncertain observation stays uncertain");

		Component fallback = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 5L, true, null),
			json -> Optional.empty(), templates("{content}")).orElseThrow();
		assertEquals("pingforit.spatial.inventory.component_too_long",
			translatableKey(argComponent(findTranslatable(fallback, ContentChatTemplate.QUALITY_KEY), 1)),
			"a component-fallback aggregate never claims an exact variant");

		Component ready = ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 5L, false, InventoryS2CPacket.Status.READY),
			json -> Optional.empty(), templates("{content}")).orElseThrow();
		assertNull(findTranslatable(ready, ContentChatTemplate.QUALITY_KEY),
			"a ready exact observation adds no quality wrapper");
	}

	@Test
	void unknownCountAndTerminalQualityAreUnavailable() {
		assertTrue(ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", -1L, false, null),
			json -> Optional.empty(), templates("{content}")).isEmpty(), "an unknown count is never zero");
		assertTrue(ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 5L, false, InventoryS2CPacket.Status.INVALID),
			json -> Optional.empty(), templates("{content}")).isEmpty(), "an invalid entry is unavailable");
		assertTrue(ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
			entry("{\"id\":\"minecraft:gunpowder\"}", 5L, false, InventoryS2CPacket.Status.UNAVAILABLE),
			json -> Optional.empty(), templates("{content}")).isEmpty(), "an unavailable entry is unavailable");
		assertTrue(ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
			new ClientInventory.EntryView("key", "minecraft:gunpowder", "item.minecraft.gunpowder",
				null, 5L, false, null, null),
			json -> Optional.empty(), templates("{content}")).isEmpty(), "a missing annotation is unavailable");
		assertTrue(ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
			new ClientInventory.EntryView("key", "minecraft:gunpowder", "item.minecraft.gunpowder",
				null, 5L, false, null, "not_a_type"),
			json -> Optional.empty(), templates("{content}")).isEmpty(), "an unknown annotation is unavailable");
	}

	@Test
	void nullArgumentsAreRejected() {
		var section = new PresentationSection(BASIC, 1,
			Map.of(PresentationBasic.CUSTOM_NAME, new PresentationValue.Text("Named Cat")), false,
			Map.of(CUSTOM_NAME, "attention"));
		var view = new PresentationView("entity", null, Map.of(BASIC, section));
		var entry = entry("{\"id\":\"minecraft:gunpowder\"}", 1L, false, null);

		assertThrows(NullPointerException.class, () -> ContentChatComposer.propertyMessage(
			null, Component.literal("Chest"), view, List.of(CUSTOM_NAME), json -> Optional.empty(), templates("{content}")));
		assertThrows(NullPointerException.class, () -> ContentChatComposer.propertyMessage(
			Component.literal("Steve"), Component.literal("Chest"), null, List.of(CUSTOM_NAME),
			json -> Optional.empty(), templates("{content}")));
		assertThrows(NullPointerException.class, () -> ContentChatComposer.propertyMessage(
			Component.literal("Steve"), Component.literal("Chest"), view, null,
			json -> Optional.empty(), templates("{content}")));
		assertThrows(NullPointerException.class, () -> ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"), entry, null, templates("{content}")));
		assertThrows(NullPointerException.class, () -> ContentChatComposer.inventoryMessage(
			Component.literal("Steve"), Component.literal("Chest"), entry, json -> Optional.empty(), null));
	}

	private static ClientInventory.EntryView entry(String displayJson, long count, boolean fallback,
		InventoryS2CPacket.Status quality) {
		return new ClientInventory.EntryView("key", "minecraft:gunpowder", "item.minecraft.gunpowder",
			displayJson, count, fallback, quality, "take");
	}

	private static void assertOnlyTypeColored(Component component, Integer inherited, PingType annotation) {
		assertOnlyTypesColored(component, inherited, Map.of(annotation.id(), annotation));
	}

	private static void assertOnlyTypesColored(Component component, Integer inherited, Map<String, PingType> annotations) {
		Integer effective = component.getStyle().getColor() == null
			? inherited : Integer.valueOf(component.getStyle().getColor().getValue());
		PingType annotation = component.getContents() instanceof TranslatableContents contents
			? annotations.values().stream().filter(type -> contents.getKey()
				.equals("presentation.pingforit.type." + type.id() + ".display")).findFirst().orElse(null) : null;
		if (annotation != null) assertEquals(annotation.textColor(), effective, "each annotation word has its own color");
		else assertNull(effective, () -> "unexpected effective color on: " + component.getContents());

		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child) assertOnlyTypesColored(child, effective, annotations);
		for (Component sibling : component.getSiblings()) assertOnlyTypesColored(sibling, effective, annotations);
	}

	private static void assertPlainEffectiveTree(Component component, Style inherited) {
		Style effective = component.getStyle().applyTo(inherited);
		assertNull(effective.getColor());
		assertFalse(effective.isItalic());
		assertFalse(effective.isBold());
		assertFalse(effective.isUnderlined());
		assertFalse(effective.isStrikethrough());
		assertFalse(effective.isObfuscated());
		assertNull(effective.getClickEvent());
		assertNull(effective.getHoverEvent());
		assertNull(effective.getInsertion());
		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child) assertPlainEffectiveTree(child, effective);
		for (Component sibling : component.getSiblings()) assertPlainEffectiveTree(sibling, effective);
	}

	private static int occurrences(String text, String value) {
		int count = 0;
		for (int index = text.indexOf(value); index >= 0; index = text.indexOf(value, index + value.length())) count++;
		return count;
	}

	private static Component findTranslatable(Component component, String key) {
		if (component.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key))
			return component;
		for (Component sibling : component.getSiblings()) {
			Component found = findTranslatable(sibling, key);
			if (found != null) return found;
		}
		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child) {
					Component found = findTranslatable(child, key);
					if (found != null) return found;
				}
		return null;
	}

	private static Component argComponent(Component translatable, int index) {
		Object[] args = ((TranslatableContents) translatable.getContents()).getArgs();
		assertInstanceOf(Component.class, args[index]);
		return (Component) args[index];
	}

	private static String argText(Component translatable, int index) {
		return String.valueOf(((TranslatableContents) translatable.getContents()).getArgs()[index]);
	}

	private static String translatableKey(Component component) {
		assertInstanceOf(TranslatableContents.class, component.getContents());
		return ((TranslatableContents) component.getContents()).getKey();
	}

	private static boolean containsIdentity(Component component, Component expected) {
		if (component == expected) return true;
		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child && containsIdentity(child, expected)) return true;
		for (Component sibling : component.getSiblings())
			if (containsIdentity(sibling, expected)) return true;
		return false;
	}

	private static String collectText(Component component) {
		StringBuilder text = new StringBuilder();
		collectText(component, text);
		return text.toString();
	}

	private static void collectText(Component component, StringBuilder text) {
		if (component.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents plain)
			text.append(plain.text());
		else if (component.getContents() instanceof TranslatableContents contents) {
			text.append(contents.getKey());
			for (Object arg : contents.getArgs()) {
				if (arg instanceof Component child) collectText(child, text);
				else text.append(arg);
			}
		}
		for (Component sibling : component.getSiblings()) collectText(sibling, text);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run();
	}

	private static void withTranslations(String locale, ThrowingRunnable body) throws IOException {
		Map<String, String> translations = new LinkedHashMap<>();
		try (InputStream stream = ContentChatComposerTest.class.getClassLoader()
			.getResourceAsStream("assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			Language.loadFromJson(stream, translations::put);
		}
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(translations));
		try {
			body.run();
		} finally {
			Language.inject(previous);
		}
	}

	private static final class MapLanguage extends Language {
		private final Map<String, String> translations;
		private MapLanguage(Map<String, String> translations) { this.translations = translations; }
		@Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
		@Override public boolean has(String key) { return translations.containsKey(key); }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}
}
