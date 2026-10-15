package nx.pingwheel.common.mixin;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Item.class)
public interface InventoryItemComponentsAccessor {
	@Accessor("components") DataComponentMap pingforit$components();
}
