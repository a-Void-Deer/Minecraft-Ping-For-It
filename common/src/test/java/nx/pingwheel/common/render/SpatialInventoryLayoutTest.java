package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.InventoryListModel;
import nx.pingwheel.common.render.SpatialInventoryView.Row;
import nx.pingwheel.common.render.SpatialInventoryView.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Arithmetic fixtures are supplied independently of the renderer's font/tuning. */
class SpatialInventoryLayoutTest {

	private static List<Row> rows(int count) {
		return IntStream.range(0, count).mapToObj(i -> new Row("r" + i, "row" + i, (long) i, null, Status.READY)).toList();
	}

	@Test
	void nativeMetricsKeepNaturalContentAndLimitOnlyTheAvailableWindow() {
		var metrics = SpatialInventoryLayout.measure(640, 360, 9, 1);
		assertTrue(metrics.width() < 128, "an empty content measure does not fill the available width");
		assertEquals(7, metrics.visibleRows(), "seven complete readable rows fit between the header and footer");
		for (int size : new int[] {0, 2, 30}) {
			var view = new SpatialInventoryView("list", true, rows(size), size == 0 ? -1 : 0, 0, 30,
				true, Status.READY, 0, 0);
			var layout = metrics.layout(view, 320, 180);
			assertEquals(Math.min(size, 7), layout.count());
			assertEquals(26 + 15 + Math.min(size, 7) * 22, layout.height());
			assertTrue(layout.height() <= 216, "content height stays within its GUI budget");
		}
		assertEquals(metrics.width(), SpatialInventoryLayout.measure(1600, 1000, 9, 1).width(),
			"a large screen does not grow an empty natural box");
	}

	private static SpatialInventoryView content(String name) {
		var row = new Row("name", "unused-fallback", 1L, null, null, Status.READY, null,
			new SpatialInventoryView.RowDisplay(net.minecraft.network.chat.Component.literal(name)));
		return new SpatialInventoryView("list", true, List.of(row), 0, 0, 5, true, Status.READY, 0, 0)
			.withNavigation(null, false, true);
	}

	@Test
	void actualDetachedContentDrivesWidthBeforeTheViewportCapAndDoesNotTrackTheSelectedWindow() {
		java.util.function.ToIntFunction<net.minecraft.network.chat.FormattedText> widths = text -> text.getString().length() * 6;
		var empty = new SpatialInventoryView("empty", true, List.of(), -1, 0, 5, true, Status.READY, 0, 0)
			.withNavigation(null, false, false);
		var shortView = content("Short item");
		var middleView = content("A moderately long detached item name");
		var longView = content("A long detached display name that can use more than the historical fixed panel width");
		double emptyWidth = SpatialInventoryLayout.measure(empty, 1600, 1000, 9, 1, widths).width();
		double shortWidth = SpatialInventoryLayout.measure(shortView, 1600, 1000, 9, 1, widths).width();
		double middleWidth = SpatialInventoryLayout.measure(middleView, 1600, 1000, 9, 1, widths).width();
		double longWidth = SpatialInventoryLayout.measure(longView, 1600, 1000, 9, 1, widths).width();
		assertTrue(emptyWidth < shortWidth && shortWidth < middleWidth && middleWidth < longWidth);
		assertTrue(middleWidth > 200 && middleWidth < 320, "room above the old width is used naturally, not filled to a new fixed maximum");
		assertTrue(longWidth > 200 && longWidth <= 320, "long content uses the room beyond the old fixed width");
		assertEquals(320, longWidth, "only the real viewport maximum clips this long name");
		Row longRow = longView.rows().getFirst();
		var distinctLongRow = new Row("long", longRow.label(), longRow.count(), longRow.itemId(), longRow.displayJson(),
			longRow.quality(), longRow.outlineColor(), longRow.display());
		var allRows = new SpatialInventoryView("list", true, List.of(shortView.rows().getFirst(), distinctLongRow),
			0, 0, 1, true, Status.READY, 0, 0).withNavigation(null, false, true);
		var nextWindow = new SpatialInventoryView("list", true, allRows.rows(), 1, 1, 1, true, Status.READY, 0, 0)
			.withNavigation(null, false, true);
		assertEquals(SpatialInventoryLayout.measure(allRows, 1600, 1000, 9, 1, widths),
			SpatialInventoryLayout.measure(nextWindow, 1600, 1000, 9, 1, widths), "bounded full-row measurement cannot jitter with scrolling");
		assertEquals(SpatialInventoryLayout.measure(80, 60, 9, 1).width(),
			SpatialInventoryLayout.measure(longView, 80, 60, 9, 1, widths).width(), "an impossible tiny cap keeps the readable floor");
	}

