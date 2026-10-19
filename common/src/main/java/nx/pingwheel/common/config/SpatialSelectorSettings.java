package nx.pingwheel.common.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Client-local spatial selector preferences. Distances are GUI pixels,
 * durations are milliseconds, and the precise capture period is ticks. Missing
 * JSON members receive model defaults; legacy wheel radii are never a source
 * for these values.
 *
 * <p>{@link #snapshot()} supplies a validated immutable set for one held
 * gesture. Editing the live configuration cannot change an existing snapshot.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public final class SpatialSelectorSettings {

	public static final int MIN_DEADZONE = 12;
	public static final int MAX_DEADZONE = 32;
	public static final int DEFAULT_DEADZONE = 24;
	public static final int DEADZONE_STEP = 1;

	public static final int MIN_STROKE = 40;
	public static final int MAX_STROKE = 85;
	public static final int DEFAULT_STROKE = 50;
	public static final int STROKE_STEP = 1;

	public static final int MIN_DWELL_MILLIS = 80;
	public static final int MAX_DWELL_MILLIS = 360;
	public static final int DEFAULT_DWELL_MILLIS = 180;
	public static final int DWELL_MILLIS_STEP = 10;

	/** Visual root-node distance, independent from every gesture threshold. */
	public static final int MIN_ROOT_DISTANCE = 32;
	public static final int MAX_ROOT_DISTANCE = 120;
	public static final int DEFAULT_ROOT_DISTANCE = 55;
	public static final int ROOT_DISTANCE_STEP = 1;

	/** Non-root visual radius, relative to the viewport-derived orbit. */
	public static final BigDecimal MIN_SUBMENU_RADIUS_SCALE = new BigDecimal("1.0");
	public static final BigDecimal MAX_SUBMENU_RADIUS_SCALE = new BigDecimal("4.0");
	public static final BigDecimal DEFAULT_SUBMENU_RADIUS_SCALE = new BigDecimal("1.5");
	public static final BigDecimal SUBMENU_RADIUS_SCALE_STEP = new BigDecimal("0.1");

	/** Maximum inventory content-panel dimensions relative to the GUI viewport. */
	public static final BigDecimal MIN_INVENTORY_MAX_WIDTH_FRACTION = new BigDecimal("0.2");
	public static final BigDecimal MAX_INVENTORY_MAX_WIDTH_FRACTION = new BigDecimal("0.8");
	public static final BigDecimal DEFAULT_INVENTORY_MAX_WIDTH_FRACTION = new BigDecimal("0.3");
	public static final BigDecimal INVENTORY_MAX_WIDTH_FRACTION_STEP = new BigDecimal("0.05");
	public static final BigDecimal MIN_INVENTORY_MAX_HEIGHT_FRACTION = new BigDecimal("0.3");
	public static final BigDecimal MAX_INVENTORY_MAX_HEIGHT_FRACTION = new BigDecimal("0.95");
	public static final BigDecimal DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION = new BigDecimal("0.7");
	public static final BigDecimal INVENTORY_MAX_HEIGHT_FRACTION_STEP = new BigDecimal("0.05");

	/** Confirmed Back-hover dwell range, step and disabled-by-default policy. */
	public static final int MIN_HOVER_MILLIS = 100;
	public static final int MAX_HOVER_MILLIS = 2000;
	public static final int HOVER_MILLIS_STEP = 50;
	public static final int DEFAULT_HOVER_MILLIS = 500;
	public static final boolean DEFAULT_HOVER_ENABLED = false;

	/** Content property/item intent menus are opt-in and disabled by default. */
	public static final boolean DEFAULT_CONTENT_INTENT_ENABLED = false;

	public static final int MIN_GESTURE_SKIP_HOLD_MILLIS = 25;
	public static final int MAX_GESTURE_SKIP_HOLD_MILLIS = 200;
	public static final int DEFAULT_GESTURE_SKIP_HOLD_MILLIS = 75;
	public static final int GESTURE_SKIP_HOLD_MILLIS_STEP = 5;

	/** Precise live-capture period in ticks; one tick captures without an interval. */
	public static final int MIN_PRECISE_CAPTURE_PERIOD_TICKS = 1;
	public static final int MAX_PRECISE_CAPTURE_PERIOD_TICKS = 50;
	public static final int DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS = 1;
	public static final int PRECISE_CAPTURE_PERIOD_TICKS_STEP = 1;

	/** Shared pointer/option trail retained-point cap; not a duration or GUI arc length. */
	public static final int MIN_TRAIL_LENGTH = 16;
	public static final int MAX_TRAIL_LENGTH = 512;
	public static final int DEFAULT_TRAIL_LENGTH = 130;
	public static final int TRAIL_LENGTH_STEP = 1;

	/** Independent trail opacity; zero hides only the trail. */
	public static final int MIN_TRAIL_OPACITY = 0;
	public static final int MAX_TRAIL_OPACITY = 100;
	public static final int DEFAULT_TRAIL_OPACITY = 50;
	public static final int TRAIL_OPACITY_STEP = 5;

	private int deadzone = DEFAULT_DEADZONE;
	private int stroke = DEFAULT_STROKE;
	private int dwellMillis = DEFAULT_DWELL_MILLIS;
	private int rootDistance = DEFAULT_ROOT_DISTANCE;
	private BigDecimal submenuRadiusScale = DEFAULT_SUBMENU_RADIUS_SCALE;
	private BigDecimal inventoryMaxWidthFraction = DEFAULT_INVENTORY_MAX_WIDTH_FRACTION;
	private BigDecimal inventoryMaxHeightFraction = DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION;
	private boolean hoverEnabled = DEFAULT_HOVER_ENABLED;
	private int hoverMillis = DEFAULT_HOVER_MILLIS;
	private boolean showTrail = true;
	private int trailLength = DEFAULT_TRAIL_LENGTH;
	private int trailOpacity = DEFAULT_TRAIL_OPACITY;
	private boolean reduceMotion = false;
	private int preciseCapturePeriodTicks = DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS;
	private boolean contentIntentEnabled = DEFAULT_CONTENT_INTENT_ENABLED;
	private boolean gestureSkipHoldEnabled = false;
	private int gestureSkipHoldMillis = DEFAULT_GESTURE_SKIP_HOLD_MILLIS;

	/** Clamps numeric preferences independently, without changing boolean choices. */
	public void validate() {
		deadzone = clampDeadzone(deadzone);
		stroke = clampStroke(stroke);
		dwellMillis = clampDwellMillis(dwellMillis);
		rootDistance = clampRootDistance(rootDistance);
		submenuRadiusScale = clampSubmenuRadiusScale(submenuRadiusScale);
		inventoryMaxWidthFraction = clampInventoryMaxWidthFraction(inventoryMaxWidthFraction);
		inventoryMaxHeightFraction = clampInventoryMaxHeightFraction(inventoryMaxHeightFraction);
		hoverMillis = clampHoverMillis(hoverMillis);
		preciseCapturePeriodTicks = clampPreciseCapturePeriodTicks(preciseCapturePeriodTicks);
		trailLength = clampTrailLength(trailLength);
		trailOpacity = clampTrailOpacity(trailOpacity);
		gestureSkipHoldMillis = clampGestureSkipHoldMillis(gestureSkipHoldMillis);
	}

	public void setDeadzone(int value) { deadzone = clampDeadzone(value); }
	public void setStroke(int value) { stroke = clampStroke(value); }
	public void setDwellMillis(int value) { dwellMillis = clampDwellMillis(value); }
	public void setRootDistance(int value) { rootDistance = clampRootDistance(value); }
	public void setSubmenuRadiusScale(BigDecimal value) { submenuRadiusScale = clampSubmenuRadiusScale(value); }
	public void setInventoryMaxWidthFraction(BigDecimal value) { inventoryMaxWidthFraction = clampInventoryMaxWidthFraction(value); }
	public void setInventoryMaxHeightFraction(BigDecimal value) { inventoryMaxHeightFraction = clampInventoryMaxHeightFraction(value); }
	public void setHoverMillis(int value) { hoverMillis = clampHoverMillis(value); }
	public void setPreciseCapturePeriodTicks(int value) { preciseCapturePeriodTicks = clampPreciseCapturePeriodTicks(value); }
	public void setTrailLength(int value) { trailLength = clampTrailLength(value); }
	public void setTrailOpacity(int value) { trailOpacity = clampTrailOpacity(value); }
	public void setGestureSkipHoldMillis(int value) { gestureSkipHoldMillis = clampGestureSkipHoldMillis(value); }

	public static int clampGestureSkipHoldMillis(int value) {
		return Math.clamp(value, MIN_GESTURE_SKIP_HOLD_MILLIS, MAX_GESTURE_SKIP_HOLD_MILLIS);
	}

	public static int clampDeadzone(int value) {
		return Math.clamp(value, MIN_DEADZONE, MAX_DEADZONE);
	}

	public static int clampStroke(int value) {
		return Math.clamp(value, MIN_STROKE, MAX_STROKE);
	}

	public static int clampDwellMillis(int value) {
		return Math.clamp(value, MIN_DWELL_MILLIS, MAX_DWELL_MILLIS);
	}

	public static int clampRootDistance(int value) {
		return Math.clamp(value, MIN_ROOT_DISTANCE, MAX_ROOT_DISTANCE);
	}

	public static BigDecimal clampSubmenuRadiusScale(BigDecimal value) {
		if (value == null) return DEFAULT_SUBMENU_RADIUS_SCALE;
		if (value.compareTo(MIN_SUBMENU_RADIUS_SCALE) < 0) return MIN_SUBMENU_RADIUS_SCALE;
		if (value.compareTo(MAX_SUBMENU_RADIUS_SCALE) > 0) return MAX_SUBMENU_RADIUS_SCALE;

		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	public static int clampHoverMillis(int value) {
		return Math.clamp(value, MIN_HOVER_MILLIS, MAX_HOVER_MILLIS);
	}

	public static BigDecimal clampInventoryMaxWidthFraction(BigDecimal value) {
		if (value == null) return DEFAULT_INVENTORY_MAX_WIDTH_FRACTION;
		if (value.compareTo(MIN_INVENTORY_MAX_WIDTH_FRACTION) < 0) return MIN_INVENTORY_MAX_WIDTH_FRACTION;
		if (value.compareTo(MAX_INVENTORY_MAX_WIDTH_FRACTION) > 0) return MAX_INVENTORY_MAX_WIDTH_FRACTION;

		return value.stripTrailingZeros();
	}

	public static BigDecimal clampInventoryMaxHeightFraction(BigDecimal value) {
		if (value == null) return DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION;
		if (value.compareTo(MIN_INVENTORY_MAX_HEIGHT_FRACTION) < 0) return MIN_INVENTORY_MAX_HEIGHT_FRACTION;
		if (value.compareTo(MAX_INVENTORY_MAX_HEIGHT_FRACTION) > 0) return MAX_INVENTORY_MAX_HEIGHT_FRACTION;

		return value.stripTrailingZeros();
	}

	public static int clampPreciseCapturePeriodTicks(int value) {
		return Math.clamp(value, MIN_PRECISE_CAPTURE_PERIOD_TICKS, MAX_PRECISE_CAPTURE_PERIOD_TICKS);
	}

	public static int clampTrailLength(int value) {
		return Math.clamp(value, MIN_TRAIL_LENGTH, MAX_TRAIL_LENGTH);
	}

	public static int clampTrailOpacity(int value) {
		return Math.clamp(value, MIN_TRAIL_OPACITY, MAX_TRAIL_OPACITY);
	}

	/** Safe even before validation of a deserialized object; does not mutate it. */
	public Snapshot snapshot() {
		return new Snapshot(clampDeadzone(deadzone), clampStroke(stroke),
			clampDwellMillis(dwellMillis), clampRootDistance(rootDistance),
			hoverEnabled, clampHoverMillis(hoverMillis),
			showTrail, reduceMotion, clampPreciseCapturePeriodTicks(preciseCapturePeriodTicks),
			clampSubmenuRadiusScale(submenuRadiusScale),
			clampTrailLength(trailLength), clampTrailOpacity(trailOpacity),
			contentIntentEnabled, gestureSkipHoldEnabled, clampGestureSkipHoldMillis(gestureSkipHoldMillis),
			clampInventoryMaxWidthFraction(inventoryMaxWidthFraction),
			clampInventoryMaxHeightFraction(inventoryMaxHeightFraction));
	}

	public record Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
		boolean hoverEnabled, int hoverMillis,
		boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks,
		BigDecimal submenuRadiusScale, int trailLength, int trailOpacity,
		boolean contentIntentEnabled, boolean gestureSkipHoldEnabled, int gestureSkipHoldMillis,
		BigDecimal inventoryMaxWidthFraction, BigDecimal inventoryMaxHeightFraction) {

		/** Previous snapshot forms retain the initialized inventory panel limits. */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks,
			BigDecimal submenuRadiusScale, int trailLength, int trailOpacity,
			boolean contentIntentEnabled, boolean gestureSkipHoldEnabled, int gestureSkipHoldMillis) {
			this(deadzone, stroke, dwellMillis, rootDistance, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, preciseCapturePeriodTicks, submenuRadiusScale,
				trailLength, trailOpacity, contentIntentEnabled, gestureSkipHoldEnabled, gestureSkipHoldMillis,
				DEFAULT_INVENTORY_MAX_WIDTH_FRACTION, DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION);
		}

		/** Previous snapshot forms keep the opt-in gesture shortcut disabled. */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks,
			BigDecimal submenuRadiusScale, int trailLength, int trailOpacity, boolean contentIntentEnabled) {
			this(deadzone, stroke, dwellMillis, rootDistance, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, preciseCapturePeriodTicks, submenuRadiusScale,
				trailLength, trailOpacity, contentIntentEnabled, false, DEFAULT_GESTURE_SKIP_HOLD_MILLIS);
		}

		/**
		 * Compatibility constructor for callers that predate the content-intent
		 * toggle; the confirmed default keeps the direct Attention annotation.
		 */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks,
			BigDecimal submenuRadiusScale, int trailLength, int trailOpacity) {
			this(deadzone, stroke, dwellMillis, rootDistance, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, preciseCapturePeriodTicks,
				submenuRadiusScale, trailLength, trailOpacity, DEFAULT_CONTENT_INTENT_ENABLED);
		}

		/**
		 * Compatibility constructor for callers that predate the trail length and
		 * opacity; supplies the default retained history and half opacity without
		 * changing the caller's trail visibility choice.
		 */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks,
			BigDecimal submenuRadiusScale) {
			this(deadzone, stroke, dwellMillis, rootDistance, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, preciseCapturePeriodTicks,
				submenuRadiusScale, DEFAULT_TRAIL_LENGTH, DEFAULT_TRAIL_OPACITY);
		}

		/**
		 * Compatibility constructor for callers that predate the submenu radius
		 * scale; the confirmed default is the baseline 1.5 multiplier.
		 */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks) {
			this(deadzone, stroke, dwellMillis, rootDistance, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, preciseCapturePeriodTicks, DEFAULT_SUBMENU_RADIUS_SCALE);
		}

		/**
		 * Compatibility constructor for callers that predate the precise capture
		 * period; the confirmed default captures every tick.
		 */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion) {
			this(deadzone, stroke, dwellMillis, rootDistance, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS);
		}
	}
}
