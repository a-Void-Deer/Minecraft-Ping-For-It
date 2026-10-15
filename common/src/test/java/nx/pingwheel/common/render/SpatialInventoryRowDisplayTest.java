package nx.pingwheel.common.render;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ResolvableProfile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.chat.ContentChatTemplate;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production row-seam coverage: the paint path resolves each row's encoded
 * display value into the count-one icon stack and localized name through the
 * shared {@link ClientItemDisplay} projection, while a missing or failed value
 * keeps an empty icon and the registry translation key.
 */
class SpatialInventoryRowDisplayTest {

	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@BeforeEach void clearCache() { ClientItemDisplay.reset(); }

	@Test void aggregateQualityUsesAllVariantsWhileExactBareDisplayHasNoQualityLabel() {
		assertEquals("pingforit.spatial.inventory.all_variants", SpatialInventoryView.Status.COMPONENT_TOO_LONG.translationKey());
		assertTrue(SpatialInventoryView.Status.COMPONENT_TOO_LONG.grey());
		assertNull(row("minecraft:stone", "block.minecraft.stone", null).quality().translationKey());
	}

	private static String json(ItemStack stack) {
		return ItemStack.SINGLE_ITEM_CODEC
			.encodeStart(RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), stack)
			.result().orElseThrow().toString();
	}

	private static SpatialInventoryView.Row row(String itemId, String label, String displayJson) {
		return new SpatialInventoryView.Row("row", label, 64L, itemId, displayJson, SpatialInventoryView.Status.READY);
	}

	@Test
	void rowSeamCarriesTransmittedComponentsIntoTheCountOneIconStack() {
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		sword.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(7));

		ClientItemDisplay.Display display = SpatialOverlayRenderer.rowDisplay(
			row("minecraft:diamond_sword", "item.minecraft.diamond_sword", json(sword)), RegistryAccess.EMPTY);
		assertTrue(display.stack().hasFoil(), "the glint override reaches the icon's native glint decision");
		assertEquals(new CustomModelData(7), display.stack().get(DataComponents.CUSTOM_MODEL_DATA));
		assertEquals(1, display.stack().getCount(), "the icon stack never carries the authoritative long quantity");
		assertEquals("item.minecraft.diamond_sword", contents(display.name()).getKey());
	}

	@Test
	void rowSeamUsesTheSharedQuotedCustomNameComposition() {
		ItemStack named = new ItemStack(Items.GUNPOWDER);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Named{root}"));

		ClientItemDisplay.Display display = SpatialOverlayRenderer.rowDisplay(
			row("minecraft:gunpowder", "item.minecraft.gunpowder", json(named)), RegistryAccess.EMPTY);
		TranslatableContents composed = contents(display.name());
		assertEquals(ContentChatTemplate.CUSTOM_ITEM_NAME_KEY, composed.getKey());
		TranslatableContents quoted = contents((Component) composed.getArgs()[0]);
		assertEquals(ContentChatTemplate.QUOTE_KEY, quoted.getKey());
		assertEquals("Named{root}", ((Component) quoted.getArgs()[0]).getString(),
			"the transmitted custom text is flattened plain text");
		assertEquals("item.minecraft.gunpowder", contents((Component) composed.getArgs()[1]).getKey(),
			"the trusted base stays a registry translation key");
	}

	@Test
	void rowSeamKeepsTheRegistryKeyWithoutAPrototypeIconOrRawData() {
		for (String malformed : List.of("{not json", "{\"id\":\"pingforit:missing_item\"}")) {
			ClientItemDisplay.Display display = SpatialOverlayRenderer.rowDisplay(
				row("minecraft:diamond_sword", "item.minecraft.diamond_sword", malformed), RegistryAccess.EMPTY);
			assertTrue(display.stack().isEmpty(), malformed);
			assertEquals("item.minecraft.diamond_sword", contents(display.name()).getKey(), malformed);
		}

		ClientItemDisplay.Display missing = SpatialOverlayRenderer.rowDisplay(
			row("minecraft:diamond_sword", "item.minecraft.diamond_sword", null), RegistryAccess.EMPTY);
		assertTrue(missing.stack().isEmpty());
		assertEquals("item.minecraft.diamond_sword", contents(missing.name()).getKey(),
			"a row without encoded display data keeps the registry translation key fallback");
	}

	@Test
	void rowSeamHandsNativeRenderingAnOwnedStackThatCannotCorruptTheNextFrame() {
		ResolvableProfile profile = new ResolvableProfile(Optional.of("Herobrine"), Optional.of(UUID.randomUUID()), new PropertyMap());
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		head.set(DataComponents.PROFILE, profile);
		String encoded = json(head);

		ClientItemDisplay.Display first = SpatialOverlayRenderer.rowDisplay(
			row("minecraft:player_head", "item.minecraft.player_head", encoded), RegistryAccess.EMPTY);
		ClientItemDisplay.Display second = SpatialOverlayRenderer.rowDisplay(
			row("minecraft:player_head", "item.minecraft.player_head", encoded), RegistryAccess.EMPTY);
		assertNotSame(first.stack(), second.stack(), "every row paint gets its own working stack");
		assertEquals(1, first.stack().getCount());

		// Native player-head rendering removes and asynchronously replaces
		// PROFILE on the stack it was handed.
		first.stack().remove(DataComponents.PROFILE);
		assertNotNull(second.stack().get(DataComponents.PROFILE), "the other frame's working stack is untouched");
		assertNotNull(SpatialOverlayRenderer.rowDisplay(
			row("minecraft:player_head", "item.minecraft.player_head", encoded), RegistryAccess.EMPTY)
			.stack().get(DataComponents.PROFILE), "the next frame still receives the transmitted profile");
	}

	private static TranslatableContents contents(Component component) {
		assertInstanceOf(TranslatableContents.class, component.getContents(), component::getString);
		return (TranslatableContents) component.getContents();
	}
}
