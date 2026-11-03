package nx.pingwheel.common.render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import net.minecraft.client.StringSplitter;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.config.ClientConfigBounds;
import nx.pingwheel.common.presentation.inventory.InventoryResourceKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Records the same fixed-scale text submissions native RowPaint sends to its scissored draw. */
@NotNullByDefault
class SpatialInventoryQuantityPriorityTest {
	private static final StringSplitter FONT = new StringSplitter((point, style) -> point == '.' ? 2f : point == ' ' ? 4f : 6f);
	private static int width(FormattedText text) { return (int) Math.ceil(FONT.stringWidth(text)); }

	@NotNullByDefault
	private record Draw(SpatialOverlayRenderer.InventoryText text, double x, double y) {}

	private static @Unmodifiable List<Draw> paint(SpatialInventoryView.Row row, double panelWidth, double scale) {
		var draws = new ArrayList<Draw>();
		var style = new SpatialOverlayRenderer.Style(100, 100, 1, scale, 140, 1, false, true);
		var paint = new SpatialOverlayRenderer.RowPaint(row, panelWidth, 22, true, false);
		// Native paintRow reserves this unchanged 16px icon and 3px gap before text submission.
		double textLeft = Math.round(-panelWidth / 2) + 5 + 16 + 3;
		SpatialOverlayRenderer.paintRowText(paint, Component.literal(row.label()), textLeft, style, 9,
			SpatialInventoryQuantityPriorityTest::width, FONT, (text, x, y, color) -> draws.add(new Draw(text, x, y)));
		return List.copyOf(draws);
	}

	private static SpatialInventoryView.Row row(InventoryResourceKind kind, String name, long amount) {
		return new SpatialInventoryView.Row(kind, "opaque", name, amount, null, null,
			SpatialInventoryView.Status.READY, null, null);
	}

