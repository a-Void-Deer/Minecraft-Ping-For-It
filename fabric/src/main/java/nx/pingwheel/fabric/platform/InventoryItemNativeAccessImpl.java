package nx.pingwheel.fabric.platform;

import java.util.Set;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemNativeAccess;
import nx.pingwheel.common.mixin.InventoryFoodEffectAccessor;

public final class InventoryItemNativeAccessImpl implements InventoryItemNativeAccess {
	@Override public MobEffectInstance foodEffect(FoodProperties.PossibleEffect value) {
		if (value == null || value.getClass() != FoodProperties.PossibleEffect.class) return null;
		return ((InventoryFoodEffectAccessor) (Object) value).pingforit$effect();
	}
	@Override public Set<?> effectCures(MobEffectInstance value) { return null; }
	@Override public String effectCureName(Object value) { return null; }
}
