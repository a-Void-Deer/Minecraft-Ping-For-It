package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelOverlayRendererLayoutTest {

	@Test
	void visualDimensionsUseTheHalvedWheelScale() {
		assertEquals(6, WheelOverlayRenderer.ICON_SIZE);
		assertEquals(2, WheelOverlayRenderer.SELECTED_ARC_THICKNESS);
		assertEquals(2, WheelOverlayRenderer.SELECTED_CENTER_BORDER_THICKNESS);
		assertEquals(1, WheelOverlayRenderer.CENTER_MARK_THICKNESS);
	}

	@Test
	void rasterStrokesKeepAtLeastOnePixel() {
		assertEquals(1, WheelOverlayRenderer.ARC_THICKNESS);
		assertEquals(1, WheelOverlayRenderer.SEPARATOR_THICKNESS);
		assertEquals(1, WheelOverlayRenderer.CENTER_BORDER_THICKNESS);
	}

	@Test
	void targetAndOptionFontScalesAreIndependent() {
		assertEquals(0.05, WheelOverlayRenderer.optionLabelScale(10), 0.000001);
		assertEquals(2.5, WheelOverlayRenderer.optionLabelScale(500), 0.000001);
		assertEquals(0.1, WheelOverlayRenderer.targetLabelScale(10), 0.000001);
		assertEquals(5.0, WheelOverlayRenderer.targetLabelScale(500), 0.000001);
	}
}
