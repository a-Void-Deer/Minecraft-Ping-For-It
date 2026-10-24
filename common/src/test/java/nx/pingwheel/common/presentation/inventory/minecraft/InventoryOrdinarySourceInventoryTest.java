package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Independent source-defined descriptor counts, never production cost counters. */
class InventoryOrdinarySourceInventoryTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void swordAndDiggerOrdinaryFloorsUseBothMainhandModifiersAndBothRules() {
		var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		for (Item item : List.of(Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.IRON_AXE)) {
			var stack = new ItemStack(item);
			ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
			assertNotNull(modifiers); assertEquals(2, modifiers.modifiers().size());
			assertTrue(modifiers.modifiers().stream().allMatch(entry -> entry.slot() == EquipmentSlotGroup.MAINHAND));
			Tool tool = stack.get(DataComponents.TOOL); assertNotNull(tool); assertEquals(2, tool.rules().size());
			// Exercise the admitted native value graph explicitly, rather than
			// relying on defaults which are outside patch identity.
			((net.minecraft.core.component.PatchedDataComponentMap) stack.getComponents()).restorePatch(
				net.minecraft.core.component.DataComponentPatch.builder().set(DataComponents.ATTRIBUTE_MODIFIERS, modifiers)
					.set(DataComponents.TOOL, tool).build());
			var nativeTag = net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(registry.createSerializationContext(NbtOps.INSTANCE), ((net.minecraft.core.component.PatchedDataComponentMap) stack.getComponents()).asPatch()).result().orElseThrow();
			var detached = InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registry, 4096,
				InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
			assertEquals(InventoryIdentityMode.EXACT, detached.identityMode());
			assertArrayEquals(InventoryItemCodec.canonicalTag(nativeTag), detached.canonicalForCatalog());
		}
	}

	@Test void sevenActualFoodDefaultsHaveSourceDefinedEffectOccurrences() {
		Map<Item, Integer> counts = Map.of(Items.CHICKEN, 1, Items.POISONOUS_POTATO, 1,
			Items.PUFFERFISH, 3, Items.ROTTEN_FLESH, 1, Items.SPIDER_EYE, 1,
			Items.GOLDEN_APPLE, 2, Items.ENCHANTED_GOLDEN_APPLE, 4);
		for (var entry : counts.entrySet()) {
			var food = new ItemStack(entry.getKey()).get(DataComponents.FOOD);
			assertNotNull(food); assertEquals(entry.getValue().intValue(), food.effects().size());
			assertTrue(food.usingConvertsTo().isEmpty(), "the seven effect foods have no bowl-conversion plan");
		}
		var conversion = new ItemStack(Items.MUSHROOM_STEW).get(DataComponents.FOOD);
		assertNotNull(conversion); assertTrue(conversion.effects().isEmpty());
		assertTrue(conversion.usingConvertsTo().orElseThrow().is(Items.BOWL));
	}

	@Test void writtenBookPrototypeGlintIsSeparateFromAddedContentAndArmorKeepsPersistentDefault() {
		var book = new ItemStack(Items.WRITTEN_BOOK);
		assertEquals(Boolean.TRUE, book.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE));
		assertNull(book.get(DataComponents.WRITTEN_BOOK_CONTENT));
		for (Item item : List.of(Items.LEATHER_CHESTPLATE, Items.DIAMOND_HELMET)) {
			var stack = new ItemStack(item); assertNotNull(stack.get(DataComponents.MAX_DAMAGE));
			assertTrue(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
				"deprecated tooltip defaults are not the persistent component map");
		}
	}
}
