package nx.pingwheel.common.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEnchantments.class)
public interface InventoryEnchantmentsAccessor {
	@Accessor("enchantments") Object2IntOpenHashMap<Holder<Enchantment>> pingforit$enchantments();
	@Accessor("showInTooltip") boolean pingforit$showInTooltip();
}
