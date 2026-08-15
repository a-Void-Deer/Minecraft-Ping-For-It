package nx.pingwheel.common.client.wheel;

import java.util.Objects;

import nx.pingwheel.common.domain.PingType;

/**
 * One immutable, strictly validated sector of the ping wheel: the angular
 * wedge {@code [startAngleRadians, endAngleRadians)} assigned to
 * {@code pingType}, with angle 0 at the top of the wheel and angles growing
 * clockwise (GUI space, y down).
 *
 * <p>Validation is strict: the index must not be negative, the ping type must
 * not be null, both angles must be finite, the start must lie in
 * {@code [0, 2π)} and the end in {@code (start, 2π]}. A single sector spanning
 * the full ring ({@code 0} to {@code 2π}) is valid. Pure JDK only.
 */
public record WheelSector(
	int index,
	PingType pingType,
	double startAngleRadians,
	double endAngleRadians
) {

	private static final double TWO_PI = Math.PI * 2.0;

	public WheelSector {
		Objects.requireNonNull(pingType, "pingType");

		if (index < 0) {
			throw new IllegalArgumentException("index must not be negative: " + index);
		}

		if (!Double.isFinite(startAngleRadians) || !Double.isFinite(endAngleRadians)) {
			throw new IllegalArgumentException(
				"angles must be finite: start=" + startAngleRadians + " end=" + endAngleRadians);
		}

		if (startAngleRadians < 0.0 || startAngleRadians >= TWO_PI) {
			throw new IllegalArgumentException(
				"startAngleRadians must lie in [0, 2π): " + startAngleRadians);
		}

		if (endAngleRadians <= startAngleRadians || endAngleRadians > TWO_PI) {
			throw new IllegalArgumentException(
				"endAngleRadians must lie in (startAngleRadians, 2π]: start="
					+ startAngleRadians + " end=" + endAngleRadians);
		}
	}

	/**
	 * The 24-bit RGB outline color of this sector's ping type.
	 */
	public int outlineColor() {
		return pingType.outlineColor();
	}
}
