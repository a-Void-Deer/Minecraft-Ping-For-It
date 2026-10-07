package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Four straight perimeter segments; pure geometry shared by radial and list
 * Back. Callers pass the frame size painted by {@code strokeRect}, whose right
 * and bottom bounds are exclusive, so the traced square ends on the last
 * painted column and row rather than one pixel past the border.
 */
final class SpatialSquareProgress {
	private SpatialSquareProgress() {}
	record Segment(double x1, double y1, double x2, double y2) {}

	static List<Segment> segments(double x, double y, double size, double progress) {
		double traced = size - 1.0;
		if (!Double.isFinite(size) || traced <= 0.0 || !Double.isFinite(progress) || progress <= 0.0) {
			return List.of();
		}
		double remaining = traced * 4.0 * Math.min(1.0, progress);
		double[] xs = { x, x + traced, x + traced, x, x };
		double[] ys = { y, y, y + traced, y + traced, y };
		List<Segment> result = new ArrayList<>(4);
		for (int i = 0; i < 4 && remaining > 0.0; i++) {
			double fraction = Math.min(1.0, remaining / traced);
			result.add(new Segment(xs[i], ys[i], xs[i] + (xs[i + 1] - xs[i]) * fraction,
				ys[i] + (ys[i + 1] - ys[i]) * fraction));
			remaining -= traced * fraction;
		}
		return List.copyOf(result);
	}
}
