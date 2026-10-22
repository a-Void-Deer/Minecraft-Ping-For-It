package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ServiceLoader;
import java.util.Set;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;

/** Loader-specific resident field access only; no copies, codecs, or gameplay hooks. */
public interface InventoryItemNativeAccess {
	/** Null means unaudited. Callers exact-class check before this port. */
	MobEffectInstance foodEffect(FoodProperties.PossibleEffect value);
	/** Null on Fabric/Forge. NeoForge's native effect owns this set for its lifetime. */
	Set<?> effectCures(MobEffectInstance value);
	/** Null means unsupported; never uses an arbitrary object's toString. */
	String effectCureName(Object value);

	static InventoryItemNativeAccess load() {
		return ServiceLoader.load(InventoryItemNativeAccess.class).findFirst()
			.orElseThrow(() -> new IllegalStateException("No InventoryItemNativeAccess implementation found"));
	}
}
