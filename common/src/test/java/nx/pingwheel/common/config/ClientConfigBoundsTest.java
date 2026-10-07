package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigBoundsTest {
	@Test
	void requestedDefaultsRemainIndependent() {
		ClientConfig config = new ClientConfig();
		assertEquals(150, config.getWheelHoldMillis());
		assertEquals(20, ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS);
		assertEquals(300, config.getWheelFontSize());
		assertEquals(100, config.getWheelTargetFontSize());
		assertEquals(100, config.getConfigurationNoticeSize());
		assertEquals(100, config.getWheelTargetOpacity());
		assertEquals(5, ClientConfigBounds.WHEEL_TARGET_OPACITY_STEP);
		config.setWheelTargetOpacity(-1);
		assertEquals(0, config.getWheelTargetOpacity());
		config.setWheelTargetOpacity(101);
		assertEquals(100, config.getWheelTargetOpacity());
	}

	@Test
	void wheelHoldMillisClampsDirectValues() {
		int inRange = ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS + 1;
		assertEquals(ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS, ClientConfigBounds.clampWheelHoldMillis(Integer.MIN_VALUE));
		assertEquals(inRange, ClientConfigBounds.clampWheelHoldMillis(inRange));
		assertEquals(ClientConfigBounds.MAX_WHEEL_HOLD_MILLIS, ClientConfigBounds.clampWheelHoldMillis(Integer.MAX_VALUE));
	}

	@Test
	void compatibilitySliceMaximumFitsTheHoldAndUiStep() {
		int step = ClientConfigBounds.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP;
		for (int hold : new int[] {
			ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS, 213, ClientConfigBounds.MAX_WHEEL_HOLD_MILLIS
		}) {
			int maximum = ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(hold);
			assertTrue(maximum >= ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
			assertTrue(maximum <= ClientConfigBounds.MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
			assertTrue(maximum <= Math.max(hold / 2, ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS));
			assertEquals(0, maximum % step);
			if (hold / 2 >= ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS
				&& hold / 2 <= ClientConfigBounds.MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS) {
				assertTrue(hold / 2 - maximum < step);
			}
		}
		int invalidHoldMaximum = ClientConfigBounds.effectiveLongPressCompatibilitySliceMaxMillis(Integer.MIN_VALUE);
		assertTrue(invalidHoldMaximum >= ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		assertTrue(invalidHoldMaximum <= Math.max(ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS / 2,
			ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS));
		if (ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS / 2
			>= ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS) {
			assertTrue(ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS / 2 - invalidHoldMaximum < step);
		}
	}

	@Test
	void compatibilitySliceClampHonorsAnExplicitValidHold() {
		int hold = 210;
		assertEquals(
			ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			ClientConfigBounds.clampLongPressCompatibilitySliceMillis(Integer.MIN_VALUE, hold));
		assertEquals(35, ClientConfigBounds.clampLongPressCompatibilitySliceMillis(35, hold));
		assertEquals(hold / 2, ClientConfigBounds.clampLongPressCompatibilitySliceMillis(Integer.MAX_VALUE, hold));
	}

	@Test
	void cancelHalfConeAngleClampsDirectValues() {
		int inRange = ClientConfigBounds.MIN_CANCEL_HALF_CONE_ANGLE_DEGREES + 1;
		assertEquals(ClientConfigBounds.MIN_CANCEL_HALF_CONE_ANGLE_DEGREES,
			ClientConfigBounds.clampCancelHalfConeAngleDegrees(Integer.MIN_VALUE));
		assertEquals(inRange, ClientConfigBounds.clampCancelHalfConeAngleDegrees(inRange));
		assertEquals(ClientConfigBounds.MAX_CANCEL_HALF_CONE_ANGLE_DEGREES,
			ClientConfigBounds.clampCancelHalfConeAngleDegrees(Integer.MAX_VALUE));
	}

	@Test
	void visualWheelRadiiClampAtTheirEndpoints() {
		assertEquals(ClientConfigBounds.MIN_WHEEL_INNER_RADIUS, ClientConfigBounds.clampWheelInnerRadius(Integer.MIN_VALUE));
		assertEquals(ClientConfigBounds.MAX_WHEEL_INNER_RADIUS, ClientConfigBounds.clampWheelInnerRadius(Integer.MAX_VALUE));
		assertEquals(ClientConfigBounds.MIN_WHEEL_OUTER_RADIUS, ClientConfigBounds.clampWheelOuterRadius(Integer.MIN_VALUE));
		assertEquals(ClientConfigBounds.MAX_WHEEL_OUTER_RADIUS, ClientConfigBounds.clampWheelOuterRadius(Integer.MAX_VALUE));
	}

	@Test
	void crossFieldRadiusClampUsesOuterThenInnerOrder() {
		var minimum = ClientConfigBounds.clampWheelRadii(Integer.MIN_VALUE, Integer.MIN_VALUE);
		assertEquals(ClientConfigBounds.MIN_WHEEL_INNER_RADIUS, minimum.innerRadius());
		assertEquals(ClientConfigBounds.MIN_WHEEL_OUTER_RADIUS, minimum.outerRadius());

		var constrained = ClientConfigBounds.clampWheelRadii(Integer.MAX_VALUE, Integer.MIN_VALUE);
		assertEquals(ClientConfigBounds.MIN_WHEEL_OUTER_RADIUS, constrained.outerRadius());
		assertEquals(
			ClientConfigBounds.MIN_WHEEL_OUTER_RADIUS - ClientConfigBounds.MIN_WHEEL_ANNULUS_THICKNESS,
			constrained.innerRadius());

		var maximum = ClientConfigBounds.clampWheelRadii(Integer.MAX_VALUE, Integer.MAX_VALUE);
		assertEquals(ClientConfigBounds.MAX_WHEEL_INNER_RADIUS, maximum.innerRadius());
		assertEquals(ClientConfigBounds.MAX_WHEEL_OUTER_RADIUS, maximum.outerRadius());
		assertTrue(maximum.outerRadius() - maximum.innerRadius() >= ClientConfigBounds.MIN_WHEEL_ANNULUS_THICKNESS);

		var wide = ClientConfigBounds.clampWheelRadii(Integer.MIN_VALUE, Integer.MAX_VALUE);
		assertEquals(ClientConfigBounds.MIN_WHEEL_INNER_RADIUS, wide.innerRadius());
		assertEquals(ClientConfigBounds.MAX_WHEEL_OUTER_RADIUS, wide.outerRadius());
	}

	@Test
	void opacityAndFontSizeClampDirectValues() {
		assertEquals(ClientConfigBounds.MIN_WHEEL_OPACITY, ClientConfigBounds.clampWheelOpacity(Integer.MIN_VALUE));
		assertEquals(50, ClientConfigBounds.clampWheelOpacity(50));
		assertEquals(ClientConfigBounds.MAX_WHEEL_OPACITY, ClientConfigBounds.clampWheelOpacity(Integer.MAX_VALUE));
		assertEquals(ClientConfigBounds.MIN_WHEEL_TARGET_OPACITY,
			ClientConfigBounds.clampWheelTargetOpacity(Integer.MIN_VALUE));
		assertEquals(50, ClientConfigBounds.clampWheelTargetOpacity(50));
		assertEquals(ClientConfigBounds.MAX_WHEEL_TARGET_OPACITY,
			ClientConfigBounds.clampWheelTargetOpacity(Integer.MAX_VALUE));
		assertEquals(ClientConfigBounds.MIN_WHEEL_FONT_SIZE, ClientConfigBounds.clampWheelFontSize(Integer.MIN_VALUE));
		assertEquals(100, ClientConfigBounds.clampWheelFontSize(100));
		assertEquals(ClientConfigBounds.MAX_WHEEL_FONT_SIZE, ClientConfigBounds.clampWheelFontSize(Integer.MAX_VALUE));
		assertEquals(ClientConfigBounds.MIN_WHEEL_TARGET_FONT_SIZE, ClientConfigBounds.clampWheelTargetFontSize(Integer.MIN_VALUE));
		assertEquals(ClientConfigBounds.MAX_WHEEL_TARGET_FONT_SIZE, ClientConfigBounds.clampWheelTargetFontSize(Integer.MAX_VALUE));
	}

	@Test
	void markerDisplayDurationKeepsFollowServerSentinelAndClampsCustomValues() {
		assertEquals(0, ClientConfigBounds.clampMarkerDisplayDuration(Integer.MIN_VALUE));
		assertEquals(0, ClientConfigBounds.clampMarkerDisplayDuration(0));
		assertEquals(ClientConfigBounds.MIN_MARKER_DISPLAY_DURATION,
			ClientConfigBounds.clampMarkerDisplayDuration(ClientConfigBounds.MIN_MARKER_DISPLAY_DURATION));
		assertEquals(ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION,
			ClientConfigBounds.clampMarkerDisplayDuration(ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION + 1));
		assertEquals(ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION,
			ClientConfigBounds.clampMarkerDisplayDuration(Integer.MAX_VALUE));
	}
}
