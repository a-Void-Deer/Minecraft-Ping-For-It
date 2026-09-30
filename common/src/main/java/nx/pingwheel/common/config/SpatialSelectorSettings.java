package nx.pingwheel.common.config;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Client-local spatial selector behavior: the target-list glide factor and the
 * Back-hover dwell. Both are user preferences and stay client-local; no other
 * selector prototype tuning (dead zone, stroke, dwell beyond the confirmed
 * Back-hover) is persisted.
 *
 * <p>JSON shape: {@code {"targetGlide": 0.25, "hoverEnabled": false, "hoverMillis": 500}}.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public final class SpatialSelectorSettings {

	/** Confirmed glide prototype range and reset value. */
	public static final BigDecimal MIN_TARGET_GLIDE = new BigDecimal("0.25");
	public static final BigDecimal MAX_TARGET_GLIDE = new BigDecimal("3");
	public static final BigDecimal DEFAULT_TARGET_GLIDE = new BigDecimal("0.25");

	/** Confirmed Back-hover dwell range, step and disabled-by-default policy. */
	public static final int MIN_HOVER_MILLIS = 100;
	public static final int MAX_HOVER_MILLIS = 2000;
	public static final int HOVER_MILLIS_STEP = 50;
	public static final int DEFAULT_HOVER_MILLIS = 500;
	public static final boolean DEFAULT_HOVER_ENABLED = false;

	private BigDecimal targetGlide = DEFAULT_TARGET_GLIDE;
	private boolean hoverEnabled = DEFAULT_HOVER_ENABLED;
	private int hoverMillis = DEFAULT_HOVER_MILLIS;

	/** Clamps both numeric values into their confirmed ranges without touching the enabled flag. */
	public void validate() {
		targetGlide = clampTargetGlide(targetGlide);
		hoverMillis = clampHoverMillis(hoverMillis);
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
}
