package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderColorPolicyTest {

	@Test
	void markerColorPassesPingColorThroughUnchanged() {
		assertEquals(0xFFFFFFFF, RenderColorPolicy.markerColor(0xFFFFFFFF));
		assertEquals(0xFF64B5F6, RenderColorPolicy.markerColor(0xFF64B5F6));
		assertEquals(0x80FF6B6B, RenderColorPolicy.markerColor(0x80FF6B6B));
		assertEquals(0x00000000, RenderColorPolicy.markerColor(0x00000000));
	}

	@Test
	void distanceTextColorIsAlwaysOpaqueWhite() {
		assertEquals(0xFFFFFFFF, RenderColorPolicy.distanceTextColor());
	}
}
