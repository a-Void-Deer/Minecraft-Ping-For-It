package nx.pingwheel.common.mixin;

import net.minecraft.network.chat.TextColor;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryRgbColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Overwrite;

/** Replaces only the fixed RGB Formatter leaf, retaining TextColor's codec/name branch. */
@Mixin(TextColor.class)
public abstract class InventoryTextColorMixin {
	@Shadow @Final private int value;
	/**
	 * Public to match Fabric API's transitive access widening of this leaf.
	 * @author Ping For It
	 * @reason Fixed seven-character native RGB leaf without Formatter/regex work.
	 */
	@Overwrite
	public String formatValue() {
		return InventoryRgbColor.format(value);
	}
}
