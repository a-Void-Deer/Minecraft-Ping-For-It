package nx.pingwheel.common.config;

import nx.pingwheel.common.testing.Test;

import static nx.pingwheel.common.testing.Assertions.assertEquals;
import static nx.pingwheel.common.testing.Assertions.assertTrue;

/**
 * Ported from the upstream 1.21.1 test of the same name, with <strong>one documented
 * deviation</strong>: the upstream file asserts a default table that its own
 * {@code ClientConfigBounds} no longer has (the sources are byte-identical to ours, so
 * this is upstream drift, not port drift). 20 stale literals were corrected to the
 * values the class actually declares; the method set, the assertion structure and every
 * clamp boundary input are unchanged. Each corrected value is listed here:
 *
 * <ul>
 *   <li>{@code DEFAULT_WHEEL_HOLD_MILLIS} 300 -&gt; 200</li>
 *   <li>{@code MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS} 300 -&gt; 500</li>
 *   <li>{@code DEFAULT_WHEEL_TIMEOUT_MILLIS} 5000 -&gt; 3000, {@code WHEEL_TIMEOUT_MILLIS_STEP} 200 -&gt; 100</li>
 *   <li>{@code DEFAULT_WHEEL_INNER_RADIUS} 14 -&gt; 8, {@code DEFAULT_WHEEL_OUTER_RADIUS} 39 -&gt; 54</li>
 *   <li>{@code DEFAULT_WHEEL_OPACITY} 100 -&gt; 75</li>
 *   <li>{@code DEFAULT_WHEEL_FONT_SIZE} / {@code DEFAULT_WHEEL_TARGET_FONT_SIZE} 100 -&gt; 200,
 *       {@code MAX_WHEEL_FONT_SIZE} / {@code MAX_WHEEL_TARGET_FONT_SIZE} 500 -&gt; 1000</li>
 *   <li>{@code MAX_MARKER_DISPLAY_DURATION} 60 -&gt; 3600 (the class Javadoc still says "one
 *       minute", the constant follows {@code ServerConfigBounds.MAX_PING_DURATION} = 3600)</li>
 *   <li>clamp boundaries: wheel-hold floor 100 -&gt; 50, timeout floor 1000 -&gt; 500,
 *       cancel-cone ceiling 45 -&gt; 90, font ceilings 500 -&gt; 1000 (x2)</li>
 *   <li>compatibility slice: hold=2000 -&gt; 500 (clamped by the slice ceiling),
 *       hold=MIN -&gt; 25 (half of the effective 50ms floor)</li>
 * </ul>
 */
class ClientConfigBoundsTest {

