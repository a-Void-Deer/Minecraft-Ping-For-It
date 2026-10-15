package nx.pingwheel.fabric.mixin.inventory;

import net.fabricmc.fabric.impl.transfer.item.InventoryStorageImpl;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = InventoryStorageImpl.class, remap = false)
public interface InventoryStorageResidentAccessor {
	@Accessor(value = "inventory", remap = false)
	Container pingforit$inventory();
}
