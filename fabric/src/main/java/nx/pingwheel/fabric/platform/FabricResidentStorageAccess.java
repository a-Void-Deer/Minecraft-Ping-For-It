package nx.pingwheel.fabric.platform;

import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import nx.pingwheel.fabric.mixin.inventory.SingleStackStorageResidentInvoker;
import nx.pingwheel.fabric.mixin.inventory.InventorySlotResidentAccessor;
import nx.pingwheel.fabric.mixin.inventory.InventoryStorageResidentAccessor;
import nx.pingwheel.common.platform.IPlatformInventoryService.ResidentReads;

/** Narrow raw-field/invoker port; none of these methods certify its delegates. */
interface FabricResidentStorageAccess {
	FabricResidentStorageAccess MIXIN = new FabricResidentStorageAccess() {};
	default ItemStack stack(SingleStackStorage storage) {
		return ((SingleStackStorageResidentInvoker) storage).pingforit$residentStack();
	}
	default SingleStackStorage mappedSlot(StorageView<?> view) {
		// Mixin accessors require the exact field descriptor. Its package-private
		// InventorySlotWrapper type cannot be named here; a mixin in that library
		// package would reserve/block the library's own classes at runtime. Read
		// only this fixed inspected field instead, never a method or conversion.
		try {
			Class<?> owner = Class.forName("net.fabricmc.fabric.impl.transfer.item.SidedInventorySlotWrapper", false, getClass().getClassLoader());
			Class<?> slotType = Class.forName("net.fabricmc.fabric.impl.transfer.item.InventorySlotWrapper", false, getClass().getClassLoader());
			Object slot = ResidentReads.field(view, owner, "slotWrapper", slotType);
			return slot instanceof SingleStackStorage storage ? storage : null;
		} catch (ClassNotFoundException | LinkageError unavailable) { return null; }
	}
	default Container inventory(SingleStackStorage slot) {
		var storage = ((InventorySlotResidentAccessor) slot).pingforit$storage();
		return ((InventoryStorageResidentAccessor) storage).pingforit$inventory();
	}
	default int localSlot(SingleStackStorage slot) {
		return ((InventorySlotResidentAccessor) slot).pingforit$localSlot();
	}
}
