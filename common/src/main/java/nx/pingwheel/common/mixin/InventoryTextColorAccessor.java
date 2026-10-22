package nx.pingwheel.common.mixin;

import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextColor.class)
public interface InventoryTextColorAccessor {
	@Accessor("name") String pingforit$name();
}
