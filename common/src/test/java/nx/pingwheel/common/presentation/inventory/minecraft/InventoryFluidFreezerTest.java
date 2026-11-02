package nx.pingwheel.common.presentation.inventory.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.InventoryFluidCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidDisplayCodec;
import nx.pingwheel.common.presentation.inventory.InventoryFluidValue;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native resident mutations, complete identity and refusal stay independent of amount/display. */
@NotNullByDefault
class InventoryFluidFreezerTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static InventoryCatalogAdmission admission() {
		return new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(1024), InventoryCatalogAdmission.workUpperBound(1024));
	}
	private static InventoryFluidValue nbt(@Nullable CompoundTag tag, long amount) {
		return java.util.Objects.requireNonNull(InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, tag), amount),
			RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission()));
	}
	@Test void nbtOrderNormalizesButNativeTypeListOrderAndAbsentTagRemainDistinct() {
		var first = new CompoundTag(); first.putInt("a", 1); first.putString("b", "water");
		var reordered = new CompoundTag(); reordered.putString("b", "water"); reordered.putInt("a", 1);
		assertEquals(nbt(first, 81).key(), nbt(reordered, Long.MAX_VALUE).key(), "amount is excluded from native identity");
		reordered.put("a", ByteTag.valueOf((byte) 1)); assertNotEquals(nbt(first, 81).key(), nbt(reordered, 81).key());
		assertNotEquals(nbt(null, 81).key(), nbt(new CompoundTag(), 81).key());
		var list = new ListTag(); list.add(IntTag.valueOf(1)); list.add(IntTag.valueOf(2)); first.put("list", list);
		var reverse = new ListTag(); reverse.add(IntTag.valueOf(2)); reverse.add(IntTag.valueOf(1)); reordered = first.copy(); reordered.put("list", reverse);
		assertNotEquals(nbt(first, 81).key(), nbt(reordered, 81).key());
	}
	@Test void borrowedNbtIsDetachedBeforeLaterProviderMutation() {
		var resident = new CompoundTag(); resident.putIntArray("payload", new int[] {1, 2, 3});
		var frozen = nbt(resident, 9007199254740993L); var original = frozen.key();
		resident.getIntArray("payload")[0] = 9; resident.putString("later", "mutation");
		assertEquals(original, frozen.key()); assertEquals(9007199254740993L, frozen.amount());
		assertNotEquals(original, nbt(resident, 9007199254740993L).key());
		assertEquals("minecraft:water", InventoryFluidDisplayCodec.decodeStrict(frozen.displayJson()).fluidId());
	}
	@Test void canonicalByteAndMalformedUtfRefusalsPreserveReliableIdAmountAndBoundedDisplay() {
		for (String value : new String[] {"x".repeat(InventoryFluidCodec.MAX_CANONICAL_BYTES + 1), "\ud800"}) {
			var tag = new CompoundTag(); tag.putString("native", value);
			var refused = nbt(tag, Long.MAX_VALUE);
			assertTrue(refused.aggregateRequired()); assertEquals("", refused.key().componentsKey());
			assertEquals(Long.MAX_VALUE, refused.amount()); assertEquals("minecraft:water", refused.resourceId());
			assertTrue(InventoryFluidDisplayCodec.decode("minecraft:water", refused.displayJson()).isPresent());
		}
	}
	@Test void persistentPatchRemovalIsExactAndNoDefaultItemGraphIsRead() {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var empty = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, DataComponentPatch.EMPTY), 81),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		var removed = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER,
			DataComponentPatch.builder().remove(DataComponents.CUSTOM_NAME).build()), 81), registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(empty); assertNotNull(removed); assertFalse(empty.aggregateRequired()); assertFalse(removed.aggregateRequired());
		assertNotEquals(empty.key(), removed.key()); assertEquals(empty.display(), removed.display());
	}
	@Test void zeroIsObservedEmptyAndUnregisteredOrNegativeInputCannotBecomeAggregate() {
		assertNull(InventoryFluidFreezer.freeze(IPlatformFluidService.Entry.empty(), RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission()));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), -1));
		assertThrows(IllegalArgumentException.class, () -> InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.EMPTY, null), 81),
			RegistryAccess.EMPTY, 1024, new InventoryFluidCodec.Catalog(), admission()));
	}
	@Test void deniedCatalogAdmissionDoesNotInstallIdentityOrDisplay() {
		var catalog = new InventoryFluidCodec.Catalog();
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> InventoryFluidFreezer.freeze(
			new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.WATER, null), 81), RegistryAccess.EMPTY, 1024, catalog,
			new InventoryCatalogAdmission(0, 0)));
		assertEquals(0, catalog.retainedBytes());
	}
	@Test void componentIdentityMatchesCompleteNativePersistentPatchAndDetachesBorrowedCustomData() throws Exception {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var tag = new CompoundTag(); tag.putString("name", "native-variant"); tag.putIntArray("cells", new int[] {2, 4, 6});
		var data = CustomData.of(tag);
		var patch = DataComponentPatch.builder().set(DataComponents.DAMAGE, 17).set(DataComponents.CUSTOM_DATA, data).build();
		var nativeTag = DataComponentPatch.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), patch).result().orElseThrow();
		var expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
			.digest(InventoryCanonicalWriter.write(nativeTag, null)));
		var frozen = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, patch), Long.MAX_VALUE),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(frozen); assertFalse(frozen.aggregateRequired()); assertEquals(expected, frozen.key().componentsKey());
		var reordered = DataComponentPatch.builder().set(DataComponents.CUSTOM_DATA, data).set(DataComponents.DAMAGE, 17).build();
		var equal = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, reordered), 81),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(equal); assertEquals(frozen.key(), equal.key());
		data.getUnsafe().getIntArray("cells")[0] = 99;
		var changed = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Components(Fluids.WATER, patch), 81),
			registries, 1024, new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(changed); assertNotEquals(frozen.key(), changed.key()); assertEquals(expected, frozen.key().componentsKey());
		assertEquals(Long.MAX_VALUE, frozen.amount()); assertEquals(frozen.display(), changed.display());
	}
	@Test void depthRefusalPreservesReliableAmountAndFlowingFluidIdIsNeverNormalized() {
		var root = new CompoundTag(); var next = root;
		for (int depth = 0; depth <= InventoryItemBudget.MAX_DEPTH; depth++) { var child = new CompoundTag(); next.put("nested", child); next = child; }
		var refused = nbt(root, 81); assertTrue(refused.aggregateRequired()); assertEquals(81, refused.amount());
		var flowing = InventoryFluidFreezer.freeze(new IPlatformFluidService.Entry(new IPlatformFluidService.Nbt(Fluids.FLOWING_WATER, null), 81),
			RegistryAccess.EMPTY, InventoryItemCodec.minimumDisplayBytes(), new InventoryFluidCodec.Catalog(), admission());
		assertNotNull(flowing); assertFalse(flowing.aggregateRequired()); assertEquals("minecraft:flowing_water", flowing.resourceId());
		assertNotEquals(nbt(null, 81).key(), flowing.key());
		assertTrue(flowing.displayJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= InventoryItemCodec.minimumDisplayBytes());
	}
}
