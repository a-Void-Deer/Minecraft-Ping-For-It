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
import static org.junit.jupiter.api.Assertions.assertNull;
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
