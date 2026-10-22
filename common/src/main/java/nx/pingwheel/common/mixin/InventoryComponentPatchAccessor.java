package nx.pingwheel.common.mixin;

import java.util.Optional;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DataComponentPatch.class)
public interface InventoryComponentPatchAccessor {
	@Accessor("map") Reference2ObjectMap<DataComponentType<?>, Optional<?>> pingforit$map();
}
