package nx.pingwheel.common.render;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.client.spatial.SpatialController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialOverlayRendererStyleTest {

	@Test
	void callerOverridesSurvivePreferenceChangesRatherThanResettingToNativeDefaults() {
		var style = new SpatialOverlayRenderer.Style(37, 0.62, 1.4, false, true);
		assertEquals(37, style.opacityPercent());
		assertEquals(0.62, style.optionTextScale());
		assertEquals(1.4, style.inventoryTextScale());
		assertFalse(style.showTrail());
		assertTrue(style.reduceMotion());
	}

	@Test
	void oldFontPercentagesRemainIndependentAndScaleProportionally() {
		var smallerOptions = SpatialOverlayRenderer.Style.fromLegacyFontSizes(37, 80, 140, 88.0, true, false);
		var largerOptions = SpatialOverlayRenderer.Style.fromLegacyFontSizes(37, 160, 140, 88.0, false, true);
		assertEquals(smallerOptions.optionTextScale() * 2.0, largerOptions.optionTextScale());
		assertEquals(smallerOptions.inventoryTextScale(), largerOptions.inventoryTextScale());
		assertEquals(1.4, largerOptions.inventoryTextScale());
		assertEquals(37, largerOptions.opacityPercent());
		assertEquals(88.0, largerOptions.rootDistance());
	}

	@Test
	void alphaMultiplicationPreservesRgbAndRelativeNativeTranslucency() {
		// Two equal-RGB paints with a 2:1 base alpha must retain that ratio.
		int solid = SpatialOverlayRenderer.withAlpha(0xC0804020, 0.25);
		int translucent = SpatialOverlayRenderer.withAlpha(0x60804020, 0.25);
		assertEquals(48, solid >>> 24);
		assertEquals(24, translucent >>> 24);
		assertEquals(0x804020, solid & 0x00FFFFFF);
		assertEquals(solid & 0x00FFFFFF, translucent & 0x00FFFFFF);
		assertEquals(0, SpatialOverlayRenderer.withAlpha(0xFF804020, 0.0) >>> 24);
	}

	@Test
	void wheelAndTargetOpacityAffectOnlyTheirAssignedLayers() {
		var style = new SpatialOverlayRenderer.Style(20, 80, 1.0, 1.0, true, false);
		var choice = new SpatialController.ChoiceView("back", "pingforit.spatial.back", null,
			true, false, false, false, true, 0.0, 90.0, null);
		var sector = new SpatialOverlayRenderer.SectorPaint(choice, 20.0);
		var node = new SpatialOverlayRenderer.NodePaint(choice, Component.literal("Back"), true, 0.0);
		var chrome = new SpatialOverlayRenderer.ChromePaint(
			new SpatialController.Snapshot(true, null, java.util.List.of(), null, java.util.List.of(), 0.0));

		assertEquals(0.2, SpatialOverlayRenderer.backdropAlpha(1.0, style), 1.0e-12);
		assertEquals(0.8, SpatialOverlayRenderer.targetAlpha(1.0, style), 1.0e-12);
		assertEquals(0.2, SpatialOverlayRenderer.layerAlpha(sector, 1.0, style), 1.0e-12);
		assertEquals(0.8, SpatialOverlayRenderer.layerAlpha(node, 1.0, style), 1.0e-12);
		assertEquals(1.0, SpatialOverlayRenderer.layerAlpha(chrome, 1.0, style), 1.0e-12);
	}

	@Test
	void zeroOpacityPairStillRendersChromeWithoutUnderlayOrTextFrames() {
		var style = new SpatialOverlayRenderer.Style(0, 0, 1.0, 1.0, true, false);
		var choice = new SpatialController.ChoiceView("back", "pingforit.spatial.back", null,
			true, false, false, false, true, 0.0, 90.0, null);
		var snapshot = new SpatialController.Snapshot(true, null, java.util.List.of(), null,
			java.util.List.of(), 0.0);
		var chrome = new SpatialOverlayRenderer.ChromePaint(snapshot);

		assertTrue(SpatialOverlayRenderer.renders(chrome, 1.0, style),
			"chrome follows only the transition fade");
		assertFalse(SpatialOverlayRenderer.renders(
			new SpatialOverlayRenderer.SectorPaint(choice, 20.0), 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(
			new SpatialOverlayRenderer.NodePaint(choice, Component.literal("Back"), true, 0.0), 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(
			new SpatialOverlayRenderer.PanelPaint(20.0, 20.0, 6.0), 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(
			new SpatialOverlayRenderer.RowPaint(new SpatialInventoryView.Row("key", "Label", 1L, null,
				SpatialInventoryView.Status.READY), 20.0, 10.0, false, false), 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(
			new SpatialOverlayRenderer.HeaderPaint(20.0, 6.0, "1 / 1",
				SpatialInventoryView.Status.READY), 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(
			new SpatialOverlayRenderer.FooterPaint(20.0, 6.0, true,
				new SpatialInventoryView.BackAffordance(true, 0.5)), 1.0, style));
		assertFalse(SpatialOverlayRenderer.renders(chrome, 0.0, style),
			"the chrome still fades with its own transition");
	}

	@Test
	void backProgressUsesTheActualRectangularFrameBoundary() {
		// drawNode paints fill(left, top, right, bottom) plus a one-pixel
		// strokeRect; both treat right/bottom as exclusive, so a 20x10 frame
		// paints columns 0..19 and rows 0..9. The path must trace those pixels.
		assertEquals(java.util.List.of(
			new SpatialSquareProgress.Segment(0.0, 0.0, 19.0, 0.0),
			new SpatialSquareProgress.Segment(19.0, 0.0, 19.0, 9.0)),
			SpatialOverlayRenderer.backProgressSegments(0, 0, 20, 10, 0.5));
		assertEquals(java.util.List.of(
			new SpatialSquareProgress.Segment(0.0, 0.0, 19.0, 0.0),
			new SpatialSquareProgress.Segment(19.0, 0.0, 19.0, 9.0),
			new SpatialSquareProgress.Segment(19.0, 9.0, 0.0, 9.0),
			new SpatialSquareProgress.Segment(0.0, 9.0, 0.0, 0.0)),
			SpatialOverlayRenderer.backProgressSegments(0, 0, 20, 10, 1.0));
		// A 17x9 frame paints 16x8 pixels, so a quarter of its 48-pixel
		// perimeter is 12 pixels along the top edge.
		assertEquals(java.util.List.of(
			new SpatialSquareProgress.Segment(0.0, 0.0, 12.0, 0.0)),
			SpatialOverlayRenderer.backProgressSegments(0, 0, 17, 9, 0.25));
	}

	@Test
	void backProgressNeverLeavesTheFramesPaintedPixels() {
		int left = 0;
		int top = 0;
		int right = 20;
		int bottom = 10;

		for (int percent = 0; percent <= 100; percent++) {
			for (SpatialSquareProgress.Segment segment :
				SpatialOverlayRenderer.backProgressSegments(left, top, right, bottom, percent / 100.0)) {
				assertTrue(segment.x1() >= left && segment.x1() <= right - 1
					&& segment.x2() >= left && segment.x2() <= right - 1, "x stays on painted columns");
				assertTrue(segment.y1() >= top && segment.y1() <= bottom - 1
					&& segment.y2() >= top && segment.y2() <= bottom - 1, "y stays on painted rows");
			}
		}
	}

	@Test
	void rootSpacingUsesOnlyCallerVisualDistanceAndChildSpacingKeepsItsOwnGeometry() {
		assertEquals(88.0, SpatialOverlayRenderer.nodeRadius(0, 120.0, 88.0, false));
		assertEquals(88.0, SpatialOverlayRenderer.nodeRadius(0, 75.0, 88.0, false));
		assertEquals(120.0, SpatialOverlayRenderer.nodeRadius(1, 120.0, 88.0, false));
		assertEquals(120.0, SpatialOverlayRenderer.nodeRadius(1, 120.0, 180.0, false));
		assertTrue(SpatialOverlayRenderer.nodeRadius(0, 120.0, 88.0, true) > 88.0);
	}

	@Test
	void halfScaleOrbitClampsAndSelectedPushKeepsItsHalfScaleOffset() {
		assertEquals(66.0, SpatialOverlayRenderer.orbitFor(1_000.0), 1.0e-9);
		assertEquals(32.0, SpatialOverlayRenderer.orbitFor(100.0), 1.0e-9);
		assertEquals(33.0, SpatialOverlayRenderer.orbitFor(300.0), 1.0e-9);
		assertEquals(44.0, SpatialOverlayRenderer.orbitFor(400.0), 1.0e-9);
		assertEquals(5.0, SpatialOverlayRenderer.ORBIT_SELECTED_PUSH);
		assertEquals(49.0, SpatialOverlayRenderer.nodeRadius(1, 44.0, 55.0, true), 1.0e-9);
		assertEquals(60.0, SpatialOverlayRenderer.nodeRadius(0, 44.0, 55.0, true), 1.0e-9);
	}
}
