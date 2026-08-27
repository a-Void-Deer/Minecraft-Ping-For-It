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
	public static final int WHEEL_HOLD_MILLIS_STEP = 10;

	public static final int DEFAULT_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS = 20;
	public static final int MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS = 10;
	public static final int MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS = 300;
	public static final int LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP = 5;

	public static final int DEFAULT_WHEEL_TIMEOUT_MILLIS = 5000;
	public static final int MIN_WHEEL_TIMEOUT_MILLIS = 1000;
	public static final int MAX_WHEEL_TIMEOUT_MILLIS = 30000;
	public static final int WHEEL_TIMEOUT_MILLIS_STEP = 200;

	public static final int DEFAULT_CANCEL_HALF_CONE_ANGLE_DEGREES = 5;
	public static final int MIN_CANCEL_HALF_CONE_ANGLE_DEGREES = 1;
	public static final int MAX_CANCEL_HALF_CONE_ANGLE_DEGREES = 45;
	public static final int CANCEL_HALF_CONE_ANGLE_DEGREES_STEP = 1;

	public static final int DEFAULT_WHEEL_INNER_RADIUS = 14;
	public static final int MIN_WHEEL_INNER_RADIUS = 6;
	public static final int MAX_WHEEL_INNER_RADIUS = 120;
	public static final int WHEEL_INNER_RADIUS_STEP = 1;

	public static final int DEFAULT_WHEEL_OUTER_RADIUS = 39;
	public static final int MIN_WHEEL_OUTER_RADIUS = 20;
	public static final int MAX_WHEEL_OUTER_RADIUS = 300;
	public static final int WHEEL_OUTER_RADIUS_STEP = 1;
	public static final int MIN_WHEEL_ANNULUS_THICKNESS = 8;

	public static final int DEFAULT_WHEEL_OPACITY = 100;
	public static final int MIN_WHEEL_OPACITY = 0;
	public static final int MAX_WHEEL_OPACITY = 100;
	public static final int WHEEL_OPACITY_STEP = 5;

	public static final int DEFAULT_WHEEL_FONT_SIZE = 100;
	public static final int MIN_WHEEL_FONT_SIZE = 10;
	public static final int MAX_WHEEL_FONT_SIZE = 500;
	public static final int WHEEL_FONT_SIZE_STEP = 10;

	/** The target-name setting uses the same shared font bounds as option labels. */
	public static final int DEFAULT_WHEEL_TARGET_FONT_SIZE = DEFAULT_WHEEL_FONT_SIZE;
	public static final int MIN_WHEEL_TARGET_FONT_SIZE = MIN_WHEEL_FONT_SIZE;
	public static final int MAX_WHEEL_TARGET_FONT_SIZE = MAX_WHEEL_FONT_SIZE;
	public static final int WHEEL_TARGET_FONT_SIZE_STEP = WHEEL_FONT_SIZE_STEP;

	public static final int DEFAULT_CONFIGURATION_NOTICE_SIZE = 100;
	public static final int MIN_CONFIGURATION_NOTICE_SIZE = 0;
	public static final int MAX_CONFIGURATION_NOTICE_SIZE = 500;
	public static final int CONFIGURATION_NOTICE_SIZE_STEP = 10;

	private ClientConfigBounds() {}

	public record WheelRadii(int innerRadius, int outerRadius) {}

	public static int clampWheelHoldMillis(int value) {
		return Math.clamp(value, MIN_WHEEL_HOLD_MILLIS, MAX_WHEEL_HOLD_MILLIS);
	}

	/**
	 * Calculates the maximum compatibility gap for a validated wheel-hold
	 * duration.  The floor is intentional: a slice never becomes a value that
	 * is not representable by the five-millisecond UI step.
	 */
	public static int effectiveLongPressCompatibilitySliceMaxMillis(int wheelHoldMillis) {
		int effectiveHoldMillis = clampWheelHoldMillis(wheelHoldMillis);
		int halfHoldMillis = Math.floorDiv(effectiveHoldMillis, 2);
		int steppedMaximum = Math.floorDiv(
			halfHoldMillis,
			LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP)
			* LONG_PRESS_COMPATIBILITY_SLICE_MILLIS_STEP;

		// Keep the range valid even if the wheel-hold lower bound changes in a
		// future version.  The current bounds produce a maximum of at least 50.
		return Math.clamp(
			Math.max(MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS, steppedMaximum),
			MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
	}

	public static int clampLongPressCompatibilitySliceMillis(int value, int wheelHoldMillis) {
		return Math.clamp(
			value,
			MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			effectiveLongPressCompatibilitySliceMaxMillis(wheelHoldMillis));
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

	public static int clampWheelInnerRadius(int value) {
		return Math.clamp(value, MIN_WHEEL_INNER_RADIUS, MAX_WHEEL_INNER_RADIUS);
	}

	public static int clampWheelOuterRadius(int value) {
		return Math.clamp(value, MIN_WHEEL_OUTER_RADIUS, MAX_WHEEL_OUTER_RADIUS);
	}

	/**
	 * Clamps both wheel radii while preserving the minimum visible annulus.
	 * The outer radius is normalized first, then the inner radius is limited to
	 * the space left inside it. This makes invalid loaded pairs converge to one
	 * deterministic result and gives setters the same reconciliation policy.
	 */
	public static WheelRadii clampWheelRadii(int innerRadius, int outerRadius) {
		int clampedOuterRadius = clampWheelOuterRadius(outerRadius);
		int maximumInnerRadius = Math.min(
			MAX_WHEEL_INNER_RADIUS,
			clampedOuterRadius - MIN_WHEEL_ANNULUS_THICKNESS);
		int clampedInnerRadius = Math.clamp(
			innerRadius,
			MIN_WHEEL_INNER_RADIUS,
			maximumInnerRadius);

		return new WheelRadii(clampedInnerRadius, clampedOuterRadius);
	}

	public static int clampWheelOpacity(int value) {
		return Math.clamp(value, MIN_WHEEL_OPACITY, MAX_WHEEL_OPACITY);
	}

	public static int clampWheelFontSize(int value) {
		return Math.clamp(value, MIN_WHEEL_FONT_SIZE, MAX_WHEEL_FONT_SIZE);
	}

	public static int clampWheelTargetFontSize(int value) {
		return Math.clamp(value, MIN_WHEEL_TARGET_FONT_SIZE, MAX_WHEEL_TARGET_FONT_SIZE);
	}

	public static int clampConfigurationNoticeSize(int value) {
		return Math.clamp(value, MIN_CONFIGURATION_NOTICE_SIZE, MAX_CONFIGURATION_NOTICE_SIZE);
	}
}
