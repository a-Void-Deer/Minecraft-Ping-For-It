package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Unit;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native patch encoding is the oracle; no effective-map or normalization round trip. */
@NotNullByDefault
class InventoryPatchIdentityTest {

	private static @Nullable RegistryAccess registry;
	private static final InventoryResidentAccess RAW = new PrototypeTrapAccess();

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test void emptyPatchIsExactAndDigestIsNotTheAggregateSentinel() {
		var stone = freeze(Items.STONE, DataComponentPatch.EMPTY, 1024);
		var sword = freeze(Items.DIAMOND_SWORD, DataComponentPatch.EMPTY, 1024);
		assertArrayEquals(InventoryItemCodec.canonicalTag(new CompoundTag()), stone.canonicalForCatalog());
		assertArrayEquals(stone.canonicalForCatalog(), sword.canonicalForCatalog(), "item ID is stored separately from patch bytes");
		var catalog = new InventoryItemCodec.Catalog(registry(), 1024);
		var stoneKey = catalog.intern(stone).key(); var swordKey = catalog.intern(sword).key();
		assertFalse(stoneKey.componentsKey().isEmpty(), "an empty exact patch still has its digest");
		assertEquals(stoneKey.componentsKey(), swordKey.componentsKey());
		assertNotEquals(stoneKey, swordKey, "registry item ID remains part of variant identity");
		assertFalse(stone.display().aggregateRequired());
	}

	@Test void rawEqualAndRedundantRemovalRemainDistinctButNativeErasedChangesStayAbsent() {
		int damage = Items.DIAMOND_SWORD.components().getOrDefault(DataComponents.MAX_DAMAGE, 0);
		var equal = DataComponentPatch.builder().set(DataComponents.MAX_DAMAGE, damage).build();
		var removal = DataComponentPatch.builder().remove(DataComponents.CUSTOM_NAME).build();
		var empty = freeze(Items.DIAMOND_SWORD, DataComponentPatch.EMPTY, 1024);
		var explicit = freeze(Items.DIAMOND_SWORD, equal, 1024);
		var removed = freeze(Items.DIAMOND_SWORD, removal, 1024);
		assertPatchOracle(explicit, equal); assertPatchOracle(removed, removal);
		assertFalse(Arrays.equals(empty.canonicalForCatalog(), explicit.canonicalForCatalog()));
		assertFalse(Arrays.equals(empty.canonicalForCatalog(), removed.canonicalForCatalog()));
		ItemStack normalized = new ItemStack(Items.DIAMOND_SWORD);
		normalized.set(DataComponents.MAX_DAMAGE, damage); normalized.remove(DataComponents.CUSTOM_NAME);
		assertTrue(new InventoryResidentAccessFields().patch((PatchedDataComponentMap) normalized.getComponents()).isEmpty());
		assertArrayEquals(empty.canonicalForCatalog(), freeze(normalized).canonicalForCatalog());
		ItemStack raw = new ItemStack(Items.DIAMOND_SWORD);
		((PatchedDataComponentMap) raw.getComponents()).restorePatch(equal);
		assertArrayEquals(explicit.canonicalForCatalog(), freeze(raw).canonicalForCatalog());
	}

	@Test void rootCountIsExcludedWhileExplicitValueRemainsInIdentity() {
		ItemStack one = new ItemStack(Items.STONE, 1); one.set(DataComponents.DAMAGE, 5);
		ItemStack many = new ItemStack(Items.STONE, 64); many.set(DataComponents.DAMAGE, 5);
		assertArrayEquals(freeze(one).canonicalForCatalog(), freeze(many).canonicalForCatalog());
		many.set(DataComponents.DAMAGE, 6);
		assertFalse(Arrays.equals(freeze(one).canonicalForCatalog(), freeze(many).canonicalForCatalog()));
	}

