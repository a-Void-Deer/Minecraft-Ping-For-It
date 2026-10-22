package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySizeScale;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryItemDisplayProjectionTest {

	private static final String LONG_NAME = "n".repeat(20000);
	private static RegistryAccess registries;

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	private static ItemStack namedWithCustomData() {
		ItemStack named = new ItemStack(Items.STONE);
		named.set(DataComponents.CUSTOM_NAME, Component.literal(LONG_NAME));
		named.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));
		return named;
	}

	@Test
	void configuredCapPreservesTheDisplayBeyondTheDefaultBound() {
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES);

		assertEquals(InventoryLimits.MAX_DISPLAY_BYTES, catalog.displayBytes());
		var encoded = catalog.encode(namedWithCustomData());

		assertTrue(utf8Length(encoded.display().displayJson()) > InventoryItemCodec.MAX_DISPLAY_BYTES,
			"the configured cap admits a display the default projection would omit");
		assertTrue(utf8Length(encoded.display().displayJson()) <= InventoryLimits.MAX_DISPLAY_BYTES);
		assertTrue(encoded.display().displayJson().contains(LONG_NAME), "the full projection is preserved");
		assertFalse(encoded.display().componentsStripped());
	}

	@Test
	void defaultProjectionStaysAtTheDefaultCap() {
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);

		assertEquals(InventoryItemCodec.MAX_DISPLAY_BYTES, catalog.displayBytes());
		var encoded = catalog.encode(namedWithCustomData());

		assertTrue(utf8Length(encoded.display().displayJson()) <= InventoryItemCodec.MAX_DISPLAY_BYTES);
		assertFalse(encoded.display().displayJson().contains(LONG_NAME), "the oversized name is omitted");
		assertFalse(encoded.display().componentsStripped(),
			"display-only omission is not the component-too-long fallback");
	}

	@Test
	void completeDisplayStrippingAtAConfiguredCapKeepsExactIdentity() {
		CompoundTag payload = new CompoundTag();
		payload.putString("data", "x".repeat(2048));
		ItemStack customData = new ItemStack(Items.STONE);
		customData.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));

		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, minimumDisplayCap());
		var encoded = catalog.encode(customData);

		assertFalse(encoded.display().aggregateRequired(), "display cap never folds identity");
		assertFalse(encoded.key().componentsKey().isEmpty());
		assertTrue(utf8Length(encoded.display().displayJson()) <= minimumDisplayCap());
		assertTrue(encoded.display().displayJson().contains("minecraft:stone"));
	}

	@Test
	void tightenDisplayReprojectsRetainedDisplaysAndNeverExpands() {
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES);
		var encoded = catalog.encode(namedWithCustomData());
		long retainedBefore = catalog.retainedBytes();
		assertTrue(utf8Length(encoded.display().displayJson()) > InventoryItemCodec.MAX_DISPLAY_BYTES);

		catalog.tightenDisplay(InventoryItemCodec.MAX_DISPLAY_BYTES);

		var tightened = catalog.displays().get(encoded.key());
		assertNotNull(tightened);
		assertEquals(1, catalog.displays().size(), "tightening never recomputes variant keys");
		assertEquals(InventoryItemCodec.MAX_DISPLAY_BYTES, catalog.displayBytes());
		assertTrue(utf8Length(tightened.displayJson()) <= InventoryItemCodec.MAX_DISPLAY_BYTES);
		assertFalse(tightened.displayJson().contains(LONG_NAME));
		assertTrue(catalog.retainedBytes() < retainedBefore, "the tightened display charge is refunded");

		catalog.tightenDisplay(InventoryLimits.MAX_DISPLAY_BYTES);

		assertEquals(InventoryItemCodec.MAX_DISPLAY_BYTES, catalog.displayBytes(),
			"a later increase never raises the retained ceiling");
		assertEquals(tightened, catalog.displays().get(encoded.key()),
			"a later increase never expands retained metadata");
		assertEquals(tightened, catalog.encode(namedWithCustomData()).display(),
			"an already-known key keeps its retained projection");
	}

	@Test
	void reprojectKeepsSafeFittingDisplaysAndPreservesUnavailableIdentity() {
		int minimum = minimumDisplayCap();
		var fits = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			"{\"id\":\"minecraft:stone\",\"components\":{\"!minecraft:lore\":{}}}", false);
		assertTrue(utf8Length(fits.displayJson()) <= minimum);
		assertSame(fits, InventoryItemCodec.reproject(fits, RegistryAccess.EMPTY, minimum));

		var invalid = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			"x".repeat(2048), false);
		assertUnavailableDisplayPreservesIdentity(invalid, minimum);

		var known = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			"x".repeat(2048), true);
		assertUnavailableDisplayPreservesIdentity(known, minimum);

		// A real oversized component fixture whose serialized id disagrees with the retained item id.
		var mismatched = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			oversizedJson(Items.DIRT), false);
		assertUnavailableDisplayPreservesIdentity(mismatched, minimum);

		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemCodec.reproject(fits, RegistryAccess.EMPTY, minimum - 1),
			"a cap below the legal effective minimum is rejected even for a fitting display");
		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemCodec.reproject(fits, RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES + 1));
	}

	@Test
	void minimumCapProjectsRealFixturesWithinTheCapAndRejectsSmallerCaps() {
		int minimum = minimumDisplayCap();
		assertEquals(1024, minimum, "the confirmed legal effective minimum is the 1 KiB display cap");
		assertEquals(minimum, InventoryItemCodec.minimumDisplayBytes(),
			"the codec exposes the same single-authority minimum");

		// Every entry point shares the one range: direct projection, catalogue constructors,
		// tightening and reprojection.
		ItemStack stone = new ItemStack(Items.STONE);
		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemCodec.display(stone, RegistryAccess.EMPTY, minimum - 1));
		assertThrows(IllegalArgumentException.class,
			() -> new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, minimum - 1));
		assertThrows(IllegalArgumentException.class,
			() -> new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryItemCodec.DEFAULT_MAX_ENTRIES,
				InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES, minimum - 1));
		var tighteningCatalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES);
		tighteningCatalog.encode(namedWithCustomData());
		assertThrows(IllegalArgumentException.class, () -> tighteningCatalog.tightenDisplay(minimum - 1));
		assertEquals(InventoryLimits.MAX_DISPLAY_BYTES, tighteningCatalog.displayBytes(),
			"a rejected tightening leaves the retained ceiling unchanged");

		// A real oversized component strips to the bounded bare form, which fits the minimum cap.
		CompoundTag payload = new CompoundTag();
		payload.putString("data", "x".repeat(2048));
		ItemStack customData = new ItemStack(Items.STONE);
		customData.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, minimum);
		var encoded = catalog.encode(customData);
		assertFalse(encoded.display().aggregateRequired());
		assertTrue(encoded.display().displayJson().contains("!minecraft:custom_data"));
		assertTrue(encoded.display().displayJson().contains("!minecraft:lore"));
		assertTrue(utf8Length(encoded.display().displayJson()) <= minimum,
			"the bare fallback never exceeds the legal minimum cap");

		// A real display-only omission also stays inside the minimum cap without claiming the fallback.
		var partialCatalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, minimum);
		var partial = partialCatalog.encode(namedWithCustomData());
		assertFalse(partial.display().componentsStripped());
		assertTrue(utf8Length(partial.display().displayJson()) <= minimum);
	}

	@Test
	void displayItemIdBoundAdmitsSafeMaximumDisplaysWithoutReconstructingMalformedInput() {
		int minimum = minimumDisplayCap();

		// Reviewer counterexample: the unbounded public item id would have produced a bare
		// form larger than the cap, so the display metadata boundary rejects it instead.
		var oversizedId = "minecraft:" + "a".repeat(1024);
		assertTrue(utf8Length("{\"id\":\"" + oversizedId + "\"}") > minimum,
			"the rejected id would have produced an oversized bare form");
		assertThrows(IllegalArgumentException.class, () -> new InventoryItemCodec.Display(
			oversizedId, "block.minecraft.stone", "x".repeat(2048), false));

		// The established 256 UTF-8 byte id bound is byte-based, not character-based.
		var unicodeOverBound = "minecraft:" + "\u00e9".repeat(200);
		assertTrue(unicodeOverBound.length() <= 256, "the character count alone would admit this id");
		assertTrue(utf8Length(unicodeOverBound) > 256);
		assertThrows(IllegalArgumentException.class, () -> new InventoryItemCodec.Display(
			unicodeOverBound, "block.minecraft.stone", "{\"id\":\"minecraft:stone\"}", false));

		// A blank item id is invalid metadata, independently of display availability.
		assertThrows(IllegalArgumentException.class, () -> new InventoryItemCodec.Display(
			" ", "block.minecraft.stone", "{\"id\":\"minecraft:stone\"}", false));

		// A suppression-bearing maximum-id envelope fits; malformed bytes remain unavailable.
		var maxId = "minecraft:" + "a".repeat(246);
		assertEquals(256, utf8Length(maxId));
		var maxFits = new InventoryItemCodec.Display(maxId, "block.minecraft.stone",
			"{\"id\":\"" + maxId + "\",\"components\":{\"!minecraft:lore\":{}}}", false);
		assertTrue(utf8Length(maxFits.displayJson()) <= minimum,
			"the valid maximum id envelope fits the minimum cap");
		assertSame(maxFits, InventoryItemCodec.reproject(maxFits, RegistryAccess.EMPTY, minimum));
		var maxDisplay = new InventoryItemCodec.Display(maxId, "block.minecraft.stone", "x".repeat(2048), false);
		assertUnavailableDisplayPreservesIdentity(maxDisplay, minimum);
	}

	@Test
	void displayCapDoesNotChangeCanonicalIdentityOrBounds() {
		var configured = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES)
			.encode(namedWithCustomData());
		var standard = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(namedWithCustomData());

		assertEquals(standard.key(), configured.key(), "the projection cap never changes variant identity");
		assertEquals(InventoryItemCodec.MAX_CANONICAL_BYTES, 65536);
		assertEquals(InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES, 262144L);
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> InventoryItemCodec.canonicalTag(net.minecraft.nbt.StringTag.valueOf(
				"x".repeat(InventoryItemCodec.MAX_CANONICAL_BYTES + 1))));
	}

	@Test
	void plainDefaultsTravelAsSparseNativePatchesWithoutChangingCompleteIdentity() {
		for (Item item : List.of(Items.STONE, Items.DIAMOND_SWORD, Items.IRON_PICKAXE)) {
			ItemStack source = new ItemStack(item);
			InventoryDetachedItem frozen = freeze(source);

			assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
			assertEquals(BuiltInRegistries.ITEM.getKey(item).toString(), frozen.display().itemId());
			assertFalse(frozen.display().aggregateRequired());

			JsonObject display = object(frozen.display().displayJson());
			JsonObject components = components(display);
			assertFalse(hasPositiveComponent(components),
				() -> "an unmodified default must inherit locally instead of being repeated for " + item);
			assertTrue(components.has("!minecraft:lore"));
			assertFalse(components.has("minecraft:lore"), "display never exposes the filtered Lore value");

			// Read the vanilla item JSON independently, then show that the sparse patch is a
			// legal native value resolving to the same effective components under these defaults.
			JsonObject nativeItem = nativeJson(source).getAsJsonObject();
			JsonObject nativePatch = nativeItem.has("components")
				? nativeItem.getAsJsonObject("components") : new JsonObject();
			assertFalse(hasPositiveComponent(nativePatch), "the fixture itself carries no explicit patch");
			ItemStack decoded = ItemStack.SINGLE_ITEM_CODEC
				.parse(registries.createSerializationContext(JsonOps.INSTANCE), display)
				.result().orElseThrow();
			assertEquals(item, decoded.getItem());
			assertEquals(1, decoded.getCount());
			assertSameEffectiveComponentsExceptLore(source, decoded);

			// The complete server-effective persistent map still supplies exact identity.
			assertArrayEquals(InventoryItemCodec.canonicalTag(nativePersistentMap(source)),
				frozen.canonicalForCatalog());
		}
	}

	@Test
	void explicitPatchValuesRemainAndNormalizedDefaultsStayOmitted() {
		var key = ResourceKey.create(Registries.ENCHANTMENT,
			ResourceLocation.fromNamespaceAndPath("pingforit_test", "finite"));
		HolderLookup.Provider provider = new RegistrySetBuilder().add(Registries.ENCHANTMENT,
			context -> context.register(key, Enchantment.enchantment(Enchantment.definition(
				HolderSet.direct(), 1, 1, Enchantment.constantCost(1), Enchantment.constantCost(1), 1))
				.build(ResourceLocation.fromNamespaceAndPath("pingforit_test", "finite")))).build(registries);

		ItemStack source = new ItemStack(Items.DIAMOND_SWORD);
		source.set(DataComponents.DAMAGE, 5);
		source.set(DataComponents.CUSTOM_NAME, Component.literal("explicit display name"));
		var enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		enchantments.set(provider.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), 2);
		source.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());

		var frozen = freeze(source, provider);
		assertArrayEquals(InventoryItemCodec.canonicalTag(nativePersistentMap(source, provider)),
			frozen.canonicalForCatalog());
		JsonObject display = components(object(frozen.display().displayJson()));
		JsonObject nativePatch = nativeJson(source, provider).getAsJsonObject().getAsJsonObject("components");
		assertNotNull(nativePatch);
		for (var entry : nativePatch.entrySet()) {
			if (entry.getKey().startsWith("!")) continue;
			assertEquals(entry.getValue(), display.get(entry.getKey()), entry.getKey());
		}
		assertEquals(5, display.get("minecraft:damage").getAsInt());
		assertFalse(display.has("minecraft:max_damage"), "an unmodified sword default is still omitted");
		assertFalse(display.has("!minecraft:max_damage"));
		assertTrue(display.has("!minecraft:lore"));

		DataComponentPatch removals = DataComponentPatch.builder()
			.remove(DataComponents.MAX_DAMAGE).remove(DataComponents.LORE).build();
		JsonObject removed = components(object(freezePatch(Items.DIAMOND_SWORD, removals).display().displayJson()));
		assertEquals(new JsonObject(), removed.get("!minecraft:max_damage"));
		assertEquals(new JsonObject(), removed.get("!minecraft:lore"));

		int maxDamage = Items.DIAMOND_SWORD.components().getOrDefault(DataComponents.MAX_DAMAGE, 0);
		assertTrue(maxDamage > 0);
		ItemStack normalized = new ItemStack(Items.DIAMOND_SWORD);
		normalized.set(DataComponents.MAX_DAMAGE, maxDamage);
		assertEquals(0, ((PatchedDataComponentMap) normalized.getComponents()).asPatch().size(),
			"the native setter normalizes an equal default away");
		JsonObject normalizedDisplay = components(object(freeze(normalized).display().displayJson()));
		assertFalse(normalizedDisplay.has("minecraft:max_damage"));
		assertFalse(normalizedDisplay.has("!minecraft:max_damage"));

		DataComponentPatch explicitEqual = DataComponentPatch.builder()
			.set(DataComponents.MAX_DAMAGE, maxDamage).build();
		JsonObject explicitDisplay = components(
			object(freezePatch(Items.DIAMOND_SWORD, explicitEqual).display().displayJson()));
		assertEquals(maxDamage, explicitDisplay.get("minecraft:max_damage").getAsInt(),
			"a source-known explicit patch value is sent when the native patch still carries it");
	}

	@Test
	void displayPatchInheritsTheClientLocalPrototypeInsteadOfTheServerDefault() throws ReflectiveOperationException {
		Field field = Item.class.getDeclaredField("components");
		field.setAccessible(true);
		var original = Items.STONE.components();
		ClientItemDisplay.reset();
		try {
			var server = DataComponentMap.builder().addAll(original)
				.set(DataComponents.DAMAGE, 3).build();
			field.set(Items.STONE, server);

			String json = freeze(new ItemStack(Items.STONE)).display().displayJson();
			assertFalse(components(object(json)).has("minecraft:damage"),
				"the server default is inherited, not transmitted");
			ItemStack serverDecoded = ItemStack.SINGLE_ITEM_CODEC
				.parse(registries.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json))
				.result().orElseThrow();
			assertEquals(3, serverDecoded.get(DataComponents.DAMAGE),
				"with the server registry the sparse patch inherits the server default");

			var client = DataComponentMap.builder().addAll(original)
				.set(DataComponents.DAMAGE, 7).build();
			field.set(Items.STONE, client);
			ClientItemDisplay.reset();
			var resolved = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone",
				"block.minecraft.stone", json);
			assertEquals(7, resolved.stack().get(DataComponents.DAMAGE),
				"the same patch inherits the client's local prototype default");
		} finally {
			field.set(Items.STONE, original);
			ClientItemDisplay.reset();
		}
	}

	private static InventoryDetachedItem freeze(ItemStack stack) {
		return freeze(stack, registries);
	}

	private static InventoryDetachedItem freeze(ItemStack stack, HolderLookup.Provider provider) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), provider,
			InventoryItemCodec.MAX_DISPLAY_BYTES, InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES),
			new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}

	private static InventoryDetachedItem freezePatch(Item item, DataComponentPatch patch) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Patch(item, patch), registries,
			InventoryItemCodec.MAX_DISPLAY_BYTES, InventoryItemBudget.root(InventoryItemCodec.MAX_DISPLAY_BYTES),
			new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}

	private static JsonElement nativeJson(ItemStack stack) {
		return nativeJson(stack, registries);
	}

	private static JsonElement nativeJson(ItemStack stack, HolderLookup.Provider provider) {
		return ItemStack.CODEC.encodeStart(provider.createSerializationContext(JsonOps.INSTANCE), stack)
			.result().orElseThrow();
	}

	private static Tag nativePersistentMap(ItemStack stack) {
		return nativePersistentMap(stack, registries);
	}

	private static Tag nativePersistentMap(ItemStack stack, HolderLookup.Provider provider) {
		return DataComponentMap.makeCodec(DataComponentType.PERSISTENT_CODEC)
			.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE),
				stack.getComponents().filter(type -> !type.isTransient()))
			.result().orElseThrow();
	}

	private static JsonObject object(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static JsonObject components(JsonObject item) {
		JsonElement value = item.get("components");
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}

	private static boolean hasPositiveComponent(JsonObject components) {
		for (String key : components.keySet()) if (!key.startsWith("!")) return true;
		return false;
	}

	private static void assertSameEffectiveComponentsExceptLore(ItemStack expected, ItemStack actual) {
		assertEquals(expected.getItem(), actual.getItem());
		int expectedLore = expected.getComponents().has(DataComponents.LORE) ? 1 : 0;
		assertEquals(expected.getComponents().size() - expectedLore, actual.getComponents().size(),
			"display effective components match the source except for the mandatory Lore strip");
		for (DataComponentType<?> type : expected.getComponents().keySet()) {
			if (type == DataComponents.LORE) continue;
			assertTrue(actual.getComponents().has(type), () -> "missing effective component: " + type);
			assertEquals(expected.getComponents().get(type), actual.getComponents().get(type),
				() -> "effective component differs: " + type);
		}
		assertFalse(actual.getComponents().has(DataComponents.LORE),
			"the display never reconstructs the server's unmodified Lore default");
	}

	/** The same authoritative minimum the codec enforces: the display scale's smallest legal cap. */
	private static int minimumDisplayCap() {
		return (int) InventorySizeScale.DISPLAY.bytes(InventorySizeScale.DISPLAY.minimumMultiplier());
	}

	private static void assertUnavailableDisplayPreservesIdentity(InventoryItemCodec.Display retained, int cap) {
		var projected = InventoryItemCodec.reproject(retained, RegistryAccess.EMPTY, cap);
		assertNull(projected.displayJson(), "unsafe retained data never reconstructs a bare display");
		assertEquals(retained.itemId(), projected.itemId());
		assertEquals(retained.label(), projected.label());
		assertEquals(retained.aggregateRequired(), projected.aggregateRequired(),
			"display availability neither folds exact identity nor clears a known aggregate");

		// Opaque identity bytes: display replacement must not decode or recompute them.
		byte[] canonical = retained.aggregateRequired() ? null : new byte[] {1, 2, 3};
		var mode = retained.aggregateRequired() ? InventoryIdentityMode.ALL_VARIANTS : InventoryIdentityMode.EXACT;
		var detached = new InventoryDetachedItem(retained.itemId(), mode, retained, canonical,
			2L * retained.displayJson().length() + (canonical == null ? 0 : canonical.length));
		var replacement = detached.withDisplay(projected);
		assertEquals(mode, replacement.identityMode());
		assertSame(canonical, replacement.canonicalForCatalog(), "reprojection keeps the exact canonical payload");
		var original = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, cap).intern(detached);
		var reprojected = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, cap).intern(replacement);
		assertEquals(original.key(), reprojected.key(), "unavailable display keeps the same exact or aggregate key");
		assertEquals(retained.aggregateRequired(), reprojected.key().componentsKey().isEmpty());
		assertEquals(projected, reprojected.display());
	}

	/** Real fixture: a stack whose JSON display exceeds the minimum cap because of a large component. */
	private static String oversizedJson(Item item) {
		CompoundTag payload = new CompoundTag();
		payload.putString("data", "x".repeat(2048));
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));
		return new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, InventoryLimits.MAX_DISPLAY_BYTES)
			.encode(stack).display().displayJson();
	}

	private static int utf8Length(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}
}
