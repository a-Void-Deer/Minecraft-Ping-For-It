package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.BlockDataSource;
import net.minecraft.network.chat.contents.NbtContents;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import nx.pingwheel.common.chat.ContentChatComposer;
import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;

import static org.junit.jupiter.api.Assertions.*;

/** Opaque values never become client display ownership merely because an item defaults or hook supplies them. */
class ClientItemDisplayOpaqueComponentsTest {

	private static final int SHARED_DAG_DEPTH = 30;
	/**
	 * Independent fixture envelope, deliberately not the production validation
	 * limit. A correct bounded guard or an optimized memoized guard must stay
	 * below it; the 2^30 expanded path count cannot.
	 */
	private static final int FIXTURE_VISIT_ENVELOPE = 1 << 16;

	private RegistryEdit items;
	private RegistryEdit components;
	private Opaque opaque;
	private DataComponentType<Opaque> persistent;
	private DataComponentType<Opaque> transientType;
	private DataComponentType<Opaque> unregistered;
	private FixtureItem item;
	private int codecCalls;

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@BeforeEach void fixtures() throws ReflectiveOperationException {
		ClientItemDisplay.reset();
		items = new RegistryEdit(BuiltInRegistries.ITEM, true);
		components = new RegistryEdit(BuiltInRegistries.DATA_COMPONENT_TYPE, false);
		opaque = new Opaque();
		Codec<Opaque> codec = Codec.INT.xmap(value -> {
			codecCalls++;
			throw new AssertionError("unknown display addition must not invoke its codec");
		}, value -> {
			codecCalls++;
			throw new AssertionError("display validation must not encode an opaque value");
		});
		persistent = DataComponentType.<Opaque>builder().persistent(codec).build();
		transientType = DataComponentType.<Opaque>builder().networkSynchronized(StreamCodec.unit(opaque)).build();
		unregistered = DataComponentType.<Opaque>builder().persistent(codec).build();
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, ResourceLocation.parse("pingforit:client_opaque"), persistent);
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, ResourceLocation.parse("pingforit:client_transient"), transientType);
		item = new FixtureItem();
		Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse("pingforit:client_display_fixture"), item);
		BuiltInRegistries.DATA_COMPONENT_TYPE.freeze();
		BuiltInRegistries.ITEM.freeze();
	}

	@AfterEach void restoreRegistries() throws ReflectiveOperationException {
		ClientItemDisplay.reset();
		if (items != null) items.close();
		if (components != null) components.close();
	}

	@Test void transmittedSuppressionRemovesAnUnknownMutableDefaultWithoutTouchingItsValue() {
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(persistent, opaque).build();
		String encoded = payload("\"!pingforit:client_opaque\":{},\"minecraft:custom_model_data\":7");
		for (int resolve = 0; resolve < 3; resolve++) {
			var display = resolve(encoded);
			assertFalse(display.stack().isEmpty());
			assertEquals(item, display.stack().getItem());
			assertNull(display.stack().get(persistent));
			assertEquals(new CustomModelData(7), display.stack().get(DataComponents.CUSTOM_MODEL_DATA));
			display.stack().remove(DataComponents.CUSTOM_MODEL_DATA);
			opaque.value++;
		}
		assertEquals(1, ClientItemDisplay.decodeMisses(), "safe suppression keeps the existing snapshot cache");
		assertTrue(ClientItemDisplay.cachedTransmittedPatch(RegistryAccess.EMPTY, itemId(), encoded).orElseThrow().get(persistent).isEmpty());
		assertNull(ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).orElseThrow().get(persistent));
		assertEquals(0, codecCalls);
		assertEquals(0, opaque.comparisons, "default suppression never compares the opaque value");
	}

	@Test void missingSuppressionCannotResurrectAnOpaqueDefaultIntoTheCacheOrIcon() {
		String encoded = payload("\"minecraft:custom_model_data\":3");
		assertFalse(resolve(encoded).stack().isEmpty(), "the same payload initially has supported defaults");
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(persistent, opaque).build();
		for (int resolve = 0; resolve < 3; resolve++) {
			assertUnavailable(resolve(encoded));
			opaque.value++;
		}
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).isEmpty());
		assertEquals(0, ClientItemDisplay.cachedEntryCount(), "a previously safe hit must be checked against effective defaults");
		assertEquals(0, codecCalls);
		assertEquals(0, opaque.comparisons);
	}

	@Test void registeredUnknownAdditionsFailBeforeTheirCodecIncludingSupportedNestedItems() {
		String unknown = payload("\"pingforit:client_opaque\":1");
		JsonObject name = new JsonObject();
		name.addProperty("text", "hover");
		JsonObject hover = new JsonObject();
		hover.addProperty("action", "show_item");
		hover.add("contents", com.google.gson.JsonParser.parseString(unknown));
		name.add("hoverEvent", hover);
		JsonObject named = new JsonObject();
		named.addProperty("id", itemId());
		JsonObject namedComponents = new JsonObject();
		namedComponents.addProperty("minecraft:custom_name", name.toString());
		named.add("components", namedComponents);
		for (String encoded : List.of(unknown,
			"{\"id\":\"minecraft:chest\",\"components\":{\"minecraft:container\":[{\"slot\":0,\"item\":" + unknown + "}]}}",
			"{\"id\":\"minecraft:bundle\",\"components\":{\"minecraft:bundle_contents\":[" + unknown + "]}}",
			"{\"id\":\"minecraft:crossbow\",\"components\":{\"minecraft:charged_projectiles\":[" + unknown + "]}}",
			"{\"id\":\"minecraft:apple\",\"components\":{\"minecraft:food\":{\"nutrition\":1,\"saturation\":0.1,\"using_converts_to\":" + unknown + "}}}",
			named.toString())) {
			assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).isEmpty());
		}
		assertUnavailable(resolve(unknown));
		assertEquals(0, codecCalls);
		assertEquals(0, ClientItemDisplay.cachedEntryCount());
	}

	@Test void unregisteredAndTransientDefaultsFailClosedInsteadOfBeingAssumedImmutable() {
		DataComponentMap original = item.defaults;
		for (DataComponentType<Opaque> type : List.of(unregistered, transientType)) {
			item.defaults = DataComponentMap.builder().addAll(original).set(type, opaque).build();
			assertUnavailable(resolve(payload("")));
			assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, payload("")).isEmpty());
		}
		assertEquals(0, opaque.comparisons);
		assertEquals(0, codecCalls);
	}

	@Test void nestedUnknownDefaultsCannotEnterThroughASupportedComponent() {
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(persistent, opaque).build();
		String nested = payload("");
		String outer = "{\"id\":\"minecraft:chest\",\"components\":{\"minecraft:container\":[{\"slot\":0,\"item\":" + nested + "}]}}";
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, outer).isEmpty(), "refuse before the native container hashes a nested opaque default");
		assertTrue(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:chest", "fallback.registry.label", outer).stack().isEmpty());
		assertEquals(0, opaque.comparisons);
		assertEquals(0, codecCalls);
	}

	@Test void constructorHookInjectionRejectsBothNamingAndRenderStacks() {
		for (boolean naming : List.of(true, false)) {
			ClientItemDisplay.reset();
			item.hook = stack -> {
				if (naming == !stack.has(DataComponents.CUSTOM_MODEL_DATA)) stack.set(persistent, opaque);
			};
			for (int resolve = 0; resolve < 2; resolve++) assertUnavailable(resolve(payload("\"minecraft:custom_model_data\":5")));
			assertEquals(0, ClientItemDisplay.cachedEntryCount(), "hook-refused snapshots are not retained");
		}
		assertEquals(4, opaque.comparisons, "only the item hook's native set compares to its absent prototype value");
		assertEquals(0, codecCalls);
	}

	@Test void namingHookCannotPublishAnOpaqueMutationOrItsDerivedText() {
		item.nameHook = stack -> stack.set(persistent, opaque);
		assertUnavailable(resolve(payload("")));
		assertEquals(0, ClientItemDisplay.cachedEntryCount());
		assertEquals(1, opaque.comparisons, "the display guard adds no value comparisons after the native item mutation");
	}

	@Test void codecOnlyNamingConstructionAlsoRejectsOpaqueHookAdditions() {
		item.hook = stack -> stack.set(persistent, opaque);
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, payload("")).isEmpty());
		assertEquals(1, opaque.comparisons, "only the native item hook compares its new value");
		assertEquals(0, codecCalls);
	}

	@Test void displayFailurePreservesTheAcceptedExactReferenceCountAndSelection() {
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(persistent, opaque).build();
		List<nx.pingwheel.common.network.IPacket> sent = new ArrayList<>();
		ClientInventory client = new ClientInventory(sent::add);
		client.presentationReset(100, 1);
		long request = client.open(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"), BlockFace.NORTH, "entity_block");
		client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(10, 20, 3, 5)).stamp(100, 1));
		client.accept(InventoryS2CPacket.policy(7, 100, 1, java.util.Set.of("entity_block")));
		String encoded = payload("");
		var entry = new InventoryS2CPacket.Entry("exact-variant", itemId(), "fallback.registry.label", encoded, 9007199254740993L, 1, false, null);
		client.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 4, 1, 5, 0, 1,
			true, InventoryS2CPacket.Status.READY, 0, List.of(entry)).stamp(100, 1));
		var reference = client.selectable(request).getFirst();
		var before = client.preview(request).entries().getFirst();
		assertUnavailable(ClientItemDisplay.resolve(RegistryAccess.EMPTY, before.itemId(), before.label(), before.displayJson()));
		assertEquals(before, client.preview(request).entries().getFirst());
		assertFalse(before.fallback(), "a display refusal is not All variants identity fallback");
		assertEquals(entry.count(), before.count());
		assertEquals(reference, client.selectable(request).getFirst());
		assertEquals(ClientInventory.DispatchOutcome.SENT, client.select(reference, "attention", packet -> {
			sent.add(packet);
			return ClientInventory.DispatchOutcome.SENT;
		}));
		var selected = assertInstanceOf(InventoryC2SPacket.class, sent.getLast());
		assertEquals(InventoryC2SPacket.Kind.SELECT, selected.kind());
		assertEquals(reference.entryKey(), selected.entryKey());
		assertEquals(reference.baselineId(), selected.baselineId());
		assertEquals(reference.stateRevision(), selected.stateRevision());
	}

	@Test void supportedVanillaDefaultsAndVariantComponentsKeepOwnedCountOneStacks() {
		for (Item vanilla : List.of(Items.STONE, Items.DIAMOND_SWORD, Items.CROSSBOW, Items.POTION, Items.APPLE, Items.PLAYER_HEAD)) {
			ItemStack source = new ItemStack(vanilla);
			source.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(11));
			String encoded = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), source).result().orElseThrow().toString();
			String id = BuiltInRegistries.ITEM.getKey(vanilla).toString();
			var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback.registry.label", encoded);
			assertFalse(first.stack().isEmpty(), id);
			assertEquals(1, first.stack().getCount());
			first.stack().remove(DataComponents.CUSTOM_MODEL_DATA);
			var next = ClientItemDisplay.resolve(RegistryAccess.EMPTY, id, "fallback.registry.label", encoded);
			assertNotSame(first.stack(), next.stack());
			assertEquals(new CustomModelData(11), next.stack().get(DataComponents.CUSTOM_MODEL_DATA));
		}
	}

	@Test @Timeout(10)
	void sharedNativeProjectileDagStaysBoundedAndRejectsLateUnsafeText() {
		ItemStack graph = new ItemStack(Items.ARROW);
		// Each native of() copies only its two outer stacks. Their component
		// values still alias the previous holder: resident O(depth), expanded 2^depth.
		for (int depth = 0; depth < SHARED_DAG_DEPTH; depth++) {
			ChargedProjectiles pair = ChargedProjectiles.of(List.of(graph, graph));
			graph = new ItemStack(Items.CROSSBOW);
			graph.set(DataComponents.CHARGED_PROJECTILES, pair);
		}
		ItemStack unsafeNested = new ItemStack(Items.STONE);
		unsafeNested.set(persistent, opaque);
		ItemStack lateUnsafe = new ItemStack(Items.STONE);
		lateUnsafe.set(DataComponents.CUSTOM_NAME, hoverItem(unsafeNested));
		ChargedProjectiles root = ChargedProjectiles.of(List.of(graph, lateUnsafe));
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(DataComponents.CHARGED_PROJECTILES, root).build();
		for (int attempt = 0; attempt < 2; attempt++) {
			assertUnavailable(resolve(payload("")));
			// Observation only: the expectation is the independent fixture envelope,
			// not the production validation cap. A memoized guard may visit O(depth)
			// and reject at the real unsafe sentinel.
			assertTrue(ClientItemDisplay.validationVisits() <= FIXTURE_VISIT_ENVELOPE,
				"owned work must stay inside the independent fixture envelope");
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			assertTrue(ClientItemDisplay.validationVisits() <= FIXTURE_VISIT_ENVELOPE,
				"the chat entrance shares the same bounded fixture outcome");
		}
		assertEquals(0, item.nameCalls, "refusal happens before naming or later sentinel traversal");
		assertEquals(0, codecCalls);
	}

	@Test void textCyclesFailWithinTheSameRootBound() {
		MutableComponent cycle = Component.literal("cycle");
		cycle.append(cycle);
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(DataComponents.CUSTOM_NAME, cycle).build();
		assertUnavailable(resolve(payload("")));
		assertTrue(ClientItemDisplay.validationVisits() <= FIXTURE_VISIT_ENVELOPE,
			"text cycle stays inside the independent fixture envelope");
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
	}

	@Test void effectiveDefaultTextContainersRejectOpaqueHoverItems() {
		ItemStack nested = new ItemStack(Items.STONE);
		nested.set(persistent, opaque);
		HoverEvent.ItemStackInfo materialized = new HoverEvent.ItemStackInfo(new ItemStack(Items.STONE));
		materialized.getItemStack().set(persistent, opaque);
		Component cachedUnsafe = Component.literal("cached").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, materialized)));
		Component unsafe = hoverItem(nested);
		DataComponentMap original = item.defaults;
		List<DataComponentMap> variants = List.of(
			DataComponentMap.builder().addAll(original).set(DataComponents.CUSTOM_NAME, unsafe).build(),
			DataComponentMap.builder().addAll(original).set(DataComponents.ITEM_NAME, unsafe).build(),
			DataComponentMap.builder().addAll(original).set(DataComponents.CUSTOM_NAME, cachedUnsafe).build(),
			DataComponentMap.builder().addAll(original).set(DataComponents.LORE, new ItemLore(List.of(unsafe))).build(),
			DataComponentMap.builder().addAll(original).set(DataComponents.LORE, new ItemLore(List.of(), List.of(unsafe))).build(),
			DataComponentMap.builder().addAll(original).set(DataComponents.WRITTEN_BOOK_CONTENT,
				new WrittenBookContent(Filterable.passThrough("title"), "author", 0, List.of(Filterable.passThrough(unsafe)), true)).build(),
			DataComponentMap.builder().addAll(original).set(DataComponents.WRITTEN_BOOK_CONTENT,
				new WrittenBookContent(Filterable.passThrough("title"), "author", 0, List.of(new Filterable<>(Component.literal("raw"), Optional.of(unsafe))), true)).build());
		for (DataComponentMap defaults : variants) {
			item.defaults = defaults;
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		}
		assertEquals(2, opaque.comparisons, "only fixture set() touched the opaque object; graph checks never hash or compare it");
	}

	@Test void effectiveTextTraversesArgumentsSiblingsSeparatorsAndEveryNativeHoverChild() {
		ItemStack nested = new ItemStack(Items.STONE);
		nested.set(persistent, opaque);
		Component unsafe = hoverItem(nested);
		List<Component> positions = List.of(
			Component.translatable("fixture.translation", unsafe),
			Component.literal("root").append(unsafe),
			MutableComponent.create(new SelectorContents("@s", Optional.of(unsafe))),
			MutableComponent.create(new NbtContents("path", false, Optional.of(unsafe), new BlockDataSource("~ ~ ~"))),
			Component.literal("root").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, unsafe))),
			Component.literal("root").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
				new HoverEvent.EntityTooltipInfo(EntityType.PIG, new UUID(1, 2), unsafe)))));
		DataComponentMap original = item.defaults;
		for (Component position : positions) {
			item.defaults = DataComponentMap.builder().addAll(original).set(DataComponents.ITEM_NAME, position).build();
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		}
	}

	@Test
	void rootNativeTextInjectedByLoadAndNamingHooksRejectsOpaqueHoverItems() {
		ItemStack nested = new ItemStack(Items.STONE);
		nested.set(persistent, opaque);
		Component unsafe = hoverItem(nested);

		// verifyComponentsAfterLoad installs the root text on both the naming and
		// working stacks; this is the post-constructor route, not a direct opaque
		// component mutation.
		item.hook = stack -> stack.set(DataComponents.CUSTOM_NAME, unsafe);
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());

		// getName installs ITEM_NAME only after the pre-call component scan, so the
		// post-name recheck is the only place that can reject it.
		item.hook = stack -> {};
		item.nameHook = stack -> stack.set(DataComponents.ITEM_NAME, unsafe);
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());

		// A book page is another effective native text route sharing the same
		// hover validation.
		item.nameHook = stack -> {};
		item.hook = stack -> stack.set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough("title"), "author", 0,
				List.of(Filterable.passThrough(unsafe)), true));
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
	}

	@Test void safeJsonHoverCannotHideAnOpaqueNestedItemConstructorHook() {
		ItemStack nested = new ItemStack(item);
		ItemStack outer = new ItemStack(Items.STONE);
		outer.set(DataComponents.CUSTOM_NAME, hoverItem(nested));
		String json = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), outer).result().orElseThrow().toString();
		item.hook = stack -> stack.set(persistent, opaque);
		assertUnavailable(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback.registry.label", json));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(json).isEmpty());
		assertEquals(0, codecCalls);
	}

	@Test void cachedEffectiveDefaultTextIsRecheckedAfterItsHoverStackChanges() {
		HoverEvent.ItemStackInfo info = new HoverEvent.ItemStackInfo(new ItemStack(Items.STONE));
		ItemStack cachedHover = info.getItemStack();
		Component name = Component.literal("safe first").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, info)));
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(DataComponents.CUSTOM_NAME, name).build();
		String json = payload("");
		assertFalse(resolve(json).stack().isEmpty());
		int misses = ClientItemDisplay.decodeMisses();
		cachedHover.set(persistent, opaque);
		assertUnavailable(resolve(json));
		assertEquals(misses, ClientItemDisplay.decodeMisses(), "the unsafe graph is caught on the existing cache hit");
		assertEquals(0, ClientItemDisplay.cachedEntryCount());
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(json).isEmpty());
	}

	@Test void deferredHoverWithAnItemLoadHookFailsWithoutStartingTheHook() {
		ItemStack nested = new ItemStack(item);
		Component pending = hoverItem(nested);
		item.hook = stack -> { throw new AssertionError("validation must not materialize a deferred hover stack"); };
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(DataComponents.CUSTOM_NAME, pending).build();
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
	}

	@Test void productionChatChecksTheEffectiveGraphAfterNamingAndContainsHookExceptions() {
		var decoder = ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY);
		item.nameHook = stack -> stack.set(persistent, opaque);
		assertTrue(decoder.apply(payload("")).isEmpty(), "getName mutation cannot publish its derived text");
		assertEquals(1, item.nameCalls, "this is the post-getName seam, not constructor rejection");
		item.nameHook = stack -> { throw new IllegalStateException("name hook failed"); };
		assertTrue(decoder.apply(payload("")).isEmpty(), "chat receives unavailable instead of an unhandled hook error");
		item.nameHook = stack -> {};
		item.hook = stack -> stack.set(persistent, opaque);
		int namesBefore = item.nameCalls;
		assertTrue(decoder.apply(payload("")).isEmpty());
		assertEquals(namesBefore, item.nameCalls, "constructor hook injection is rejected before name composition");
	}

	@Test void throwingNameHookReportsOriginalCauseOnceWhileNormalSafetySuppressionStaysSilent() {
		try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
			item.nameHook = stack -> stack.set(persistent, opaque);
			assertUnavailable(resolve(payload(""))); assertTrue(logs.events().isEmpty());
			item.nameHook = stack -> { throw new IllegalStateException("name hook failed", new RuntimeException("display hook root cause")); };
			assertUnavailable(resolve(payload(""))); assertUnavailable(resolve(payload("")));
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("display hook root cause"));
			assertTrue(logs.messages().contains("operation=inventory/client-display-resolve")); assertNull(logs.events().getFirst().getThrown());
		}
	}

	@Test void constructorRuntimeFailuresKeepDisplayFallbacksButLinkageErrorsPropagate() {
		String encoded = payload("");
		var runtime = new IllegalStateException("display constructor failed", new RuntimeException("constructor root cause"));
		item.hook = stack -> { throw runtime; };
		try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
			assertUnavailable(resolve(encoded));
			assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).isEmpty());
			assertTrue(ClientItemDisplay.decodeName(RegistryAccess.EMPTY, encoded).isEmpty());
			assertEquals(3, logs.events().size()); assertTrue(logs.messages().contains("constructor root cause"));
		}
		var linkage = new LinkageError("display constructor linkage failure");
		item.hook = stack -> { throw linkage; };
		try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
			assertSame(linkage, assertThrows(LinkageError.class, () -> resolve(encoded)));
			assertSame(linkage, assertThrows(LinkageError.class, () -> ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded)));
			assertSame(linkage, assertThrows(LinkageError.class, () -> ClientItemDisplay.decodeName(RegistryAccess.EMPTY, encoded)));
			assertTrue(logs.events().isEmpty(), "the caller still owns a propagated linkage failure");
		}
	}

	@Test void renderConstructorAndNameHookLinkageErrorsStillReachTheCaller() {
		var linkage = new LinkageError("display hook linkage failure");
		item.hook = stack -> { if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) throw linkage; };
		try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
			assertSame(linkage, assertThrows(LinkageError.class, () -> resolve(payload("\"minecraft:custom_model_data\":7"))),
				"the reflective render constructor must not turn a linkage failure into display fallback");
			item.hook = stack -> {};
			item.nameHook = stack -> { throw linkage; };
			assertSame(linkage, assertThrows(LinkageError.class, () -> resolve(payload(""))));
			assertSame(linkage, assertThrows(LinkageError.class, () -> ClientItemDisplay.decodeName(RegistryAccess.EMPTY, payload(""))));
			assertTrue(logs.events().isEmpty());
		}
	}

	@Test void cachedDefaultRuntimeFailureEvictsButLinkageFailurePropagatesWithoutFallback() {
		String encoded = payload("");
		assertFalse(resolve(encoded).stack().isEmpty()); assertEquals(1, ClientItemDisplay.cachedEntryCount());
		item.componentsReadHook = () -> { throw new IllegalStateException("cached defaults failed"); };
		try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
			assertUnavailable(resolve(encoded)); assertEquals(0, ClientItemDisplay.cachedEntryCount());
			assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("operation=inventory/client-display-cache"));
		}
		item.componentsReadHook = () -> {};
		assertFalse(resolve(encoded).stack().isEmpty()); assertEquals(1, ClientItemDisplay.cachedEntryCount());
		var linkage = new LinkageError("cached defaults linkage failure");
		item.componentsReadHook = () -> { throw linkage; };
		try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
			assertSame(linkage, assertThrows(LinkageError.class, () -> resolve(encoded)));
			assertEquals(1, ClientItemDisplay.cachedEntryCount(), "a propagated failure does not execute fallback eviction");
			assertTrue(logs.events().isEmpty());
		}
	}

	@Test void nativeDecodeContextRuntimeFailureFallsBackButLinkageFailurePropagates() {
		String encoded = payload("");
		for (Throwable failure : List.of(new IllegalStateException("native display context failed"), new LinkageError("native display context linkage failure"))) {
			var provider = new HolderLookup.Provider() {
				@Override public java.util.stream.Stream<ResourceKey<? extends Registry<?>>> listRegistries() { throw new AssertionError("context fails first"); }
				@Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) { throw new AssertionError("context fails first"); }
				@Override public <T> RegistryOps<T> createSerializationContext(DynamicOps<T> ops) {
					if (failure instanceof RuntimeException runtime) throw runtime;
					throw (LinkageError) failure;
				}
			};
			try (var logs = new nx.pingwheel.common.presentation.inventory.InventoryDiagnosticsTestCapture()) {
				if (failure instanceof RuntimeException) {
					assertUnavailable(ClientItemDisplay.resolve(provider, itemId(), "fallback.registry.label", encoded));
					assertTrue(ClientItemDisplay.decode(provider, encoded).isEmpty()); assertTrue(ClientItemDisplay.decodeName(provider, encoded).isEmpty());
					assertEquals(1, logs.events().size()); assertTrue(logs.messages().contains("operation=inventory/client-display-codec"));
				} else {
					assertSame(failure, assertThrows(LinkageError.class, () -> ClientItemDisplay.resolve(provider, itemId(), "fallback.registry.label", encoded)));
					assertSame(failure, assertThrows(LinkageError.class, () -> ClientItemDisplay.decode(provider, encoded)));
					assertSame(failure, assertThrows(LinkageError.class, () -> ClientItemDisplay.decodeName(provider, encoded)));
					assertTrue(logs.events().isEmpty());
				}
			}
		}
	}

	@Test void descriptionHookIsFencedBeforeAnyNewCustomTextCanBeFormatted() {
		// The custom name itself is safe. Only the extra registered opaque
		// component can make the post-description map unsafe, so a missing
		// immediate fence returns a name instead of failing later text work.
		item.descriptionHook = stack -> {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("safe custom name"));
			stack.set(persistent, opaque);
		};
		assertThrows(ClientItemDisplay.UnsafeDisplay.class,
			() -> ClientItemDisplay.displayName(new ItemStack(item)),
			"displayName must fence the description hook before composing its safe custom text");
		assertUnavailable(resolve(payload("")));
		assertTrue(ClientItemDisplay.decodeName(RegistryAccess.EMPTY, payload("")).isEmpty());
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		assertEquals(0, item.nameCalls, "the immediate description fence precedes the next name hook");
	}

	@Test @Timeout(10) void translationExpansionBombsAreRefusedAtEveryProductionEntrance() {
		for (boolean custom : List.of(false, true)) {
			Component bomb = Component.empty();
			for (int depth = 0; depth < 20; depth++) bomb = Component.translatableWithFallback("pingforit.test.missing", "%1$s%1$s%1$s%1$s", bomb);
			if (custom) item.defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS).set(DataComponents.CUSTOM_NAME, bomb).build();
			else item.nameResult = bomb;
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			if (custom) assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, payload("")).isEmpty(), "codec-only callers cannot obtain a text bomb");
		}
	}

	@Test void legacyItemAndModernFailureFallbackAreGuardedBeforeNestedOpaqueCodecs() {
		String unknown = "{id:'minecraft:stone',count:1,components:{'pingforit:client_opaque':1}}";
		for (boolean modern : List.of(false, true)) {
			JsonObject text = new JsonObject(); text.addProperty("text", "outer");
			JsonObject hover = new JsonObject(); hover.addProperty("action", "show_item");
			hover.addProperty("value", unknown);
			if (modern) hover.add("contents", new com.google.gson.JsonArray());
			text.add("hoverEvent", hover);
			assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, namedPayload(text)).isEmpty());
		}
		assertEquals(0, codecCalls);
	}

	@Test @Timeout(10) void legacyExpandedTextBombIsRejectedWithoutNativeFlattening() {
		JsonObject graph = new JsonObject(); graph.addProperty("text", "");
		for (int depth = 0; depth < 20; depth++) {
			JsonObject parent = new JsonObject(); parent.addProperty("translate", "pingforit.test.missing");
			parent.addProperty("fallback", "%1$s%1$s%1$s%1$s");
			com.google.gson.JsonArray args = new com.google.gson.JsonArray(); args.add(graph); parent.add("with", args); graph = parent;
		}
		JsonObject text = new JsonObject(); text.addProperty("text", "outer");
		JsonObject hover = new JsonObject(); hover.addProperty("action", "show_item"); hover.add("value", graph); text.add("hoverEvent", hover);
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, namedPayload(text)).isEmpty());
		assertTrue(ClientItemDisplay.validationVisits() <= FIXTURE_VISIT_ENVELOPE);
	}

	@Test void smallLegacyTranslationIsFlattenedIntoOwnedLiteralBeforeNativeConversion() {
		JsonObject legacy = new JsonObject(); legacy.addProperty("translate", "pingforit.test.missing");
		legacy.addProperty("fallback", "{id:'minecraft:stone',count:%s}");
		com.google.gson.JsonArray args = new com.google.gson.JsonArray(); args.add(1); legacy.add("with", args);
		JsonObject text = new JsonObject(); text.addProperty("text", "outer");
		JsonObject hover = new JsonObject(); hover.addProperty("action", "show_item"); hover.add("value", legacy); text.add("hoverEvent", hover);
		String encoded = namedPayload(text);
		ItemStack decoded = ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).orElseThrow();
		assertStoneHover(decoded);
		ItemStack resolved = resolve(encoded).stack();
		assertFalse(resolved.isEmpty());
		assertStoneHover(resolved);
	}

	private static void assertStoneHover(ItemStack stack) {
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		assertNotNull(name);
		HoverEvent hover = name.getStyle().getHoverEvent();
		assertNotNull(hover);
		assertEquals(HoverEvent.Action.SHOW_ITEM, hover.getAction());
		ItemStack hovered = hover.getValue(HoverEvent.Action.SHOW_ITEM).getItemStack();
		assertEquals(Items.STONE, hovered.getItem());
		assertEquals(1, hovered.getCount());
	}

	@Test void malformedModernItemPayloadCanUseItsGuardedSafeLegacyAlternative() {
		JsonObject text = new JsonObject(); text.addProperty("text", "outer");
		JsonObject hover = new JsonObject(); hover.addProperty("action", "show_item");
		hover.add("contents", new com.google.gson.JsonArray());
		hover.addProperty("value", "{id:'minecraft:diamond',count:1}"); text.add("hoverEvent", hover);
		ItemStack decoded = ClientItemDisplay.decode(RegistryAccess.EMPTY, namedPayload(text)).orElseThrow();
		var info = decoded.get(DataComponents.CUSTOM_NAME).getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_ITEM);
		assertEquals(Items.DIAMOND, info.getItemStack().getItem());
	}

	@Test void nestedLegacyEntityNameCannotHideAnOpaqueItemCodec() {
		JsonObject unsafeName = new JsonObject(); unsafeName.addProperty("text", "nested");
		JsonObject nestedHover = new JsonObject(); nestedHover.addProperty("action", "show_item");
		nestedHover.addProperty("value", "{id:'minecraft:stone',count:1,components:{'pingforit:client_opaque':1}}");
		unsafeName.add("hoverEvent", nestedHover);
		net.minecraft.nbt.CompoundTag entityTag = new net.minecraft.nbt.CompoundTag();
		entityTag.putString("type", "minecraft:pig"); entityTag.putString("id", new UUID(1, 2).toString());
		entityTag.putString("name", unsafeName.toString());
		JsonObject text = new JsonObject(); text.addProperty("text", "outer");
		JsonObject hover = new JsonObject(); hover.addProperty("action", "show_entity"); hover.addProperty("value", entityTag.toString());
		text.add("hoverEvent", hover);
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, namedPayload(text)).isEmpty());
		assertEquals(0, codecCalls);
	}

	@Test @Timeout(10) void lexicalAdmissionRejectsDeepTreesBeforeJsonOrSnbtParsing() {
		String deepJson = "[".repeat(2000) + "0" + "]".repeat(2000);
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, deepJson).isEmpty());
		JsonObject text = new JsonObject(); text.addProperty("text", "outer");
		JsonObject hover = new JsonObject(); hover.addProperty("action", "show_item");
		hover.addProperty("value", "{id:'minecraft:stone',count:1,components:{'minecraft:custom_data':" + deepJson + "}}");
		text.add("hoverEvent", hover);
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, namedPayload(text)).isEmpty());
		// A lenient comment cannot spoof a negative lexical nesting depth and
		// let the actual parser enter an otherwise hidden deep tree.
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, "/*" + "}".repeat(2000) + "*/" + deepJson).isEmpty());
	}

	@Test void recursiveNbtPathsAreFencedAtWireDefaultAndEveryItemTextHookEntrance() {
		String path = "{x:".repeat(60) + "0" + "}".repeat(60);
		JsonObject encodedText = new JsonObject(); encodedText.addProperty("nbt", path); encodedText.addProperty("storage", "minecraft:test");
		String wire = namedPayload(encodedText);
		assertUnavailable(resolve(wire));
		assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, wire).isEmpty());
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(wire).isEmpty());
		Component text = MutableComponent.create(new NbtContents(path, false, Optional.empty(), new BlockDataSource("~ ~ ~")));
		item.defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS).set(DataComponents.ITEM_NAME, text).build();
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		item.defaults = DataComponents.COMMON_ITEM_COMPONENTS;
		item.nameResult = text;
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientItemDisplay.displayName(new ItemStack(item)));
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		item.nameResult = Component.literal("safe");
		item.descriptionHook = stack -> stack.set(DataComponents.CUSTOM_NAME, text);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientItemDisplay.displayName(new ItemStack(item)));
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		item.descriptionHook = stack -> {};
		item.hook = stack -> stack.set(DataComponents.CUSTOM_NAME, text);
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
	}

	@Test void embeddedSelectorSnbtIsFencedAtWireDefaultsReturnedNamesAndBothMutationHooks() {
		String selector = "@e[nbt=" + "{x:".repeat(60) + "0" + "}".repeat(60) + "]";
		for (int type = 0; type < 3; type++) {
			JsonObject textJson = new JsonObject();
			Component text;
			if (type == 0) {
				textJson.addProperty("selector", selector);
				text = MutableComponent.create(new SelectorContents(selector, Optional.empty()));
			} else if (type == 1) {
				JsonObject score = new JsonObject(); score.addProperty("name", selector); score.addProperty("objective", "objective"); textJson.add("score", score);
				text = MutableComponent.create(new net.minecraft.network.chat.contents.ScoreContents(selector, "objective"));
			} else {
				textJson.addProperty("nbt", "path"); textJson.addProperty("entity", selector);
				text = MutableComponent.create(new NbtContents("path", false, Optional.empty(), new net.minecraft.network.chat.contents.EntityDataSource(selector)));
			}
			String encoded = namedPayload(textJson);
			assertUnavailable(resolve(encoded));
			assertTrue(ClientItemDisplay.decode(RegistryAccess.EMPTY, encoded).isEmpty());
			assertTrue(ClientItemDisplay.decodeName(RegistryAccess.EMPTY, encoded).isEmpty());
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(encoded).isEmpty());
			item.defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS).set(DataComponents.CUSTOM_NAME, text).build();
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			item.defaults = DataComponents.COMMON_ITEM_COMPONENTS;
			item.nameResult = text;
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientItemDisplay.displayName(new ItemStack(item)));
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			item.nameResult = Component.literal("safe");
			item.nameHook = stack -> stack.set(DataComponents.ITEM_NAME, text);
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			item.nameHook = stack -> {};
			item.descriptionHook = stack -> stack.set(DataComponents.CUSTOM_NAME, text);
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientItemDisplay.displayName(new ItemStack(item)));
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			item.descriptionHook = stack -> {};
			item.hook = stack -> stack.set(DataComponents.ITEM_NAME, text);
			assertUnavailable(resolve(payload("")));
			assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
			item.hook = stack -> {};
		}
	}

	@Test void namingAndRenderHooksCannotMutateCachedTextOrPublishSharedKnownValues() {
		String encoded = payload("\"minecraft:custom_name\":\"\\\"before\\\"\"");
		item.descriptionHook = stack -> ((MutableComponent) stack.get(DataComponents.CUSTOM_NAME)).append(" named");
		var first = resolve(encoded);
		assertFalse(first.stack().isEmpty());
		assertEquals("before", first.stack().get(DataComponents.CUSTOM_NAME).getString());
		Component quote = findTranslatable(first.name(), ContentChatTemplate.QUOTE_KEY);
		assertNotNull(quote); assertEquals("before named", ((Component) ((TranslatableContents) quote.getContents()).getArgs()[0]).getString());
		var second = resolve(encoded);
		assertEquals(first.name().getString(), second.name().getString());
		assertEquals("before", ClientItemDisplay.cachedTransmittedPatch(RegistryAccess.EMPTY, itemId(), encoded).orElseThrow().get(DataComponents.CUSTOM_NAME).orElseThrow().getString());
		assertEquals(1, ClientItemDisplay.decodeMisses());

		ClientItemDisplay.reset(); item.descriptionHook = stack -> {};
		MutableComponent producer = Component.literal("hook value");
		item.hook = stack -> stack.set(DataComponents.ITEM_NAME, producer);
		var hooked = resolve(payload(""));
		assertFalse(hooked.stack().isEmpty());
		assertNotSame(producer, hooked.stack().get(DataComponents.ITEM_NAME));
		producer.append(" changed");
		assertEquals("hook value", hooked.stack().get(DataComponents.ITEM_NAME).getString(), "known hook values are owned, not only validated");
	}

	@Test void ordinaryWorkingCopyDetachesDefaultLoreAndRetainsNativeAdventurePredicates() {
		MutableComponent line = Component.literal("lore before");
		ItemLore lore = new ItemLore(List.of(line));
		item.defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS).set(DataComponents.LORE, lore).build();
		ItemStack naming = ClientItemDisplay.decode(RegistryAccess.EMPTY, payload("")).orElseThrow();
		assertNotSame(lore, naming.get(DataComponents.LORE));
		assertNotSame(line, naming.get(DataComponents.LORE).lines().getFirst());
		assertEquals(lore.styledLines().getFirst().getStyle(), naming.get(DataComponents.LORE).styledLines().getFirst().getStyle());
		line.append(" changed");
		assertEquals("lore before", naming.get(DataComponents.LORE).lines().getFirst().getString());
		assertEquals("lore before", naming.get(DataComponents.LORE).styledLines().getFirst().getString());

		item.defaults = DataComponents.COMMON_ITEM_COMPONENTS;
		net.minecraft.nbt.CompoundTag predicateData = new net.minecraft.nbt.CompoundTag(); predicateData.putInt("x", 1);
		ItemStack fixture = new ItemStack(item);
		fixture.set(DataComponents.CAN_BREAK, new net.minecraft.world.item.AdventureModePredicate(List.of(
			new net.minecraft.advancements.critereon.BlockPredicate(Optional.of(net.minecraft.core.HolderSet.direct(net.minecraft.world.level.block.Blocks.STONE.builtInRegistryHolder())),
				Optional.empty(), Optional.of(new net.minecraft.advancements.critereon.NbtPredicate(predicateData)))), true));
		fixture.set(DataComponents.DEBUG_STICK_STATE, net.minecraft.world.item.component.DebugStickState.EMPTY);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		String encoded = ItemStack.SINGLE_ITEM_CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), fixture).result().orElseThrow().toString();
		var first = ClientItemDisplay.resolve(registries, itemId(), "fallback.registry.label", encoded); assertFalse(first.stack().isEmpty());
		var raw = nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess.load();
		raw.predicates(first.stack().get(DataComponents.CAN_BREAK)).getFirst().nbt().orElseThrow().tag().putInt("x", 2);
		int misses = ClientItemDisplay.decodeMisses();
		var next = ClientItemDisplay.resolve(registries, itemId(), "fallback.registry.label", encoded); assertFalse(next.stack().isEmpty());
		assertEquals(1, raw.predicates(next.stack().get(DataComponents.CAN_BREAK)).getFirst().nbt().orElseThrow().tag().getInt("x"));
		assertNotSame(first.stack().get(DataComponents.CAN_BREAK), next.stack().get(DataComponents.CAN_BREAK));
		assertEquals(misses, ClientItemDisplay.decodeMisses());
	}

	private String namedPayload(JsonObject text) {
		JsonObject stack = new JsonObject(); stack.addProperty("id", itemId());
		JsonObject components = new JsonObject(); components.addProperty("minecraft:custom_name", text.toString()); stack.add("components", components);
		return stack.toString();
	}

	@Test void productionChatRejectsUnsafeReturnedTextAndUnknownArgumentObjectsWithoutFormattingThem() {
		ItemStack nested = new ItemStack(Items.STONE);
		nested.set(persistent, opaque);
		item.nameResult = hoverItem(nested);
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		assertUnavailable(resolve(payload("")));
		item.nameResult = Component.translatable("fixture.translation", opaque);
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
	}

	@Test void writablePagesRejectUnknownValueShapesWithoutFormattingThem() {
		@SuppressWarnings({"unchecked", "rawtypes"})
		List<Filterable<String>> unsafePages = (List) List.of(Filterable.passThrough(opaque));
		item.defaults = DataComponentMap.builder().addAll(item.defaults)
			.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(unsafePages)).build();
		assertUnavailable(resolve(payload("")));
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isEmpty());
		assertEquals(0, opaque.comparisons);
	}

	@Test void safeNativeTextDefaultsHoverAndFilteredBookStringsRemainAvailable() {
		Component safe = hoverItem(new ItemStack(Items.DIAMOND));
		item.defaults = DataComponentMap.builder().addAll(item.defaults).set(DataComponents.CUSTOM_NAME, safe)
			.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(List.of(new Filterable<>("raw", Optional.of("filtered")))))
			.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("title"), "author", 0,
				List.of(new Filterable<>(safe, Optional.of(Component.translatable("item.minecraft.stone")))), true)).build();
		var display = resolve(payload(""));
		assertFalse(display.stack().isEmpty());
		Component retainedSafe = display.stack().get(DataComponents.CUSTOM_NAME);
		assertNotNull(retainedSafe);
		assertEquals(safe.getString(), retainedSafe.getString());
		HoverEvent retainedItemHover = retainedSafe.getStyle().getHoverEvent();
		assertNotNull(retainedItemHover, "a safe known text hover remains on the icon stack");
		assertEquals(HoverEvent.Action.SHOW_ITEM, retainedItemHover.getAction());
		ItemStack expectedHover = safe.getStyle().getHoverEvent().getValue(HoverEvent.Action.SHOW_ITEM).getItemStack();
		ItemStack actualHover = retainedItemHover.getValue(HoverEvent.Action.SHOW_ITEM).getItemStack();
		assertEquals(expectedHover.getItem(), actualHover.getItem());
		assertEquals(expectedHover.getCount(), actualHover.getCount());
		assertEquals(expectedHover.getComponents().keySet(), actualHover.getComponents().keySet());
		for (DataComponentType<?> type : expectedHover.getComponents().keySet())
			assertEquals(expectedHover.get(type), actualHover.get(type), "each safe effective component is retained, irrespective of patch representation");
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isPresent());
		item.defaults = DataComponents.COMMON_ITEM_COMPONENTS;
		MutableComponent safeName = Component.translatable("fixture.returned", Component.literal("safe argument"))
			.withColor(0x00AA00)
			.withStyle(style -> style.withBold(true)
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("safe hover"))));
		safeName.append(Component.translatable("fixture.sibling"));
		item.nameResult = safeName;
		Component listName = resolve(payload("")).name();
		Component chatName = ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).orElseThrow();
		for (Component name : List.of(listName, chatName)) {
			assertNotNull(name.getStyle().getColor());
			assertEquals(0x00AA00, name.getStyle().getColor().getValue());
			assertTrue(name.getStyle().isBold());
			HoverEvent hover = name.getStyle().getHoverEvent();
			assertNotNull(hover);
			assertEquals(HoverEvent.Action.SHOW_TEXT, hover.getAction());
			assertEquals("safe hover", hover.getValue(HoverEvent.Action.SHOW_TEXT).getString());
			TranslatableContents contents = assertInstanceOf(TranslatableContents.class, name.getContents());
			assertEquals("fixture.returned", contents.getKey());
			assertEquals("safe argument", ((Component) contents.getArgs()[0]).getString());
			assertFalse(name.getSiblings().isEmpty(), "the trusted translation sibling remains");
			assertEquals("fixture.sibling", assertInstanceOf(TranslatableContents.class,
				name.getSiblings().getFirst().getContents()).getKey());
		}

		// Mutating the source component after resolution must not publish a new
		// opaque hover through either already-returned safe name.
		HoverEvent retainedHover = listName.getStyle().getHoverEvent();
		ItemStack unsafeNested = new ItemStack(Items.STONE);
		unsafeNested.set(persistent, opaque);
		safeName.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,
			new HoverEvent.ItemStackInfo(unsafeNested))));
		assertEquals(retainedHover, listName.getStyle().getHoverEvent());
		assertEquals(retainedHover, chatName.getStyle().getHoverEvent());
		item.nameResult = Component.literal("fixture-derived name");
		ItemStack ordinary = new ItemStack(Items.STONE);
		ordinary.set(DataComponents.CUSTOM_NAME, safe);
		String json = ItemStack.SINGLE_ITEM_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), ordinary).result().orElseThrow().toString();
		assertFalse(ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback.registry.label", json).stack().isEmpty());
		item.defaults = DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS)
			.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("redacted")))).build();
		var lore = resolve(payload(""));
		assertTrue(lore.stack().isEmpty(), "ordinary Lore remains icon-only refusal");
		assertEquals("fixture-derived name", lore.name().getString(), "safe text still survives ordinary Lore policy");
		assertTrue(ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY).apply(payload("")).isPresent());
	}

	@Test
	void productionChatMessageUsesRegistryLabelAndPreservesExactCountAndReferenceOnUnsafeItemName() {
		List<nx.pingwheel.common.network.IPacket> sent = new ArrayList<>();
		ClientInventory client = new ClientInventory(sent::add);
		client.presentationReset(100, 1);
		long request = client.open(new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			BlockFace.NORTH, "entity_block");
		client.accept(InventoryS2CPacket.offer(7, new InventoryS2CPacket.Offer(10, 20, 3, 5)).stamp(100, 1));
		client.accept(InventoryS2CPacket.policy(7, 100, 1, java.util.Set.of("entity_block")));
		String encoded = payload("");
		var entry = new InventoryS2CPacket.Entry("exact-variant", itemId(), "fallback.registry.label", encoded,
			9007199254740993L, 1, false, null, 0, false, "take");
		client.accept(InventoryS2CPacket.data(InventoryS2CPacket.Kind.PREVIEW, 7, request, null, 4, 1, 5, 0, 1,
			true, InventoryS2CPacket.Status.READY, 0, List.of(entry)).stamp(100, 1));
		var reference = client.selectable(request).getFirst();
		var before = client.preview(request).entries().getFirst();

		ItemStack nested = new ItemStack(Items.STONE);
		nested.set(persistent, opaque);
		item.nameResult = hoverItem(nested);
		var decoder = ContentChatComposer.itemDisplayDecoder(RegistryAccess.EMPTY);
		assertTrue(decoder.apply(encoded).isEmpty(), "the real naming hook path must reject unsafe returned text");
		Component message = ContentChatComposer.inventoryMessage(Component.literal("Steve"), Component.literal("Chest"),
			before, decoder, type -> "{content}").orElseThrow();

		Component itemCount = findTranslatable(message, ContentChatTemplate.ITEM_COUNT_KEY);
		assertNotNull(itemCount);
		assertEquals("fallback.registry.label", translatableKey(argComponent(itemCount, 0)),
			"chat uses the registry label fallback, not the unsafe returned name");
		assertEquals("9007199254740993", argText(itemCount, 1), "the exact long count is preserved");
		assertNoHover(message);
		assertEquals(before, client.preview(request).entries().getFirst(), "the accepted entry is unchanged");
		assertEquals(reference, client.selectable(request).getFirst(), "the exact selection reference is unchanged");
	}

	private static Component hoverItem(ItemStack stack) {
		HoverEvent.ItemStackInfo info = new HoverEvent.ItemStackInfo(stack);
		return Component.literal("hover name").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, info)));
	}

	private ClientItemDisplay.Display resolve(String encoded) {
		return ClientItemDisplay.resolve(RegistryAccess.EMPTY, itemId(), "fallback.registry.label", encoded);
	}
	private String itemId() { return BuiltInRegistries.ITEM.getKey(item).toString(); }
	private String payload(String components) { return "{\"id\":\"" + itemId() + "\",\"components\":{" + components + "}}"; }
	private static void assertUnavailable(ClientItemDisplay.Display display) {
		assertTrue(display.stack().isEmpty());
		assertEquals("fallback.registry.label", assertInstanceOf(TranslatableContents.class, display.name().getContents()).getKey());
	}

	private static Component findTranslatable(Component component, String key) {
		if (component.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) return component;
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
		return assertInstanceOf(Component.class, args[index]);
	}

	private static String argText(Component translatable, int index) {
		return String.valueOf(((TranslatableContents) translatable.getContents()).getArgs()[index]);
	}

	private static String translatableKey(Component component) {
		return assertInstanceOf(TranslatableContents.class, component.getContents()).getKey();
	}

	private static void assertNoHover(Component component) {
		assertNull(component.getStyle().getHoverEvent(), () -> "unexpected hover on: " + component.getContents());
		if (component.getContents() instanceof TranslatableContents contents)
			for (Object arg : contents.getArgs())
				if (arg instanceof Component child) assertNoHover(child);
		for (Component sibling : component.getSiblings()) assertNoHover(sibling);
	}

	private static final class Opaque {
		int value;
		int comparisons;
		@Override public int hashCode() { throw new AssertionError("opaque display value must not be hashed"); }
		@Override public boolean equals(Object other) {
			comparisons++;
			if (other != null) throw new AssertionError("opaque display value must not be compared");
			return false;
		}
		@Override public String toString() { throw new AssertionError("opaque display value must not be formatted"); }
	}

	private static final class FixtureItem extends Item {
		DataComponentMap defaults;
		Runnable componentsReadHook = () -> {};
		Consumer<ItemStack> hook = stack -> {};
		Consumer<ItemStack> nameHook = stack -> {};
		Consumer<ItemStack> descriptionHook = stack -> {};
		Component nameResult = Component.literal("fixture-derived name");
		int nameCalls;
		FixtureItem() { super(new Item.Properties()); defaults = super.components(); }
		@Override public DataComponentMap components() { componentsReadHook.run(); return defaults; }
		@Override public void verifyComponentsAfterLoad(ItemStack stack) { hook.accept(stack); }
		@Override public Component getName(ItemStack stack) { nameCalls++; nameHook.accept(stack); return nameResult; }
		@Override public String getDescriptionId(ItemStack stack) { descriptionHook.accept(stack); return "item.pingforit.fixture"; }
	}

	/** Restores the native registries after registering real mod-shaped fixture types and item hooks. */
	private static final class RegistryEdit implements AutoCloseable {
		private final MappedRegistry<?> registry;
		private final Map<Field, Object> state = new HashMap<>();
		RegistryEdit(Registry<?> registry, boolean intrusive) throws ReflectiveOperationException {
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
			if (intrusive) field("unregisteredIntrusiveHolders").set(registry, new IdentityHashMap<>());
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
