package nx.pingwheel.fabric.platform;

import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInventoryResidentTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	private static class StackStorage extends SingleStackStorage {
		final ItemStack stack = new ItemStack(Items.STONE, 7);
		final AtomicInteger rawReads = new AtomicInteger();
		@Override protected ItemStack getStack() { rawReads.incrementAndGet(); return stack; }
		@Override protected void setStack(ItemStack stack) { throw new AssertionError("mutation"); }
		FabricResidentStorageAccess raw() {
			return new FabricResidentStorageAccess() {
				@Override public ItemStack stack(SingleStackStorage storage) { assertSame(StackStorage.this, storage); return getStack(); }
			};
		}
	}

	@Test void rawSingleStackDoesNotConstructHashOrQueryTheVariantAndDoesNotCopy() {
		var storage = new StackStorage();
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage, () -> true, storage.raw());
		var result = access.readResident(0);
		assertSame(storage.stack, ((InventoryResidentItem.Stack) result.item()).resident());
		assertEquals(7, result.amount()); assertEquals(1, storage.rawReads.get());
		assertFalse(access.supportsDetachedSlotCapture(), "unknown getStack is external trust, not certified");
	}

	@Test void virtualizedStackResourceRemainsExternalTrustWithoutRawResourceBypass() {
		AtomicInteger resources = new AtomicInteger();
		var storage = new StackStorage() {
			@Override public ItemVariant getResource() { resources.incrementAndGet(); throw new IllegalStateException("copy/hash trap"); }
		};
		assertThrows(IllegalStateException.class, storage::getResource); // positive control
		resources.set(0);
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage, () -> true, storage.raw());
		assertThrows(IllegalStateException.class, () -> access.readResident(0));
		assertEquals(1, resources.get(), "custom getter retains its live semantics and failure");
		assertFalse(access.supportsDetachedSlotCapture());
	}
	@Test void virtualizedLongAmountDoesNotRouteBackThroughInheritedVariantConversion() {
		var storage = new StackStorage() { @Override public long getAmount() { return 9007199254740993L; } };
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage, () -> true, storage.raw());
		var entry = access.readResident(0);
		assertEquals(9007199254740993L, entry.amount());
		assertSame(storage.stack, ((InventoryResidentItem.Stack) entry.item()).resident());
		assertFalse(access.supportsDetachedSlotCapture());
	}

	private static ItemVariant variant() {
		return (ItemVariant) Proxy.newProxyInstance(PlatformInventoryResidentTest.class.getClassLoader(), new Class<?>[] {ItemVariant.class}, (proxy, method, args) -> switch (method.getName()) {
			case "getItem", "getObject" -> Items.STONE;
			case "getComponents" -> DataComponentPatch.EMPTY;
			case "isBlank" -> false;
			default -> throw new AssertionError("conversion/hash/format trap: " + method.getName());
		});
	}

	@Test void externalVariantPreservesLongAmountWithoutToStackOrHash() {
		var resource = variant();
		assertThrows(AssertionError.class, () -> resource.toStack(1)); // old bridge positive control
		var storage = new SingleVariantStorage<ItemVariant>() {
			@Override protected ItemVariant getBlankVariant() { return resource; }
			@Override protected long getCapacity(ItemVariant variant) { throw new AssertionError("capacity"); }
		};
		storage.variant = resource; storage.amount = 9007199254740993L;
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage);
		var result = access.readResident(0);
		assertEquals(9007199254740993L, result.amount());
		assertSame(Items.STONE, ((InventoryResidentItem.Patch) result.item()).item());
		assertSame(DataComponentPatch.EMPTY, ((InventoryResidentItem.Patch) result.item()).residentPatch());
		assertFalse(access.supportsDetachedSlotCapture(), "proxy variant is explicitly external trust");
	}
	@Test void inheritedSingleVariantWithNativeVariantOptsIntoDetachedReads() {
		var storage = new SingleVariantStorage<ItemVariant>() {
			// SingleVariantStorage calls this during superclass construction. The
			// normal factory needs ItemMixin's ItemVariantCache even for AIR; these
			// headless tests construct the exact same native item/patch value directly.
			@Override protected ItemVariant getBlankVariant() { return new net.fabricmc.fabric.impl.transfer.item.ItemVariantImpl(Items.AIR, DataComponentPatch.EMPTY); }
			@Override protected long getCapacity(ItemVariant resource) { throw new AssertionError("capacity"); }
		};
		assertTrue(storage.variant.isBlank());
		storage.variant = new net.fabricmc.fabric.impl.transfer.item.ItemVariantImpl(Items.STONE, DataComponentPatch.EMPTY);
		storage.amount = 9007199254740993L;
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage);
		assertTrue(access.supportsDetachedSlotCapture());
		var entry = access.readResident(0);
		assertEquals(9007199254740993L, entry.amount());
		assertSame(Items.STONE, ((InventoryResidentItem.Patch) entry.item()).item());
		assertSame(DataComponentPatch.EMPTY, ((InventoryResidentItem.Patch) entry.item()).residentPatch());
	}

	@Test void nativeInventoryWrappersKeepFrozenFaceLocalOrderAndSourceAuthority() {
		class Inventory extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
			Inventory() { super(4); }
			@Override public int[] getSlotsForFace(net.minecraft.core.Direction face) { assertEquals(net.minecraft.core.Direction.NORTH, face); return new int[] {3, 1}; }
			@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
			@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
		}
		var inventory = new Inventory(); inventory.setItem(3, new ItemStack(Items.DIAMOND, 7)); inventory.setItem(1, new ItemStack(Items.DIRT, 11));
		var storage = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(inventory, net.minecraft.core.Direction.NORTH);
		var raw = new FabricResidentStorageAccess() {
			@Override public net.minecraft.world.Container inventory(SingleStackStorage value) { return inventory; }
			@Override public int localSlot(SingleStackStorage value) {
				try { var field = value.getClass().getDeclaredField("slot"); field.setAccessible(true); return field.getInt(value); }
				catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			}
			@Override public ItemStack stack(SingleStackStorage value) { return inventory.getItem(localSlot(value)); }
		};
		AtomicBoolean allowed = new AtomicBoolean(true);
		var scope = new nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope() {
			@Override public net.minecraft.core.Direction face() { return net.minecraft.core.Direction.NORTH; }
			@Override public boolean valid() { return allowed.get(); }
			@Override public boolean allows(net.minecraft.world.Container value) { return value == inventory && allowed.get(); }
			@Override public boolean audited(net.minecraft.world.Container value) { return value == inventory; } // fixed fixture graph
			@Override public java.util.List<Integer> slots(net.minecraft.world.Container value) { return java.util.List.of(3, 1); }
		};
		var access = new PlatformInventoryServiceImpl.SlottedAccess(storage, () -> true, raw).scopedResidents(scope);
		assertFalse(access.supportsDetachedSlotCapture(), "mutable CombinedStorage.parts metadata is not certified by the leaf graph");
		assertSame(inventory.getItem(3), ((InventoryResidentItem.Stack) access.readResident(0).item()).resident());
		assertEquals(11, access.readResident(1).amount());
		allowed.set(false); assertThrows(IllegalStateException.class, () -> access.readResident(0));
	}

	private static SingleStackStorage mappedNativeSlot(StorageView<?> value) {
		try { var field = value.getClass().getDeclaredField("slotWrapper"); field.setAccessible(true); return (SingleStackStorage) field.get(value); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	@Test void layoutlessNativeInventoryWrappersCannotReadHiddenFaceSlots() {
		class Inventory extends net.minecraft.world.SimpleContainer implements net.minecraft.world.WorldlyContainer {
			int reads;
			Inventory() { super(3); }
			@Override public int[] getSlotsForFace(net.minecraft.core.Direction face) { assertEquals(net.minecraft.core.Direction.NORTH, face); return new int[] {2, 0}; }
			@Override public ItemStack getItem(int slot) { reads++; if (slot == 1) throw new AssertionError("hidden content/loot"); return super.getItem(slot); }
			@Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
			@Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, net.minecraft.core.Direction face) { throw new AssertionError("write query"); }
		}
		var inventory = new Inventory();
		inventory.setItem(2, new ItemStack(Items.DIAMOND, 7)); inventory.setItem(0, new ItemStack(Items.DIRT, 11));
		var raw = new FabricResidentStorageAccess() {
			@Override public SingleStackStorage mappedSlot(StorageView<?> value) { return mappedNativeSlot(value); }
			@Override public net.minecraft.world.Container inventory(SingleStackStorage value) { return inventory; }
			@Override public int localSlot(SingleStackStorage value) {
				try { var field = value.getClass().getDeclaredField("slot"); field.setAccessible(true); return field.getInt(value); }
				catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			}
			@Override public ItemStack stack(SingleStackStorage value) { return inventory.getItem(localSlot(value)); }
		};
		var scope = new nx.pingwheel.common.platform.IPlatformInventoryService.ResidentScope() {
			@Override public net.minecraft.core.Direction face() { return net.minecraft.core.Direction.NORTH; }
			@Override public boolean valid() { return true; }
			@Override public boolean allows(net.minecraft.world.Container value) { return value == inventory; }
			@Override public boolean audited(net.minecraft.world.Container value) { return false; }
			@Override public java.util.List<Integer> slots(net.minecraft.world.Container value) { return null; }
		};
		assertThrows(AssertionError.class, () -> inventory.getItem(1)); inventory.reads = 0; // trap positive control
		var unsided = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(inventory, null);
		var denied = new PlatformInventoryServiceImpl.SlottedAccess(unsided, () -> true, raw).scopedResidents(scope);
		assertFalse(denied.supportsDetachedSlotCapture());
		assertThrows(IllegalStateException.class, () -> denied.readResident(1));
		assertThrows(IllegalStateException.class, () -> denied.readResident(0), "unsided order [0,1,2] cannot stand in for [2,0]");
		assertEquals(0, inventory.reads);
		var sided = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(inventory, net.minecraft.core.Direction.NORTH);
		var allowed = new PlatformInventoryServiceImpl.SlottedAccess(sided, () -> true, raw).scopedResidents(scope);
		assertFalse(allowed.supportsDetachedSlotCapture(), "layout-less native wrappers retain LIVE access only");
		var first = allowed.readResident(0); var second = allowed.readResident(1);
		assertEquals(7L, first.amount()); assertEquals(11L, second.amount());
		assertSame(Items.DIAMOND, ((InventoryResidentItem.Stack) first.item()).resident().getItem());
		assertSame(Items.DIRT, ((InventoryResidentItem.Stack) second.item()).resident().getItem());
		assertEquals(2, inventory.reads);
		// Enumerated access must authorize the same real native leaf by face
		// membership, even though it has no positional cursor witness.
		var enumerated = new PlatformInventoryServiceImpl.EnumeratedAccess(unsided, () -> true, raw).scopedResidents(scope);
		inventory.reads = 0;
		AtomicInteger handedOff = new AtomicInteger();
		assertThrows(IllegalStateException.class, () -> enumerated.observeResident(3, entry -> { handedOff.incrementAndGet(); return true; }));
		assertEquals(1, handedOff.get()); assertEquals(1, inventory.reads, "slot 0 is face-visible; hidden slot 1 must stop before content");
		var selected = new PlatformInventoryServiceImpl.EnumeratedAccess(sided, () -> true, raw).scopedResidents(scope);
		java.util.List<Long> amounts = new java.util.ArrayList<>();
		assertEquals(2, selected.observeResident(2, entry -> { amounts.add(entry.amount()); return true; }).visited());
		assertEquals(java.util.List.of(7L, 11L), amounts);
	}

	@SuppressWarnings("unchecked")
	private static Storage<ItemVariant> emptyViews(AtomicBoolean member, AtomicInteger nextCalls) {
		return (Storage<ItemVariant>) Proxy.newProxyInstance(PlatformInventoryResidentTest.class.getClassLoader(), new Class<?>[] {Storage.class}, (proxy, method, args) -> {
			assertTrue(member.get());
			if (!method.getName().equals("iterator")) throw new AssertionError(method.getName());
			return new Iterator<StorageView<ItemVariant>>() {
				int cursor;
				@Override public boolean hasNext() { assertTrue(member.get()); return cursor < 3; }
				@Override public StorageView<ItemVariant> next() {
					assertTrue(member.get()); cursor++; nextCalls.incrementAndGet();
					return (StorageView<ItemVariant>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SingleSlotStorage.class}, (view, getter, parameters) -> {
						assertTrue(member.get());
						if (getter.getName().equals("isResourceBlank")) return true;
						throw new AssertionError("blank view content getter: " + getter.getName());
					});
				}
			};
		});
	}

	@Test void cursorlessEmptyViewsAreCountedConsumedImmediatelyAndRevocationStopsTheNextHandoff() {
		AtomicBoolean member = new AtomicBoolean(true); AtomicInteger next = new AtomicInteger();
		var access = new PlatformInventoryServiceImpl.EnumeratedAccess(emptyViews(member, next), member::get);
		assertEquals(0, access.observeResident(0, entry -> true).visited()); assertEquals(0, next.get());
		var stopped = access.observeResident(3, entry -> false);
		assertEquals(1, stopped.visited()); assertFalse(stopped.complete()); assertEquals(1, next.get());
		assertThrows(IllegalStateException.class, () -> access.observeResident(3, entry -> { member.set(false); return true; }));
		assertEquals(2, next.get());
	}
}
