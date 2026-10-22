package nx.pingwheel.common.mixin;

import net.minecraft.core.HolderOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.core.RegistrySetBuilder$EmptyTagLookup")
public interface InventoryBuilderOwnerAccessor {
	@Accessor("owner") HolderOwner<?> pingforit$owner();
}
