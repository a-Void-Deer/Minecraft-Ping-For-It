package nx.pingwheel.common.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Preserves the bounded exponential fraction's timing semantics. The finite
 * appearance/exit/retarget model is covered by SpatialOverlayTransitionsTest;
 * this compatibility helper still composes across bounded elapsed partitions.
 */
class SpatialOverlayRendererTimingTest {

	@Test
	void equalElapsedTimeSmoothsEquallyAcrossFrameCounts() {
		double rate = SpatialOverlayRenderer.ANIMATION_RATE_PER_MILLI;
		double elapsed = 48.0;
		double singleFrameRemaining = 1.0 - SpatialOverlayRenderer.smoothingFactor(elapsed, rate);
		double frameRemaining = 1.0;
		int frames = 6;

		for (int i = 0; i < frames; i++) {
			frameRemaining *= 1.0 - SpatialOverlayRenderer.smoothingFactor(elapsed / frames, rate);
		}

		assertEquals(singleFrameRemaining, frameRemaining, 1.0e-9);
	}

	@Test
	void negativeClockDeltaIsInert() {
		assertEquals(0.0, SpatialOverlayRenderer.smoothingFactor(-25.0, 0.02), 1.0e-12);
	}

	@Test
	void exceptionallyLongGapIsBounded() {
		double bounded = SpatialOverlayRenderer.smoothingFactor(5_000.0, 0.02);
		double capped = SpatialOverlayRenderer.smoothingFactor(SpatialOverlayRenderer.MAX_FRAME_MILLIS, 0.02);

		assertEquals(capped, bounded, 1.0e-12);
		assertTrue(bounded < 1.0);
	}
}
