package nx.pingwheel.common.presentation.inventory.client;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.EffectCure;
import net.neoforged.neoforge.common.EffectCures;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual NeoForge-patched Minecraft artifact, not common's vanilla runtime. */
class ClientDisplayFoodNeoForgeTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void explicitCuresSurviveFoodDecodeDisplayAndNativeReencodingWithoutAliases() throws Exception {
		ClientItemDisplay.reset();
		var effect = new MobEffectInstance(MobEffects.REGENERATION, 100, 1);
		effect.getCures().clear(); effect.getCures().add(EffectCures.HONEY);
		var ops = RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE);
		var encodedFood = FoodProperties.DIRECT_CODEC.encodeStart(ops, food(nativeWrapper(effect))).result().orElseThrow();
		assertEquals("honey", encodedFood.getAsJsonObject().getAsJsonArray("effects").get(0).getAsJsonObject().getAsJsonObject("effect").getAsJsonArray("neoforge:cures").get(0).getAsString());
		JsonObject json = new JsonObject(); json.addProperty("id", "minecraft:stone"); JsonObject patch = new JsonObject();
		patch.add("minecraft:food", encodedFood); json.add("components", patch); String wire = json.toString();
		try {
			var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", wire);
			assertFalse(first.stack().isEmpty());
			var ownedFood = first.stack().get(DataComponents.FOOD);
			var owned = resident(ownedFood.effects().getFirst());
			assertCures(owned, EffectCures.HONEY);
			assertSame(effect.getEffect(), owned.getEffect()); assertEquals(0.25F, ownedFood.effects().getFirst().probability());
			assertEquals(encodedFood, FoodProperties.DIRECT_CODEC.encodeStart(ops, ownedFood).result().orElseThrow());
			var cached = resident(ClientItemDisplay.cachedTransmittedPatch(RegistryAccess.EMPTY, "minecraft:stone", wire).orElseThrow().get(DataComponents.FOOD).orElseThrow().effects().getFirst());
			assertNotSame(cached, owned); assertNotSame(cached.getCures(), owned.getCures());
			owned.getCures().clear(); owned.getCures().add(EffectCures.MILK);
			assertCures(cached, EffectCures.HONEY); assertCures(effect, EffectCures.HONEY);
			var second = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", wire);
			assertFalse(second.stack().isEmpty());
			var next = resident(second.stack().get(DataComponents.FOOD).effects().getFirst());
			assertCures(next, EffectCures.HONEY); assertNotSame(next.getCures(), owned.getCures());
			cached.getCures().clear(); cached.getCures().add(EffectCures.PROTECTED_BY_TOTEM);
			effect.getCures().clear();
			assertCures(next, EffectCures.HONEY); assertCures(owned, EffectCures.MILK);
			assertEquals(1, ClientItemDisplay.decodeMisses());
		} finally { ClientItemDisplay.reset(); }
	}

	@Test void copyingNativeFoodEffectsNeverInitializesOrLeaksEffectsToTheirOwnerHook() throws Exception {
		var registry = new net.minecraft.core.MappedRegistry<MobEffect>(net.minecraft.core.registries.Registries.MOB_EFFECT, com.mojang.serialization.Lifecycle.stable());
		var hook = new CountingEffect();
		var holder = registry.register(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.MOB_EFFECT,
			net.minecraft.resources.ResourceLocation.parse("test:counting_food_effect")), hook, net.minecraft.core.RegistrationInfo.BUILT_IN);
		registry.freeze();
		var hidden = new MobEffectInstance(holder, 300, 0);
		var source = new MobEffectInstance(holder, 100, 1, true, false, true, hidden);
		assertEquals(2, hook.calls); assertSame(source, hook.last);
		source.getCures().add(EffectCures.HONEY); hidden.getCures().add(EffectCures.MILK);
		var food = food(nativeWrapper(source)); ItemStack stack = new ItemStack(Items.STONE); stack.set(DataComponents.FOOD, food);
		hook.calls = 0;
		var copy = ClientDisplayWorkingCopy.overlay(stack.getComponents(), DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation());
		var owned = resident(copy.get(DataComponents.FOOD).orElseThrow().effects().getFirst());
		assertEquals(0, hook.calls); assertSame(source, hook.last); assertNotSame(hook.last, owned);
		assertSame(holder, owned.getEffect()); assertNotSame(source, owned);
		var ownedHidden = new ClientDisplayFoodTestPorts().hidden(owned);
		assertNotSame(hidden, ownedHidden); assertCures(owned, EffectCures.HONEY); assertCures(ownedHidden, EffectCures.MILK);
		assertEquals(100, owned.getDuration()); assertEquals(1, owned.getAmplifier()); assertTrue(owned.isAmbient()); assertFalse(owned.isVisible()); assertTrue(owned.showIcon());
		var blend = java.util.Arrays.stream(MobEffectInstance.class.getDeclaredFields()).filter(f -> f.getType().getSimpleName().equals("BlendState")).findFirst().orElseThrow();
		blend.setAccessible(true); assertNotSame(blend.get(source), blend.get(owned)); assertNotSame(blend.get(hidden), blend.get(ownedHidden));
		owned.getCures().clear(); ownedHidden.getCures().clear();
		assertCures(source, EffectCures.HONEY); assertCures(hidden, EffectCures.MILK);
		source.getCures().add(EffectCures.PROTECTED_BY_TOTEM); hidden.getCures().add(EffectCures.HONEY);
		assertCures(owned); assertCures(ownedHidden);
		var field = net.minecraft.world.item.Item.class.getDeclaredField("components"); field.setAccessible(true);
		var defaults = (net.minecraft.core.component.DataComponentMap) field.get(Items.STONE);
		try {
			field.set(Items.STONE, net.minecraft.core.component.DataComponentMap.builder().addAll(defaults).set(DataComponents.FOOD, food).build());
			ClientItemDisplay.reset(); hook.calls = 0;
			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", "{\"id\":\"minecraft:stone\"}");
			assertFalse(display.stack().isEmpty());
			assertSame(food, display.stack().get(DataComponents.FOOD), "local defaults stay native references, unlike the explicit patch copy above");
			var rendered = resident(display.stack().get(DataComponents.FOOD).effects().getFirst());
			assertEquals(0, hook.calls); assertSame(source, hook.last); assertSame(source, rendered);
			assertCures(rendered, EffectCures.HONEY, EffectCures.PROTECTED_BY_TOTEM);
			@Nullable MobEffectInstance inheritedHidden = new ClientDisplayFoodTestPorts().hidden(rendered);
			assertSame(hidden, inheritedHidden);
			assertCures(inheritedHidden, EffectCures.MILK, EffectCures.HONEY);
		} finally { field.set(Items.STONE, defaults); ClientItemDisplay.reset(); }
	}

	private static final class CountingEffect extends MobEffect {
		int calls; MobEffectInstance last;
		CountingEffect() { super(MobEffectCategory.BENEFICIAL, 0x123456); }
		@Override public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance instance) { calls++; last = instance; }
	}
	private static void assertCures(MobEffectInstance effect, EffectCure... expected) {
		assertEquals(expected.length, effect.getCures().size());
		for (EffectCure cure : expected) assertTrue(effect.getCures().stream().anyMatch(actual -> actual == cure));
	}

	@Test void unauditedCureBackingsAndExhaustedAllowanceRefuseBeforeAnyCallback() throws Exception {
		var registry = new net.minecraft.core.MappedRegistry<MobEffect>(net.minecraft.core.registries.Registries.MOB_EFFECT, com.mojang.serialization.Lifecycle.stable());
		var hook = new CountingEffect();
		var holder = registry.register(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.MOB_EFFECT,
			net.minecraft.resources.ResourceLocation.parse("test:guarded_food_effect")), hook, net.minecraft.core.RegistrationInfo.BUILT_IN);
		registry.freeze(); var source = new MobEffectInstance(holder, 100); source.getCures().add(EffectCures.HONEY);
		hook.calls = 0; var calls = new AtomicInteger();
		var field = MobEffectInstance.class.getDeclaredField("cures"); field.setAccessible(true); Object original = field.get(source);
		Set<EffectCure> unknown = new java.util.AbstractSet<>() {
			@Override public int size() { calls.incrementAndGet(); throw new AssertionError("unknown size"); }
			@Override public java.util.Iterator<EffectCure> iterator() { calls.incrementAndGet(); throw new AssertionError("unknown iterator"); }
		};
		class UnknownBacking extends java.util.IdentityHashMap<EffectCure, Boolean> {
			boolean armed;
			@Override public int size() { if (armed) { calls.incrementAndGet(); throw new AssertionError("unknown backing size"); } return super.size(); }
			@Override public Set<EffectCure> keySet() { if (armed) { calls.incrementAndGet(); throw new AssertionError("unknown backing keys"); } return super.keySet(); }
		}
		var unknownMap = new UnknownBacking(); Set<EffectCure> sameOuter = java.util.Collections.newSetFromMap(unknownMap); unknownMap.armed = true;
		try {
			for (Set<?> malformed : List.of(unknown, sameOuter, java.util.Collections.newSetFromMap(new java.util.HashMap<>()))) {
				field.set(source, malformed); assertEffectRefused(source, new ClientItemDisplay.Validation());
				ItemStack stack = new ItemStack(Items.STONE); stack.set(DataComponents.FOOD, food(nativeWrapper(source)));
				assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplayWorkingCopy.overlay(stack.getComponents(), DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation()));
			}
			// Correct outer/backing but not an immutable exact EffectCure leaf.
			Set<Object> badLeaf = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()); badLeaf.add("not a cure");
			field.set(source, badLeaf); assertEffectRefused(source, new ClientItemDisplay.Validation());
			// Empty retained tables also have to fit before any native copy.
			var sparseMap = new java.util.IdentityHashMap<EffectCure, Boolean>(4096);
			field.set(source, java.util.Collections.newSetFromMap(sparseMap)); assertEffectRefused(source, new ClientItemDisplay.Validation());
			field.set(source, original);
			assertEffectRefused(source, new ClientItemDisplay.Validation(100, 4096));
			var owned = copyEffect(source, new ClientItemDisplay.Validation()); assertNotSame(source, owned); assertCures(owned, EffectCures.HONEY);
			// Previously spent root allowance is not replenished for an effect copy.
			var spent = new ClientItemDisplay.Validation(); spent.cells(spent.remainingVisits() - 100); assertEffectRefused(source, spent);
		} finally { field.set(source, original); }
		assertEquals(0, calls.get()); assertEquals(0, hook.calls); assertSame(source, hook.last);
	}

	@Test void nativeCureTableGrowthIsAdmittedWithoutChangingImmutableLeafIdentities() throws Exception {
		var source = new MobEffectInstance(MobEffects.REGENERATION, 100); source.getCures().clear();
		for (int i = 0; i < 22; i++) source.getCures().add(EffectCure.get("test_copy_growth_" + i));
		var owned = copyEffect(source, new ClientItemDisplay.Validation());
		assertNotSame(source.getCures(), owned.getCures()); assertEquals(22, owned.getCures().size());
		for (EffectCure cure : source.getCures()) assertTrue(owned.getCures().stream().anyMatch(actual -> actual == cure));
		owned.getCures().clear(); assertEquals(22, source.getCures().size());
	}

	private static MobEffectInstance copyEffect(MobEffectInstance source, ClientItemDisplay.Validation budget) throws Exception {
		var method = ClientDisplayWorkingCopy.class.getDeclaredMethod("effect", MobEffectInstance.class, int.class, ClientItemDisplay.Validation.class);
		method.setAccessible(true); return (MobEffectInstance) method.invoke(null, source, 0, budget);
	}
	private static void assertEffectRefused(MobEffectInstance source, ClientItemDisplay.Validation budget) {
		var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> copyEffect(source, budget));
		assertInstanceOf(ClientItemDisplay.UnsafeDisplay.class, failure.getCause());
	}

	private static FoodProperties.PossibleEffect nativeWrapper(MobEffectInstance effect) throws ReflectiveOperationException {
		var constructor = FoodProperties.PossibleEffect.class.getDeclaredConstructor(MobEffectInstance.class, float.class);
		assertTrue(Modifier.isPrivate(constructor.getModifiers()), "proves patched NeoForge class is first on the test runtime");
		constructor.setAccessible(true); return constructor.newInstance(effect, 0.25F);
	}
	private static FoodProperties food(FoodProperties.PossibleEffect effect) { return new FoodProperties(4, 0.3F, false, 1.6F, Optional.empty(), List.of(effect)); }
	private static MobEffectInstance resident(FoodProperties.PossibleEffect effect) { return ClientDisplayFoodAccess.resident(effect, 0, new ClientItemDisplay.Validation()); }

	@Test void decodedNativeCodecCaptureHasExactAnchorClassAndProducesOwnedOrdinaryFoodIcon() throws Exception {
		ClientItemDisplay.reset();
		var hidden = new MobEffectInstance(MobEffects.REGENERATION, 300, 0);
		var effect = new MobEffectInstance(MobEffects.REGENERATION, 100, 1, false, true, true, hidden);
		var possible = nativeWrapper(effect);
		assertSame(effect, resident(possible), "descriptor read, without native copying effect() getter");
		var ops = RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE);
		var encodedFood = FoodProperties.DIRECT_CODEC.encodeStart(ops, food(possible)).result().orElseThrow();
		assertFalse(encodedFood.getAsJsonObject().getAsJsonArray("effects").isEmpty());
		var decoded = FoodProperties.DIRECT_CODEC.parse(ops, encodedFood).result().orElseThrow();
		assertSame(possible.effectSupplier().getClass(), decoded.effects().getFirst().effectSupplier().getClass(), "CODEC uses the exact anchored direct-constructor call site");
		assertEquals(100, resident(decoded.effects().getFirst()).getDuration());
		JsonObject stack = new JsonObject(); stack.addProperty("id", "minecraft:stone"); JsonObject patch = new JsonObject();
		patch.add("minecraft:food", encodedFood); stack.add("components", patch); String json = stack.toString();
		var first = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", json);
		assertFalse(first.stack().isEmpty()); assertEquals(1, first.stack().getCount()); assertEquals(Items.STONE, first.stack().getItem());
		var owned = first.stack().get(DataComponents.FOOD).effects().getFirst();
		assertEquals(0.25F, owned.probability()); assertSame(effect.getEffect(), resident(owned).getEffect());
		var transmitted = ClientItemDisplay.cachedTransmittedPatch(RegistryAccess.EMPTY, "minecraft:stone", json).orElseThrow().get(DataComponents.FOOD).orElseThrow().effects().getFirst();
		assertNotSame(resident(transmitted), resident(owned));
		resident(owned).update(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
		var next = ClientItemDisplay.resolve(RegistryAccess.EMPTY, "minecraft:stone", "fallback", json);
		assertFalse(next.stack().isEmpty()); assertEquals(100, resident(next.stack().get(DataComponents.FOOD).effects().getFirst()).getDuration());
		assertEquals(100, resident(transmitted).getDuration()); assertEquals(1, ClientItemDisplay.decodeMisses());

		ItemStack source = new ItemStack(Items.STONE); source.set(DataComponents.FOOD, food(possible));
		var copied = ClientDisplayWorkingCopy.overlay(source.getComponents(), DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation());
		MobEffectInstance detached = resident(copied.get(DataComponents.FOOD).orElseThrow().effects().getFirst());
		var raw = new ClientDisplayFoodTestPorts();
		assertNotSame(effect, detached); assertNotSame(hidden, raw.hidden(detached));
		effect.update(new MobEffectInstance(MobEffects.REGENERATION, 200, 1)); hidden.update(new MobEffectInstance(MobEffects.REGENERATION, 400, 0));
		assertEquals(100, detached.getDuration()); assertEquals(300, raw.hidden(detached).getDuration());
		var later = ClientDisplayWorkingCopy.overlay(source.getComponents(), DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation());
		MobEffectInstance laterEffect = resident(later.get(DataComponents.FOOD).orElseThrow().effects().getFirst());
		assertEquals(200, laterEffect.getDuration()); assertEquals(400, raw.hidden(laterEffect).getDuration());
		assertNotSame(laterEffect, detached); assertEquals(100, detached.getDuration());
	}

	@Test void publicUnknownSuppliersAreRefusedWithZeroCallbacksAndNoSourceMutation() throws Exception {
		var calls = new AtomicInteger(); MobEffectInstance effect = new MobEffectInstance(MobEffects.REGENERATION, 100);
		Supplier<MobEffectInstance> counting = () -> { calls.incrementAndGet(); return effect; };
		Supplier<MobEffectInstance> throwing = () -> { calls.incrementAndGet(); throw new AssertionError("supplier must never run"); };
		Supplier<MobEffectInstance> otherLambda = () -> effect;
		Class<?> anchor = nativeWrapper(effect).effectSupplier().getClass();
		for (Supplier<MobEffectInstance> supplier : List.of(counting, throwing, otherLambda)) {
			assertNotSame(anchor, supplier.getClass());
			var unknown = new FoodProperties.PossibleEffect(supplier, 0.25F);
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> resident(unknown));
			ItemStack source = new ItemStack(Items.STONE, 7); FoodProperties food = food(unknown); source.set(DataComponents.FOOD, food);
			assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplayWorkingCopy.overlay(source.getComponents(), DataComponentPatch.EMPTY, 0, new ClientItemDisplay.Validation()));
			assertSame(food, source.get(DataComponents.FOOD)); assertEquals(7, source.getCount()); assertEquals(Items.STONE, source.getItem());
		}
		assertEquals(0, calls.get());
	}

	@Test void unknownNativeDefaultIsInheritedWithoutChangingExactEntryCountOrSelectionReference() throws Exception {
		var calls = new AtomicInteger(); var unknown = new FoodProperties.PossibleEffect(() -> {
			calls.incrementAndGet(); throw new AssertionError("unknown default supplier");
		}, 0.25F);
		List<nx.pingwheel.common.network.IPacket> sent = new java.util.ArrayList<>();
		var inventory = new ClientInventory(sent::add); inventory.presentationReset(100, 1);
		long request = inventory.open(new nx.pingwheel.common.domain.Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest"),
			nx.pingwheel.common.domain.BlockFace.NORTH, "entity_block");
		inventory.accept(nx.pingwheel.common.network.InventoryS2CPacket.offer(7, new nx.pingwheel.common.network.InventoryS2CPacket.Offer(10, 20, 3, 5)).stamp(100, 1));
		inventory.accept(nx.pingwheel.common.network.InventoryS2CPacket.policy(7, 100, 1, java.util.Set.of("entity_block")));
		String json = "{\"id\":\"minecraft:stone\"}";
		var entry = new nx.pingwheel.common.network.InventoryS2CPacket.Entry("exact-food", "minecraft:stone", "fallback", json, 9007199254740993L, 1, false, null);
		inventory.accept(nx.pingwheel.common.network.InventoryS2CPacket.data(nx.pingwheel.common.network.InventoryS2CPacket.Kind.PREVIEW, 7, request, null,
			4, 1, 5, 0, 1, true, nx.pingwheel.common.network.InventoryS2CPacket.Status.READY, 0, List.of(entry)).stamp(100, 1));
		var reference = inventory.selectable(request).getFirst(); var before = inventory.preview(request).entries().getFirst();
		var field = net.minecraft.world.item.Item.class.getDeclaredField("components"); field.setAccessible(true);
		var defaults = (net.minecraft.core.component.DataComponentMap) field.get(Items.STONE);
		try {
			FoodProperties defaultFood = food(unknown);
			field.set(Items.STONE, net.minecraft.core.component.DataComponentMap.builder().addAll(defaults).set(DataComponents.FOOD, defaultFood).build());
			ClientItemDisplay.reset();
			var display = ClientItemDisplay.resolve(RegistryAccess.EMPTY, entry.itemId(), entry.label(), entry.displayJson());
			assertFalse(display.stack().isEmpty());
			assertEquals(Items.STONE, display.stack().getItem()); assertEquals(1, display.stack().getCount());
			assertSame(defaultFood, display.stack().get(DataComponents.FOOD));
			assertEquals("fallback", ((net.minecraft.network.chat.contents.TranslatableContents) display.name().getContents()).getKey());
			assertTrue(ClientItemDisplay.decodeName(RegistryAccess.EMPTY, json).isEmpty());
			assertEquals(before, inventory.preview(request).entries().getFirst()); assertFalse(before.fallback());
			assertEquals(9007199254740993L, before.count()); assertEquals(reference, inventory.selectable(request).getFirst());
		} finally { field.set(Items.STONE, defaults); ClientItemDisplay.reset(); }
		assertEquals(0, calls.get());
	}

	@Test void nativeWrapperConstructionHonorsRemainingRootAllowance() {
		var effect = new MobEffectInstance(MobEffects.REGENERATION, 100);
		assertThrows(ClientItemDisplay.UnsafeDisplay.class, () -> ClientDisplayFoodAccess.wrap(effect, 0.25F, 0, new ClientItemDisplay.Validation(1, 4096)));
		var wrapped = ClientDisplayFoodAccess.wrap(effect, 0.25F, 0, new ClientItemDisplay.Validation(64, 4096));
		assertSame(effect, resident(wrapped)); assertEquals(0.25F, wrapped.probability());
	}
}
