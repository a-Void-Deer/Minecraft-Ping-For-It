package nx.pingwheel.forge.platform;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import nx.pingwheel.common.mixin.InventoryFoodEffectAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Loader ABI/copying-getter evidence, not a claim that Mixin transformed the record. */
class InventoryItemNativeAccessShapeTest {
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test void foodPortTargetsStoredEffectRatherThanCopyingGetter() throws Exception {
		var field = FoodProperties.PossibleEffect.class.getDeclaredField("effect");
		assertEquals(MobEffectInstance.class, field.getType());
		assertEquals(field.getType(), InventoryFoodEffectAccessor.class.getDeclaredMethod("pingforit$effect").getReturnType());
		var resident = new MobEffectInstance(MobEffects.POISON, 100);
		var possible = new FoodProperties.PossibleEffect(resident, 1.0F);
		field.setAccessible(true);
		assertSame(resident, field.get(possible));
		assertNotSame(resident, possible.effect(), "positive control: public getter allocates an effect copy");
	}

	@Test void loaderServiceDoesNotInventNeoForgeCures() {
		var port = new InventoryItemNativeAccessImpl();
		assertNull(port.effectCures(new MobEffectInstance(MobEffects.POISON, 100)));
		assertNull(port.effectCureName(new Object()));
	}
}
