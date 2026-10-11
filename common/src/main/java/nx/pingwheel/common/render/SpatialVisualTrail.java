package nx.pingwheel.common.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialController.Point;

/** One headless, bounded displayed history. Input, focus and paint admission remain external. */
final class SpatialVisualTrail<K> {
	private final long duration;
	private final List<Point> history = new ArrayList<>();
	private int capacity;
	private K owner; // null is POINTER; otherwise the exact active/departing follower visual key
	private Point handoffFrom;
	private long handoffBegan, cursor, epoch, alignment, lastTime;
	private boolean initialized, active, hasTime;

	SpatialVisualTrail(long duration) {
		if (duration <= 0) throw new IllegalArgumentException("invalid handoff duration");
		this.duration = duration;
	}

	/** Before historical paint: fence coordinate discontinuities and preserve an unfinished exit handoff. */
	void prepare(SpatialController.Snapshot snapshot, int capacity, long nowNanos, boolean reduced) {
		if (capacity <= 0 || capacity > 512) throw new IllegalArgumentException("invalid trail bound");
		this.capacity = capacity;
		time(nowNanos);
		boolean nextActive = snapshot != null && snapshot.active();
		if (!nextActive) {
			// An ordinary exit keeps the current owner and any unfinished finite handoff:
			// the departing pose may still move and must continue the same interval.
			// Only reduced motion, a hard reset, or a new hold clears it.
			active = false;
			if (reduced) clear();
			return;
		}
		boolean nativeSamples = snapshot.trailSequence() >= 0;
		if (!active || !initialized || nativeSamples && epoch != snapshot.trailEpoch()) {
			history.clear(); owner = null; handoffFrom = null;
			cursor = nativeSamples ? Math.max(snapshot.trailSequence() - snapshot.trail().size(), snapshot.trailAlignmentSequence()) : -1;
		} else if (nativeSamples && alignment != snapshot.trailAlignment()) {
			// Projection corrections are not input. Fence old points but retain callbacks after the correction.
			history.clear(); owner = null; handoffFrom = null;
			cursor = snapshot.trailAlignmentSequence();
		}
		active = initialized = true;
		epoch = snapshot.trailEpoch(); alignment = snapshot.trailAlignment();
		while (history.size() > capacity) history.removeFirst();
	}

	List<Point> points() { return List.copyOf(history); }
	K owner() { return owner; }

	/** After node paint: only the caller-certified actual follower centre may enter history. */
	void commit(SpatialController.Snapshot snapshot, K desiredOwner, Point paintedFollower,
		long nowNanos, boolean reduced) {
		long now = time(nowNanos);
		if (!active) {
			// Only the exact departing follower's successfully painted pose is a sample. A
			// missing paint leaves history untouched; a later paint resumes the same clock.
			if (owner != null && paintedFollower != null) sample(paintedFollower, now, reduced);
			return;
		}
		boolean changed = !Objects.equals(owner, desiredOwner);
		if (changed) {
			owner = desiredOwner;
			handoffFrom = history.isEmpty() || reduced ? null : history.getLast();
			handoffBegan = now;
		}
		if (owner != null) {
			advance(snapshot); // Never replay native samples accumulated during follower ownership.
			if (paintedFollower != null) sample(paintedFollower, now, reduced);
		} else if (changed) {
			advance(snapshot);
			if (snapshot.pointer() != null) sample(snapshot.pointer(), now, reduced);
		} else if (handoffFrom != null) {
			// New pointer-owned callback batches still contribute every effective sample during a handoff.
			if (snapshot.trailSequence() >= 0) {
				long first = snapshot.trailSequence() - snapshot.trail().size() + 1;
				for (int i = 0; i < snapshot.trail().size(); i++) {
					if (first + i > cursor) sample(snapshot.trail().get(i), now, reduced);
				}
			}
			advance(snapshot);
			if (snapshot.pointer() != null) sample(snapshot.pointer(), now, reduced);
		} else if (snapshot.trailSequence() >= 0) {
			long first = snapshot.trailSequence() - snapshot.trail().size() + 1;
			for (int i = 0; i < snapshot.trail().size(); i++) {
				if (first + i > cursor) append(snapshot.trail().get(i));
			}
			advance(snapshot);
		} else if (snapshot.pointer() != null) {
			// Only compatibility projections lack native identities. Never infer resets from coordinates.
			append(snapshot.pointer());
		}
	}

	private void advance(SpatialController.Snapshot snapshot) {
		if (snapshot.trailSequence() >= 0) cursor = snapshot.trailSequence();
	}

	private void sample(Point target, long now, boolean reduced) {
		if (handoffFrom != null && !reduced) {
			double elapsed = now <= handoffBegan ? 0.0 : now - handoffBegan < 0 ? Double.MAX_VALUE : now - handoffBegan;
			double t = Math.min(1.0, elapsed / duration);
			if (t < 1.0) {
				double ease = t * t * (3.0 - 2.0 * t);
				append(new Point(handoffFrom.x() + (target.x() - handoffFrom.x()) * ease,
					handoffFrom.y() + (target.y() - handoffFrom.y()) * ease));
				return;
			}
		}
		handoffFrom = null;
		append(target); // No additional lag filter after the finite handoff.
	}

	private void append(Point point) {
		if (history.isEmpty() || !history.getLast().equals(point)) {
			history.add(point);
			if (history.size() > capacity) history.removeFirst();
		}
	}

	private long time(long now) {
		lastTime = hasTime ? Math.max(lastTime, now) : now;
		hasTime = true;
		return lastTime;
	}

	void clear() {
		history.clear(); owner = null; handoffFrom = null;
		active = initialized = hasTime = false;
		cursor = epoch = alignment = lastTime = 0L;
	}
}
