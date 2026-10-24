package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryItemUnavailableDisplayTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
	@Test void sizeTerminalTighteningKeepsIdentityAndCanonicalSharing() {
		String json = "{\"id\":\"minecraft:stone\",\"components\":{" +
			"\"!test:" + "a".repeat(1100) + "\":{}}}";
		var display = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone", json, false);
		byte[] canonical = {1, 2, 3};
		var detached = new InventoryDetachedItem(display.itemId(), InventoryIdentityMode.EXACT, display, canonical, 4096);
		var projected = InventoryItemCodec.reproject(display, RegistryAccess.EMPTY, 1024);
		assertEquals("{\"id\":\"minecraft:stone\"}", projected.displayJson()); assertFalse(projected.aggregateRequired());
		var replacement = detached.withDisplay(projected);
		assertSame(canonical, replacement.canonicalForCatalog()); assertEquals(InventoryIdentityMode.EXACT, replacement.identityMode());
		assertEquals(detached.retainedBytes() - 2L * (json.length() - projected.displayJson().length()), replacement.retainedBytes());
		assertSame(projected, InventoryItemCodec.reproject(projected, RegistryAccess.EMPTY, 4096));
	}

	@Test void malformedEvenSmallInputIsUnavailableWithoutUnsafeBareReconstruction() {
		var retained = new InventoryItemCodec.Display("minecraft:stone", "block.minecraft.stone", "{malformed}", false);
		var projected = InventoryItemCodec.reproject(retained, RegistryAccess.EMPTY, 1024);
		assertNull(projected.displayJson()); assertFalse(projected.aggregateRequired());
	}

	@Test void aggregateFactoryUsesFixedRedactionsAtEveryLegalCapWithoutRegistry() {
		for (String id : List.of("x:" + "a".repeat(254), "minecraft:stone", "minecraft:shulker_box",
			"minecraft:white_shulker_box", "minecraft:orange_shulker_box", "minecraft:magenta_shulker_box",
			"minecraft:light_blue_shulker_box", "minecraft:yellow_shulker_box", "minecraft:lime_shulker_box",
			"minecraft:pink_shulker_box", "minecraft:gray_shulker_box", "minecraft:light_gray_shulker_box",
			"minecraft:cyan_shulker_box", "minecraft:purple_shulker_box", "minecraft:blue_shulker_box",
			"minecraft:brown_shulker_box", "minecraft:green_shulker_box", "minecraft:red_shulker_box", "minecraft:black_shulker_box")) {
			for (int cap : new int[] {1024, 4096, 131072}) {
				var display = InventoryItemCodec.aggregateDisplay(id, "item.test", cap);
				assertTrue(display.aggregateRequired()); assertNotNull(display.displayJson());
				assertTrue(InventoryDisplayWriter.utf8(display.displayJson()) <= 327);
				var patch = JsonParser.parseString(display.displayJson()).getAsJsonObject().getAsJsonObject("components");
				assertTrue(patch.has("!minecraft:lore"));
				assertEquals(id.endsWith("shulker_box"), patch.has("!minecraft:container"));
			}
		}
		assertThrows(IllegalArgumentException.class, () -> InventoryItemCodec.aggregateDisplay("test:\"bad", "item.test", 1024));
		assertThrows(IllegalArgumentException.class, () -> InventoryItemCodec.aggregateDisplay("test:good", "x".repeat(263), 1024));
	}

	@Test void largeSafeNameDegradesDisplayWithoutFoldingAndVanillaAliasesMatchNativeDescriptions() {
		var stack = new ItemStack(Items.STONE); stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(20000)));
		var frozen = freeze(stack);
		assertEquals(InventoryIdentityMode.EXACT, frozen.identityMode()); assertNotNull(frozen.canonicalForCatalog());
		assertFalse(frozen.display().aggregateRequired());
		assertTrue(frozen.display().displayJson() == null || InventoryDisplayWriter.utf8(frozen.display().displayJson()) <= 1024);
		for (Item item : List.of(Items.REDSTONE, Items.STRING, Items.WHEAT_SEEDS, Items.COCOA_BEANS,
			Items.PUMPKIN_SEEDS, Items.MELON_SEEDS, Items.NETHER_WART, Items.CARROT, Items.POTATO,
			Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD, Items.BEETROOT_SEEDS, Items.SWEET_BERRIES,
			Items.GLOW_BERRIES, Items.POWDER_SNOW_BUCKET)) assertEquals(item.getDescriptionId(), freeze(new ItemStack(item)).display().label());
	}
	private static InventoryDetachedItem freeze(ItemStack stack) {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(stack), RegistryAccess.EMPTY, 1024,
			InventoryItemBudget.root(1024), new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}
}
