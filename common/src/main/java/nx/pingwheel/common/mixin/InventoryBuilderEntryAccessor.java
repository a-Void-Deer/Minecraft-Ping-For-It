package nx.pingwheel.common.mixin;

import net.minecraft.resources.RegistryOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.core.RegistrySetBuilder$1Entry")
public interface InventoryBuilderEntryAccessor {
	@Accessor("opsInfo") RegistryOps.RegistryInfo<?> pingforit$opsInfo();
}
