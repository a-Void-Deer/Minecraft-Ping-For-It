package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded, paint-only vertical placement. Unsolvable crowds retain overlap rather than changing text or input. */
final class SpatialLabelAvoidance {
	private static final int MAX_LABELS = 64;
	private static final int PASSES = 3;
	private static final double MAX_SHIFT = 24.0;
	private static final double GAP = 2.0;
	private static final double HYSTERESIS = 0.5;
	private final Map<String, Double> offsets = new HashMap<>();
	private List<Label> previousLabels = List.of();
	private String owner;

	/** Bounds are measured after font layout, pixel rounding and visual scale, relative to the menu origin. */
	record Label(String id, double x, double y, double left, double top, double right, double bottom,
		double start, double span, boolean movable, boolean focused) {}

	Map<String, Double> place(String menu, List<Label> labels) {
		if (!menu.equals(owner)) {
			clear();
			owner = menu;
		}
		if (labels.size() > MAX_LABELS) {
			offsets.clear();
			previousLabels = List.of();
			return Map.of();
		}
		if (labels.equals(previousLabels)) return Map.copyOf(offsets);
		Map<String, Double> result = new HashMap<>();
		for (Label label : labels) {
			double previous = offsets.getOrDefault(label.id(), 0.0);
			result.put(label.id(), allowed(label, previous) ? previous : 0.0);
		}
		// Stable caller order, finite passes and a keep-current margin prevent small
		// pointer/metric changes from continually exchanging the upper/lower solution.
		for (int pass = 0; pass < PASSES; pass++) {
			boolean changed = false;
			for (Label label : labels) {
				if (!label.movable()) continue;
				double current = result.get(label.id());
				double best = current;
				double score = score(label, current, labels, result);
				List<Double> candidates = new ArrayList<>();
				candidates.add(0.0);
				candidates.add(-budget(label));
				candidates.add(budget(label));
				for (Label other : labels) {
					if (other == label || !horizontalOverlap(label, other)) continue;
					double dy = result.get(other.id());
					candidates.add(other.y() + dy + other.top() - GAP - label.y() - label.bottom());
					candidates.add(other.y() + dy + other.bottom() + GAP - label.y() - label.top());
				}
				for (double candidate : candidates) {
					if (!allowed(label, candidate)) continue;
					double next = score(label, candidate, labels, result);
					if (next + HYSTERESIS < score) {
						score = next;
						best = candidate;
					}
				}
				if (best != current) {
					result.put(label.id(), best);
					changed = true;
				}
			}
			if (!changed) break;
		}
		offsets.clear();
		offsets.putAll(result);
		previousLabels = List.copyOf(labels);
		return Map.copyOf(result);
	}

	void clear() {
		owner = null;
		offsets.clear();
		previousLabels = List.of();
	}

	private static double budget(Label label) {
		return Math.min(MAX_SHIFT, Math.hypot(label.x(), label.y()) * 0.35);
	}

	private static boolean allowed(Label label, double offset) {
		if (offset == 0.0) return true;
		if (!label.movable() || Math.abs(offset) > budget(label)) return false;
		double bearing = Math.toDegrees(Math.atan2(label.x(), -label.y() - offset));
		double within = ((bearing - label.start()) % 360.0 + 360.0) % 360.0;
		return within < label.span();
	}

	private static boolean horizontalOverlap(Label a, Label b) {
		return a.x() + a.left() < b.x() + b.right() + GAP
			&& b.x() + b.left() < a.x() + a.right() + GAP;
	}

	private static double score(Label label, double offset, List<Label> labels, Map<String, Double> positions) {
		double score = Math.abs(offset) * 0.05;
		for (Label other : labels) {
			if (label == other || !horizontalOverlap(label, other)) continue;
			double dy = positions.get(other.id());
			double overlap = Math.min(label.y() + offset + label.bottom(), other.y() + dy + other.bottom())
				- Math.max(label.y() + offset + label.top(), other.y() + dy + other.top()) + GAP;
			if (overlap > 0.0) score += overlap * (other.focused() ? 4.0 : 1.0);
		}
		return score;
	}
}
