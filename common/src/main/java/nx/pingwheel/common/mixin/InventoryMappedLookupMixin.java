package nx.pingwheel.common.mixin;

import net.minecraft.core.MappedRegistry;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.core.MappedRegistry$1")
public abstract class InventoryMappedLookupMixin implements InventoryResidentAccess.MappedLookup {
	@Shadow(remap = false, aliases = {"f_254673_", "field_36468"}) private MappedRegistry this$0;
	@Override public MappedRegistry<?> pingforit$registry() { return this$0; }
}
