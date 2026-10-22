package nx.pingwheel.common.presentation.inventory.client;

import java.util.List;
import java.util.Optional;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native Fabric-mapped classpath; production codec and ordinary display, headlessly. */
class ClientDisplayFoodFabricTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void explicitNonemptyFoodPatchUsesNativeResidentAndOwnedDisplayEffects() {
		ClientItemDisplay.reset();
		var effect = new MobEffectInstance(MobEffects.REGENERATION, 100);
		var possible = new FoodProperties.PossibleEffect(effect, 0.25F);
		var food = new FoodProperties(4, 0.3F, false, 1.6F, Optional.empty(), List.of(possible));
		var encoded = FoodProperties.DIRECT_CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), food).result().orElseThrow();
		assertFalse(encoded.getAsJsonObject().getAsJsonArray("effects").isEmpty());
		JsonObject stack = new JsonObject(); stack.addProperty("id", "minecraft:stone"); JsonObject patch = new JsonObject();
		patch.add("minecraft:food", encoded); stack.add("components", patch); String json = stack.toString();
		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", json);
		assertFalse(first.stack().isEmpty()); assertEquals(1, first.stack().getCount());
		var owned = first.stack().get(DataComponents.FOOD).effects().getFirst();
		assertEquals(0.25F, owned.probability());
		var resident = ClientDisplayFoodAccess.resident(owned, 0, new ClientItemDisplay.Validation());
		assertNotSame(effect, resident); assertEquals(100, resident.getDuration()); assertSame(effect.getEffect(), resident.getEffect());
		resident.update(new MobEffectInstance(MobEffects.REGENERATION, 200));
		var next = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", json);
		assertFalse(next.stack().isEmpty()); assertEquals(100, ClientDisplayFoodAccess.resident(next.stack().get(DataComponents.FOOD).effects().getFirst(), 0, new ClientItemDisplay.Validation()).getDuration());
		assertEquals(1, ClientItemDisplay.decodeMisses());
	}
	@Test void nativeCopyKeepsAnIndependentlyOwnedHiddenEffectChain() {
		var hidden = new MobEffectInstance(MobEffects.REGENERATION, 300, 0);
		var effect = new MobEffectInstance(MobEffects.REGENERATION, 100, 1, true, false, true, hidden);
		var food = new FoodProperties(4, 0.3F, false, 1.6F, Optional.empty(), List.of(new FoodProperties.PossibleEffect(effect, 0.25F)));
		var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE); stack.set(DataComponents.FOOD, food);
		var patch = ClientDisplayWorkingCopy.overlay(stack.getComponents(), net.minecraft.core.component.DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation());
		var owned = ClientDisplayFoodAccess.resident(patch.get(DataComponents.FOOD).orElseThrow().effects().getFirst(), 0, new ClientItemDisplay.Validation());
		var ownedHidden = new ClientDisplayFoodTestPorts().hidden(owned);
		assertNotSame(effect, owned); assertNotSame(hidden, ownedHidden); assertSame(effect.getEffect(), owned.getEffect());
		assertEquals(100, owned.getDuration()); assertEquals(1, owned.getAmplifier()); assertTrue(owned.isAmbient()); assertFalse(owned.isVisible()); assertTrue(owned.showIcon());
		effect.update(new MobEffectInstance(MobEffects.REGENERATION, 200, 1)); hidden.update(new MobEffectInstance(MobEffects.REGENERATION, 400, 0));
		assertEquals(100, owned.getDuration()); assertEquals(300, ownedHidden.getDuration());
		ownedHidden.update(new MobEffectInstance(MobEffects.REGENERATION, 500, 0)); assertEquals(400, hidden.getDuration());
	}
}