	@Test void everyNestedFamilyUsesActualChildPatchWithNativeCountSlotAndOrder() {
		ItemStack first = new ItemStack(Items.DIAMOND, 3);
		((PatchedDataComponentMap) first.getComponents()).restorePatch(DataComponentPatch.builder()
			.set(DataComponents.MAX_STACK_SIZE, 64).remove(DataComponents.CUSTOM_NAME).build());
		ItemStack second = new ItemStack(Items.STONE, 7);
		second.set(DataComponents.DAMAGE, 2);
		assertNested(Items.CHEST, DataComponents.CONTAINER,
			ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, first, ItemStack.EMPTY, second)));
		assertNested(Items.BUNDLE, DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(first, second)));
		assertNested(Items.CROSSBOW, DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(List.of(first, second)));
		assertNested(Items.APPLE, DataComponents.FOOD,
			new FoodProperties(4, 0.3F, false, 1.6F, Optional.of(first), List.of()));
	}

	private static <T> void assertNested(Item item, DataComponentType<T> type, T value) {
		DataComponentPatch patch = DataComponentPatch.builder().set(type, value).build();
		assertPatchOracle(freeze(item, patch, 4096), patch);
	}

	@Test void transientOnlyRootAdditionsAndRemovalsKeepEmptyPersistentPatchIdentity() {
		var empty = freeze(Items.STONE, DataComponentPatch.EMPTY, 4096);
		for (DataComponentPatch patch : transientPatches()) {
			var frozen = freeze(Items.STONE, patch, 4096); assertPatchOracle(frozen, patch);
			assertArrayEquals(empty.canonicalForCatalog(), frozen.canonicalForCatalog());
			assertEquals(empty.display(), frozen.display());
			var stack = new ItemStack(Items.STONE, 3);
			((PatchedDataComponentMap) stack.getComponents()).restorePatch(patch);
			assertArrayEquals(empty.canonicalForCatalog(), freeze(stack).canonicalForCatalog());
		}
	}

	@Test void transientOnlyContainerChildKeepsNativeEmptyComponentsAtItsSlot() {
		assertTransientOnlyNestedEnvelope(Items.CHEST, DataComponents.CONTAINER,
			child -> ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, child, ItemStack.EMPTY, new ItemStack(Items.DIAMOND, 7))),
			encoded -> {
				var slots = encoded.getList("minecraft:container", Tag.TAG_COMPOUND);
				assertEquals(2, slots.size());
				assertEquals(1, slots.getCompound(0).getInt("slot"));
				assertEquals(3, slots.getCompound(1).getInt("slot"));
				assertEquals("minecraft:diamond", slots.getCompound(1).getCompound("item").getString("id"));
				assertEquals(7, slots.getCompound(1).getCompound("item").getInt("count"));
				return slots.getCompound(0).getCompound("item");
			}, ItemStack.CODEC, true);
	}

	@Test void transientOnlyBundleChildKeepsNativeEmptyComponentsAndListOrder() {
		assertTransientOnlyNestedEnvelope(Items.BUNDLE, DataComponents.BUNDLE_CONTENTS,
			child -> new BundleContents(List.of(child, new ItemStack(Items.DIAMOND, 7))),
			encoded -> {
				var items = encoded.getList("minecraft:bundle_contents", Tag.TAG_COMPOUND);
				assertEquals(2, items.size());
				assertEquals("minecraft:diamond", items.getCompound(1).getString("id"));
				assertEquals(7, items.getCompound(1).getInt("count"));
				return items.getCompound(0);
			}, ItemStack.CODEC, true);
	}

	@Test void transientOnlyProjectileChildKeepsNativeEmptyComponentsAndListOrder() {
		assertTransientOnlyNestedEnvelope(Items.CROSSBOW, DataComponents.CHARGED_PROJECTILES,
			child -> ChargedProjectiles.of(List.of(child, new ItemStack(Items.DIAMOND, 7))),
			encoded -> {
				var items = encoded.getList("minecraft:charged_projectiles", Tag.TAG_COMPOUND);
				assertEquals(2, items.size());
				assertEquals("minecraft:diamond", items.getCompound(1).getString("id"));
				assertEquals(7, items.getCompound(1).getInt("count"));
				return items.getCompound(0);
			}, ItemStack.CODEC, true);
	}

	@Test void transientOnlyFoodConversionKeepsNativeEmptyComponentsWithoutCount() {
		assertTransientOnlyNestedEnvelope(Items.APPLE, DataComponents.FOOD,
			child -> new FoodProperties(4, 0.3F, false, 1.6F, Optional.of(child), List.of()),
			encoded -> encoded.getCompound("minecraft:food").getCompound("using_converts_to"),
			ItemStack.SINGLE_ITEM_CODEC, false);
	}

	private static @Unmodifiable List<DataComponentPatch> transientPatches() {
		assertTrue(DataComponents.CREATIVE_SLOT_LOCK.isTransient());
		return List.of(DataComponentPatch.EMPTY,
			DataComponentPatch.builder().set(DataComponents.CREATIVE_SLOT_LOCK, Unit.INSTANCE).build(),
			DataComponentPatch.builder().remove(DataComponents.CREATIVE_SLOT_LOCK).build());
	}

	private static <T> void assertTransientOnlyNestedEnvelope(Item item, DataComponentType<T> type,
		Function<ItemStack, T> wrap, Function<CompoundTag, CompoundTag> savedChild,
		Codec<ItemStack> childCodec, boolean countIncluded) {
		@Nullable InventoryDetachedItem empty = null;
		for (DataComponentPatch childPatch : transientPatches()) {
			var child = new ItemStack(Items.STONE, 3);
			((PatchedDataComponentMap) child.getComponents()).restorePatch(childPatch);
			DataComponentPatch patch = DataComponentPatch.builder().set(type, wrap.apply(child)).build();
			var ops = registry().createSerializationContext(NbtOps.INSTANCE);
			var expected = (CompoundTag) DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElseThrow();
			var nativeChild = (CompoundTag) childCodec.encodeStart(ops, child).result().orElseThrow();
			assertEquals(nativeChild, savedChild.apply(expected), "the outer native codec preserves the child envelope");
			assertEquals("minecraft:stone", nativeChild.getString("id"));
			assertEquals(countIncluded, nativeChild.contains("count"));
			if (countIncluded) assertEquals(3, nativeChild.getInt("count"));
			assertEquals(!childPatch.isEmpty(), nativeChild.contains("components"));
			assertTrue(nativeChild.getCompound("components").isEmpty(), "transient values and removals are never persistent entries");
			var frozen = freeze(item, patch, 4096);
			assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
			assertArrayEquals(InventoryItemCodec.canonicalTag(expected), frozen.canonicalForCatalog());
			assertNotNull(frozen.display().displayJson());
			assertFalse(InventoryDisplayWriter.isDefaultIcon(frozen.itemId(), frozen.display().displayJson()));
			if (empty == null) empty = frozen;
			else {
				assertFalse(Arrays.equals(empty.canonicalForCatalog(), frozen.canonicalForCatalog()),
					"nested components:{} is distinct from a truly absent components field");
				assertEquals(empty.display(), frozen.display(), "ordinary display still adds fixed suppression to either child");
			}
		}
	}

	@Test void defaultGettersAndUnmodifiedValuesAreNotAuthenticationInputs() {
		// The raw port traps every prototype/default read in both Stack and Patch
		// inputs; ordinary native ItemStack construction is deliberately outside it.
		assertEquals(InventoryIdentityMode.EXACT, freeze(new ItemStack(Items.DIAMOND_SWORD)).identityMode());
		assertEquals(InventoryIdentityMode.EXACT, freeze(Items.APPLE, DataComponentPatch.EMPTY, 1024).identityMode());
	}

	@Test void sizeTerminalRetainsExactIdentityThroughCatalogTighteningAndLaterRaises() {
		// Registered removals have no value codec to invoke. Their complete legal
		// last-pass suppression envelope is independently measured above the cap.
		DataComponentPatch.Builder builder = DataComponentPatch.builder();
		for (DataComponentType<?> type : BuiltInRegistries.DATA_COMPONENT_TYPE) if (!type.isTransient()) builder.remove(type);
		DataComponentPatch patch = builder.build();
		var high = freeze(Items.STONE, patch, 4096); var low = freeze(Items.STONE, patch, 1024);
		assertPatchOracle(high, patch); assertPatchOracle(low, patch);
		assertTrue(high.display().displayJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024);
		assertArrayEquals(high.canonicalForCatalog(), low.canonicalForCatalog());
		assertFalse(InventoryDisplayWriter.isDefaultIcon("minecraft:stone", high.display().displayJson()));
		assertTrue(InventoryDisplayWriter.isDefaultIcon("minecraft:stone", low.display().displayJson()));
		var catalog = new InventoryItemCodec.Catalog(registry(), 4096);
		var key = catalog.intern(high).key(); catalog.tightenDisplay(1024);
		@Nullable var terminal = catalog.displays().get(key);
		assertNotNull(terminal); assertTrue(InventoryDisplayWriter.isDefaultIcon(key.itemId(), terminal.displayJson()));
		catalog.tightenDisplay(4096);
		assertEquals(key, catalog.intern(high).key()); assertEquals(terminal, catalog.displays().get(key));
		assertSame(terminal, InventoryItemCodec.reproject(terminal, registry(), 4096));
	}

	private static void assertPatchOracle(InventoryDetachedItem actual, DataComponentPatch patch) {
		Tag expected = DataComponentPatch.CODEC.encodeStart(registry().createSerializationContext(NbtOps.INSTANCE), patch).result().orElseThrow();
		assertEquals(InventoryIdentityMode.EXACT, actual.identityMode());
		assertArrayEquals(InventoryItemCodec.canonicalTag(expected), actual.canonicalForCatalog());
	}
	private static RegistryAccess registry() { return java.util.Objects.requireNonNull(registry); }
	private static InventoryDetachedItem freeze(Item item, DataComponentPatch patch, int cap) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Patch(item, patch), registry(), cap,
			InventoryItemBudget.root(cap), RAW, new InventoryTestNativeAccess());
	}
	private static InventoryDetachedItem freeze(ItemStack stack) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registry(), 4096,
			InventoryItemBudget.root(4096), RAW, new InventoryTestNativeAccess());
	}
	@NotNullByDefault
	private static final class PrototypeTrapAccess extends InventoryResidentAccessFields {
		@Override public DataComponentMap itemComponents(Item item) { throw new AssertionError("item defaults read"); }
		@Override public DataComponentMap prototype(PatchedDataComponentMap map) { throw new AssertionError("prototype read"); }
		@Override public java.util.Map<DataComponentType<?>, Object> simple(DataComponentMap map) { throw new AssertionError("default values read"); }
	}
}
