package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class ClientDisplayTextTest {

	/**
	 * Independent fixture envelope, deliberately not the production validation
	 * limit. A bounded guard or a future memoized guard stays below it; an
	 * unbounded expansion of the repeated-argument fixture cannot.
	 */
	private static final int FIXTURE_VISIT_ENVELOPE = 1 << 16;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void nativeFormattingOracleCoversIndexedMixedEscapedPrimitiveAndInvalidTemplates() {
		Object[] arguments = {Component.literal("A"), "B", null, 42, 2.5, true};
		for (String template : List.of("%s", "%2$s/%1$s", "%%", "%s %2$s %s", "%3$s %4$s %5$s %6$s",
			"plain", "", "%d", "%0$s", "%9$s", "before%s%9$safter", "%", "%999999999999999999999$s", "%1$%")) {
			Component nativeValue = Component.translatableWithFallback("pingforit.test.missing", template, arguments.clone());
			String expected = nativeValue.getString(); // Only this small, independently bounded oracle graph.
			ClientDisplayText.OwnedText owned = ClientDisplayText.own(nativeValue, 0, new ClientItemDisplay.Validation());
			assertEquals(expected, owned.plain(), template);
			assertEquals(expected, owned.component().getString(), template);
		}
	}

	@Test @Timeout(10) void repeatedArgumentExpansionStaysBoundedAndRejectsHugeOutput() {
		for (int depth : List.of(8, 20)) {
			Component hugeOutput = repeatedArgumentExpansion(Component.literal("x"), depth);
			assertThrows(ClientItemDisplay.UnsafeDisplay.class,
				() -> ClientDisplayText.own(hugeOutput, 0, new ClientItemDisplay.Validation()).plain(),
				"an expansion that would emit exponentially much text must be refused");
			assertTrue(ClientItemDisplay.validationVisits() <= FIXTURE_VISIT_ENVELOPE,
				"independent fixture work envelope, not production tuning");
		}
		for (Component leaf : List.of(Component.empty(),
			MutableComponent.create(new ScoreContents("name", "objective")),
			MutableComponent.create(new NbtContents("path", false, Optional.empty(), new BlockDataSource("~ ~ ~"))))) {
			for (int depth : List.of(8, 20)) {
				assertZeroOutputExpansionIsBounded(repeatedArgumentExpansion(leaf, depth));
			}
		}
	}

	private static Component repeatedArgumentExpansion(Component leaf, int depth) {
		Component graph = leaf;
		for (int i = 0; i < depth; i++)
			graph = Component.translatableWithFallback("pingforit.test.missing", "%1$s%1$s%1$s%1$s", graph);
		return graph;
	}

	/**
	 * A future memoized guard may visit each actual distinct occurrence and
	 * finish a zero-output graph inside the budget. The contract protects
	 * bounded work, not the current per-occurrence charge, so either bounded
	 * completion with empty output or a bounded budget refusal is acceptable.
	 */
	private static void assertZeroOutputExpansionIsBounded(Component graph) {
		try {
			var owned = ClientDisplayText.own(graph, 0, new ClientItemDisplay.Validation());
			assertEquals("", owned.plain());
			assertEquals("", owned.component().getString());
		} catch (ClientItemDisplay.UnsafeDisplay boundedRejection) {
			// The current per-occurrence budget may refuse this graph.
		}
		assertTrue(ClientItemDisplay.validationVisits() <= FIXTURE_VISIT_ENVELOPE,
			"zero-output expansion must stay inside the independent fixture envelope");
	}

	@Test void residentTranslationCacheCannotSubstituteAStaleUnsafeArgument() {
		for (boolean cycle : List.of(false, true)) {
			MutableComponent stale = Component.literal("A");
			MutableComponent source = Component.translatableWithFallback("pingforit.test.missing", "%s", stale);
			assertEquals("A", source.getString()); // Warm resident decomposedParts with A.
			((TranslatableContents) source.getContents()).getArgs()[0] = Component.literal("B");
			if (cycle) stale.append(stale);
			else stale.append(Component.translatableWithFallback("pingforit.test.missing", "%s", new Object() {
				@Override public String toString() { throw new AssertionError("stale cache must never be formatted"); }
			}));
			var owned = ClientDisplayText.own(source, 0, new ClientItemDisplay.Validation());
			assertEquals("B", owned.plain());
			assertEquals("B", owned.component().getString());
		}
	}

	@Test void sourceArgsSiblingsTextAndEntityHoversAreDeeplyDetachedWithNullableStyleIntact() {
		MutableComponent argument = Component.literal("arg"), sibling = Component.literal("sibling"), hoverText = Component.literal("hover"), entityName = Component.literal("pig");
		Component entity = Component.literal("entity").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
			new HoverEvent.EntityTooltipInfo(EntityType.PIG, new UUID(1, 2), entityName))));
		MutableComponent source = Component.translatableWithFallback("pingforit.test.missing", "%s", argument).append(sibling).append(entity)
			.withStyle(Style.EMPTY.withBold(false).withItalic(true).withInsertion("insert")
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
		Component owned = ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation());
		argument.append("changed"); sibling.append("changed"); hoverText.append("changed"); entityName.append("changed");
		((TranslatableContents) source.getContents()).getArgs()[0] = Component.literal("replaced");
		assertEquals("argsiblingentity", owned.getString());
		assertEquals("hover", owned.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_TEXT).getString());
		assertEquals("pig", owned.getSiblings().get(1).getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_ENTITY).name.orElseThrow().getString());
		assertEquals(source.getStyle().withHoverEvent(null), owned.getStyle().withHoverEvent(null));
		assertFalse(owned.getStyle().applyTo(Style.EMPTY.withBold(true)).isBold(), "explicit false is not an inherited null");
	}

	@Test void itemHoverCopiesNestedProjectileTextWithoutPrototypeEqualityFolding() {
		MutableComponent nestedText = Component.literal("nested");
		ItemStack arrow = new ItemStack(Items.ARROW);
		arrow.set(DataComponents.CUSTOM_NAME, nestedText);
		ItemStack crossbow = new ItemStack(Items.CROSSBOW);
		crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(arrow)));
		HoverEvent.ItemStackInfo info = new HoverEvent.ItemStackInfo(crossbow);
		ItemStack cached = info.getItemStack();
		Component source = Component.literal("root").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, info)));
		Component owned = ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation());
		nestedText.append(" changed");
		cached.set(DataComponents.CUSTOM_NAME, Component.literal("late"));
		ItemStack copied = owned.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_ITEM).getItemStack();
		assertNull(copied.get(DataComponents.CUSTOM_NAME));
		ItemStack copiedArrow = nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess.load()
			.projectiles(copied.get(DataComponents.CHARGED_PROJECTILES)).getFirst();
		assertEquals("nested", copiedArrow.get(DataComponents.CUSTOM_NAME).getString());
	}

	@Test
	void itemHoverCopyRetainsTransmittedTextThatEqualsTheMutablePrototype() throws ReflectiveOperationException {
		ClientItemDisplay.reset();
		try (RegistryEdit registry = new RegistryEdit(BuiltInRegistries.ITEM)) {
			MutableComponent prototypeText = Component.literal("prototype-equal");
			DataComponentMap defaults = DataComponentMap.builder()
				.set(DataComponents.CUSTOM_NAME, prototypeText).build();
			PrototypeTextItem item = new PrototypeTextItem(defaults);
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath("pingforit", "client_display_text_fixture");
			Registry.register(BuiltInRegistries.ITEM, id, item);
			BuiltInRegistries.ITEM.freeze();

			JsonObject stack = new JsonObject();
			stack.addProperty("id", id.toString());
			JsonObject components = new JsonObject();
			JsonObject name = new JsonObject();
			name.addProperty("text", "prototype-equal");
			components.addProperty("minecraft:custom_name", name.toString());
			stack.add("components", components);

			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id.toString(), "fallback.registry.label", stack.toString());
			ItemStack working = display.stack();
			assertFalse(working.isEmpty(), "the fixture is a normal safe display");
			Component transmitted = working.get(DataComponents.CUSTOM_NAME);
			assertNotNull(transmitted);
			assertEquals(prototypeText, transmitted,
				"fixture text must equal the prototype default so native equality folding is reachable");
			assertNotSame(prototypeText, transmitted,
				"resolve must retain the transmitted value instead of folding it into the prototype");

			Component source = Component.literal("root").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,
				new HoverEvent.ItemStackInfo(working))));
			Component owned = ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation());
			HoverEvent hover = owned.getStyle().getHoverEvent();
			assertNotNull(hover, "a normal safe item hover remains present");
			assertEquals(HoverEvent.Action.SHOW_ITEM, hover.getAction());

			prototypeText.append(" changed");
			ItemStack copied = hover.getValue(HoverEvent.Action.SHOW_ITEM).getItemStack();
			assertFalse(copied.isEmpty());
			Component copiedName = copied.get(DataComponents.CUSTOM_NAME);
			assertNotNull(copiedName);
			assertEquals("prototype-equal", copiedName.getString(),
				"copying must not fall back to the now-mutated prototype component");
			assertNotSame(prototypeText, copiedName);
		} finally {
			ClientItemDisplay.reset();
		}
	}

	@Test void selectorScoreAndNbtKeepNativeUnresolvedTextAndSiblings() {
		for (Component source : List.of(
			MutableComponent.create(new SelectorContents("@a", Optional.of(Component.literal("separator")))).append("tail"),
			MutableComponent.create(new ScoreContents("score", "objective")).append("tail"),
			MutableComponent.create(new NbtContents("path", false, Optional.of(Component.literal("separator")), new BlockDataSource("~ ~ ~"))).append("tail"))) {
			assertEquals(source.getString(), ClientDisplayText.own(source, 0, new ClientItemDisplay.Validation()).plain());
		}
	}

	@Test void embeddedNbtPathStructureIsAdmittedBeforeNativeRecompilation() {
		// Small enough for the fixture's native constructor; deliberately beyond
		// the display graph depth envelope. No huge recursive native oracle runs.
		String path = "{x:".repeat(60) + "0" + "}".repeat(60);
		Component source = MutableComponent.create(new NbtContents(path, false, Optional.empty(),
			new StorageDataSource(ResourceLocation.parse("minecraft:test"))));
		assertThrows(ClientItemDisplay.UnsafeDisplay.class,
			() -> ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation()));
		ItemStack stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, source);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientItemDisplay.displayName(stack));
	}

	@Test void selectorsEmbeddedInAllThreeNativeTextConstructorsAreAdmittedBeforeRecompilation() {
		String selector = "@e[nbt=" + "{x:".repeat(60) + "0" + "}".repeat(60) + "]";
		// Bounded fixture only: never use a huge native recursive oracle.
		for (Component source : selectorTexts(selector)) {
			assertThrows(ClientItemDisplay.UnsafeDisplay.class,
				() -> ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation()), source.getContents().getClass().getSimpleName());
			ItemStack stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, source);
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientItemDisplay.displayName(stack));
		}
	}

	private static List<Component> selectorTexts(String selector) {
		return List.of(MutableComponent.create(new SelectorContents(selector, Optional.empty())),
			MutableComponent.create(new ScoreContents(selector, "objective")),
			MutableComponent.create(new NbtContents("path", false, Optional.empty(), new EntityDataSource(selector))));
	}

	@Test void ordinarySelectorOptionsQuotedBracesScoreNamesAndCoordinatesKeepNativeSemantics() throws Exception {
		for (String selector : List.of("@s", "@e[limit=1]", "@e[nbt={x:{y:0}}]", "@e[nbt=!{x:'it\\'s \\\\ [quoted]'}]",
			"@e['nbt'={x:1},name=\"a\\\"b\"]", "@e[name='" + "{[".repeat(55) + "']",
			"@e[scores={test=1..3},advancements={minecraft:story/root=true}]", "@e[limit=1]trailing{[")) {
			assertDoesNotThrow(() -> new net.minecraft.commands.arguments.selector.EntitySelectorParser(new com.mojang.brigadier.StringReader(selector), true).parse(), selector);
			assertDoesNotThrow(() -> ClientDisplaySyntax.selector(selector, 0, new ClientItemDisplay.Validation()), selector);
			for (Component source : selectorTexts(selector)) {
				Component owned = ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation());
				assertEquals(source.getString(), owned.getString(), selector);
				String textJson = net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, source).result().orElseThrow().toString();
				JsonObject stack = new JsonObject(); stack.addProperty("id", "minecraft:stone"); JsonObject components = new JsonObject();
				components.addProperty("minecraft:custom_name", textJson); stack.add("components", components);
				assertTrue(ClientItemDisplay.decodeName(RegistryAccess.EMPTY, stack.toString()).isPresent(), selector);
			}
		}
		// ScoreContents permits literal names which need not be valid selector or
		// SNBT syntax. They have no recursive parse before the first @ option.
		for (String name : List.of("literal{[", "*", "not a selector", "a'" + "{x:".repeat(60))) {
			Component source = MutableComponent.create(new ScoreContents(name, "objective"));
			assertEquals(source.getString(), ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation()).getString());
		}
		for (String pos : List.of("1 2 3", "~ ~1 ~-2", "^ ^1 ^-2")) {
			var data = new BlockDataSource(pos); assertNotNull(data.compiledPos());
			Component source = MutableComponent.create(new NbtContents("path", false, Optional.empty(), data));
			assertEquals(source.getString(), ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation()).getString());
		}
	}

	@Test void cachedCustomNameNeverAliasesAnEarlierWorkingIconOrItsComposedName() {
		ClientItemDisplay.reset();
		String json = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_name\":\"\\\"before\\\"\"}}";
		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", json);
		assertFalse(first.stack().isEmpty());
		MutableComponent text = (MutableComponent) first.stack().get(DataComponents.CUSTOM_NAME);
		text.append(" changed");
		var next = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", json);
		assertFalse(next.stack().isEmpty());
		assertEquals("before", next.stack().get(DataComponents.CUSTOM_NAME).getString());
		assertNotSame(text, next.stack().get(DataComponents.CUSTOM_NAME));
		assertEquals(1, next.stack().getCount());
		assertEquals(1, ClientItemDisplay.decodeMisses(), "isolation must not bypass the cache");
		assertEquals(first.name().getString(), next.name().getString());
	}

	@Test void gsonLenientUnquotedApostropheCannotHideArrayDepthFromAdmission() throws ReflectiveOperationException {
		String encoded = "{id:'minecraft:stone',a':" + "[".repeat(60) + "0" + "]".repeat(60) + ",b':0}";
		var admission = ClientItemDisplay.class.getDeclaredMethod("admitSyntax", String.class, int.class, ClientItemDisplay.Validation.class);
		admission.setAccessible(true);
		var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
			() -> admission.invoke(null, encoded, 0, new ClientItemDisplay.Validation()));
		assertInstanceOf(ClientItemDisplay.UnsafeDisplay.class, failure.getCause(), "refuse before native tree construction");
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).isEmpty());
		assertTrue(ClientItemDisplay.decodeName(RegistryAccess.EMPTY, encoded).isEmpty());
		assertTrue(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", encoded).stack().isEmpty());
	}

	@Test void lenientStreamingAdmissionMatchesSmallNativeTokensAndCountsEveryContainer() {
		for (String source : List.of("{id:'minecraft:stone',a':[[0]],b':0}",
			"{id:'minecraft:stone',a\":[[0]],b\":0}", "/*'}]}*/{id:'minecraft:stone',x:[1;2,]}",
			")]}'\n{id:'minecraft:stone',x:[true,null,\"a\\\"[b\",'c\\']d']}",
			"{id:'minecraft:stone',x=>[NaN,Infinity,unquoted]}", "# }]}\n{id:'minecraft:stone'}")) {
			assertDoesNotThrow(() -> com.google.gson.JsonParser.parseString(source), source); // Small native lexical oracle only.
			assertDoesNotThrow(() -> ClientDisplaySyntax.json(source, 0, new ClientItemDisplay.Validation()), source);
		}
		String hidden = "{id:'minecraft:stone',a':" + "[".repeat(60) + "0" + "]".repeat(60) + ",b':0}";
		for (String source : List.of(hidden, "/* }]} */" + "[".repeat(60) + "0" + "]".repeat(60)))
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplaySyntax.json(source, 0, new ClientItemDisplay.Validation()));
		var remaining = new ClientItemDisplay.Validation(64, 4096);
		remaining.characterUnits(4090);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplaySyntax.json("{longname:'value'}", 0, remaining),
			"token workspace is reserved from the existing root before JsonReader allocates decoded names");
	}

	@Test void snbtAndPathQuotesUseBrigadierEscapesAndDoNotCountQuotedBrackets() throws Exception {
		for (String path : List.of("path", "foo[0].bar", "foo[{x:1}].bar", "{x:{y:0}}.foo",
			"\"" + "{[".repeat(60) + "\"", "'it\\'s[quoted]'.foo", "foo\\bar{x:'text\\\\[still quoted]'}")) {
			// Small native inputs; quoted brackets are not recursive parser calls.
			assertDoesNotThrow(() -> net.minecraft.commands.arguments.NbtPathArgument.nbtPath().parse(new com.mojang.brigadier.StringReader(path)), path);
			assertDoesNotThrow(() -> ClientDisplaySyntax.nbt(path, 0, new ClientItemDisplay.Validation()), path);
			Component text = MutableComponent.create(new NbtContents(path, false, Optional.empty(), new StorageDataSource(ResourceLocation.parse("minecraft:test"))));
			assertEquals("", ClientDisplayText.own(text, 0, new ClientItemDisplay.Validation()).plain());
		}
		String deep = "{x:".repeat(60) + "0" + "}".repeat(60);
		for (String path : List.of(deep, "foo" + deep, "foo[" + deep + "]", "'it\\'s'" + deep))
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplaySyntax.nbt(path, 0, new ClientItemDisplay.Validation()));
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplaySyntax.nbt("{x:'bad\\n'}", 0, new ClientItemDisplay.Validation()),
			"SNBT is not JSON and does not accept JSON-only escape sequences");
		var budget = new ClientItemDisplay.Validation(32, 4096); budget.cells(30);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplaySyntax.nbt("a.b.c", 0, budget));
	}

	@Test void itemHoverLoreRetainsNativeStyleAndDetachesSourceLines() {
		MutableComponent line = Component.literal("lore");
		var sourceLore = new net.minecraft.world.item.component.ItemLore(List.of(line));
		ItemStack item = new ItemStack(Items.STONE); item.set(DataComponents.LORE, sourceLore);
		Component source = Component.literal("root").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,
			new HoverEvent.ItemStackInfo(item))));
		Component owned = ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation());
		var lore = owned.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_ITEM).getItemStack().get(DataComponents.LORE);
		assertEquals(sourceLore.styledLines().getFirst().getStyle(), lore.styledLines().getFirst().getStyle());
		line.append("changed");
		assertEquals("lore", lore.lines().getFirst().getString());
		assertEquals("lore", lore.styledLines().getFirst().getString());
	}

	@Test void unknownArgumentIsRefusedEvenWhenTheTemplateWouldNotUseIt() {
		Component source = Component.translatableWithFallback("pingforit.test.missing", "unused", new Number() {
			@Override public int intValue() { throw new AssertionError(); }
			@Override public long longValue() { throw new AssertionError(); }
			@Override public float floatValue() { throw new AssertionError(); }
			@Override public double doubleValue() { throw new AssertionError(); }
			@Override public String toString() { throw new AssertionError(); }
		});
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplayText.snapshot(source, 0, new ClientItemDisplay.Validation()));
	}

	@Test void textSnapshotRespectsInputLengthAndPreviouslyConsumedCharacterBudget() {
		String small = "abc";
		int inputCharacters = 64;
		var ample = ClientDisplayText.own(Component.literal(small), 0, new ClientItemDisplay.Validation(64, 4096));
		assertEquals(small, ample.plain());
		assertEquals(small, ample.component().getString());

		// The same independent budget accepts the short input and rejects a
		// longer one, so the boundary follows input length rather than an
		// internal visit formula.
		var shortInput = ClientDisplayText.own(Component.literal(small), 0, new ClientItemDisplay.Validation(64, inputCharacters));
		assertEquals(small, shortInput.plain());
		String longInput = "x".repeat(inputCharacters + 1);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class,
			() -> ClientDisplayText.own(Component.literal(longInput), 0, new ClientItemDisplay.Validation(64, inputCharacters)));

		// Prior work leaves less room than the input itself. Refusal must honor
		// that remaining allowance, independently of copy/flatten pass counts or
		// whether a later implementation caches an already flattened value.
		var remaining = new ClientItemDisplay.Validation(64, inputCharacters);
		remaining.characterUnits(inputCharacters - small.length() + 1);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class,
			() -> ClientDisplayText.own(Component.literal(small), 0, remaining),
			"snapshot admission must not reset a budget already consumed by prior work");
	}

	/** Real item with a mutable default component but no load-verification hook. */
	private static final class PrototypeTextItem extends Item {
		private final DataComponentMap defaults;
		PrototypeTextItem(DataComponentMap defaults) {
			super(new Item.Properties());
			this.defaults = defaults;
		}
		@Override public DataComponentMap components() { return defaults; }
	}

	/** Adds one uniquely named fixture item and restores every touched registry field. */
	private static final class RegistryEdit implements AutoCloseable {
		private final MappedRegistry<?> registry;
		private final Map<Field, Object> state = new HashMap<>();
		RegistryEdit(Registry<?> registry) throws ReflectiveOperationException {
			this.registry = (MappedRegistry<?>) registry;
			for (String name : List.of("byId", "toId", "byLocation", "byKey", "byValue", "registrationInfos")) {
				Field field = field(name);
				Object value = field.get(registry);
				state.put(field, value instanceof List<?> list ? new ArrayList<>(list) : new IdentityHashMap<>((Map<?, ?>) value));
			}
			for (String name : List.of("frozen", "unregisteredIntrusiveHolders", "registryLifecycle")) {
				Field field = field(name);
				state.put(field, field.get(registry));
			}
			field("frozen").set(registry, false);
			field("unregisteredIntrusiveHolders").set(registry, new IdentityHashMap<>());
		}
		private static Field field(String name) throws ReflectiveOperationException {
			Field field = MappedRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		}
		@SuppressWarnings({"rawtypes", "unchecked"})
		@Override public void close() throws ReflectiveOperationException {
			for (var entry : state.entrySet()) {
				Object current = entry.getKey().get(registry);
				if (entry.getValue() instanceof List list) { ((List) current).clear(); ((List) current).addAll(list); }
				else if (entry.getValue() instanceof Map map) { ((Map) current).clear(); ((Map) current).putAll(map); }
				else entry.getKey().set(registry, entry.getValue());
			}
		}
	}
}
