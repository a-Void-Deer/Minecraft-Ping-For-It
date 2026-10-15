package nx.pingwheel.common.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Object2IntOpenHashMap.class, remap = false)
public interface InventoryObjectIntMapCapacityAccessor {
	@Accessor("n") int pingforit$capacity();
}
