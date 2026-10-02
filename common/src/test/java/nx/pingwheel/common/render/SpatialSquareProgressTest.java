package nx.pingwheel.common.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialSquareProgressTest {
	@Test
	void perimeterWalkUsesStraightConnectedSegmentsAndOnlyThePresentedProgress() {
		assertEquals(List.of(new SpatialSquareProgress.Segment(10, 20, 30, 20),
			new SpatialSquareProgress.Segment(30, 20, 30, 30)), SpatialSquareProgress.segments(10, 20, 20, 0.375));
		assertEquals(List.of(new SpatialSquareProgress.Segment(10, 20, 30, 20),
			new SpatialSquareProgress.Segment(30, 20, 30, 40), new SpatialSquareProgress.Segment(30, 40, 10, 40),
			new SpatialSquareProgress.Segment(10, 40, 10, 20)), SpatialSquareProgress.segments(10, 20, 20, 1.0));
		assertTrue(SpatialSquareProgress.segments(10, 20, 20, 0.0).isEmpty());
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
