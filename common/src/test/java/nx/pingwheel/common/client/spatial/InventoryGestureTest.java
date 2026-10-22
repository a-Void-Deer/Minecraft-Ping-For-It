package nx.pingwheel.common.client.spatial;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Independent GUI endpoints, panel boundaries and raw-sample oracles; no native cursor or renderer. */
class InventoryGestureTest {
	private static final double EPS = 1e-9;
	// Default fixture viewport 401x101, view offset (-30, 17): controller-space pixel centres.
	private static final double SCREEN_LEFT = -170.5;
	private static final double SCREEN_RIGHT = 229.5;
	private static final class Fixture {
		final InventoryListModel list = new InventoryListModel(5);
		final InventoryGesture gesture;
		final SpatialController controller;
		SpatialController.Viewport viewport = new SpatialController.Viewport(401, 101, -30, 17);
		double left = -50, right = 50;
		Fixture(int count, boolean hover, boolean mirrored) {
			this(count, hover, mirrored, 24, 50);
		}
		Fixture(int count, boolean hover, boolean mirrored, int deadzone, int stroke) {
			list.open(IntStream.range(0, count).mapToObj(i -> new InventoryListModel.Entry("k" + i, "Item", count - i, "ready")).toList(),
				0, 0, mirrored ? 90 : 180);
			gesture = new InventoryGesture(list, deadzone, stroke, hover);
			gesture.begin(list.direction());
			controller = new SpatialController(SpatialMenu.of("root"),
				new SpatialController.Tuning(deadzone, stroke, 100, false, 250));
			controller.start(0);
			controller.enterExternal(SpatialMenu.of("list"), 0, 0, 0);
			controller.useExternalSurface();
			controller.constrainPointer(viewport, 0);
			sync();
		}
		void sync() { controller.alignExternalPointer(controller.snapshot().pointer().x(), gesture.selectedY(viewport), 0); }
		SpatialController.Point pointer() { return controller.snapshot().pointer(); }
		InventoryGesture.Action move(double dx, double dy) {
			dx = gesture.horizontalDelta(dx, dy);
			double before = pointer().x();
			controller.moveExternalPointerWithoutTrail(dx, 0, 1);
			var action = gesture.moveHorizontal(dx, before, pointer().x(), left, right, viewport);
			double admitted = gesture.verticalDelta(dy);
			var effective = controller.moveExternalPointerWithoutTrail(0, admitted, 1);
			if (effective.y() != 0) gesture.selectAt(pointer().y(), viewport);
			return action;
		}
		boolean wheel(double rows) {
			if (!gesture.wheelRows(rows)) return false;
			sync();
			return true;
		}
		void moveToScreenY(double targetScreenY) { move(0, targetScreenY - screenY()); }
		double screenY() { return pointer().y() + viewport.centerY() + viewport.viewY(); }
	}

	@Test void absoluteMappingUsesFullListAndInclusiveGuiEndpointsMonotonically() {
		for (int count : new int[] {2, 7, 101, 300}) {
			var f = new Fixture(count, false, false);
			assertEquals(0, f.list.selectedIndex()); assertEquals(0, f.screenY(), EPS);
			int prior = 0;
			for (int y = 1; y <= 100; y++) {
				f.move(0, 1);
				assertTrue(f.list.selectedIndex() >= prior);
				if (count == 101) assertEquals(y, f.list.selectedIndex(), "one row per screen pixel");
				assertEquals(y, f.screenY(), EPS, "selection does not quantize the pointer");
				prior = f.list.selectedIndex();
			}
			assertEquals(count - 1, prior);
			f.move(0, Double.MAX_VALUE); f.move(0, -1);
			assertEquals(99, f.screenY(), EPS, "endpoint overshoot is discarded");
		}

		var two = new Fixture(2, false, false);
		two.moveToScreenY(49); assertEquals(0, two.list.selectedIndex());
		two.moveToScreenY(50); assertEquals(1, two.list.selectedIndex());
		two.moveToScreenY(100); assertEquals(1, two.list.selectedIndex());

		// Literal nearest-row boundaries for N=7 across the inclusive 0..100 pixel centres.
		var seven = new Fixture(7, false, false);
		int[][] literalBoundaries = {
			{0, 0}, {8, 0}, {9, 1}, {24, 1}, {25, 2}, {41, 2}, {42, 3},
			{58, 3}, {59, 4}, {74, 4}, {75, 5}, {91, 5}, {92, 6}, {100, 6}
		};
		for (int[] boundary : literalBoundaries) {
			seven.moveToScreenY(boundary[0]);
			assertEquals(boundary[1], seven.list.selectedIndex(),
				"literal N=7 boundary at screen y=" + boundary[0]);
			assertEquals(boundary[0], seven.screenY(), EPS, "pointer remains at the literal screen coordinate");
		}
	}

