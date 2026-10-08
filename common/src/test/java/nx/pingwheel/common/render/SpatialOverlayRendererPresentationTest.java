package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Paint-admission predicates only; no GuiGraphics or GPU submission is claimed. */
class SpatialOverlayRendererPresentationTest {
	@Test void onlyVisibleCurrentNodesCanAcknowledgeTheirPublicationAfterPainting() {
		var style = SpatialOverlayRenderer.Style.NATIVE;
		assertTrue(SpatialOverlayRenderer.acknowledgesNodePaint(true, true, 1, style));
		assertFalse(SpatialOverlayRenderer.acknowledgesNodePaint(false, true, 1, style), "detached exit data is not selectable");
		assertFalse(SpatialOverlayRenderer.acknowledgesNodePaint(true, false, 1, style), "ancestor nodes do not paint the active precise branch");
		assertFalse(SpatialOverlayRenderer.acknowledgesNodePaint(true, true, 0, style), "first appearance is still invisible");
		assertFalse(SpatialOverlayRenderer.acknowledgesNodePaint(true, true, 0.0001, style), "quantized zero pixels are not presentation");
		assertFalse(SpatialOverlayRenderer.acknowledgesNodePaint(true, true, 1,
			new SpatialOverlayRenderer.Style(100, 0, 1, 1, true, false)), "a backdrop or pointer alone cannot certify a node");
	}

	@Test void absentGraphicsNeverAcknowledgesAnyCandidateOrStartsRenderTransitions() {
		var session = new SpatialOverlayRenderer.Session();
		assertSame(SpatialOverlayRenderer.RowLayout.NONE, session.drawFrame(null, null, null, null,
			SpatialOverlayRenderer.Style.NATIVE, 0));
		assertTrue(session.paintedChoiceIds().isEmpty()); assertFalse(session.isAnimating());
		session.reset(); assertTrue(session.paintedChoiceIds().isEmpty());
	}
}
