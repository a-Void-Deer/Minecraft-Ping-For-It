package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HolderSet.Direct.class)
public interface InventoryDirectHolderSetAccessor {
	@Accessor("contents") List<Holder<?>> pingforit$contents();
}