	@Test void splitSmallSamplesPreserveSubrowPointerTravelAndMatchBatch() {
		var split = new Fixture(7, false, false); var batch = new Fixture(7, false, false);
		for (int i = 0; i < 180; i++) split.move(0, 0.25);
		batch.move(0, 45);
		assertEquals(batch.pointer(), split.pointer());
		assertEquals(batch.list.selectedKey(), split.list.selectedKey());
		assertEquals(45, split.screenY(), EPS);
	}

	@Test void emptySingleAndSinglePixelViewportNeverDivideByZeroOrInventSelection() {
		var empty = new Fixture(0, false, false); empty.move(0, 80);
		assertNull(empty.list.selectedKey());
		var single = new Fixture(1, false, false); single.move(0, Double.MAX_VALUE);
		assertEquals("k0", single.list.selectedKey()); assertEquals(100, single.screenY(), EPS);
		single.viewport = new SpatialController.Viewport(1, 1, 9, -3);
		single.controller.constrainPointer(single.viewport, 2); single.sync();
		single.move(0, Double.MAX_VALUE); single.move(0, -Double.MAX_VALUE);
		assertEquals("k0", single.list.selectedKey());
		assertEquals(single.pointer(), single.viewport.confine(single.pointer()));
	}

	@Test void wheelSynchronizesRepresentativeRetainsSignedFractionsAndOnlyAdmitsUnlockExcess() {
		var f = new Fixture(11, false, false);
		f.wheel(0.75); f.wheel(-0.25); f.wheel(0.5);
		assertEquals(1, f.list.selectedIndex()); assertEquals(10, f.screenY(), EPS);
		f.move(5, 10); f.move(0, -4); // signed net +6; X remains live
		assertEquals(5, f.pointer().x(), EPS); assertEquals(10, f.screenY(), EPS);
		f.move(0, 12); // net +18: only four pixels move
		assertEquals(14, f.screenY(), EPS);
		f.move(0, -1); assertEquals(13, f.screenY(), EPS);
		f.wheel(1); f.move(0, -14); // exact unlock admits zero, but releases the lock
		assertEquals(20, f.screenY(), EPS);
		f.move(0, 1); assertEquals(21, f.screenY(), EPS);
	}

	@Test void batchedAndSplitWheelUnlockSamplesPayTheSameFourteenPixelToll() {
		var split = new Fixture(11, false, false); var batch = new Fixture(11, false, false);
		split.wheel(5); batch.wheel(5);
		for (double dy : new double[] {10, -5, 4, 16}) split.move(0, dy);
		batch.move(0, 25);
		assertEquals(batch.pointer(), split.pointer()); assertEquals(61, split.screenY(), EPS);
	}

	@Test void sideIntentCannotPayWheelUnlockTravelAndOnlyExcessMovesY() {
		var f = new Fixture(11, false, false);
		assertTrue(f.wheel(1));
		assertEquals(1, f.list.selectedIndex()); assertEquals(10, f.screenY(), EPS);

		f.move(0, 10); // ten of the fourteen unlock pixels
		assertEquals(10, f.screenY(), EPS);
		f.move(75, 0); // forward side, 25 px beyond the panel edge
		assertTrue(f.gesture.sideIntent());
		assertEquals(InventoryGesture.Action.NONE, f.move(0, 10));
		assertEquals(10, f.screenY(), EPS, "side-suppressed Y cannot pay unlock travel");

		f.move(-75, 0);
		assertFalse(f.gesture.sideIntent());
		f.move(0, 3); // 13 total; still locked because the side sample did not pay
		assertEquals(10, f.screenY(), EPS, "the full remaining toll is still required");
		f.move(0, 1); // exact threshold admits zero
		assertEquals(10, f.screenY(), EPS);
		f.move(0, 5); // only post-threshold travel moves Y
		assertEquals(15, f.screenY(), EPS);
	}

