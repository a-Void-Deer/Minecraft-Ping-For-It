package nx.pingwheel.common.render;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialLabelAvoidanceTest {
	private static SpatialLabelAvoidance.Label label(String id, double x, double y, boolean movable, boolean focused) {
		return new SpatialLabelAvoidance.Label(id, x, y, -40, -8, 40, 8, 0, 180, movable, focused);
	}

	@Test void separatesShallowOverlapsAndKeepsAStableSingleAxisSolution() {
		var model = new SpatialLabelAvoidance();
		var labels = List.of(label("a", 60, -10, true, false), label("b", 60, 0, true, false), label("c", 60, 30, true, false));
		var offsets = model.place("menu", labels);
		for (int i = 0; i < labels.size(); i++) for (int j = i + 1; j < labels.size(); j++) {
			double a = labels.get(i).y() + offsets.get(labels.get(i).id());
			double b = labels.get(j).y() + offsets.get(labels.get(j).id());
			assertTrue(Math.abs(a - b) >= 16, "complete title boxes are disjoint");
		}
		for (int i = 0; i < 100; i++) assertEquals(offsets, model.place("menu", labels));
	}

	@Test void focusedAndProtectedNodesStayFixedAndTinyPointerJitterDoesNotFlipNeighbors() {
		var model = new SpatialLabelAvoidance();
		var labels = List.of(label("focus", 60, 0, false, true), label("other", 60, 10, true, false));
		var first = model.place("menu", labels);
		assertEquals(0, first.get("focus"));
		assertTrue(10 + first.get("other") >= 16);
		for (int i = 0; i < 40; i++) {
			var next = model.place("menu", List.of(label("focus", 60, i % 2 * 0.05, false, true), labels.getLast()));
			assertEquals(first.get("other"), next.get("other"));
		}
	}

	@Test void impossibleCrowdsStayBoundedAndCentersRemainInsideTheirOriginalSector() {
		var model = new SpatialLabelAvoidance();
		var labels = List.of(new SpatialLabelAvoidance.Label("a", 40, 0, -200, -100, 200, 100, 85, 10, true, false),
			new SpatialLabelAvoidance.Label("b", 40, 1, -200, -100, 200, 100, 85, 10, true, false));
		var offsets = model.place("dense", labels);
		for (var label : labels) {
			double dy = offsets.get(label.id());
			assertTrue(Math.abs(dy) <= 24);
			double bearing = Math.toDegrees(Math.atan2(label.x(), -label.y() - dy));
			assertTrue(bearing >= 85 && bearing < 95);
		}
		for (int i = 0; i < 100; i++) assertEquals(offsets, model.place("dense", labels));
		model.clear();
		assertEquals(new SpatialLabelAvoidance().place("dense", labels), model.place("dense", labels));
		assertEquals(0, model.place("new-menu", List.of(labels.getFirst())).get("a"));
	}
}
