package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;

/** Four straight perimeter segments; pure geometry shared by radial and list Back. */
final class SpatialSquareProgress {
	private SpatialSquareProgress() {}
	record Segment(double x1, double y1, double x2, double y2) {}

	static List<Segment> segments(double x, double y, double size, double progress) {
		if (!Double.isFinite(size) || size <= 0.0 || !Double.isFinite(progress) || progress <= 0.0) {
			return List.of();
		}
		double remaining = size * 4.0 * Math.min(1.0, progress);
		double[] xs = { x, x + size, x + size, x, x };
		double[] ys = { y, y, y + size, y + size, y };
		List<Segment> result = new ArrayList<>(4);
		for (int i = 0; i < 4 && remaining > 0.0; i++) {
			double fraction = Math.min(1.0, remaining / size);
			result.add(new Segment(xs[i], ys[i], xs[i] + (xs[i + 1] - xs[i]) * fraction,
				ys[i] + (ys[i + 1] - ys[i]) * fraction));
			remaining -= size * fraction;
		}
		return List.copyOf(result);
	}
}
