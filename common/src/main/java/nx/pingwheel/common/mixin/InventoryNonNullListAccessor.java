package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.core.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NonNullList.class)
public interface InventoryNonNullListAccessor {
	@Accessor("list") List<?> pingforit$list();
}
