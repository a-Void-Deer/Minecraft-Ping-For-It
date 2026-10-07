package nx.pingwheel.common.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialSquareProgressTest {
	@Test
	void perimeterWalkUsesStraightConnectedSegmentsAndOnlyThePresentedProgress() {
		// A size-20 frame at (10,20) paints columns 10..29 and rows 20..39:
		// strokeRect's right and bottom bounds are exclusive, so the path ends
		// on the last painted column and row, 29 and 39, not 30 and 40.
		assertEquals(List.of(new SpatialSquareProgress.Segment(10, 20, 29, 20),
			new SpatialSquareProgress.Segment(29, 20, 29, 29.5)), SpatialSquareProgress.segments(10, 20, 20, 0.375));
		assertEquals(List.of(new SpatialSquareProgress.Segment(10, 20, 29, 20),
			new SpatialSquareProgress.Segment(29, 20, 29, 39), new SpatialSquareProgress.Segment(29, 39, 10, 39),
			new SpatialSquareProgress.Segment(10, 39, 10, 20)), SpatialSquareProgress.segments(10, 20, 20, 1.0));
		assertTrue(SpatialSquareProgress.segments(10, 20, 20, 0.0).isEmpty());
		assertTrue(SpatialSquareProgress.segments(10, 20, 1, 1.0).isEmpty());
	}

	@Test
	void immutableBackAffordanceDoesNotCreateProgressWithoutFocusAndPreservesRowAnchor() {
		var view = new SpatialInventoryView("list", true, List.of(), -1, 0, 4, false,
			SpatialInventoryView.Status.UNKNOWN, 30, 40).withSelectedRowCenterY(25);
		var focused = view.withBackAffordance(true, 0.375);
		assertTrue(focused.backAffordance().focused());
		assertEquals(0.375, focused.backAffordance().progress());
		assertEquals(25, focused.selectedRowCenterY());
		assertFalse(focused.backLeft());
		assertFalse(view.backAffordance().focused());
		assertEquals(0.0, focused.withBackAffordance(false, 0.9).backAffordance().progress());
		assertEquals(1.0, focused.withBackAffordance(true, 2.0).backAffordance().progress());
		assertEquals(0.0, focused.withBackAffordance(true, Double.NaN).backAffordance().progress());
	}
}
