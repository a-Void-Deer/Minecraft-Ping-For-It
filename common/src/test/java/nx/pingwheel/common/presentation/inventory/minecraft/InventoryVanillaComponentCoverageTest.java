package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Independently small native-codec oracles; no giant input is encoded to discover its size. */
class InventoryVanillaComponentCoverageTest {
	private static RegistryAccess registries;

	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	private static InventoryDetachedItem freeze(ItemStack stack) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), registries, 4096,
			InventoryItemBudget.root(4096), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}
	private static void exactOracle(ItemStack stack) {
		var detached = freeze(stack); assertEquals(InventoryIdentityMode.EXACT, detached.identityMode(), () -> "fixture " + stack.getItem());
		var encoded = DataComponentMap.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack.getComponents()).result().orElseThrow();
		assertArrayEquals(InventoryItemCodec.canonicalTag(encoded), detached.canonicalForCatalog());
	}

	@Test void ordinaryDefaultsToolsArmourAndAllSevenEffectBearingFoodsMatchStaticOracle() {
		for (Item item : List.of(Items.STONE, Items.DIAMOND, Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE,
			Items.IRON_AXE, Items.LEATHER_CHESTPLATE, Items.DIAMOND_HELMET, Items.CHICKEN,
			Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.ROTTEN_FLESH, Items.SPIDER_EYE,
			Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.MUSHROOM_STEW,
			Items.POTION, Items.WRITABLE_BOOK, Items.WRITTEN_BOOK, Items.FIREWORK_ROCKET, Items.DEBUG_STICK)) exactOracle(new ItemStack(item));
	}

	@Test void completeTextEncodeFloorIncludesStoredScoreSelectorNbtAndShowEntity() {
		for (Component component : List.of(Component.literal("styled").withStyle(net.minecraft.ChatFormatting.GOLD),
			Component.translatable("item.minecraft.stone", "argument"),
			MutableComponent.create(new ScoreContents("player", "objective")),
			MutableComponent.create(new SelectorContents("@s", Optional.of(Component.literal(",")))),
			MutableComponent.create(new NbtContents("path", false, Optional.empty(), new BlockDataSource("~ ~ ~"))),
			MutableComponent.create(new NbtContents("path", false, Optional.empty(), new EntityDataSource("@s"))),
			MutableComponent.create(new NbtContents("path", false, Optional.empty(), new StorageDataSource(net.minecraft.resources.ResourceLocation.parse("minecraft:test")))),
			Component.literal("entity").withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
				new HoverEvent.EntityTooltipInfo(net.minecraft.world.entity.EntityType.PIG, new java.util.UUID(1, 2), Component.literal("pig"))))))) {
			var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, component); exactOracle(stack);
		}
	}

	@Test void potionHiddenDescendantsAreIntentionallyOutsidePersistentIdentity() {
		var hidden = new MobEffectInstance(MobEffects.POISON, 500, 0);
		var first = new MobEffectInstance(MobEffects.POISON, 30, 2, false, true, true, hidden);
		var second = new MobEffectInstance(MobEffects.POISON, 30, 2, false, true, true, null);
		var a = new ItemStack(Items.POTION); a.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(), List.of(first)));
		var b = new ItemStack(Items.POTION); b.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.empty(), List.of(second)));
		exactOracle(a); exactOracle(b); assertArrayEquals(freeze(a).canonicalForCatalog(), freeze(b).canonicalForCatalog());
	}

	@Test void allSeventeenShulkerIdentitiesKeepContentsExactButDisplayOmitsThem() {
		List<Item> boxes = List.of(Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
			Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
			Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
			Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, Items.BLACK_SHULKER_BOX);
		for (Item box : boxes) {
			var empty = new ItemStack(box); exactOracle(empty);
			var full = new ItemStack(box); full.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
			exactOracle(full); var frozen = freeze(full);
			assertFalse(java.util.Arrays.equals(freeze(empty).canonicalForCatalog(), frozen.canonicalForCatalog()));
			assertTrue(frozen.display().displayJson().contains("!minecraft:container")); assertFalse(frozen.display().displayJson().contains("minecraft:diamond"));
		}
	}

	@Test void bundlesAndNonShulkerContainerContentsRemainAndNestedOmissionsAreRecursive() {
		var box = new ItemStack(Items.BLUE_SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
		box.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("secret lore"))));
		var bundle = new ItemStack(Items.BUNDLE); bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(box)));
		exactOracle(bundle); String json = freeze(bundle).display().displayJson();
		assertTrue(json.contains("minecraft:blue_shulker_box")); assertFalse(json.contains("minecraft:diamond")); assertFalse(json.contains("secret lore"));
		var chest = new ItemStack(Items.CHEST); chest.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 2))));
		exactOracle(chest); assertTrue(freeze(chest).display().displayJson().contains("minecraft:diamond"));
	}
}
