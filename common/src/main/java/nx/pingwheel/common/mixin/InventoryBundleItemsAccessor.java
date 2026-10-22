package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BundleContents.class)
public interface InventoryBundleItemsAccessor {
	@Accessor("items") List<ItemStack> pingforit$items();
}
