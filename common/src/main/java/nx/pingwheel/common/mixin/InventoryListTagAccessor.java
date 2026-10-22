package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ListTag.class)
public interface InventoryListTagAccessor {
	@Accessor("list") List<Tag> pingforit$list();
}
