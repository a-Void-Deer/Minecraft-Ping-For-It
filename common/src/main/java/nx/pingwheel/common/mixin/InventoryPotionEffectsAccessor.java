package nx.pingwheel.common.mixin;

import java.util.List;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.PotionContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** PotionContents.customEffects() is a copying transformed view, not resident access. */
@Mixin(PotionContents.class)
public interface InventoryPotionEffectsAccessor {
	@Accessor("customEffects") List<MobEffectInstance> pingforit$effects();
}
