package nx.pingwheel.common.client.wheel;

/**
 * An immutable 2D point in GUI space (x right, y down), relative to the wheel
 * center.
 *
 * <p>Pure JDK: this package never references Minecraft classes, so the wheel
 * geometry stays unit-testable without a game client.
 */
public record WheelPoint(double x, double y) {

	public WheelPoint {
		if (!Double.isFinite(x) || !Double.isFinite(y)) {
			throw new IllegalArgumentException(
				"coordinates must be finite: x=" + x + " y=" + y);
		}
	}
}