	@Test void panelInteriorAndExactDeadzoneHaveNoIntentAndStrokeIsNotAddedToDeadzone() {
		for (boolean mirrored : List.of(false, true)) {
			for (double side : new double[] {-1, 1}) {
				var f = new Fixture(11, false, mirrored);
				assertEquals(InventoryGesture.Action.NONE, f.move(side * 50, 0));
				assertEquals(0, f.gesture.horizontalTravel());
				f.move(side * 24, 0); assertFalse(f.gesture.sideIntent());
				f.move(0, 10); assertEquals(10, f.screenY(), EPS);
				f.move(side * 0.5, 0); assertTrue(f.gesture.sideIntent());
				f.move(0, 50); assertEquals(10, f.screenY(), EPS, "side hint freezes Y");
				assertEquals(InventoryGesture.Action.NONE, f.move(side * 25, 0));
				var expected = (side > 0) == mirrored ? InventoryGesture.Action.BACK : InventoryGesture.Action.FORWARD;
				assertEquals(expected, f.move(side * 0.5, 0), "stroke is measured from the panel, not the deadzone edge");
			}
		}
	}

	@Test void nonDefaultDeadzoneAndStrokeKeepExactPanelBoundaries() {
		for (boolean mirrored : List.of(false, true)) {
			for (double side : new double[] {-1, 1}) {
				var f = new Fixture(11, false, mirrored, 16, 64);
				assertEquals(InventoryGesture.Action.NONE, f.move(side * 50, 0));
				assertEquals(0, f.gesture.horizontalTravel(), EPS);
				f.move(side * 16, 0);
				assertFalse(f.gesture.sideIntent(), "16 px is exactly the deadzone");
				f.move(side * 0.5, 0);
				assertTrue(f.gesture.sideIntent(), "16.5 px is beyond the deadzone");
				assertEquals(InventoryGesture.Action.NONE, f.move(side * 47, 0)); // 63.5 total
				var expected = (side > 0) == mirrored ? InventoryGesture.Action.BACK : InventoryGesture.Action.FORWARD;
				assertEquals(expected, f.move(side * 0.5, 0),
					"the 64 px stroke is measured from the panel edge, not added to the deadzone");
			}
		}
	}

	@Test void crossingSampleCountsOnlyExteriorAndReentryClearsProgress() {
		var f = new Fixture(11, false, false);
		f.move(60, 0); assertEquals(10, f.gesture.horizontalTravel(), EPS);
		f.move(-11, 0); assertEquals(0, f.gesture.horizontalTravel(), EPS);
		f.move(-100, 0); assertEquals(-1, f.gesture.horizontalTravel(), EPS);
		f.move(1, 0); assertEquals(0, f.gesture.horizontalTravel(), EPS);
	}

	@Test void coneFiltersCurrentDxWithoutDeletingHistoryIncludingExactBoundary() {
		for (double sign : new double[] {-1, 1}) {
			var f = new Fixture(11, true, false);
			f.move(sign * 60, 0);
			double exact = 10 * Math.tan(Math.toRadians(25));
			f.move(sign * exact, 10);
			assertEquals(sign * 10, f.gesture.horizontalTravel(), EPS);
			assertEquals(sign * 60, f.pointer().x(), EPS);
			f.move(sign * (exact + 0.01), 10);
			assertEquals(sign * (10 + exact + 0.01), f.gesture.horizontalTravel(), EPS);
		}
	}

	@Test void crossedPanelCanContinueAtClampAndSaturatesWithoutOvershootDebt() {
		for (double sign : new double[] {-1, 1}) {
			var f = new Fixture(11, true, false);
			f.left = -150; f.right = 200; // only 29.5px reachable beyond right panel edge
			f.move(sign * Double.MAX_VALUE, 0);
			assertEquals(sign * 50, f.gesture.horizontalTravel(), EPS);
			var held = f.pointer(); f.move(sign * Double.MAX_VALUE, 0);
			assertEquals(held, f.pointer()); assertEquals(sign * 50, f.gesture.horizontalTravel(), EPS);
			f.move(-sign, 0);
			assertEquals(sign * 49, f.gesture.horizontalTravel(), EPS, "one reverse pixel acts immediately");
		}
	}

