package nx.pingwheel.common.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Holder.Reference.class)
public interface InventoryHolderReferenceAccessor {
	@Accessor("owner") HolderOwner<?> pingforit$owner();
	@Accessor("key") ResourceKey<?> pingforit$key();
	@Accessor("value") Object pingforit$value();
}
