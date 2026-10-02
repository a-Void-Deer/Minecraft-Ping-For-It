package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Main-thread, bounded keyed visual transitions. Time is supplied by the caller;
 * no frame delta, game object, configuration or inventory operation is owned here.
 * An unchanged target follows one finite smoothstep interval, irrespective of
 * frame count. Retargeting starts at the currently displayed state, including
 * when a key reappears during its exit. Removed keys retain only their detached
 * paint data for one exit interval. At capacity, outgoing keys yield to visible
 * targets; excess targets are omitted in caller order, never retained unboundedly.
 */
public final class SpatialOverlayTransitions<K, V> {

	public record Target<K, V>(K key, double x, double y, double scale, double alpha,
		double entryX, double entryY, V data) {

		public Target {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(data, "data");
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(entryX)
				|| !Double.isFinite(entryY) || !Double.isFinite(scale) || scale <= 0.0
				|| !Double.isFinite(alpha) || alpha < 0.0 || alpha > 1.0) {
				throw new IllegalArgumentException("invalid visual target");
			}
		}
	}

	/** A detached paint state; exiting states are never interactive. */
	public record State<K, V>(K key, double x, double y, double scale, double alpha,
		boolean present, V data) {}

	private record Pose(double x, double y, double scale, double alpha) {}

	private final class Node {
		Pose from;
		Pose to;
		long began;
		boolean present = true;
		double entryX;
		double entryY;
		V data;

		Node(Target<K, V> target, long now, boolean reduceMotion) {
			to = pose(target);
			from = reduceMotion ? to : new Pose(target.entryX(), target.entryY(),
				target.scale() * appearanceScale, 0.0);
			began = now;
			updateData(target);
		}

		void updateData(Target<K, V> target) {
			entryX = target.entryX();
			entryY = target.entryY();
			data = target.data();
		}

		Pose at(long now) {
			double fraction = Math.min(1.0, elapsed(now, began) / durationNanos);
			double eased = fraction * fraction * (3.0 - 2.0 * fraction);
			return new Pose(lerp(from.x(), to.x(), eased), lerp(from.y(), to.y(), eased),
				lerp(from.scale(), to.scale(), eased), lerp(from.alpha(), to.alpha(), eased));
		}

		void retarget(Pose next, long now, boolean reduceMotion) {
			if (reduceMotion) {
				from = next;
				to = next;
				began = now;
			} else if (!next.equals(to)) {
				from = at(now);
				to = next;
				began = now;
			}
		}
	}

	private final long durationNanos;
	private final int capacity;
	private final double appearanceScale;
	private final Map<K, Node> nodes = new LinkedHashMap<>();
	private boolean hasTime;
	private long lastTime;

	public SpatialOverlayTransitions(long durationNanos, int capacity, double appearanceScale) {
		if (durationNanos <= 0 || capacity <= 0 || !Double.isFinite(appearanceScale)
			|| appearanceScale <= 0.0 || appearanceScale > 1.0) {
			throw new IllegalArgumentException("invalid transition bounds");
		}
		this.durationNanos = durationNanos;
		this.capacity = capacity;
		this.appearanceScale = appearanceScale;
	}

	/** Empty targets fade an inactive overlay; keep calling until {@link #isEmpty()}. */
	public List<State<K, V>> update(List<Target<K, V>> targets, long nowNanos, boolean reduceMotion) {
		Objects.requireNonNull(targets, "targets");
		long now = hasTime ? Math.max(lastTime, nowNanos) : nowNanos;
		hasTime = true;
		lastTime = now;
		nodes.entrySet().removeIf(entry -> !entry.getValue().present
			&& elapsed(now, entry.getValue().began) >= durationNanos);

		Set<K> visible = new LinkedHashSet<>();
		List<Target<K, V>> admitted = new ArrayList<>();
		for (Target<K, V> target : targets) {
			Objects.requireNonNull(target, "target");
			if (visible.size() >= capacity) {
				break;
			}
			if (visible.add(target.key())) {
				admitted.add(target);
			}
		}

		for (Map.Entry<K, Node> entry : nodes.entrySet()) {
			Node node = entry.getValue();
			if (!visible.contains(entry.getKey()) && node.present) {
				node.retarget(new Pose(node.entryX, node.entryY, node.to.scale() * appearanceScale, 0.0), now,
					reduceMotion);
				node.present = false;
			}
		}
		if (reduceMotion) {
			nodes.entrySet().removeIf(entry -> !entry.getValue().present);
		}

		for (Target<K, V> target : admitted) {
			Node node = nodes.get(target.key());
			if (node == null) {
				if (nodes.size() >= capacity) {
					var iterator = nodes.entrySet().iterator();
					while (iterator.hasNext()) {
						if (!visible.contains(iterator.next().getKey())) {
							iterator.remove();
							break;
						}
					}
				}
				node = new Node(target, now, reduceMotion);
				nodes.put(target.key(), node);
			} else {
				node.retarget(pose(target), now, reduceMotion);
				node.updateData(target);
				node.present = true;
			}
		}

		List<State<K, V>> states = new ArrayList<>(nodes.size());
		// Outgoing content is painted underneath currently presented content.
		for (boolean present : new boolean[] { false, true }) {
			for (Map.Entry<K, Node> entry : nodes.entrySet()) {
				Node node = entry.getValue();
				if (node.present == present) {
					Pose pose = node.at(now);
					states.add(new State<>(entry.getKey(), pose.x(), pose.y(), pose.scale(), pose.alpha(),
						node.present, node.data));
				}
			}
		}
		return List.copyOf(states);
	}

	public int retainedSize() {
		return nodes.size();
	}

	public boolean isEmpty() {
		return nodes.isEmpty();
	}

	/** Hard disposal (disconnect/screen/world change), not the normal visual exit. */
	public void clear() {
		nodes.clear();
		hasTime = false;
		lastTime = 0L;
	}

	private static <K, V> Pose pose(Target<K, V> target) {
		return new Pose(target.x(), target.y(), target.scale(), target.alpha());
	}

	private static double elapsed(long now, long began) {
		if (now <= began) {
			return 0.0;
		}
		long difference = now - began;
		return difference < 0L ? Double.MAX_VALUE : difference;
	}

	private static double lerp(double from, double to, double fraction) {
		return from + (to - from) * fraction;
	}
}
