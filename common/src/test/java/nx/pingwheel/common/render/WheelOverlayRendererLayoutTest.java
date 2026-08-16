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
}
