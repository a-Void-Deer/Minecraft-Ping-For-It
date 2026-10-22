package nx.pingwheel.fabric.mixin.inventory;

import net.fabricmc.fabric.impl.transfer.item.InventoryStorageImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.fabricmc.fabric.impl.transfer.item.InventorySlotWrapper", remap = false)
public interface InventorySlotResidentAccessor {
	@Accessor(value = "storage", remap = false)
	InventoryStorageImpl pingforit$storage();
	@Accessor(value = "slot", remap = false)
	int pingforit$localSlot();
}
