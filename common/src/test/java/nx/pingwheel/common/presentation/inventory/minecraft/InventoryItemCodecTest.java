package nx.pingwheel.common.presentation.inventory.minecraft;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.presentation.inventory.InventoryScanner;

import static org.junit.jupiter.api.Assertions.*;

class InventoryItemCodecTest {

	@BeforeAll
	static void bootStrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void canonicalTagSortsCompoundKeysAndPreservesTypesAndListOrder() {
		CompoundTag first = new CompoundTag();
		first.putString("b", "x");
		first.putInt("a", 1);
		first.put("list", listOf(IntTag.valueOf(1), IntTag.valueOf(2)));

		CompoundTag second = new CompoundTag();
		second.put("list", listOf(IntTag.valueOf(1), IntTag.valueOf(2)));
		second.putInt("a", 1);
		second.putString("b", "x");

		assertArrayEquals(InventoryItemCodec.canonicalTag(first), InventoryItemCodec.canonicalTag(second));
		assertArrayEquals(InventoryItemCodec.canonicalTag(first), InventoryItemCodec.canonicalTag(first.copy()));

		CompoundTag ascending = new CompoundTag();
		ascending.put("list", listOf(IntTag.valueOf(1), IntTag.valueOf(2)));
		CompoundTag descending = new CompoundTag();
		descending.put("list", listOf(IntTag.valueOf(2), IntTag.valueOf(1)));
		assertFalse(Arrays.equals(InventoryItemCodec.canonicalTag(ascending),
			InventoryItemCodec.canonicalTag(descending)), "list order is identity");

		assertFalse(Arrays.equals(InventoryItemCodec.canonicalTag(IntTag.valueOf(1)),
			InventoryItemCodec.canonicalTag(ByteTag.valueOf((byte) 1))), "tag types are identity");
	}