	@Test
	void countHeaderAndFooterChromeContributeTheirUnscaledNaturalWidths() {
		java.util.function.ToIntFunction<net.minecraft.network.chat.FormattedText> widths = text -> {
			if (text.getString().equals("pingforit.spatial.inventory.forward")) return 40;
			if (text.getString().equals(Status.UPDATING.translationKey())) return 110;
			return text.getString().length() * 6;
		};
		var shortView = content("Short item");
		double shortWidth = SpatialInventoryLayout.measure(shortView, 1600, 1000, 9, 1, widths).width();
		var footer = shortView.withNavigation(null, true, true);
		var retained = shortView.withNavigation("name", false, false);
		assertTrue(SpatialInventoryLayout.measure(footer, 1600, 1000, 9, 1, widths).width() > shortWidth);
		assertEquals(SpatialInventoryLayout.measure(footer, 1600, 1000, 9, 1, widths).width(),
			SpatialInventoryLayout.measure(retained, 1600, 1000, 9, 1, widths).width(), "item retention reserves the same Forward chrome");
		var status = new SpatialInventoryView("list", true, shortView.rows(), 0, 0, 1, true, Status.UPDATING, 0, 0)
			.withNavigation(null, false, true);
		assertTrue(SpatialInventoryLayout.measure(status, 1600, 1000, 9, 1, widths).width() > shortWidth);
		Row row = shortView.rows().getFirst();
		var quantity = new Row(row.key(), row.label(), Long.MAX_VALUE, row.itemId(), row.displayJson(), row.quality(), null, row.display());
		var quantityView = new SpatialInventoryView("list", true, List.of(quantity), 0, 0, 1, true, Status.READY, 0, 0)
			.withNavigation(null, false, true);
		double quantityWidth = SpatialInventoryLayout.measure(quantityView, 1600, 1000, 9, 1, widths).width();
		assertTrue(quantityWidth > shortWidth);
		assertTrue(quantityWidth >= widths.applyAsInt(SpatialInventoryLayout.quantity(Long.MAX_VALUE)) * 2,
			"natural width can show the whole full-sized quantity in its half-row slot");
	}

	@Test
	void positionHeaderReservesTheWidestActualLabelBeforeAnySelectionScrollsToIt() {
		java.util.function.ToIntFunction<net.minecraft.network.chat.FormattedText> widths = text -> {
			String value = text.getString();
			if (value.contains(" / ")) return value.startsWith("1 / ") ? 120 : 30;
			return value.length() * 6;
		};
		var view = new SpatialInventoryView("header", true, rows(12), 11, 11, 1, true, Status.READY, 0, 0)
			.withNavigation(null, false, true);
		var metrics = SpatialInventoryLayout.measure(view, 1600, 1000, 9, 1, widths);
		assertTrue(metrics.width() - 8 >= widths.applyAsInt(net.minecraft.network.chat.Component.literal("1 / 12")),
			"proportional digit widths cannot make the first position wider than the reserved header");
		assertEquals(metrics, SpatialInventoryLayout.measure(view.withWindow(0, 1), 1600, 1000, 9, 1, widths),
			"header demand does not follow the current viewport window");
	}

	@Test
	void tinyGuiKeepsReadableIconTextAndOneWholeRowInsteadOfShrinkingThePattern() {
		var normal = SpatialInventoryLayout.measure(640, 360, 9, 1);
		var tiny = SpatialInventoryLayout.measure(80, 60, 9, 1);
		var view = new SpatialInventoryView("list", true, rows(30), 15, 0, 30, false, Status.READY, 7, -3);
		var layout = tiny.layout(view, 40, 30);
		assertEquals(1, tiny.visibleRows());
		assertEquals(normal.rowHeight(), layout.rowHeight());
		assertEquals(normal.headerHeight(), layout.headerHeight());
		assertEquals(normal.footerHeight(), layout.footerHeight());
		assertEquals(50, layout.width(), "icon plus existing row insets/gap and two readable line-height glyph cells");
		assertEquals(63, layout.height(), "one complete row and natural chrome take precedence over an impossible cap");
		assertEquals(15, layout.first());
		assertTrue(Double.isFinite(layout.rowCenterY(15)));
		assertEquals(47, layout.centerX(), "no panel-only viewport clamp changes the rigid pattern");
	}

