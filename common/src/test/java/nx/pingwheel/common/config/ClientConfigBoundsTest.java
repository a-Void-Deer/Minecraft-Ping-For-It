package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigBoundsTest {

	@Test
	void defaultsAndUiStepsMatchTheInteractionDefaults() {
		assertEquals(300, ClientConfigBounds.DEFAULT_WHEEL_HOLD_MILLIS);
		assertEquals(20, ClientConfigBounds.DEFAULT_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertEquals(10, ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertEquals(300, ClientConfigBounds.MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertEquals(5000, ClientConfigBounds.DEFAULT_WHEEL_TIMEOUT_MILLIS);
		assertEquals(5, ClientConfigBounds.DEFAULT_CANCEL_HALF_CONE_ANGLE_DEGREES);
		assertEquals(14, ClientConfigBounds.DEFAULT_WHEEL_INNER_RADIUS);
		assertEquals(39, ClientConfigBounds.DEFAULT_WHEEL_OUTER_RADIUS);
		assertEquals(100, ClientConfigBounds.DEFAULT_WHEEL_OPACITY);
		assertEquals(100, ClientConfigBounds.DEFAULT_WHEEL_FONT_SIZE);
		assertEquals(100, ClientConfigBounds.DEFAULT_WHEEL_TARGET_FONT_SIZE);
		assertEquals(10, ClientConfigBounds.MIN_WHEEL_FONT_SIZE);
		assertEquals(500, ClientConfigBounds.MAX_WHEEL_FONT_SIZE);
		assertEquals(120, ClientConfigBounds.MAX_WHEEL_INNER_RADIUS);
		assertEquals(300, ClientConfigBounds.MAX_WHEEL_OUTER_RADIUS);
		assertEquals(10, ClientConfigBounds.WHEEL_HOLD_MILLIS_STEP);
		assertEquals(5, ClientConfigBounds.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP);
		assertEquals(200, ClientConfigBounds.WHEEL_TIMEOUT_MILLIS_STEP);
		assertEquals(1, ClientConfigBounds.CANCEL_HALF_CONE_ANGLE_DEGREES_STEP);
		assertEquals(1, ClientConfigBounds.WHEEL_INNER_RADIUS_STEP);
		assertEquals(1, ClientConfigBounds.WHEEL_OUTER_RADIUS_STEP);
		assertEquals(5, ClientConfigBounds.WHEEL_OPACITY_STEP);
		assertEquals(10, ClientConfigBounds.WHEEL_FONT_SIZE_STEP);
		assertEquals(10, ClientConfigBounds.MIN_WHEEL_TARGET_FONT_SIZE);
		assertEquals(500, ClientConfigBounds.MAX_WHEEL_TARGET_FONT_SIZE);
		assertEquals(10, ClientConfigBounds.WHEEL_TARGET_FONT_SIZE_STEP);
		assertEquals(0, ClientConfigBounds.DEFAULT_MARKER_DISPLAY_DURATION);
		assertEquals(0, ClientConfigBounds.FOLLOW_SERVER_MARKER_DISPLAY_DURATION);
		assertEquals(1, ClientConfigBounds.MIN_MARKER_DISPLAY_DURATION);
		assertEquals(60, ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION);
		assertEquals(1, ClientConfigBounds.MARKER_DISPLAY_DURATION_STEP);
	}

	@Test
	void wheelHoldMillisClampsDirectValues() {
		assertEquals(100, ClientConfigBounds.clampWheelHoldMillis(Integer.MIN_VALUE));
		assertEquals(100, ClientConfigBounds.clampWheelHoldMillis(100));
		assertEquals(750, ClientConfigBounds.clampWheelHoldMillis(750));
		assertEquals(2000, ClientConfigBounds.clampWheelHoldMillis(Integer.MAX_VALUE));
	}

	@Test
	void compatibilitySliceMaximumUsesTheEffectiveHoldFloorAndSafeLowerBound() {
		assertEquals(50, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(100));
		assertEquals(55, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(110));
		assertEquals(150, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(300));
		assertEquals(300, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(2000));
		assertEquals(50, ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(Integer.MIN_VALUE));
		assertEquals(10, ClientConfigBounds.clampLongPressCompatibilitySliceMillis(Integer.MIN_VALUE, 100));
		assertEquals(50, ClientConfigBounds.clampLongPressCompatibilitySliceMillis(Integer.MAX_VALUE, 100));
	}

	@Test
	void wheelTimeoutMillisClampsDirectValues() {
		assertEquals(1000, ClientConfigBounds.clampWheelTimeoutMillis(Integer.MIN_VALUE));
		assertEquals(5000, ClientConfigBounds.clampWheelTimeoutMillis(5000));
		assertEquals(30000, ClientConfigBounds.clampWheelTimeoutMillis(Integer.MAX_VALUE));
	}

	@Test
	void cancelHalfConeAngleClampsDirectValues() {
		assertEquals(1, ClientConfigBounds.clampCancelHalfConeAngleDegrees(Integer.MIN_VALUE));
		assertEquals(5, ClientConfigBounds.clampCancelHalfConeAngleDegrees(5));
		assertEquals(45, ClientConfigBounds.clampCancelHalfConeAngleDegrees(Integer.MAX_VALUE));
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
		assertEquals(500, ClientConfigBounds.clampWheelFontSize(Integer.MAX_VALUE));
		assertEquals(10, ClientConfigBounds.clampWheelTargetFontSize(Integer.MIN_VALUE));
		assertEquals(500, ClientConfigBounds.clampWheelTargetFontSize(Integer.MAX_VALUE));
	}

	@Test
	void markerDisplayDurationKeepsFollowServerSentinelAndClampsCustomValues() {
		assertEquals(0, ClientConfigBounds.clampMarkerDisplayDuration(Integer.MIN_VALUE));
		assertEquals(0, ClientConfigBounds.clampMarkerDisplayDuration(0));
		assertEquals(1, ClientConfigBounds.clampMarkerDisplayDuration(1));
		assertEquals(60, ClientConfigBounds.clampMarkerDisplayDuration(60));
		assertEquals(60, ClientConfigBounds.clampMarkerDisplayDuration(Integer.MAX_VALUE));
	}
}
