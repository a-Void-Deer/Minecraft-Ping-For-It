package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InventoryItemFrontGuardTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	private static InventoryDetachedItem freeze(ItemStack stack) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), RegistryAccess.EMPTY,
			4096, InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}
	private static byte[] oracle(ItemStack safe) {
		return InventoryItemCodec.canonicalTag(DataComponentMap.CODEC.encodeStart(NbtOps.INSTANCE, safe.getComponents()).result().orElseThrow());
	}

	@Test void safeEffectiveComponentsMatchIndependentUncachedVanillaMapBytes() {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.DAMAGE, 7);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("safe"));
		var detached = freeze(stack); assertEquals(InventoryIdentityMode.EXACT, detached.identityMode());
		assertArrayEquals(oracle(stack), detached.canonicalForCatalog());
	}

	@Test void actualOversizedArraysRefuseBeforeAnyCopyCodecOrResidentHash() {
		for (int kind = 0; kind < 3; kind++) {
			var custom = CustomData.of(new CompoundTag());
			if (kind == 0) custom.getUnsafe().putByteArray("large", new byte[100000]);
			if (kind == 1) custom.getUnsafe().putIntArray("large", new int[25000]);
			if (kind == 2) custom.getUnsafe().putLongArray("large", new long[12500]);
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_DATA, custom);
			var detached = freeze(stack);
			assertEquals(InventoryIdentityMode.ALL_VARIANTS, detached.identityMode());
			assertNull(detached.canonicalForCatalog()); assertTrue(detached.display().aggregateRequired());
		}
	}

	@Test void unknownCodecAndValueHooksAreNotInvokedBeforeAggregate() {
		var calls = new AtomicInteger();
		Codec<Object> trap = Codec.INT.xmap(number -> new Object(), object -> {
			calls.incrementAndGet(); throw new IllegalStateException("unknown codec");
		});
		var unknown = new Object() {
			@Override public int hashCode() { fail("resident hash"); return 0; }
			@Override public boolean equals(Object object) { fail("resident equals"); return false; }
			@Override public String toString() { fail("resident toString"); return ""; }
		};
		assertThrows(IllegalStateException.class, () -> trap.encodeStart(NbtOps.INSTANCE, unknown)); calls.set(0);
		var type = DataComponentType.builder().persistent(trap).build();
		// Raw resident input avoids ItemStack.set's own equals semantics during setup.
		var patch = DataComponentPatch.builder().set(type, unknown).build();
		var detached = InventoryItemCodec.freeze(new InventoryResidentItem.Patch(Items.STONE, patch), RegistryAccess.EMPTY,
			4096, InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
		assertEquals(InventoryIdentityMode.ALL_VARIANTS, detached.identityMode()); assertEquals(0, calls.get());
	}

	@Test void removalCanExcludeUnsafePrototypeAndEquivalentEffectiveMapsHaveSameBytes() {
		var unsafe = CustomData.of(new CompoundTag()); unsafe.getUnsafe().putByteArray("large", new byte[100000]);
		var prototype = DataComponentMap.builder().addAll(Items.STONE.components()).set(DataComponents.CUSTOM_DATA, unsafe).build();
		var raw = new InventoryResidentAccessFields() {
			@Override public DataComponentMap itemComponents(net.minecraft.world.item.Item item) { return prototype; }
		};
		var removal = DataComponentPatch.builder().remove(DataComponents.CUSTOM_DATA).build();
		var detached = InventoryItemCodec.freeze(new InventoryResidentItem.Patch(Items.STONE, removal), RegistryAccess.EMPTY,
			4096, InventoryItemBudget.root(4096), raw, new InventoryTestNativeAccess());
		assertEquals(InventoryIdentityMode.EXACT, detached.identityMode());
		assertArrayEquals(freeze(new ItemStack(Items.STONE)).canonicalForCatalog(), detached.canonicalForCatalog());
	}

	@Test void mutationAfterFreezeDoesNotReachOwnedIdentityDisplayOrCatalogEvidence() {
		var custom = CustomData.of(new CompoundTag()); custom.getUnsafe().putString("mutable", "before");
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_DATA, custom);
		var detached = freeze(stack); byte[] expected = detached.canonicalForCatalog().clone(); String display = detached.display().displayJson();
		custom.getUnsafe().putString("mutable", "after"); stack.set(DataComponents.DAMAGE, 10);
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY);
		var first = catalog.intern(detached); assertEquals(display, first.display().displayJson());
		assertArrayEquals(expected, detached.canonicalForCatalog());
		var budget = InventoryItemBudget.root(4096); budget.begin(4096); byte[] exported = detached.canonicalCopy(budget); budget.end();
		exported[0] ^= 1; assertArrayEquals(expected, detached.canonicalForCatalog());
		assertEquals(first.key(), catalog.intern(detached).key());
	}

	@Test void loreRedactionAndTighteningKeepDistinctExactIdentities() {
		var first = new ItemStack(Items.STONE); first.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("private first lore"))));
		var second = new ItemStack(Items.STONE); second.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("private second lore"))));
		var a = freeze(first); var b = freeze(second);
		assertEquals(InventoryIdentityMode.EXACT, a.identityMode()); assertEquals(InventoryIdentityMode.EXACT, b.identityMode());
		assertFalse(java.util.Arrays.equals(a.canonicalForCatalog(), b.canonicalForCatalog()));
		assertFalse(a.display().displayJson().contains("private first lore")); assertTrue(a.display().displayJson().contains("!minecraft:lore"));
		var catalog = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY); var key = catalog.intern(a).key();
		catalog.tightenDisplay(InventoryItemCodec.minimumDisplayBytes());
		assertFalse(catalog.displays().get(key).aggregateRequired()); assertEquals(key, catalog.intern(a).key());
	}

	@Test void literalLocksNeedNoLanguageOrHoverResolution() {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("part").append(" key"));
		assertTrue(InventoryItemCodec.lockMatchesResident(stack, "part key", InventoryItemBudget.root(4096), new InventoryResidentAccessFields()));
		assertFalse(InventoryItemCodec.lockMatchesResident(stack, "other", InventoryItemBudget.root(4096), new InventoryResidentAccessFields()));
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable("translation.must.not.resolve"));
		assertFalse(InventoryItemCodec.lockMatchesResident(stack, "translation.must.not.resolve", InventoryItemBudget.root(4096), new InventoryResidentAccessFields()));
	}
}
