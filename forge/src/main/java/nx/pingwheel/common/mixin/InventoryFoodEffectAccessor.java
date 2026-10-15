package nx.pingwheel.common.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** PossibleEffect.effect() copies; admission needs its stored resident field. */
@Mixin(FoodProperties.PossibleEffect.class)
public interface InventoryFoodEffectAccessor {
	@Accessor("effect") MobEffectInstance pingforit$effect();
}
