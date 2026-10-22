package nx.pingwheel.common.mixin;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundTag.class)
public interface InventoryCompoundTagAccessor {
	@Accessor("tags") Map<String, Tag> pingforit$tags();
}
