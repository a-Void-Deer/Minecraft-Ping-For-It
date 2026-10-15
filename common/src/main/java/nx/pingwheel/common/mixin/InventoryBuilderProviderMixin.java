package nx.pingwheel.common.mixin;

import java.util.Map;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.core.RegistrySetBuilder$3")
public abstract class InventoryBuilderProviderMixin implements InventoryResidentAccess.BuilderProvider {
	@Shadow(remap = false, aliases = {"val$lookups", "val$entries", "f_316609_", "field_49167"}) private Map val$map;
	@Override public Map<?, ?> pingforit$entries() { return val$map; }
}
