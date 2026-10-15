package nx.pingwheel.common.mixin;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.core.component.DataComponentMap$Builder$SimpleMap")
public interface InventorySimpleComponentsAccessor {
	@Accessor("map") Reference2ObjectMap<DataComponentType<?>, Object> pingforit$map();
}
