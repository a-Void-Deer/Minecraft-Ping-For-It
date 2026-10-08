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
	public static final int DEFAULT_DEADZONE = 18;
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

	public static final BigDecimal MIN_TARGET_GLIDE = new BigDecimal("0.25");
	public static final BigDecimal MAX_TARGET_GLIDE = new BigDecimal("3");
	public static final BigDecimal DEFAULT_TARGET_GLIDE = new BigDecimal("0.25");
	public static final BigDecimal TARGET_GLIDE_STEP = new BigDecimal("0.25");

	/** Confirmed Back-hover dwell range, step and disabled-by-default policy. */
	public static final int MIN_HOVER_MILLIS = 100;
	public static final int MAX_HOVER_MILLIS = 2000;
	public static final int HOVER_MILLIS_STEP = 50;
	public static final int DEFAULT_HOVER_MILLIS = 500;
	public static final boolean DEFAULT_HOVER_ENABLED = false;

	/** Precise live-capture period in ticks; one tick captures without an interval. */
	public static final int MIN_PRECISE_CAPTURE_PERIOD_TICKS = 1;
	public static final int MAX_PRECISE_CAPTURE_PERIOD_TICKS = 50;
	public static final int DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS = 1;
	public static final int PRECISE_CAPTURE_PERIOD_TICKS_STEP = 1;

	private int deadzone = DEFAULT_DEADZONE;
	private int stroke = DEFAULT_STROKE;
	private int dwellMillis = DEFAULT_DWELL_MILLIS;
	private int rootDistance = DEFAULT_ROOT_DISTANCE;
	private BigDecimal submenuRadiusScale = DEFAULT_SUBMENU_RADIUS_SCALE;
	private BigDecimal targetGlide = DEFAULT_TARGET_GLIDE;
	private boolean hoverEnabled = DEFAULT_HOVER_ENABLED;
	private int hoverMillis = DEFAULT_HOVER_MILLIS;
	private boolean showTrail = true;
	private boolean reduceMotion = false;
	private int preciseCapturePeriodTicks = DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS;

	/** Clamps numeric preferences independently, without changing boolean choices. */
	public void validate() {
		deadzone = clampDeadzone(deadzone);
		stroke = clampStroke(stroke);
		dwellMillis = clampDwellMillis(dwellMillis);
		rootDistance = clampRootDistance(rootDistance);
		submenuRadiusScale = clampSubmenuRadiusScale(submenuRadiusScale);
		targetGlide = clampTargetGlide(targetGlide);
		hoverMillis = clampHoverMillis(hoverMillis);
		preciseCapturePeriodTicks = clampPreciseCapturePeriodTicks(preciseCapturePeriodTicks);
	}

	public void setDeadzone(int value) { deadzone = clampDeadzone(value); }
	public void setStroke(int value) { stroke = clampStroke(value); }
	public void setDwellMillis(int value) { dwellMillis = clampDwellMillis(value); }
	public void setRootDistance(int value) { rootDistance = clampRootDistance(value); }
	public void setSubmenuRadiusScale(BigDecimal value) { submenuRadiusScale = clampSubmenuRadiusScale(value); }
	public void setTargetGlide(BigDecimal value) { targetGlide = clampTargetGlide(value); }
	public void setHoverMillis(int value) { hoverMillis = clampHoverMillis(value); }
	public void setPreciseCapturePeriodTicks(int value) { preciseCapturePeriodTicks = clampPreciseCapturePeriodTicks(value); }

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

	public static BigDecimal clampTargetGlide(BigDecimal value) {
		if (value == null) return DEFAULT_TARGET_GLIDE;
		if (value.compareTo(MIN_TARGET_GLIDE) < 0) return MIN_TARGET_GLIDE;
		if (value.compareTo(MAX_TARGET_GLIDE) > 0) return MAX_TARGET_GLIDE;

		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	public static int clampHoverMillis(int value) {
		return Math.clamp(value, MIN_HOVER_MILLIS, MAX_HOVER_MILLIS);
	}

	public static int clampPreciseCapturePeriodTicks(int value) {
		return Math.clamp(value, MIN_PRECISE_CAPTURE_PERIOD_TICKS, MAX_PRECISE_CAPTURE_PERIOD_TICKS);
	}

	/** Safe even before validation of a deserialized object; does not mutate it. */
	public Snapshot snapshot() {
		return new Snapshot(clampDeadzone(deadzone), clampStroke(stroke),
			clampDwellMillis(dwellMillis), clampRootDistance(rootDistance),
			clampTargetGlide(targetGlide), hoverEnabled, clampHoverMillis(hoverMillis),
			showTrail, reduceMotion, clampPreciseCapturePeriodTicks(preciseCapturePeriodTicks),
			clampSubmenuRadiusScale(submenuRadiusScale));
	}

	public record Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
		BigDecimal targetGlide, boolean hoverEnabled, int hoverMillis,
		boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks,
		BigDecimal submenuRadiusScale) {

		/**
		 * Compatibility constructor for callers that predate the submenu radius
		 * scale; the confirmed default is the baseline 1.5 multiplier.
		 */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			BigDecimal targetGlide, boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion, int preciseCapturePeriodTicks) {
			this(deadzone, stroke, dwellMillis, rootDistance, targetGlide, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, preciseCapturePeriodTicks, DEFAULT_SUBMENU_RADIUS_SCALE);
		}

		/**
		 * Compatibility constructor for callers that predate the precise capture
		 * period; the confirmed default captures every tick.
		 */
		public Snapshot(int deadzone, int stroke, int dwellMillis, int rootDistance,
			BigDecimal targetGlide, boolean hoverEnabled, int hoverMillis,
			boolean showTrail, boolean reduceMotion) {
			this(deadzone, stroke, dwellMillis, rootDistance, targetGlide, hoverEnabled,
				hoverMillis, showTrail, reduceMotion, DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS);
		}
	}
}
