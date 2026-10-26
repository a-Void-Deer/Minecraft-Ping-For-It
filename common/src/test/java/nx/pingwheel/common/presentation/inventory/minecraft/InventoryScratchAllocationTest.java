package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Per-root ownership and pre-callback admission, not a whole-JVM allocation certificate. */
@NotNullByDefault
class InventoryScratchAllocationTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@NotNullByDefault
	private static final class NativeEffects implements InventoryItemNativeAccess {
		final List<MobEffectInstance> visited = new ArrayList<>();
		final Set<Object> cures = Collections.newSetFromMap(new IdentityHashMap<>());
		@Override public @Nullable MobEffectInstance foodEffect(FoodProperties.PossibleEffect value) {
			return InventoryResidentAccessFields.field(value, "effect");
		}
		@Override public Set<?> effectCures(MobEffectInstance value) { visited.add(value); return cures; }
		@Override public @Nullable String effectCureName(Object value) { return value instanceof String name ? name : null; }
	}

	@Test void firstEffectArrayRefusesBeforeCureCallbacksAndZeroEffectRangesNeedNoArray() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var raw = new InventoryResidentAccessFields(); var nativeEffects = new NativeEffects();
			var certificate = new InventoryComponentCertificate(budget, raw, nativeEffects, new InventoryComponentBounds(budget, raw));
			long before = budget.rootAllocatedBytes();
			certificate.admitEffectCopies(0, 0); certificate.admitEmptyMaps(1, false);
			assertEquals(0, certificate.effectCopyCount());
			assertTrue(budget.rootAllocatedBytes() - before < 4096, "zero-effect work does not reserve the reference array");
			budget.allocate(2L * 1024 * 1024 - budget.rootAllocatedBytes() - 4096, 0, 0, 0);
			assertThrows(InventoryItemBudget.RootLimitException.class,
				() -> certificate.effectCopy(new MobEffectInstance(MobEffects.POISON, 20), 0));
			assertEquals(0, certificate.effectCopyCount()); assertTrue(nativeEffects.visited.isEmpty());
		} finally { budget.end(); }
	}

	@Test void orderedEffectRangesRemainIndependentAcrossIdentityDisplayAndLaterNestedPlanning() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var raw = new InventoryResidentAccessFields(); var nativeEffects = new NativeEffects();
			var certificate = new InventoryComponentCertificate(budget, raw, nativeEffects, new InventoryComponentBounds(budget, raw));
			nativeEffects.cures.add("milk");
			var first = new MobEffectInstance(MobEffects.POISON, 20);
			var second = new MobEffectInstance(MobEffects.REGENERATION, 30);
			var a = certificate.effectCopy(first, 0);
			nativeEffects.cures.clear();
			var b = certificate.effectCopy(second, 1);
			assertEquals(2, certificate.effectCopyCount()); assertEquals(List.of(first, second), nativeEffects.visited);
			long before = budget.rootAllocatedBytes(); certificate.admitEffectCopies(0, 2);
			long both = budget.rootAllocatedBytes() - before;
			assertEquals(vector(a) + vector(b), both);
			budget.beginDisplay(); before = budget.rootAllocatedBytes();
			certificate.admitEffectCopies(1, 2); assertEquals(vector(b), budget.rootAllocatedBytes() - before);
			var c = certificate.effectCopy(new MobEffectInstance(MobEffects.ABSORPTION, 40), 2);
			assertEquals(3, certificate.effectCopyCount());
			before = budget.rootAllocatedBytes(); certificate.admitEffectCopies(0, 3);
			assertEquals(both + vector(c), budget.rootAllocatedBytes() - before);
			assertEquals(3, nativeEffects.visited.size(), "range replay never reinspects resident cures");
		} finally { budget.end(); }
	}

	private static long vector(InventoryComponentCertificate.EffectCost value) {
		return InventoryItemBudget.allocationCost(value.bytes(), 0, value.references(), value.objects());
	}

	@Test void multipleAndNestedNativeEffectFamiliesKeepThePersistentPatchOracleAndDisplay() {
		@Unmodifiable List<MobEffectInstance> effects = List.of(new MobEffectInstance(MobEffects.POISON, 20, 1), new MobEffectInstance(MobEffects.REGENERATION, 30));
		var food = new FoodProperties(4, .5F, false, 1.6F, java.util.Optional.empty(),
			List.of(new FoodProperties.PossibleEffect(effects.getFirst(), .25F), new FoodProperties.PossibleEffect(effects.getLast(), .5F)));
		var potion = new ItemStack(Items.POTION);
		potion.set(DataComponents.POTION_CONTENTS, new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(), effects));
		var root = new ItemStack(Items.CHEST); root.set(DataComponents.FOOD, food);
		root.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(potion)));
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var frozen = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(root), registries, 16384,
			InventoryItemBudget.root(16384), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
		assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode());
		var nativeTag = DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), root.getComponentsPatch()).getOrThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(nativeTag), frozen.canonicalForCatalog());
		String display = java.util.Objects.requireNonNull(frozen.display().displayJson());
		assertTrue(display.contains("minecraft:food")); assertTrue(display.contains("minecraft:potion_contents"));
		assertTrue(display.contains("minecraft:poison")); assertTrue(display.contains("minecraft:regeneration"));
	}
}
