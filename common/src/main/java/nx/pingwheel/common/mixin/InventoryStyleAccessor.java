package nx.pingwheel.common.mixin;

import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Style.class)
public interface InventoryStyleAccessor {
	@Accessor("bold") Boolean pingforit$bold();
	@Accessor("italic") Boolean pingforit$italic();
	@Accessor("underlined") Boolean pingforit$underlined();
	@Accessor("strikethrough") Boolean pingforit$strikethrough();
	@Accessor("obfuscated") Boolean pingforit$obfuscated();
	@Accessor("font") ResourceLocation pingforit$font();
}
