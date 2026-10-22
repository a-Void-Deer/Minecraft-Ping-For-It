package nx.pingwheel.common.mixin;

import net.minecraft.core.Registry;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Synthetic capture spelling differs across the three resolved mapping pipelines. */
@Mixin(targets = "net.minecraft.core.RegistryAccess$1")
public abstract class InventoryRegistryRootMixin implements InventoryResidentAccess.RegistryRoot {
	@Shadow(remap = false, aliases = {"val$registries", "val$p_206166_", "f_206216_", "field_36470"})
	private Registry val$registryOfRegistries;

	@Override public Registry<?> pingforit$registryDelegate() { return val$registryOfRegistries; }
}
