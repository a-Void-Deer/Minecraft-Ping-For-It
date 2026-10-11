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
	void axisConfinementPreservesPointerSelectionAndSubrowTravelAtEitherBoundary() {
		for (double sign : new double[] {-1, 1}) {
			var model = listWithRows(40); model.select(20);
			var gesture = new InventoryGesture(model, 1, false, 20);
			gesture.begin(sign * 100, 0, leftBack());
			gesture.moveGui(0, 39);
			int window = model.windowFirst();
			var viewport = new SpatialController.Viewport(100, 100, 0, 0);
			gesture.constrainAxis(viewport);
			assertEquals(sign < 0 ? -50 : 49, gesture.axisX());
			assertEquals(sign * 100, gesture.pointerX(), "axis correction alone never moves the pointer");
			assertEquals(39, gesture.pointerY());
			assertEquals(20, model.selectedIndex()); assertEquals(window, model.windowFirst());
			gesture.alignPointer(gesture.axisX(), 39);
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0, 1));
			assertEquals(21, model.selectedIndex(), "projection alignment does not clear the almost-row fraction");
		}
	}

	@Test
	void axisConfinementPreservesSignedWheelUnlockAndOrdinaryWheelFraction() {
		var model = listWithRows(40); model.select(20);
		var gesture = new InventoryGesture(model, 1, false, 20);
		gesture.begin(-100, 0, leftBack()); gesture.wheelRows(1.5);
		gesture.moveGui(0, 10); gesture.moveGui(0, -4); // signed unlock travel = +6
		gesture.constrainAxis(new SpatialController.Viewport(100, 100, 0, 0));
		gesture.alignPointer(-50, 6);
		gesture.moveGui(0, 7); // +13 must remain locked
		assertEquals(21, model.selectedIndex());
		gesture.moveGui(0, 1); // +14 unlocks; this sample contributes 0.5 glide pixels
		gesture.moveGui(0, 38);
		assertEquals(21, model.selectedIndex());
		gesture.moveGui(0, 1);
		assertEquals(22, model.selectedIndex(), "axis alignment preserves the signed unlock remainder");
		assertEquals(1, gesture.wheelRows(0.5), "ordinary-wheel half-row survives axis correction and glide");
		assertEquals(23, model.selectedIndex());
	}

	@Test
	void axisConfinementKeepsAReachableAxisInsteadOfFollowingIntentionalPointerOffset() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1, false, 20);
		gesture.begin(0, 0, leftBack()); gesture.moveGui(20, 0);
		for (int frame = 0; frame < 3; frame++) {
			gesture.constrainAxis(new SpatialController.Viewport(100, 100, 0, 0));
			gesture.alignPointer(20, 0);
			assertEquals(0, gesture.axisX());
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0, 0, 1000));
			assertEquals(0, model.selectedIndex(), "the established reachable side corridor remains in force");
		}
		gesture.moveGui(-20, 0); gesture.moveGui(0, 40);
		assertEquals(1, model.selectedIndex());
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
	void relativeGlideUsesFrozenRowHeightAndWheelLocksUntilUnscaledThreshold() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());

		gesture.move(0.0, 39.0);
		assertEquals(0, model.selectedIndex());
		gesture.move(0.0, 1.0);
		assertEquals(1, model.selectedIndex(), "40 native GUI pixels supply the frozen 20px row threshold at 1x");

		assertEquals(2, gesture.wheelRows(2));
		assertEquals(3, model.selectedIndex());

		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 10.0));
		assertEquals(3, model.selectedIndex(), "wheel lock ignores sub-threshold movement");

		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 4.0));
		assertEquals(3, model.selectedIndex(), "the unlock sample seeds the new glide fraction");

		gesture.move(0.0, 36.0);
		assertEquals(4, model.selectedIndex());
	}

	@Test
	void horizontalNonCentralMovementSuppressesGlide() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 1.0, false, 20.0);
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
		model.select(20);
		InventoryGesture gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());
		gesture.move(0.0, 79.0);
		assertEquals(21, model.selectedIndex());
		assertEquals("k21", model.selectedKey());
		int window = model.windowFirst();

		gesture.rebase(100.0, 200.0);

		assertEquals(100.0, gesture.axisX(), DELTA);
		assertEquals(100.0, gesture.pointerX(), DELTA);
		assertEquals(200.0, gesture.pointerY(), DELTA);
		assertEquals(21, model.selectedIndex(), "the model keeps its selection through a rebase");
		assertEquals("k21", model.selectedKey());
		assertEquals(window, model.windowFirst());
		gesture.move(0.0, 1.0);
		assertEquals(21, model.selectedIndex(), "rebase discards the former almost-whole row");

		assertEquals(1, gesture.wheelRows(1));
		assertEquals(22, model.selectedIndex());
	}

	@Test
	void reversalClearsOpposingSubrowTravelButZeroDoesNot() {
		var model = listWithRows(40);
		model.select(20);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0, 500, leftBack());
		gesture.move(0, 36);
		gesture.move(0, -4);
		assertEquals(20, model.selectedIndex());
		gesture.move(0, -36);
		assertEquals(19, model.selectedIndex(), "opposing 18px is cleared before the new direction accumulates");
		gesture.move(0, -36);
		gesture.move(0, 0);
		gesture.move(0, -4);
		assertEquals(18, model.selectedIndex(), "zero samples do not clear a same-direction remainder");
		gesture.move(0, -36);
		gesture.move(0, 4);
		gesture.move(0, 36);
		assertEquals(19, model.selectedIndex(), "both reversal directions discard the opposing fraction");
	}

	@Test
	void multiplierScalesRelativeTravelWithoutChangingTheNativeRowHeight() {
		for (double multiplier : new double[] {2.0, 0.25}) {
			var model = listWithRows(40);
			var gesture = new InventoryGesture(model, multiplier, false, 20.0);
			gesture.begin(0, -500, leftBack());
			double threshold = multiplier == 2.0 ? 20.0 : 160.0;
			gesture.move(0, threshold - 1);
			assertEquals(0, model.selectedIndex());
			gesture.move(0, 1);
			assertEquals(1, model.selectedIndex());
		}
	}

	@Test
	void constantDirectionIsIndependentOfBatchingStartingYAndAlignedPointerY() {
		for (double sign : new double[] {-1, 1}) {
			for (double startY : new double[] {-900, 0, 900}) {
				var split = listWithRows(40); var batch = listWithRows(40);
				split.select(20); batch.select(20);
				var splitGesture = new InventoryGesture(split, 1.0, false, 20.0);
				var batchGesture = new InventoryGesture(batch, 1.0, false, 20.0);
				splitGesture.begin(0, startY, leftBack()); batchGesture.begin(0, startY, leftBack());
				for (double delta : new double[] {20, 31, 29, 77, 84}) {
					splitGesture.alignPointer(0, startY * 2);
					splitGesture.move(0, sign * delta);
				}
				batchGesture.move(0, sign * 241);
				assertEquals(20 + (int) sign * 6, batch.selectedIndex());
				assertEquals(batch.selectedIndex(), split.selectedIndex());
				splitGesture.move(0, sign * 39); batchGesture.move(0, sign * 39);
				assertEquals(20 + (int) sign * 7, split.selectedIndex());
				assertEquals(batch.selectedIndex(), split.selectedIndex(), "equal whole rows also retain equal sub-row travel");
			}
		}
	}

	@Test
	void attemptedRowsAtEndpointsNeverBankOvershootEvenForHugeFiniteTravel() {
		for (double travel : new double[] {80000, Double.MAX_VALUE}) {
			var model = listWithRows(40); model.select(20);
			var gesture = new InventoryGesture(model, 3.0, false, 20.0);
			gesture.begin(0, 0, leftBack());
			gesture.move(0, 0, travel);
			assertEquals(39, model.selectedIndex());
			gesture.move(0, 0, -80);
			assertEquals(33, model.selectedIndex(), "the first reverse row travel is never paid against overshoot");
			gesture.move(0, 0, -travel);
			assertEquals(0, model.selectedIndex());
			gesture.move(0, 0, 80);
			assertEquals(6, model.selectedIndex());
			assertEquals(0, gesture.pointerY());
		}
	}

	@Test
	void independentSelectionTravelCannotTriggerNavigationAfterPointerAlignment() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0, 0, leftBack());
		for (double x : new double[] {-100, 100}) {
			gesture.alignPointer(x, 0);
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0, 0, 800));
			assertEquals(0, model.selectedIndex(), "the established side-hint suppression remains in force");
		}
	}

	@Test
	void wheelUnlockUsesSignedUnscaledTravelWithoutDirectionClearing() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 2.0, false, 20.0);
		gesture.begin(0, 0, leftBack()); gesture.wheelRows(2);
		gesture.move(0, 0, 10);
		gesture.move(0, 0, -10);
		gesture.move(0, 0, -10);
		gesture.move(0, 0, -4); // signed net -14 unlocks; only this sample glides
		assertEquals(2, model.selectedIndex());
		gesture.move(0, 0, -15);
		assertEquals(2, model.selectedIndex());
		gesture.move(0, 0, -1);
		assertEquals(1, model.selectedIndex());
	}

	@Test
	void backHoverSuppressionClearsPartialWheelUnlockTravelWithoutUnlocking() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, true, 20.0);
		gesture.begin(0, 0, leftBack()); gesture.wheelRows(2);
		gesture.moveGui(0, 10);
		gesture.moveGui(-30, 500);
		assertEquals(2, model.selectedIndex());
		gesture.moveGui(30, 0);
		gesture.moveGui(0, 13);
		gesture.moveGui(0, 1); // only this sample unlocks, not the former 10px
		gesture.moveGui(0, 38);
		assertEquals(2, model.selectedIndex());
		gesture.moveGui(0, 1);
		assertEquals(3, model.selectedIndex());
	}

	@Test
	void ordinaryWheelRetainsItsSignedFractionAcrossReversalAndGlideSamples() {
		var model = listWithRows(40); model.select(20);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0, 0, leftBack());
		assertEquals(0, gesture.wheelPixels(76));
		assertEquals(0, gesture.wheelPixels(-4));
		gesture.move(0, 39); // independent almost-row glide fraction
		assertEquals(0, gesture.wheelPixels(27));
		assertEquals(1, gesture.wheelPixels(1));
		assertEquals(21, model.selectedIndex(), "wheel cancels signed travel normally instead of using glide reversal rules");
	}

	@Test
	void nonfiniteMovementIsInertIncludingIndependentSelectionTravel() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, true, 20.0);
		gesture.begin(0, 0, leftBack()); gesture.moveGui(0, 39);
		for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(bad, 0, 1));
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0, bad, 1));
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0, 1, bad));
			assertEquals(0, gesture.pointerX()); assertEquals(39, gesture.pointerY());
			assertEquals(0, model.selectedIndex());
		}
		gesture.moveGui(0, 1);
		assertEquals(1, model.selectedIndex(), "invalid samples do not poison the accumulated fraction");
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
