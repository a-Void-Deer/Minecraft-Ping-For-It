package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link InventoryGesture}. Cases drive explicit
 * pointer/wheel samples and assert the emitted navigation actions, glide row
 * movement and frozen-axis behavior. No renderer, timestamps, or Minecraft
 * state is involved.
 */
class InventoryGestureTest {

	private static final double DELTA = 1.0e-9;

	private static InventoryListModel listWithRows(int rows) {
		List<InventoryListModel.Entry> entries = new ArrayList<>();

		for (int i = 0; i < rows; i++) {
			entries.add(new InventoryListModel.Entry("k" + i, "label", rows - i, "common"));
		}

		InventoryListModel model = new InventoryListModel(5);
		model.open(entries, 0.0, 0.0, 90.0);
		return model;
	}

	/** Back left / forward right, the vertical-parent fallback. */
	private static InventoryListModel.Direction leftBack() {
		return InventoryListModel.directionFor(180.0);
	}

	@Test
	void forwardGateEmitsForwardAndHoverDisablesDistanceBack() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, false);
		gesture.begin(0.0, 0.0, leftBack());

		assertEquals(InventoryGesture.Action.NONE, gesture.move(30.0, 0.0), "hint region must not glide");
		assertEquals(InventoryGesture.Action.FORWARD, gesture.move(60.0, 0.0));
		assertEquals(0, model.selectedIndex(), "a side gate never changes rows by itself");

		gesture.begin(0.0, 0.0, leftBack());
		assertEquals(InventoryGesture.Action.NONE, gesture.move(-30.0, 0.0));
		assertEquals(InventoryGesture.Action.BACK, gesture.move(-60.0, 0.0));
	}

	@Test
	void hoverEnabledSuppressesDistanceBackButKeepsForward() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, true);

		gesture.begin(0.0, 0.0, leftBack());
		assertEquals(InventoryGesture.Action.NONE, gesture.move(-90.0, 0.0), "hover owns Back instead of distance");

		gesture.begin(0.0, 0.0, leftBack());
		assertEquals(InventoryGesture.Action.FORWARD, gesture.move(90.0, 0.0));
	}

	@Test
	void backHoverFocusSuppressesGlideAndWheel() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, true);
		gesture.begin(0.0, 0.0, leftBack());
		gesture.setBackHoverFocused(true);

		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 200.0));
		assertEquals(0, model.selectedIndex());
		assertEquals(0, gesture.wheelRows(3));
		assertEquals(0, model.selectedIndex());

		gesture.setBackHoverFocused(false);
		assertEquals(3, gesture.wheelRows(3));
		assertEquals(3, model.selectedIndex());
	}

	@Test
	void verticalGlideMovesRowsAndWheelLocksUntilThreshold() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, false);
		gesture.begin(0.0, 0.0, leftBack());

		gesture.move(0.0, 50.0);
		assertEquals(5, model.selectedIndex(), "glide gain converts the first vertical stroke");

		assertEquals(2, gesture.wheelRows(2));
		assertEquals(7, model.selectedIndex());

		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 10.0));
		assertEquals(7, model.selectedIndex(), "wheel lock ignores sub-threshold movement");

		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 5.0));
		assertEquals(7, model.selectedIndex(), "unlock movement may not reach a whole row yet");

		gesture.move(0.0, 20.0);
		assertEquals(10, model.selectedIndex());
	}

	@Test
	void horizontalNonCentralMovementSuppressesGlide() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, false);
		gesture.begin(0.0, 0.0, leftBack());

		gesture.move(25.0, 100.0);
		assertEquals(0, model.selectedIndex(), "off-axis motion must not glide rows");

		gesture.begin(0.0, 0.0, leftBack());
		gesture.move(0.0, 100.0);
		assertTrue(model.selectedIndex() > 0);
	}

	@Test
	void wheelPixelsConvertAtHundredPixelsPerRow() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, false);
		gesture.begin(0.0, 0.0, leftBack());

		assertEquals(2, gesture.wheelPixels(250.0));
		assertEquals(2, model.selectedIndex());

		assertEquals(1, gesture.wheelPixels(60.0));
		assertEquals(3, model.selectedIndex());

		gesture.setBackHoverFocused(true);
		assertEquals(0, gesture.wheelPixels(500.0));
		assertEquals(3, model.selectedIndex());
	}

	@Test
	void rebaseResetsAxisAndFractionsButKeepsSelection() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, false);
		gesture.begin(0.0, 0.0, leftBack());
		gesture.move(0.0, 50.0);
		assertEquals(5, model.selectedIndex());

		gesture.rebase(100.0, 200.0);

		assertEquals(100.0, gesture.axisX(), DELTA);
		assertEquals(200.0, gesture.glideBaseY(), DELTA);
		assertEquals(100.0, gesture.pointerX(), DELTA);
		assertEquals(200.0, gesture.pointerY(), DELTA);
		assertEquals(5, model.selectedIndex(), "the model keeps its selection through a rebase");

		assertEquals(1, gesture.wheelRows(1));
		assertEquals(6, model.selectedIndex());
	}

	@Test
	void directionPairDrivesWhichSideIsBack() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 0.25, false);
		InventoryListModel.Direction rightBack = InventoryListModel.directionFor(90.0);

		gesture.begin(0.0, 0.0, rightBack);
		assertEquals(InventoryGesture.Action.BACK, gesture.move(90.0, 0.0));

		gesture.begin(0.0, 0.0, rightBack);
		assertEquals(InventoryGesture.Action.FORWARD, gesture.move(-90.0, 0.0));
	}
}