	@Test
	void canonicalTagIsBounded() {
		StringTag oversized = StringTag.valueOf("x".repeat(InventoryItemCodec.MAX_CANONICAL_BYTES + 1));
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> InventoryItemCodec.canonicalTag(oversized));
	}

	@Test
	void digestIsDeterministicAndCollidingDifferentBytesAreRejected() {
		byte[] canonical = {1, 2, 3};
		assertEquals(InventoryItemCodec.digest(canonical), InventoryItemCodec.digest(canonical.clone()));
		assertNotEquals(InventoryItemCodec.digest(canonical), InventoryItemCodec.digest(new byte[] {3, 2, 1}));

		Map<String, byte[]> known = new HashMap<>();
		Set<String> conflicts = new HashSet<>();
		InventoryItemCodec.registerCanonical(known, conflicts, "digest", new byte[] {1});
		InventoryItemCodec.registerCanonical(known, conflicts, "digest", new byte[] {1});
		assertFalse(conflicts.contains("digest"));

		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> InventoryItemCodec.registerCanonical(known, conflicts, "digest", new byte[] {2}));
		assertTrue(conflicts.contains("digest"));
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> InventoryItemCodec.registerCanonical(known, conflicts, "digest", new byte[] {1}));
	}

	@Test
	void catalogKeepsVariantIdentityAndDetachedDisplay() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		ItemStack named = new ItemStack(Items.STONE);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
		ItemStack counted = new ItemStack(Items.STONE);
		counted.setCount(3);

		InventoryItemCodec.Encoded plain = catalog.encode(new ItemStack(Items.STONE));
		InventoryItemCodec.Encoded custom = catalog.encode(named);
		InventoryItemCodec.Encoded again = catalog.encode(counted);

		assertNotEquals(plain.key(), custom.key());
		assertEquals(plain.key(), again.key(), "stack count is not variant identity");
		assertEquals(2, catalog.displays().size());
		assertEquals("minecraft:stone", plain.display().itemId());
		assertEquals("block.minecraft.stone", plain.display().label());
		assertFalse(plain.display().componentsStripped());
		assertTrue(utf8Length(plain.display().displayJson()) <= InventoryItemCodec.MAX_DISPLAY_BYTES);
		assertThrows(UnsupportedOperationException.class, () -> catalog.displays().clear());
	}

	@Test
	void partialDisplayOmissionKeepsIdentityWithoutClaimingComponentFallback() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		InventoryScanner.Key plainKey = catalog.encode(new ItemStack(Items.STONE)).key();

		ItemStack named = new ItemStack(Items.STONE);
		named.set(DataComponents.CUSTOM_NAME,
			Component.literal("n".repeat(InventoryItemCodec.MAX_DISPLAY_BYTES + 512)));
		named.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));

		InventoryItemCodec.Encoded encoded = catalog.encode(named);
		assertFalse(encoded.display().componentsStripped(),
			"partial display omission is not the component-too-long fallback");
		assertNotEquals(plainKey, encoded.key(), "display projection must not change identity");
		assertTrue(utf8Length(encoded.display().displayJson()) <= InventoryItemCodec.MAX_DISPLAY_BYTES);
		assertFalse(encoded.display().displayJson().contains("nnnnnnnnnnnnnnnn"));
	}

	@Test
	void completeDisplayStrippingNeverClaimsIdentityAggregation() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		CompoundTag payload = new CompoundTag();
		payload.putString("data", "x".repeat(InventoryItemCodec.MAX_DISPLAY_BYTES + 512));
		ItemStack customData = new ItemStack(Items.STONE);
		customData.set(DataComponents.CUSTOM_DATA, CustomData.of(payload));

		InventoryItemCodec.Encoded encoded = catalog.encode(customData);
		assertFalse(encoded.display().aggregateRequired(),
			"complete display omission cannot change safely admitted identity");
		assertFalse(encoded.key().componentsKey().isEmpty());
		assertTrue(utf8Length(encoded.display().displayJson()) <= InventoryItemCodec.MAX_DISPLAY_BYTES);
		assertTrue(encoded.display().displayJson().contains("minecraft:stone"));
	}

	@Test
	void naturallyComponentlessStackIsNeverMarkedAsComponentFallback() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		ItemStack counted = new ItemStack(Items.STONE);
		counted.setCount(7);

		assertFalse(catalog.encode(new ItemStack(Items.STONE)).display().componentsStripped());
		assertFalse(catalog.encode(counted).display().componentsStripped());
	}

	@Test
	void unsafeIdentityUsesNoDigestAndMandatoryDefaultSuppression() {
		var custom = CustomData.of(new CompoundTag());
		custom.getUnsafe().putByteArray("resident", new byte[100000]);
		var stack = new ItemStack(Items.STONE);
		stack.set(DataComponents.CUSTOM_DATA, custom);
		var encoded = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack);
		assertTrue(encoded.display().aggregateRequired());
		assertEquals("", encoded.key().componentsKey(), "unsafe identity has no fake exact digest");
		assertTrue(encoded.display().displayJson().contains("!minecraft:lore"));
	}

	@Test
	void catalogueEntryBoundRejectsNewVariantsBeforeRetainingThem() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY, 1,
			InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES);
		catalog.encode(new ItemStack(Items.STONE));
		long retained = catalog.retainedBytes();

		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.encode(new ItemStack(Items.DIRT)));
		assertEquals(1, catalog.displays().size());
		assertEquals(retained, catalog.retainedBytes(), "a rejected variant retains nothing");
		assertDoesNotThrow(() -> catalog.encode(new ItemStack(Items.STONE)));
	}

	@Test
	void catalogueByteBoundIsCheckedBeforeAnyPayloadIsRetained() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY,
			InventoryItemCodec.DEFAULT_MAX_ENTRIES, 1L);

		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.encode(new ItemStack(Items.STONE)));
		assertTrue(catalog.displays().isEmpty());
		assertEquals(0L, catalog.retainedBytes());
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.encode(new ItemStack(Items.DIRT)));
	}

	@Test
	void catalogueChargesCanonicalBytesOncePerDigestAndDisplayBytesOncePerKey() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		InventoryItemCodec.Encoded stone = catalog.encode(new ItemStack(Items.STONE));
		long afterStone = catalog.retainedBytes();
		assertTrue(afterStone > displayCharge(stone.display()), "canonical bytes are retained once");

		catalog.encode(new ItemStack(Items.STONE));
		assertEquals(afterStone, catalog.retainedBytes(), "the same key is charged once");
		ItemStack counted = new ItemStack(Items.STONE);
		counted.setCount(5);
		catalog.encode(counted);
		assertEquals(afterStone, catalog.retainedBytes(), "stack count is not catalogue identity");

		InventoryItemCodec.Encoded dirt = catalog.encode(new ItemStack(Items.DIRT));
		assertEquals(afterStone + displayCharge(dirt.display()), catalog.retainedBytes(),
			"a shared component digest retains no second canonical payload");
		assertEquals(2, catalog.displays().size());
	}

	@Test
	void emptyStackIsRejectedRatherThanTreatedAsZero() {
		InventoryItemCodec.Catalog catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		assertThrows(IllegalArgumentException.class, () -> catalog.encode(ItemStack.EMPTY));
		assertTrue(catalog.displays().isEmpty());
	}

	private static ListTag listOf(Tag... tags) {
		ListTag list = new ListTag();
		for (Tag tag : tags) {
			list.add(tag);
		}
		return list;
	}

	private static int utf8Length(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static long displayCharge(InventoryItemCodec.Display display) {
		return (long) utf8Length(display.itemId()) + utf8Length(display.label())
			+ utf8Length(display.displayJson());
	}
}
