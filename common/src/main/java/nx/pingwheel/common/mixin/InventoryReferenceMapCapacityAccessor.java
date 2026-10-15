package nx.pingwheel.common.mixin;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Reference2ObjectOpenHashMap.class, remap = false)
public interface InventoryReferenceMapCapacityAccessor {
	@Accessor("n") int pingforit$capacity();
}