	@Test void panelAtOrBeyondScreenEdgeCanQualifyFromRawOutwardTravelInBothDirections() {
		for (double sign : new double[] {-1, 1}) {
			var f = new Fixture(11, false, false);
			f.left = -1000; f.right = 1000;
			double edge = sign < 0 ? -170.5 : 229.5;
			f.move(edge, 0); assertEquals(0, f.gesture.horizontalTravel());
			f.move(sign * 24, 0); assertFalse(f.gesture.sideIntent());
			f.move(sign, 0); assertTrue(f.gesture.sideIntent());
			assertEquals(sign < 0 ? InventoryGesture.Action.BACK : InventoryGesture.Action.FORWARD, f.move(sign * 25, 0));
			f.move(-sign, 0);
			assertEquals(0, f.gesture.horizontalTravel(), "actual screen-edge reentry clears fallback progress");
		}
	}

	@Test void panelEdgeExactlyOnScreenEdgeUsesFallbackOnBothSidesAndMirrors() {
		for (boolean mirrored : List.of(false, true)) {
			for (double side : new double[] {-1, 1}) {
				var f = new Fixture(11, false, mirrored);
				double edge = side < 0 ? SCREEN_LEFT : SCREEN_RIGHT;
				if (side < 0) f.left = SCREEN_LEFT; else f.right = SCREEN_RIGHT;
				f.move(edge, 0);
				assertEquals(edge, f.pointer().x(), EPS);
				assertEquals(0, f.gesture.horizontalTravel(), EPS);
				f.move(side * 24, 0);
				assertFalse(f.gesture.sideIntent());
				f.move(side, 0);
				assertTrue(f.gesture.sideIntent());
				var expected = (side > 0) == mirrored ? InventoryGesture.Action.BACK : InventoryGesture.Action.FORWARD;
				assertEquals(expected, f.move(side * 25, 0),
					"an edge exactly on the screen edge can start fallback from that edge");
			}
		}
	}

	@Test void asymmetricPanelKeepsRealCrossingInsideAndFallbackBeyondTheScreen() {
		var inside = new Fixture(11, false, false);
		inside.left = -1000; // beyond the left screen edge
		inside.right = 200;  // reachable inside the screen
		inside.move(200, 0);
		assertEquals(0, inside.gesture.horizontalTravel(), EPS);
		inside.move(24, 0); assertFalse(inside.gesture.sideIntent());
		inside.move(1, 0); assertTrue(inside.gesture.sideIntent());
		assertEquals(InventoryGesture.Action.FORWARD, inside.move(25, 0),
			"the inside edge counts only the exterior crossing");

		var beyond = new Fixture(11, false, false);
		beyond.left = -1000; // beyond the left screen edge
		beyond.right = 200;  // reachable inside the screen
		beyond.move(SCREEN_LEFT, 0);
		assertEquals(SCREEN_LEFT, beyond.pointer().x(), EPS);
		assertEquals(0, beyond.gesture.horizontalTravel(), EPS);
		beyond.move(-24, 0); assertFalse(beyond.gesture.sideIntent());
		beyond.move(-1, 0); assertTrue(beyond.gesture.sideIntent());
		assertEquals(InventoryGesture.Action.BACK, beyond.move(-25, 0),
			"the offscreen edge uses its screen-edge fallback");
	}

	@Test void whollyOffscreenPanelAndSinglePixelWidthUseOrderedScreenBoundariesWithoutSyntheticProgress() {
		for (double sign : new double[] {-1, 1}) {
			var f = new Fixture(3, false, false);
			f.left = sign < 0 ? -2000 : 1000;
			f.right = sign < 0 ? -1000 : 2000;
			double edge = sign < 0 ? -170.5 : 229.5;
			f.move(edge, 0);
			assertEquals(0, f.gesture.horizontalTravel());
			f.move(sign * 25, 0);
			assertEquals(sign * 25, f.gesture.horizontalTravel());
			f.move(-sign, 0);
			assertFalse(f.gesture.sideIntent(), "one physical reverse sample clears the fallback without debt");
			f.gesture.rebase();
			f.viewport = new SpatialController.Viewport(1, 101, -0.5, 17);
			f.controller.constrainPointer(f.viewport, 2); f.sync();
			var pointer = f.pointer();
			f.move(sign * 25, 0);
			assertEquals(pointer, f.pointer());
			assertEquals(sign * 25, f.gesture.horizontalTravel(), "a collapsed viewport still qualifies only fresh raw input");
		}
	}

	@Test void hoverOwnsBackInsteadOfStrokeAndSuppressesWheelUntilPhysicalReentry() {
		var f = new Fixture(11, true, false);
		assertEquals(InventoryGesture.Action.NONE, f.move(-1000, 0));
		assertTrue(f.gesture.isBackHoverFocused()); f.wheel(3);
		assertEquals(0, f.list.selectedIndex());
		f.move(1000, 0); // Forward still emits normally in hover mode
		assertFalse(f.gesture.isBackHoverFocused()); f.wheel(1);
		assertEquals(1, f.list.selectedIndex());
	}

