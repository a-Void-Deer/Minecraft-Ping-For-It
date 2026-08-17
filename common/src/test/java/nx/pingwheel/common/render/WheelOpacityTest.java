package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelOpacityTest {

	@Test
	void zeroOpacityMakesEveryBaseAlphaInvisible() {
		assertEquals(0x00010203, WheelOpacity.apply(0xFF010203, 0));
		assertEquals(0x00010203, WheelOpacity.apply(0x50010203, 0));
		assertEquals(0x00010203, WheelOpacity.apply(0x00010203, 0));
	}

	@Test
	void halfOpacityRoundsEachBaseAlphaIndependently() {
		assertEquals(0x80010203, WheelOpacity.apply(0xFF010203, 50));
		assertEquals(0x28010203, WheelOpacity.apply(0x50010203, 50));
		assertEquals(0x44010203, WheelOpacity.apply(0x88010203, 50));
		assertEquals(0x4D010203, WheelOpacity.apply(0x99010203, 50));
	}

	@Test
	void fullOpacityPreservesTheOriginalArgbExactly() {
		assertEquals(0xFF010203, WheelOpacity.apply(0xFF010203, 100));
		assertEquals(0x50010203, WheelOpacity.apply(0x50010203, 100));
		assertEquals(0x00010203, WheelOpacity.apply(0x00010203, 100));
	}
}