	@Test void defaultSmallGuiReservesTheComplete42PixelQuantityBeforeTheWaterName() throws IOException {
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(english()));
		try {
			var water = row(InventoryResourceKind.FLUID, "Water", 999945);
			var view = new SpatialInventoryView("list", true, List.of(water), 0, 0, 1, true,
				SpatialInventoryView.Status.READY, 0, 0);
			var metrics = SpatialInventoryLayout.measure(view, 320, 240, 9, 1, SpatialInventoryQuantityPriorityTest::width);
			assertEquals(96, metrics.width(), "the supported small GUI reaches the ordinary panel width cap");
			assertEquals("12.345 B", SpatialInventoryLayout.quantity(water).getString());
			assertEquals(42, width(SpatialInventoryLayout.quantity(water)));
			var draws = paint(water, metrics.width(), 1);
			assertEquals("12.345 B", draws.get(1).text().text().getString(), "all digits and the bucket unit must reach text submission");
			assertEquals(42, draws.get(1).text().width());
			assertEquals(22, draws.getFirst().text().width(), "68px total minus 42px quantity and the 4px gap");
			assertEquals("Wa…", draws.getFirst().text().text().getString());
			assertEquals(44, draws.get(1).x() + draws.get(1).text().width());
		} finally { Language.inject(previous); }
	}

	@Test void approximateSubMillibucketAndLocalizedLargeUnitsRemainCompleteAcrossScales() throws IOException {
		Language previous = Language.getInstance();
		var translations = english();
		translations.put("pingforit.inventory.quantity.western_12", "%s trillion buckets");
		Language.inject(new MapLanguage(translations));
		try {
			for (long amount : new long[] {1, 82, 999945, Long.MAX_VALUE}) {
				var water = row(InventoryResourceKind.FLUID, "A descriptive water variant name", amount);
				String quantity = SpatialInventoryLayout.quantity(water).getString();
				if (amount == 1) assertTrue(quantity.contains("<1 mB"));
				if (amount == 82) assertTrue(quantity.contains("≈"));
				if (amount == Long.MAX_VALUE) assertTrue(quantity.contains("trillion buckets"));
				for (double scale : new double[] {0.75, 1, 1.5, 2}) {
					double quantityWidth = width(SpatialInventoryLayout.quantity(water)) * scale;
					// Leave less than half the row to the name but enough for its ellipsis.
					double panelWidth = 28 + Math.ceil(quantityWidth + 4 + 18 * scale);
					var draws = paint(water, panelWidth, scale);
					assertEquals(quantity, draws.get(1).text().text().getString());
					assertEquals(scale, draws.get(1).text().scale());
					assertEquals(quantityWidth, draws.get(1).text().width());
					assertTrue(draws.getFirst().text().text().getString().endsWith("…"));
					assertTrue(draws.getFirst().x() + draws.getFirst().text().width() <= draws.get(1).x() - 4);
				}
			}
		} finally { Language.inject(previous); }
	}

	@Test void supported170PercentRowSubmitsTheCompleteQuantityWithoutRoundTripClipping() throws IOException {
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(english()));
		try {
			assertEquals(170, ClientConfigBounds.clampWheelTargetFontSize(170));
			var draws = paint(row(InventoryResourceKind.FLUID, "Water", 999945), 120, 1.7);
			var quantity = draws.get(1).text();
			assertEquals("12.345 B", quantity.text().getString(), "the allocated scaled width fits all 42 font pixels");
			assertEquals(42 * 1.7, quantity.width());
			assertTrue(width(quantity.text()) * quantity.scale() <= quantity.width());
			assertEquals(1.7, quantity.scale());
			assertTrue(draws.getFirst().text().width() > 0, "descriptive text gets only the remaining space");
			assertTrue(draws.getFirst().x() + draws.getFirst().text().width() <= draws.get(1).x() - 4);
		} finally { Language.inject(previous); }
	}

	@ParameterizedTest
	@ValueSource(ints = {10, 75, 100, 110, 130, 170, 230, 1000})
	void supportedFractionalScalesKeepWholeQuantityOnlyWhenItsScaledWidthFits(int percent) throws IOException {
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(english()));
		try {
			assertEquals(percent, ClientConfigBounds.clampWheelTargetFontSize(percent));
			double scale = percent / 100.0;
			Component quantity = Component.literal("12.345 B");
			assertEquals(42, width(quantity));
			double needed = 42 * scale;
			var exact = SpatialOverlayRenderer.inventoryText(quantity, needed, scale,
				SpatialInventoryQuantityPriorityTest::width, FONT);
			assertEquals(quantity.getString(), exact.text().getString());
			assertEquals(needed, exact.width());
			assertTrue(width(exact.text()) * scale <= exact.width());
			for (double cap : new double[] {Math.nextDown(needed), needed - 0.1 * scale}) {
				var clipped = SpatialOverlayRenderer.inventoryText(quantity, cap, scale,
					SpatialInventoryQuantityPriorityTest::width, FONT);
				assertNotEquals(quantity.getString(), clipped.text().getString(), "even a just-too-small slot must not expand");
				assertTrue(clipped.text().getString().endsWith("…"));
				assertEquals(cap, clipped.width());
				assertTrue(width(clipped.text()) * scale <= cap);
			}
			var draws = paint(row(InventoryResourceKind.FLUID, "Water", 999945), Math.max(120, Math.ceil(needed) + 28), scale);
			assertEquals(quantity.getString(), draws.get(1).text().text().getString());
			assertTrue(width(draws.get(1).text().text()) * scale <= draws.get(1).text().width());
		} finally { Language.inject(previous); }
	}

	@Test void anOverfullQuantityUsesTheWholeBoundedSlotAfterTheNameHasYielded() throws IOException {
		Language previous = Language.getInstance();
		Language.inject(new MapLanguage(english()));
		try {
			var water = row(InventoryResourceKind.FLUID, "Water", Long.MAX_VALUE);
			var draws = paint(water, 48, 2);
			assertEquals("", draws.getFirst().text().text().getString());
			assertEquals(0, draws.getFirst().text().width());
			assertEquals(20, draws.get(1).text().width(), "the row's entire bounded text slot is offered before clipping");
			assertTrue(width(draws.get(1).text().text()) * 2 <= 20);
			assertEquals(2, draws.get(1).text().scale(), "no implicit adaptive font or panel expansion");
		} finally { Language.inject(previous); }
	}

	@Test void healthyItemRowsRetainTheirExistingHalfRowQuantityAllocation() {
		var draws = paint(row(InventoryResourceKind.ITEM, "Stone", Long.MAX_VALUE), 96, 1);
		assertEquals(34, draws.get(1).text().width());
		assertEquals("x922…", draws.get(1).text().text().getString());
		assertEquals("Stone", draws.getFirst().text().text().getString());
		assertEquals(1, draws.get(1).text().scale());
	}

	private static Map<String, String> english() throws IOException {
		try (var stream = SpatialInventoryQuantityPriorityTest.class.getResourceAsStream("/assets/pingforit/lang/en_us.json")) {
			assertNotNull(stream);
			var translations = new java.util.HashMap<String, String>();
			JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
				.getAsJsonObject().entrySet().forEach(entry -> translations.put(entry.getKey(), entry.getValue().getAsString()));
			return translations;
		}
	}

	@NotNullByDefault
	private static final class MapLanguage extends Language {
		private final @Unmodifiable Map<String, String> translations;
		MapLanguage(Map<String, String> translations) { this.translations = Map.copyOf(translations); }
		@Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
		@Override public boolean has(String key) { return translations.containsKey(key); }
		@Override public boolean isDefaultRightToLeft() { return false; }
		@Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}
}
