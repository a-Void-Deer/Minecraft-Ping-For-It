package nx.pingwheel.common.presentation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryItemPingTypesTest {
	private static final String CHEST = "minecraft:chest";

	@Test
	void actualChestTagGrantsExactlyOneTakeAndAMissingTagFailsClosed() {
		var policy = InventoryItemPingTypes.builtIn();

		assertEquals(List.of("attention", "request", "take"), policy.effective(CHEST, Set.of("c:chests")));
		assertTrue(policy.allows("take", CHEST, Set.of("c:chests")));

		// A client-expected registry or tag claim alone is never the server's
		// actual evidence: without the actual tag the item policy fails closed.
		assertEquals(List.of("attention", "danger"), policy.effective(CHEST, Set.of()));
		assertFalse(policy.allows("take", CHEST, Set.of()));
		assertFalse(policy.allows("take", CHEST, Set.of("create:chests")));
		// A registry ID that merely spells the tag name is not a tag match.
		assertFalse(policy.allows("take", "c:chests", Set.of()));
	}

	@Test
	void nonChestItemsAndRegularPropertiesKeepTheirExistingTypes() {
		var items = InventoryItemPingTypes.builtIn();
		assertEquals(List.of("attention", "danger"), items.effective("minecraft:barrel", Set.of()));
		assertFalse(items.allows("take", "minecraft:barrel", Set.of()));

		var properties = PresentationPropertyPingTypes.builtIn();
		assertEquals(List.of("attention", "request"), properties.effective(CHEST, Set.of("c:chests")));
		assertFalse(properties.allows("take", CHEST, Set.of("c:chests")),
			"take is inventory-only and stays out of the regular property policy");
	}
}
