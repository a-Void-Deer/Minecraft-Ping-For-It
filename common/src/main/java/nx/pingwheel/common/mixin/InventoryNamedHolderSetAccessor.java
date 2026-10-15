package nx.pingwheel.common.mixin;

import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HolderSet.Named.class)
public interface InventoryNamedHolderSetAccessor {
	@Accessor("owner") HolderOwner<?> pingforit$owner();
	@Accessor("key") TagKey<?> pingforit$key();
}
