package nx.pingwheel.common.presentation.inventory.minecraft;

import java.nio.charset.StandardCharsets;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.config.InventorySizeScale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryItemDisplayProjectionTest {

	private static final String LONG_NAME = "n".repeat(20000);

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
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
	void completeStrippingAtAConfiguredCapKeepsTheSizeEvidence() {
		CompoundTag payload = new CompoundTag();
		payload.putString("data", "x".repeat(2048));
		ItemStack customData = new ItemStack(Items.STONE);
		customData.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));

		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, minimumDisplayCap());
		var encoded = catalog.encode(customData);

		assertTrue(encoded.display().componentsStripped(),
			"every component was dropped because the projection exceeded the configured cap");
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
	void reprojectKeepsFittingDisplaysAndFallsBackHonestly() {
		int minimum = minimumDisplayCap();
		var fits = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			"{\"id\":\"minecraft:stone\"}", false);
		assertSame(fits, InventoryItemCodec.reproject(fits, RegistryAccess.EMPTY, minimum));

		var invalid = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			"x".repeat(2048), false);
		var fallback = InventoryItemCodec.reproject(invalid, RegistryAccess.EMPTY, minimum);
		assertEquals("minecraft:stone", fallback.itemId());
		assertEquals("{\"id\":\"minecraft:stone\"}", fallback.displayJson());
		assertFalse(fallback.componentsStripped(), "a parse failure never invents component-too-long");
		assertTrue(utf8Length(fallback.displayJson()) <= minimum);

		var known = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			"x".repeat(2048), true);
		assertTrue(InventoryItemCodec.reproject(known, RegistryAccess.EMPTY, minimum).componentsStripped(),
			"known size evidence survives the fallback");

		// A real oversized component fixture whose decoded identity disagrees with the retained item id.
		var mismatched = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone",
			oversizedJson(Items.DIRT), false);
		var mismatchFallback = InventoryItemCodec.reproject(mismatched, RegistryAccess.EMPTY, minimum);
		assertEquals("{\"id\":\"minecraft:stone\"}", mismatchFallback.displayJson(),
			"a decoded identity that disagrees with the retained item id is not adopted");
		assertTrue(utf8Length(mismatchFallback.displayJson()) <= minimum);

		assertThrows(IllegalArgumentException.class,
			() -> InventoryItemCodec.reproject(fits, RegistryAccess.EMPTY, minimum - 1),
			"a cap below the legal effective minimum is rejected instead of producing an oversized bare form");
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
		assertTrue(encoded.display().componentsStripped());
		assertEquals("{\"id\":\"minecraft:stone\"}", encoded.display().displayJson());
		assertTrue(utf8Length(encoded.display().displayJson()) <= minimum,
			"the bare fallback never exceeds the legal minimum cap");

		// A real display-only omission also stays inside the minimum cap without claiming the fallback.
		var partialCatalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, minimum);
		var partial = partialCatalog.encode(namedWithCustomData());
		assertFalse(partial.display().componentsStripped());
		assertTrue(utf8Length(partial.display().displayJson()) <= minimum);
	}

	@Test
	void displayItemIdBoundKeepsBareFallbacksWithinTheAdmittedCap() {
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

		// A blank item id never becomes a bogus bare fallback.
		assertThrows(IllegalArgumentException.class, () -> new InventoryItemCodec.Display(
			" ", "block.minecraft.stone", "{\"id\":\"minecraft:stone\"}", false));

		// A valid maximum id still falls back honestly and its envelope fits the minimum cap.
		var maxId = "minecraft:" + "a".repeat(246);
		assertEquals(256, utf8Length(maxId));
		var maxDisplay = new InventoryItemCodec.Display(maxId, "block.minecraft.stone", "x".repeat(2048), false);
		var maxFallback = InventoryItemCodec.reproject(maxDisplay, RegistryAccess.EMPTY, minimum);
		assertEquals("{\"id\":\"" + maxId + "\"}", maxFallback.displayJson());
		assertTrue(utf8Length(maxFallback.displayJson()) <= minimum,
			"the valid maximum id envelope fits the minimum cap");
		assertFalse(maxFallback.componentsStripped(), "a parse failure never invents component-too-long");
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

	/** The same authoritative minimum the codec enforces: the display scale's smallest legal cap. */
	private static int minimumDisplayCap() {
		return (int) InventorySizeScale.DISPLAY.bytes(InventorySizeScale.DISPLAY.minimumMultiplier());
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
