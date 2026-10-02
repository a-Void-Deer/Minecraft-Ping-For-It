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
			"pingforit.spatial.inventory.component_too_long", "pingforit.spatial.inventory.expired"), keys);
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
