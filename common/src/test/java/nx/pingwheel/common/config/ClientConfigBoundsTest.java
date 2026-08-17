package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigBoundsTest {

	@Test
	void defaultsAndUiStepsMatchTheInteractionDefaults() {
		assertEquals(300, ClientConfigBounds.DEFAULT_WHEEL_HOLD_MILLIS);
		assertEquals(5000, ClientConfigBounds.DEFAULT_WHEEL_TIMEOUT_MILLIS);
		assertEquals(5, ClientConfigBounds.DEFAULT_CANCEL_HALF_CONE_ANGLE_DEGREES);
		assertEquals(50, ClientConfigBounds.WHEEL_HOLD_MILLIS_STEP);
		assertEquals(500, ClientConfigBounds.WHEEL_TIMEOUT_MILLIS_STEP);
		assertEquals(1, ClientConfigBounds.CANCEL_HALF_CONE_ANGLE_DEGREES_STEP);
	}

	@Test
	void wheelHoldMillisClampsDirectValues() {
		assertEquals(100, ClientConfigBounds.clampWheelHoldMillis(Integer.MIN_VALUE));
		assertEquals(100, ClientConfigBounds.clampWheelHoldMillis(100));
		assertEquals(750, ClientConfigBounds.clampWheelHoldMillis(750));
		assertEquals(2000, ClientConfigBounds.clampWheelHoldMillis(Integer.MAX_VALUE));
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
}
