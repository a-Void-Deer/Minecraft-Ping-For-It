package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Set;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;

/** Common tests use vanilla's actual stored field, not its allocating public getter. */
public final class InventoryTestNativeAccess implements InventoryItemNativeAccess {
	@Override public MobEffectInstance foodEffect(FoodProperties.PossibleEffect value) {
		return InventoryResidentAccessFields.field(value, "effect");
	}
	@Override public Set<?> effectCures(MobEffectInstance value) { return null; }
	@Override public String effectCureName(Object value) { return null; }
}
