package nx.pingwheel.neoforge.platform;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.food.Foods;
import net.neoforged.neoforge.common.EffectCure;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemNativeAccess;

/**
 * Only the 13 PossibleEffect OBJECTS constructed by the seven audited Foods
 * constants certify their captured-instance suppliers. Equality, item ID,
 * namespace, lambda class, or a merely equal supplier is not a certificate.
 */
public final class InventoryItemNativeAccessImpl implements InventoryItemNativeAccess {
	private static final Class<?> SINGLETON = ImmutableList.of(new Object()).getClass();
	private static final Class<?> REGULAR = ImmutableList.of(new Object(), new Object()).getClass();

	@Override public MobEffectInstance foodEffect(FoodProperties.PossibleEffect value) {
		if (value == null || value.getClass() != FoodProperties.PossibleEffect.class) return null;
		if (contains(Foods.CHICKEN, 1, value) || contains(Foods.POISONOUS_POTATO, 1, value)
			|| contains(Foods.PUFFERFISH, 3, value) || contains(Foods.ROTTEN_FLESH, 1, value)
			|| contains(Foods.SPIDER_EYE, 1, value) || contains(Foods.GOLDEN_APPLE, 2, value)
			|| contains(Foods.ENCHANTED_GOLDEN_APPLE, 4, value)) {
			// This specific constructor captures a MobEffectInstance and returns
			// it without allocation. Do not replace with value.effect(): it copies.
			return value.effectSupplier().get();
		}
		return null;
	}

	private static boolean contains(FoodProperties food, int expected, FoodProperties.PossibleEffect candidate) {
		List<FoodProperties.PossibleEffect> list = food.effects();
		Class<?> type = list.getClass();
		if (type != SINGLETON && type != REGULAR) return false;
		if (list.size() != expected) return false;
		for (int index = 0; index < expected; index++) if (list.get(index) == candidate) return true;
		return false;
	}

	@Override public Set<?> effectCures(MobEffectInstance value) {
		if (value == null || value.getClass() != MobEffectInstance.class) return null;
		return value.getCures();
	}

	@Override public String effectCureName(Object value) {
		return value != null && value.getClass() == EffectCure.class ? ((EffectCure) value).name() : null;
	}
}
