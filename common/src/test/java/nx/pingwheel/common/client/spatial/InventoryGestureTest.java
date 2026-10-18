package nx.pingwheel.common.client.spatial;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic tests for {@link InventoryGesture}. Cases drive explicit raw
 * GUI samples and assert emitted navigation actions, glide row movement,
 * accumulator saturation/reversal and frozen-axis behavior. No renderer,
 * timestamps, or Minecraft state is involved.
 */
class InventoryGestureTest {

	private static final double DELTA = 1.0e-9;
	private static final double CONE_TANGENT = Math.tan(Math.toRadians(25.0));

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
	void rowMetricRelayoutKeepsGlideFractionSideProgressAndWheelLock() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0, 0, leftBack());
		gesture.move(0, 20); // half a row under the old metric
		gesture.move(10, 0);
		gesture.updateRowHeight(40);
		assertEquals(10, gesture.horizontalTravel());
		assertEquals(0, model.selectedIndex());
		gesture.move(0, 40); // remaining half under the new metric
		assertEquals(1, model.selectedIndex());
		gesture.wheelRows(0.75);
		gesture.updateRowHeight(30);
		assertEquals(1, gesture.wheelRows(0.25), "fractional wheel input survives independently");
		gesture.move(0, 10); // partial signed wheel unlock
		gesture.updateRowHeight(40);
		gesture.move(0, 3);
		assertEquals(2, model.selectedIndex(), "relayout did not clear the wheel lock");
		gesture.move(0, 1); // unlock at 14; this sample alone contributes half a pixel
		gesture.move(0, 79);
		assertEquals(3, model.selectedIndex(), "relayout preserved partial unlock travel");
	}

	@Test
	void physicalSamplesKeepBothLogicalPointerAxesWhileLongVerticalTravelGlides() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(120.0, -40.0, leftBack());

		for (int i = 0; i < 20; i++) {
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0.0, 39.0));
		}

		assertEquals(120.0, gesture.pointerX(), DELTA, "physical list input never moves the logical pointer x");
		assertEquals(-40.0, gesture.pointerY(), DELTA, "physical list input never moves the logical pointer y");
		assertEquals(120.0, gesture.axisX(), DELTA);
		assertEquals(0.0, gesture.horizontalTravel(), DELTA);
		assertEquals(19, model.selectedIndex(), "780px of vertical travel glides at the frozen 20px row threshold");

		assertEquals(InventoryGesture.Action.FORWARD, gesture.moveGui(200.0, 0.0));
		assertEquals(120.0, gesture.pointerX(), DELTA, "the horizontal gate accumulates without pointer travel");
		assertEquals(-40.0, gesture.pointerY(), DELTA);
	}

	@Test
	void inclusiveConeIgnoresInsideOrExactDxInBothDirectionsAndPreservesPriorProgress() {
		for (double sign : new double[] {-1, 1}) {
			var model = listWithRows(40);
			var gesture = new InventoryGesture(model, 1.0, false, 20.0);
			gesture.begin(0.0, 0.0, leftBack());
			assertEquals(InventoryGesture.Action.NONE, gesture.move(sign * 10.0, 0.0));
			assertEquals(sign * 10.0, gesture.horizontalTravel(), DELTA);

			double exact = 100.0 * CONE_TANGENT;
			assertEquals(InventoryGesture.Action.NONE, gesture.move(sign * (exact - 0.001), 100.0));
			assertEquals(sign * 10.0, gesture.horizontalTravel(), DELTA, "inside-cone dx is ignored and prior progress survives");
			assertEquals(InventoryGesture.Action.NONE, gesture.move(sign * exact, 100.0));
			assertEquals(sign * 10.0, gesture.horizontalTravel(), DELTA, "the inclusive bound still ignores dx");
			assertEquals(InventoryGesture.Action.NONE, gesture.move(sign * (exact + 1.0), 100.0));
			assertEquals(sign * (10.0 + exact + 1.0), gesture.horizontalTravel(), DELTA, "outside-cone dx accumulates");
		}
	}

	@Test
	void verticalOnlyTravelCannotNavigateOrCrossEitherSideGate() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());

		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 1000.0));
		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, -2000.0));
		assertEquals(0.0, gesture.horizontalTravel(), DELTA, "vertical-only samples never accumulate side travel");
		assertFalse(gesture.isBackSideFocused());
	}

	@Test
	void saturatedExitDiscardsOvershootAndAllowsImmediateReversalWithoutDebt() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());

		assertEquals(InventoryGesture.Action.FORWARD, gesture.move(1000.0, 0.0));
		assertEquals(76.0, gesture.horizontalTravel(), DELTA, "the exit threshold bounds the accumulator");
		assertEquals(InventoryGesture.Action.NONE, gesture.move(10.0, 0.0), "an outward saturated sample is not effective");
		assertEquals(76.0, gesture.horizontalTravel(), DELTA);
		assertEquals(InventoryGesture.Action.NONE, gesture.move(-1.0, 0.0), "reversal acts at once from the saturated bound");
		assertEquals(75.0, gesture.horizontalTravel(), DELTA);
		assertEquals(InventoryGesture.Action.BACK, gesture.move(-151.0, 0.0));
		assertEquals(-76.0, gesture.horizontalTravel(), DELTA);
	}

	@Test
	void independentBoundedSaturationsReachBothGatesWithoutCrossingTheOther() {
		for (double sign : new double[] {-1, 1}) {
			var model = listWithRows(40);
			var gesture = new InventoryGesture(model, 1.0, false, 20.0);
			gesture.begin(0.0, 0.0, leftBack());

			InventoryGesture.Action saturated = sign > 0 ? InventoryGesture.Action.FORWARD : InventoryGesture.Action.BACK;
			InventoryGesture.Action opposite = sign > 0 ? InventoryGesture.Action.BACK : InventoryGesture.Action.FORWARD;
			assertEquals(saturated, gesture.move(sign * 1000.0, 0.0));
			assertEquals(sign * 76.0, gesture.horizontalTravel(), DELTA);
			assertEquals(InventoryGesture.Action.NONE, gesture.move(-sign * 1.0, 0.0));
			assertEquals(sign * 75.0, gesture.horizontalTravel(), DELTA, "the opposite gate is not reachable in one reversal pixel");
			assertEquals(opposite, gesture.move(-sign * 151.0, 0.0));
			assertEquals(-sign * 76.0, gesture.horizontalTravel(), DELTA);
		}
	}

	@Test
	void glideBlockingOutsideTheHintCorridorUsesTheAccumulatorNotThePointer() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());

		gesture.alignPointer(5000.0, 5000.0);
		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 1000.0));
		assertTrue(model.selectedIndex() > 0, "an imported pointer never creates the hint corridor");

		gesture.move(20.0, 0.0);
		int corridorBlocked = model.selectedIndex();
		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 1000.0));
		assertEquals(corridorBlocked, model.selectedIndex(), "accumulated progress beyond the hint gate blocks glide");

		gesture.alignPointer(-5000.0, -5000.0);
		assertEquals(InventoryGesture.Action.NONE, gesture.move(-1.0, 0.0));
		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 1000.0));
		assertTrue(model.selectedIndex() > corridorBlocked, "only the accumulator returning inside the corridor restores glide");
	}

	@Test
	void backHoverSuppressionComesFromTheAccumulatorAndReleasesOnReversal() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, true, 20.0);
		gesture.begin(0.0, 0.0, leftBack());

		assertEquals(InventoryGesture.Action.NONE, gesture.move(-50.0, 0.0));
		assertTrue(gesture.isBackSideFocused());
		assertEquals(InventoryGesture.Action.NONE, gesture.move(0.0, 1000.0));
		assertEquals(0, model.selectedIndex(), "Back hover suppresses glide");
		assertEquals(0, gesture.wheelRows(3), "Back hover suppresses wheel");
		assertEquals(0, model.selectedIndex());

		assertEquals(InventoryGesture.Action.NONE, gesture.move(60.0, 0.0));
		assertFalse(gesture.isBackSideFocused(), "reversal below the hint gate releases Back focus");
		assertEquals(3, gesture.wheelRows(3));
		assertEquals(3, model.selectedIndex());
	}

	@Test
	void wheelUnlockClearsPartialUnlockTravelWhenBackFocusResetsIt() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, true, 20.0);
		gesture.begin(0.0, 0.0, leftBack());
		gesture.wheelRows(2);
		gesture.move(-50.0, 0.0); // genuine Back focus resets the partial unlock without unlocking
		gesture.move(-30.0, 500.0);
		assertEquals(2, model.selectedIndex());
		gesture.move(50.0, 0.0); // leave Back: the former 10px of unlock travel was discarded
		gesture.move(0.0, 13.0);
		gesture.move(0.0, 1.0); // only this sample unlocks, not the former travel
		gesture.move(0.0, 38.0);
		assertEquals(2, model.selectedIndex());
		gesture.move(0.0, 1.0);
		assertEquals(3, model.selectedIndex());
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
	void nearVerticalTravelGlidesWhileOffAxisTravelSuppressesGlide() {
		InventoryListModel model = listWithRows(40);
		InventoryGesture gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());

		gesture.move(25.0, 100.0); // inside the inclusive cone: dx ignored, dy glides
		assertEquals(2, model.selectedIndex());

		gesture.begin(0.0, 0.0, leftBack());
		gesture.move(25.0, 50.0); // outside the cone: progress beyond the hint gate suppresses glide
		assertEquals(2, model.selectedIndex(), "gesture rebase preserves selection and off-axis travel adds no rows");
		assertEquals(25.0, gesture.horizontalTravel(), DELTA);
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
	void rebaseResetsAxisFractionsAndProgressButKeepsSelection() {
		InventoryListModel model = listWithRows(40);
		model.select(20);
		InventoryGesture gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0.0, 0.0, leftBack());
		gesture.move(0.0, 79.0);
		assertEquals(21, model.selectedIndex());
		assertEquals("k21", model.selectedKey());
		int window = model.windowFirst();

		gesture.move(50.0, 0.0);
		assertEquals(50.0, gesture.horizontalTravel(), DELTA);
		gesture.rebase(100.0, 200.0);

		assertEquals(100.0, gesture.axisX(), DELTA);
		assertEquals(100.0, gesture.pointerX(), DELTA);
		assertEquals(200.0, gesture.pointerY(), DELTA);
		assertEquals(0.0, gesture.horizontalTravel(), DELTA, "rebase resets the horizontal accumulator");
		assertFalse(gesture.isBackSideFocused());
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
	void independentSelectionTravelCannotTriggerNavigationRegardlessOfPointer() {
		var model = listWithRows(40);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(0, 0, leftBack());
		for (double x : new double[] {-100, 100}) {
			int before = model.selectedIndex();
			gesture.alignPointer(x, 0);
			assertEquals(InventoryGesture.Action.NONE, gesture.moveGui(0, 0, 800));
			assertEquals(0.0, gesture.horizontalTravel(), DELTA, "an imported pointer never becomes side travel");
			assertTrue(model.selectedIndex() > before, "vertical selection travel glides without any side navigation");
		}
	}

	@Test
	void projectionAxisCorrectionPreservesProgressUnlockAndSubrowFraction() {
		var model = listWithRows(40); model.select(20);
		var gesture = new InventoryGesture(model, 1.0, false, 20.0);
		gesture.begin(100.0, 0.0, leftBack());
		gesture.wheelRows(1);      // wheel locked, selection 21
		gesture.move(0.0, 10.0);   // partial signed unlock travel
		gesture.move(30.0, 0.0);   // retain side progress after the admitted unlock sample

		gesture.constrainAxis(new SpatialController.Viewport(100, 100, 0, 0));
		assertEquals(49.0, gesture.axisX(), DELTA);
		assertEquals(100.0, gesture.pointerX(), DELTA, "projection never moves the pointer copy");
		assertEquals(30.0, gesture.horizontalTravel(), DELTA, "projection never resets horizontal progress");

		gesture.move(-30.0, 0.0);  // progress returns inside the corridor; the 10px unlock travel survives
		gesture.move(0.0, 4.0);    // 14 unlocks; this sample seeds only its own 2px glide
		gesture.move(0.0, 38.0);   // 2 + 19 = 21px crosses one frozen row
		assertEquals(22, model.selectedIndex());
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
			assertEquals(0.0, gesture.pointerX()); assertEquals(0.0, gesture.pointerY());
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
