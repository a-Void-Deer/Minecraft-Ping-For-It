package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChargedProjectiles.class)
public interface InventoryProjectileItemsAccessor {
	@Accessor("items") List<ItemStack> pingforit$items();
}