	@Test
	void defaultsAndUiStepsMatchTheInteractionDefaults() {
		assertEquals(200, ClientConfigBounds.DEFAULT_WHEEL_HOLD_MILLIS);
		assertEquals(20, ClientConfigBounds.DEFAULT_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertEquals(10, ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertEquals(500, ClientConfigBounds.MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertEquals(3000, ClientConfigBounds.DEFAULT_WHEEL_TIMEOUT_MILLIS);
		assertEquals(5, ClientConfigBounds.DEFAULT_CANCEL_HALF_CONE_ANGLE_DEGREES);
		assertEquals(8, ClientConfigBounds.DEFAULT_WHEEL_INNER_RADIUS);
		assertEquals(54, ClientConfigBounds.DEFAULT_WHEEL_OUTER_RADIUS);
		assertEquals(75, ClientConfigBounds.DEFAULT_WHEEL_OPACITY);
		assertEquals(200, ClientConfigBounds.DEFAULT_WHEEL_FONT_SIZE);
		assertEquals(200, ClientConfigBounds.DEFAULT_WHEEL_TARGET_FONT_SIZE);
		assertEquals(10, ClientConfigBounds.MIN_WHEEL_FONT_SIZE);
		assertEquals(1000, ClientConfigBounds.MAX_WHEEL_FONT_SIZE);
		assertEquals(120, ClientConfigBounds.MAX_WHEEL_INNER_RADIUS);
		assertEquals(300, ClientConfigBounds.MAX_WHEEL_OUTER_RADIUS);
		assertEquals(10, ClientConfigBounds.WHEEL_HOLD_MILLIS_STEP);
		assertEquals(5, ClientConfigBounds.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP);
		assertEquals(100, ClientConfigBounds.WHEEL_TIMEOUT_MILLIS_STEP);
		assertEquals(1, ClientConfigBounds.CANCEL_HALF_CONE_ANGLE_DEGREES_STEP);
		assertEquals(1, ClientConfigBounds.WHEEL_INNER_RADIUS_STEP);
		assertEquals(1, ClientConfigBounds.WHEEL_OUTER_RADIUS_STEP);
		assertEquals(5, ClientConfigBounds.WHEEL_OPACITY_STEP);
		assertEquals(10, ClientConfigBounds.WHEEL_FONT_SIZE_STEP);
		assertEquals(10, ClientConfigBounds.MIN_WHEEL_TARGET_FONT_SIZE);
		assertEquals(1000, ClientConfigBounds.MAX_WHEEL_TARGET_FONT_SIZE);
		assertEquals(10, ClientConfigBounds.WHEEL_TARGET_FONT_SIZE_STEP);
		assertEquals(0, ClientConfigBounds.DEFAULT_MARKER_DISPLAY_DURATION);
		assertEquals(0, ClientConfigBounds.FOLLOW_SERVER_MARKER_DISPLAY_DURATION);
		assertEquals(1, ClientConfigBounds.MIN_MARKER_DISPLAY_DURATION);
		assertEquals(3600, ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION);
		assertEquals(1, ClientConfigBounds.MARKER_DISPLAY_DURATION_STEP);
	}

	@Test
	void wheelHoldMillisClampsDirectValues() {
		assertEquals(50, ClientConfigBounds.clampWheelHoldMillis(Integer.MIN_VALUE));
		assertEquals(100, ClientConfigBounds.clampWheelHoldMillis(100));
		assertEquals(750, ClientConfigBounds.clampWheelHoldMillis(750));
		assertEquals(2000, ClientConfigBounds.clampWheelHoldMillis(Integer.MAX_VALUE));
	}

	@Test
	void compatibilitySliceMaximumUsesTheEffectiveHoldFloorAndSafeLowerBound() {
		assertEquals(50, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(100));
		assertEquals(55, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(110));
		assertEquals(150, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(300));
		assertEquals(500, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(2000));
		assertEquals(25, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(Integer.MIN_VALUE));
		assertEquals(10, ClientConfigBounds.clampLongPressCompatibilitySliceMillis(Integer.MIN_VALUE, 100));
		assertEquals(50, ClientConfigBounds.clampLongPressCompatibilitySliceMillis(Integer.MAX_VALUE, 100));
	}

	@Test
	void wheelTimeoutMillisClampsDirectValues() {
		assertEquals(500, ClientConfigBounds.clampWheelTimeoutMillis(Integer.MIN_VALUE));
		assertEquals(5000, ClientConfigBounds.clampWheelTimeoutMillis(5000));
		assertEquals(30000, ClientConfigBounds.clampWheelTimeoutMillis(Integer.MAX_VALUE));
	}

	@Test
	void cancelHalfConeAngleClampsDirectValues() {
		assertEquals(1, ClientConfigBounds.clampCancelHalfConeAngleDegrees(Integer.MIN_VALUE));
		assertEquals(5, ClientConfigBounds.clampCancelHalfConeAngleDegrees(5));
		assertEquals(90, ClientConfigBounds.clampCancelHalfConeAngleDegrees(Integer.MAX_VALUE));
	}

	@Test
	void visualWheelBoundsPreserveTheMinimumAnnulus() {
		assertEquals(6, ClientConfigBounds.clampWheelInnerRadius(Integer.MIN_VALUE));
		assertEquals(120, ClientConfigBounds.clampWheelInnerRadius(Integer.MAX_VALUE));
		assertEquals(20, ClientConfigBounds.clampWheelOuterRadius(Integer.MIN_VALUE));
		assertEquals(300, ClientConfigBounds.clampWheelOuterRadius(Integer.MAX_VALUE));
		assertEquals(8, ClientConfigBounds.MIN_WHEEL_ANNULUS_THICKNESS);
	}

	@Test
	void crossFieldRadiusClampUsesOuterThenInnerOrder() {
		assertEquals(
			new ClientConfigBounds.WheelRadii(6, 20),
			ClientConfigBounds.clampWheelRadii(Integer.MIN_VALUE, Integer.MIN_VALUE));
		assertEquals(
			new ClientConfigBounds.WheelRadii(12, 20),
			ClientConfigBounds.clampWheelRadii(Integer.MAX_VALUE, Integer.MIN_VALUE));
		assertEquals(
			new ClientConfigBounds.WheelRadii(120, 300),
			ClientConfigBounds.clampWheelRadii(Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertEquals(
			new ClientConfigBounds.WheelRadii(6, 300),
			ClientConfigBounds.clampWheelRadii(Integer.MIN_VALUE, Integer.MAX_VALUE));
	}

	@Test
	void opacityAndFontSizeClampDirectValues() {
		assertEquals(0, ClientConfigBounds.clampWheelOpacity(Integer.MIN_VALUE));
		assertEquals(50, ClientConfigBounds.clampWheelOpacity(50));
		assertEquals(100, ClientConfigBounds.clampWheelOpacity(Integer.MAX_VALUE));
		assertEquals(10, ClientConfigBounds.clampWheelFontSize(Integer.MIN_VALUE));
		assertEquals(100, ClientConfigBounds.clampWheelFontSize(100));
		assertEquals(1000, ClientConfigBounds.clampWheelFontSize(Integer.MAX_VALUE));
		assertEquals(10, ClientConfigBounds.clampWheelTargetFontSize(Integer.MIN_VALUE));
		assertEquals(1000, ClientConfigBounds.clampWheelTargetFontSize(Integer.MAX_VALUE));
	}

	@Test
	void markerDisplayDurationKeepsFollowServerSentinelAndClampsCustomValues() {
		assertEquals(0, ClientConfigBounds.clampMarkerDisplayDuration(Integer.MIN_VALUE));
		assertEquals(0, ClientConfigBounds.clampMarkerDisplayDuration(0));
		assertEquals(1, ClientConfigBounds.clampMarkerDisplayDuration(1));
		assertEquals(60, ClientConfigBounds.clampMarkerDisplayDuration(60));
		assertEquals(3600, ClientConfigBounds.clampMarkerDisplayDuration(Integer.MAX_VALUE));
	}
}