	@Test
	void retainedAnchorSurvivesCapacityAndFontRelayout() {
		var view = new SpatialInventoryView("list", true, rows(30), 15, 7, 17, true, Status.READY, 19, -10)
			.withSelectedRowCenterY(27).withNavigation("r15", false, false);
		for (var metrics : List.of(SpatialInventoryLayout.measure(1000, 700, 9, 1),
			SpatialInventoryLayout.measure(320, 240, 9, 1), SpatialInventoryLayout.measure(320, 240, 14, 1.25))) {
			var layout = metrics.layout(view, 160, 120);
			assertEquals(147, layout.rowCenterY(15), "the frozen row anchor, not its old row slot, owns the item origin");
			assertEquals(179, layout.centerX());
			assertTrue(layout.count() <= metrics.visibleRows(), "the anchor never bypasses the measured capacity");
		}
	}

	@Test
	void layoutUsesCallerWindowAndViewportAndReturnsAbsoluteGuiRowY() {
		var view = new SpatialInventoryView("list", true, rows(12), 6, 4, 5, true, Status.READY, 30.0, -10.0);
		var layout = SpatialInventoryLayout.of(view, 320.0, 180.0, 200.0, 20.0, 10.0, 10.0);
		assertEquals(4, layout.first());
		assertEquals(5, layout.count());
		assertEquals(350.0, layout.centerX());
		assertEquals(170.0, layout.centerY());
		assertEquals(80.0, layout.height());
		// Panel top 130, header end 150, selected third row occupies [170, 180].
		assertEquals(175.0, layout.rowCenterY(6));
		assertEquals(155.0, layout.rowCenterY(4));
		assertEquals(195.0, layout.rowCenterY(8));
		assertTrue(Double.isNaN(layout.rowCenterY(3)));
		assertTrue(Double.isNaN(layout.rowCenterY(9)));
		var anchor = new SpatialOverlayRenderer.RowLayout(layout.rowCenterY(6), layout.width());
		assertEquals(-5.0, anchor.controllerY(360.0));
		assertEquals(175.0, anchor.centerY(), "conversion never mutates or double-converts absolute geometry");
	}

	@Test
	void oversizedViewportAndEndWindowClampOnlyToPresentedData() {
		var view = new SpatialInventoryView("list", true, rows(3), 2, 2, 30, false, Status.UPDATING, 0.0, 0.0);
		var layout = SpatialInventoryLayout.of(view, 100.0, 100.0, 180.0, 20.0, 16.0, 10.0);
		assertEquals(2, layout.first());
		assertEquals(1, layout.count());
		assertEquals(105.0, layout.rowCenterY(2));
		assertTrue(Double.isNaN(layout.rowCenterY(1)));
	}

	@Test
	void nativeLogicalRowAnchorIsCentreRelativeAndConvertedToAbsoluteOnce() {
		var view = new SpatialInventoryView("list", true, rows(12), 6, 4, 5, true, Status.READY, 30.0, -10.0)
			.withSelectedRowCenterY(25.0);
		var layout = SpatialInventoryLayout.of(view, 320.0, 180.0, 200.0, 20.0, 10.0, 10.0);
		assertEquals(205.0, layout.rowCenterY(6));
		assertEquals(185.0, layout.rowCenterY(4));
		assertEquals(225.0, layout.rowCenterY(8));
		var anchor = new SpatialOverlayRenderer.RowLayout(layout.rowCenterY(6), layout.width());
		assertEquals(25.0, anchor.controllerY(360.0));
	}

	@Test
	void emptyViewsKeepPanelAndStatusButInventNoSelectedRow() {
		var view = new SpatialInventoryView("empty", true, List.of(), -1, 0, 4, true, Status.UNAVAILABLE, 0.0, 0.0);
		var layout = SpatialInventoryLayout.of(view, 100.0, 100.0, 180.0, 20.0, 16.0, 10.0);
		assertEquals(0, layout.count());
		assertEquals(30.0, layout.height());
		assertTrue(Double.isNaN(layout.rowCenterY(0)));
		assertEquals(Status.UNAVAILABLE, view.status());
	}

