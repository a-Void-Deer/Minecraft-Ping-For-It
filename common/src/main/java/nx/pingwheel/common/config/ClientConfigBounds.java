package nx.pingwheel.common.config;

/**
 * Pure bounds and clamps for client-side interaction settings.
 *
 * <p>The class deliberately has no Minecraft, loader, or file-system
 * dependency. {@link ClientConfig#validate()} delegates here so values read
 * from JSON and values changed directly have the same safe boundaries.
 */
public final class ClientConfigBounds {

	public static final int DEFAULT_WHEEL_HOLD_MILLIS = 300;
	public static final int MIN_WHEEL_HOLD_MILLIS = 100;
	public static final int MAX_WHEEL_HOLD_MILLIS = 2000;
	public static final int WHEEL_HOLD_MILLIS_STEP = 50;

	public static final int DEFAULT_WHEEL_TIMEOUT_MILLIS = 5000;
	public static final int MIN_WHEEL_TIMEOUT_MILLIS = 1000;
	public static final int MAX_WHEEL_TIMEOUT_MILLIS = 30000;
	public static final int WHEEL_TIMEOUT_MILLIS_STEP = 500;

	public static final int DEFAULT_CANCEL_HALF_CONE_ANGLE_DEGREES = 5;
	public static final int MIN_CANCEL_HALF_CONE_ANGLE_DEGREES = 1;
	public static final int MAX_CANCEL_HALF_CONE_ANGLE_DEGREES = 45;
	public static final int CANCEL_HALF_CONE_ANGLE_DEGREES_STEP = 1;

	private ClientConfigBounds() {}

	public static int clampWheelHoldMillis(int value) {
		return Math.clamp(value, MIN_WHEEL_HOLD_MILLIS, MAX_WHEEL_HOLD_MILLIS);
	}

	public static int clampWheelTimeoutMillis(int value) {
		return Math.clamp(value, MIN_WHEEL_TIMEOUT_MILLIS, MAX_WHEEL_TIMEOUT_MILLIS);
	}

	public static int clampCancelHalfConeAngleDegrees(int value) {
		return Math.clamp(
			value,
			MIN_CANCEL_HALF_CONE_ANGLE_DEGREES,
			MAX_CANCEL_HALF_CONE_ANGLE_DEGREES);
	}
}