	@Test void nonHoverBackSideStillAllowsWheelSelectionButHoverBackSuppressesIt() {
		var nonHover = new Fixture(11, false, false);
		nonHover.move(-75, 0);
		assertTrue(nonHover.gesture.isBackSideFocused());
		assertFalse(nonHover.gesture.isBackHoverFocused());
		assertTrue(nonHover.wheel(1));
		assertEquals(1, nonHover.list.selectedIndex());
		assertEquals(10, nonHover.screenY(), EPS, "whole-row wheel synchronizes the pointer to the row representative");

		var hover = new Fixture(11, true, false);
		hover.move(-75, 0);
		assertTrue(hover.gesture.isBackHoverFocused());
		assertFalse(hover.wheel(1));
		assertEquals(0, hover.list.selectedIndex());
		assertEquals(0, hover.screenY(), EPS);
	}

	@Test void hoverModeForwardStillReturnsForwardAction() {
		var f = new Fixture(11, true, false);
		assertEquals(InventoryGesture.Action.NONE, f.move(-75, 0));
		assertTrue(f.gesture.isBackHoverFocused());
		assertEquals(InventoryGesture.Action.FORWARD, f.move(300, 0),
			"Back-hover suppresses wheel and distance Back, not the Forward action");
		assertFalse(f.gesture.isBackHoverFocused());
	}

	@Test void hoverBackFocusClearsPartialWheelUnlockTravel() {
		var f = new Fixture(11, true, false);
		assertTrue(f.wheel(2));
		assertEquals(2, f.list.selectedIndex()); assertEquals(20, f.screenY(), EPS);
		f.move(0, 10);
		assertEquals(20, f.screenY(), EPS);
		f.move(-75, 0);
		assertTrue(f.gesture.isBackHoverFocused());
		f.move(75, 0);
		assertFalse(f.gesture.isBackHoverFocused());
		f.move(0, 13);
		assertEquals(20, f.screenY(), EPS, "Back-hover reset the earlier partial unlock travel");
		f.move(0, 1);
		assertEquals(20, f.screenY(), EPS, "the exact threshold admits no excess");
		f.move(0, 5);
		assertEquals(25, f.screenY(), EPS);
	}

	@Test void alignmentCannotSelectFreezeDiscoveryUnlockWheelOrChangeSideProgress() {
		var f = new Fixture(11, false, false);
		f.wheel(2); f.move(0, 10); f.move(60, 0);
		f.viewport = new SpatialController.Viewport(500, 300, 8, -7);
		f.controller.constrainPointer(f.viewport, 50); f.sync();
		assertEquals("k2", f.list.selectedKey()); assertEquals(10, f.gesture.horizontalTravel());
		double y = f.pointer().y(); f.move(0, 8);
		assertEquals(y + 4, f.pointer().y(), EPS, "alignment did not pay or reset the signed unlock toll");
		var pristine = new Fixture(2, false, false);
		assertEquals(List.of("k0", "k1"),
			pristine.list.snapshot().entries().stream().map(InventoryListModel.Entry::key).toList(),
			"precondition: two discovered keys");
		pristine.sync();
		assertEquals(List.of("k0", "k1"),
			pristine.list.snapshot().entries().stream().map(InventoryListModel.Entry::key).toList(),
			"system alignment is not a user scroll");
		assertTrue(pristine.list.applyBatch(List.of(
			new InventoryListModel.Entry("new", "New", 999, "ready"))));
		assertEquals(List.of("new", "k0", "k1"),
			pristine.list.snapshot().entries().stream().map(InventoryListModel.Entry::key).toList(),
			"alignment did not mark the list as scrolled; the later high-count batch still sorts first");
	}

	@Test void nonfiniteInputNeverPoisonsPointerSelectionOrFractions() {
		var f = new Fixture(11, false, false); f.wheel(0.75);
		for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertEquals(0, f.gesture.horizontalDelta(bad, 1));
			assertEquals(0, f.gesture.verticalDelta(bad)); assertFalse(f.gesture.wheelRows(bad));
		}
		f.wheel(0.25); assertEquals(1, f.list.selectedIndex());
	}
}