	@Test
	void encodedDisplaySurvivesBothCompatibilityProjectionsWithoutChangingTheOldApi() {
		var model = new InventoryListModel(2);
		model.open(List.of(new InventoryListModel.Entry("a", "A", 4L, "READY"),
			new InventoryListModel.Entry("b", "B", 3L, "UNCERTAIN")), 30.0, -10.0, 90.0);
		String encoded = "{\"id\":\"minecraft:stone\"}";

		var legacy = SpatialOverlayRenderer.inventoryView("preview:42", model, Map.of("a", "minecraft:stone"), Status.READY);
		assertNull(legacy.rows().getFirst().displayJson(), "the compatibility form keeps its native-name fallback");

		var carried = SpatialOverlayRenderer.inventoryView("preview:42", model, Map.of("a", "minecraft:stone"),
			Map.of("a", encoded), Status.READY);
		assertEquals("minecraft:stone", carried.rows().getFirst().itemId());
		assertEquals(encoded, carried.rows().getFirst().displayJson());
		assertNull(carried.rows().get(1).displayJson(), "a row without a supplied value keeps the fallback");

		var snapshot = SpatialOverlayRenderer.inventoryView("preview:43", model.snapshot(), 2,
			Map.of("a", "minecraft:stone"), Map.of("a", encoded), Status.READY);
		assertEquals(encoded, snapshot.rows().getFirst().displayJson());
		assertEquals(2, snapshot.visibleRows());
	}

	@Test
	void modelProjectionDoesNotSortRecountOrChooseItsOwnViewport() {
		var model = new InventoryListModel(2);
		model.open(List.of(new InventoryListModel.Entry("a", "A", 4L, "READY"),
			new InventoryListModel.Entry("b", "B", 3L, "UNCERTAIN"),
			new InventoryListModel.Entry("c", "C", 0L, "COMPONENT_TOO_LONG")), 30.0, -10.0, 90.0);
		model.select(2);
		model.updateCount("a", 1L);
		var view = SpatialOverlayRenderer.inventoryView("preview:42", model, Map.of("c", "minecraft:stone"), Status.INCOMPLETE);
		assertEquals(List.of("a", "b", "c"), view.rows().stream().map(Row::key).toList());
		assertEquals(2, view.visibleRows());
		assertEquals(1, view.windowFirst());
		assertEquals(2, view.selectedIndex());
		assertFalse(view.backLeft());
		assertEquals(0L, view.rows().get(2).count());
		assertEquals("minecraft:stone", view.rows().get(2).itemId());
		assertEquals(Status.COMPONENT_TOO_LONG, view.rows().get(2).quality());
		assertEquals(Status.INCOMPLETE, view.status());
		assertEquals(3, model.size());
	}

	@Test
	void statusIsSeparateFromRowsAndUnknownCountIsNotZero() {
		var input = new ArrayList<>(List.of(new Row("missing", "Missing", null, null, Status.UNKNOWN),
			new Row("zero", "Observed", 0L, null, Status.UNCERTAIN)));
		var view = new SpatialInventoryView("list", true, input, 0, 0, 5, true, Status.UPDATING, 0.0, 0.0);
		input.clear();
		assertEquals(2, view.rows().size());
		assertNull(view.rows().getFirst().count());
		assertEquals(0L, view.rows().get(1).count());
		assertEquals(Status.UPDATING, view.status());
		assertEquals(Status.UNCERTAIN, view.rows().get(1).quality());
		assertThrows(UnsupportedOperationException.class, () -> view.rows().clear());
	}

	@Test
	void allNonReadyStatusesHaveDistinctLocalizedLabelsAndGreyQualitySurvivesNameMapping() {
		Set<String> keys = List.of(Status.UPDATING, Status.UNKNOWN, Status.UNCERTAIN, Status.INCOMPLETE,
			Status.UNAVAILABLE, Status.INVALID, Status.COMPONENT_TOO_LONG, Status.EXPIRED).stream()
			.map(Status::translationKey).collect(Collectors.toSet());
		assertEquals(Set.of("pingforit.spatial.inventory.updating", "pingforit.spatial.inventory.unknown",
			"pingforit.spatial.inventory.uncertain", "pingforit.spatial.inventory.incomplete",
			"pingforit.spatial.inventory.unavailable", "pingforit.spatial.inventory.invalid",
			"pingforit.spatial.inventory.all_variants", "pingforit.spatial.inventory.expired"), keys);
		assertNull(Status.READY.translationKey());
		assertEquals(Status.UNKNOWN, Status.fromName(null));
		assertEquals(Status.UNKNOWN, Status.fromName("unsupported-quality"));
		assertEquals(Status.READY, Status.fromName("complete"));
		assertEquals(Status.UNCERTAIN, Status.fromName("uncertain"));
		assertTrue(Status.fromName("uncertain").grey());
		assertTrue(Status.fromName("component_too_long").grey());
		assertFalse(Status.READY.grey());
	}
}
