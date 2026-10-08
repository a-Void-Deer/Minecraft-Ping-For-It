package nx.pingwheel.common.render;

import java.util.List;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.client.spatial.SpatialController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused oracles for the radial sector backdrop: its row geometry follows the
 * controller's choice sectors, disabled/reserved dimming is shared with the
 * nodes, and the backdrop layer always paints before nodes and chrome.
 */
class SpatialOverlayRendererSectorTest {

	private static final double RADIUS = 60.0;

	@Test
	void backdropRowBoundsFollowTheChoiceStartAndSpanDegrees() {
		double halfWidth = Math.sqrt(RADIUS * RADIUS - 30.0 * 30.0);

		assertRow(-RADIUS, RADIUS, 0.0, 0.0, 360.0);
		assertRow(-halfWidth, halfWidth, 30.0, 0.0, 360.0);
		assertRow(0.0, halfWidth, -30.0, 0.0, 90.0);
		assertRow(0.0, RADIUS, 0.0, 0.0, 90.0);
		assertRow(0.0, halfWidth, 30.0, 90.0, 90.0);
		assertRow(-halfWidth, halfWidth, -30.0, 270.0, 180.0);
		assertEmpty(30.0, 0.0, 90.0);
		assertEmpty(-30.0, 90.0, 90.0);
		assertEmpty(30.0, 270.0, 180.0);
		assertEmpty(RADIUS + 10.0, 0.0, 360.0);
	}

	@Test
	void adjacentSectorsTileAtTheirSharedBoundary() {
		double halfWidth = Math.sqrt(RADIUS * RADIUS - 30.0 * 30.0);
		double[] upRight = SpatialOverlayRenderer.sectorRowBounds(-30.0, RADIUS, 0.0, 90.0);
		double[] upLeft = SpatialOverlayRenderer.sectorRowBounds(-30.0, RADIUS, 270.0, 90.0);

		assertEquals(0.0, upRight[0], 1.0e-9);
		assertEquals(halfWidth, upRight[1], 1.0e-9);
		assertEquals(-halfWidth, upLeft[0], 1.0e-9);
		assertEquals(0.0, upLeft[1], 1.0e-9);
	}

	@Test
	void backdropCoversTheRootSectorBearingItDecorates() {
		// The fixed root danger sector spans 332.5..27.5 degrees, and the
		// neighbouring diagonal reserved sector starts on its shared edge.
		double[] danger = SpatialOverlayRenderer.sectorRowBounds(-30.0, RADIUS, 332.5, 55.0);
		double[] diagonal = SpatialOverlayRenderer.sectorRowBounds(-30.0, RADIUS, 27.5, 35.0);

		assertTrue(danger[0] < 0.0 && danger[1] > 0.0, "the node bearing stays inside the backdrop");
		assertEquals(danger[1], diagonal[0], 1.0e-6, "neighbouring backdrops share their edge");
	}

	@Test
	void backdropAlphaMatchesNodeAlphaIncludingDisabledAndReservedDimming() {
		assertEquals(1.0, SpatialOverlayRenderer.nodeAlpha(true, true, false));
		assertEquals(0.8, SpatialOverlayRenderer.nodeAlpha(true, false, false));
		assertEquals(SpatialOverlayRenderer.ANCESTOR_ALPHA, SpatialOverlayRenderer.nodeAlpha(false, false, false));
		assertEquals(SpatialOverlayRenderer.DISABLED_ALPHA_FACTOR, SpatialOverlayRenderer.nodeAlpha(true, true, true));
		assertEquals(SpatialOverlayRenderer.ANCESTOR_ALPHA * SpatialOverlayRenderer.DISABLED_ALPHA_FACTOR,
			SpatialOverlayRenderer.nodeAlpha(false, false, true), 1.0e-12);
	}

	@Test
	void backdropPaintStaysSemiTransparentAndScalesThroughTheSharedAlphaHelper() {
		int base = SpatialOverlayRenderer.SECTOR_BACKGROUND;
		assertTrue((base >>> 24) < 0xFF, "the backdrop colour must stay translucent");
		assertEquals(base, SpatialOverlayRenderer.withAlpha(base, 1.0));
		int half = SpatialOverlayRenderer.withAlpha(base, 0.5);
		assertEquals(19, half >>> 24);
		assertEquals(base & 0x00FFFFFF, half & 0x00FFFFFF);
	}

	@Test
	void backdropLayerPaintsBeforePanelsNodesAndChrome() {
		var choice = new SpatialController.ChoiceView("root:danger", "label", "ping:danger",
			false, false, false, false, true, 332.5, 55.0, null);
		var snapshot = new SpatialController.Snapshot(true, null, List.of(), null, List.of(), 0.0);
		int sector = SpatialOverlayRenderer.paintRank(new SpatialOverlayRenderer.SectorPaint(choice, RADIUS));
		int panel = SpatialOverlayRenderer.paintRank(new SpatialOverlayRenderer.PanelPaint(10.0, 10.0, 3.0));
		int node = SpatialOverlayRenderer.paintRank(new SpatialOverlayRenderer.NodePaint(
			choice, Component.literal("label"), true, 0.0));
		int chrome = SpatialOverlayRenderer.paintRank(new SpatialOverlayRenderer.ChromePaint(snapshot));

		assertTrue(sector < panel);
		assertTrue(panel < node);
		assertTrue(sector < node);
		assertTrue(sector < chrome);
		assertEquals(node, chrome);
	}

	@Test
	void backdropRadiusExtendsTheBaseOrbitByTheFocusMargin() {
		assertEquals(67.0, SpatialOverlayRenderer.sectorRadius(55.0));
		assertTrue(SpatialOverlayRenderer.sectorRadius(44.0) > 44.0);
		assertEquals(SpatialOverlayRenderer.SECTOR_PADDING,
			SpatialOverlayRenderer.ORBIT_SELECTED_PUSH + SpatialOverlayRenderer.BOX_HEIGHT / 2.0);
		assertEquals(SpatialOverlayRenderer.sectorRadius(44.0) - 44.0,
			SpatialOverlayRenderer.sectorRadius(55.0) - 55.0, 1.0e-12);
	}

	private static void assertRow(double min, double max, double dy, double start, double span) {
		double[] bounds = SpatialOverlayRenderer.sectorRowBounds(dy, RADIUS, start, span);
		assertEquals(min, bounds[0], 1.0e-9);
		assertEquals(max, bounds[1], 1.0e-9);
	}

	private static void assertEmpty(double dy, double start, double span) {
		double[] bounds = SpatialOverlayRenderer.sectorRowBounds(dy, RADIUS, start, span);
		assertTrue(bounds[0] > bounds[1], "expected an empty row");
	}
}
