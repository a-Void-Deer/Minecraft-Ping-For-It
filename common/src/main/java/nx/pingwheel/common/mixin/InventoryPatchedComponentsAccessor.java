package nx.pingwheel.common.mixin;

import java.util.Optional;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PatchedDataComponentMap.class)
public interface InventoryPatchedComponentsAccessor {
	@Accessor("prototype") DataComponentMap pingforit$prototype();
	@Accessor("patch") Reference2ObjectMap<DataComponentType<?>, Optional<?>> pingforit$patch();
}
