package nx.pingwheel.common.render;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.network.InventoryS2CPacket.Status;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.client.ClientItemDisplay;

import static org.junit.jupiter.api.Assertions.*;

class InventoryTrackingRendererTest {
	private static ClientInventory.EntryView entry(String label, long count, boolean fallback, Status quality) {
		return new ClientInventory.EntryView("opaque:" + label, "minecraft:stone", label, null, count, fallback, quality, "request");
	}
	@Test void onlyReceivedTrackingDataSuppliesBoundedItemLinesAndNeverSynthesizesZero() {
		assertTrue(InventoryTrackingRenderer.lines(ClientInventory.Tracking.empty(new MarkerId(1))).isEmpty());
		var projection = new ClientInventory.Tracking(new MarkerId(1), Status.READY, false, true, 1, 1, 1,
			List.of(entry(" ", 10, false, null), entry("A".repeat(100), -1, false, null), entry("B", 0, false, null),
				entry("C", 3, false, null), entry("D", 4, false, null)));
		var lines = InventoryTrackingRenderer.lines(projection);
		assertEquals(3, lines.size()); assertEquals(64, lines.getFirst().label().length()); assertNull(lines.getFirst().count());
		assertEquals(0L, lines.get(1).count()); assertEquals("request", lines.getFirst().pingType()); assertFalse(lines.getFirst().grey());
	}
	@Test void incompleteInvalidAndComponentFallbackKeepCountsAndExplicitGreyStatus() {
		for (Status status : List.of(Status.INCOMPLETE, Status.INVALID, Status.UNCERTAIN)) {
			var projection = new ClientInventory.Tracking(new MarkerId(1), status, true, false, 1, 2, 1,
				List.of(entry("Stone", 7, false, null), entry("Folded", 8, true, null)));
			var lines = InventoryTrackingRenderer.lines(projection);
			assertEquals(7L, lines.getFirst().count()); assertTrue(lines.getFirst().grey());
			assertEquals(SpatialInventoryView.Status.fromName(status.name()).translationKey(), lines.getFirst().statusKey());
			assertEquals(SpatialInventoryView.Status.COMPONENT_TOO_LONG.translationKey(), lines.get(1).statusKey()); assertTrue(lines.get(1).grey());
		}
	}
	@Test void trackingAnnotationResolvesTheSharedLocalizedNamePolicyNotTheRawLabel() throws IOException {
		withLanguage(Map.of("item.minecraft.stone", "Stone", "item.minecraft.gunpowder", "Gunpowder"), () -> {
			var projection = new ClientInventory.Tracking(new MarkerId(1), Status.READY, false, true, 1, 1, 1,
				List.of(new ClientInventory.EntryView("opaque:stone", "minecraft:stone", "item.minecraft.stone",
						null, 5, false, null, "request"),
					new ClientInventory.EntryView("opaque:named", "minecraft:gunpowder", "item.minecraft.gunpowder",
						"{\"id\":\"minecraft:gunpowder\"}", 2, false, null, "request")));
			var lines = InventoryTrackingRenderer.lines(projection);
			assertEquals("Stone", lines.getFirst().label(),
				"the annotation resolves the registry localized name, never the raw translation key");
			assertEquals("Gunpowder", lines.get(1).label(),
				"without a client registry context the row keeps the registry translation key fallback, never raw JSON");
			assertFalse(lines.get(1).label().contains("{"), "malformed or undecodable display data never leaks raw JSON");
			assertEquals(5L, lines.getFirst().count());
			assertEquals(ClientItemDisplay.resolve(null, "minecraft:stone", "item.minecraft.stone", null).name().getString(),
				lines.getFirst().label(), "the annotation and the inventory row share one name projection");
		});
	}

	private static void withLanguage(Map<String, String> translations, ThrowingRunnable body) throws IOException {
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(translations));
		try {
			body.run();
		} finally {
			Language.inject(previous);
		}
	}

	private static final class MapLanguage extends Language {
		private final Map<String, String> translations;
		private MapLanguage(Map<String, String> translations) { this.translations = translations; }
		@Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
		@Override public boolean has(String key) { return translations.containsKey(key); }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run();
	}
}
