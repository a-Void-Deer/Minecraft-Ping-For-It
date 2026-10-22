package nx.pingwheel.common.mixin;

import java.util.Map;
import com.mojang.serialization.Lifecycle;
import net.minecraft.resources.ResourceKey;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.core.RegistrySetBuilder$2")
public abstract class InventoryBuilderLookupMixin implements InventoryResidentAccess.BuilderLookup {
	@Shadow(remap = false, aliases = {"val$key", "val$p_311196_", "f_302924_", "field_47488", "val$p_312781_"}) private ResourceKey val$registryKey;
	@Shadow(remap = false, aliases = {"val$lifecycle", "val$p_311352_", "f_303753_", "field_47489", "val$p_312783_"}) private Lifecycle val$registryLifecycle;
	@Shadow(remap = false, aliases = {"val$entries", "val$lookups", "val$p_311458_", "f_302363_", "field_40947", "val$p_312180_"}) private Map val$elements;
	@Override public ResourceKey<?> pingforit$key() { return val$registryKey; }
	@Override public Lifecycle pingforit$lifecycle() { return val$registryLifecycle; }
	@Override public Map<?, ?> pingforit$elements() { return val$elements; }
}
