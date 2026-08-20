package nx.pingwheel.common.client.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import nx.pingwheel.common.marker.TargetKey;

/**
 * Main-thread deferred line state shared by optional entity-block sources and
 * the late common block-outline pass.
 *
 * <p>Sources never receive the frame buffer. They receive a private sink bound
 * to the frame generation and exact block target. A commit validates the
 * complete immutable batch while holding the state lock and swaps the
 * published map in one operation. {@link #beginFrame()} and {@link #leave()}
 * invalidate every sink from an earlier generation.</p>
 */
public final class DeferredEntityBlockGeometryState {
	public static final DeferredEntityBlockGeometryState INSTANCE =
		new DeferredEntityBlockGeometryState();

	/** Hard per-target line budget; each line produces two line vertices. */
	public static final int MAX_LINES_PER_TARGET = 8_192;
	/** Hard aggregate frame budget across all deferred targets. */
	public static final int MAX_LINES_PER_FRAME = 65_536;

	private final Object lock = new Object();
	private long generation;
	private boolean active;
	private Map<TargetKey.BlockKey, List<EntityBlockGeometryLine>> committed = Map.of();
	private int committedLineCount;

	private DeferredEntityBlockGeometryState() {}

	/** Starts a new render frame and invalidates all sinks from prior frames. */
	public void beginFrame() {
		synchronized (lock) {
			generation++;
			active = true;
			committed = Map.of();
			committedLineCount = 0;
		}
	}

	/** Clears the frame and invalidates all outstanding sinks. */
	public void leave() {
		synchronized (lock) {
			generation++;
			active = false;
			committed = Map.of();
			committedLineCount = 0;
		}
	}

	/**
	 * Opens a sink bound to the current frame and exact target. If no frame is
	 * active, the returned sink is stale and all operations are rejected.
	 */
	public EntityBlockGeometryLineSink open(TargetKey.BlockKey targetKey) {
		return new BatchSink(this, generationSnapshot(), Objects.requireNonNull(targetKey, "targetKey"));
	}

	/** Returns the committed immutable lines for the current frame and target. */
	public List<EntityBlockGeometryLine> linesFor(TargetKey.BlockKey targetKey) {
		if (targetKey == null) {
			return List.of();
		}

		synchronized (lock) {
			return committed.getOrDefault(targetKey, List.of());
		}
	}

	/** Whether this frame has a nonempty committed batch for {@code targetKey}. */
	public boolean hasLinesFor(TargetKey.BlockKey targetKey) {
		return !linesFor(targetKey).isEmpty();
	}

	/** Whether any supplied current target has a deferred batch this frame. */
	public boolean hasLinesFor(Set<TargetKey.BlockKey> targetKeys) {
		Objects.requireNonNull(targetKeys, "targetKeys");
		synchronized (lock) {
			for (TargetKey.BlockKey targetKey : targetKeys) {
				if (committed.containsKey(targetKey)) {
					return true;
				}
			}
			return false;
		}
	}

	/** Number of currently committed lines, mainly a focused-test seam. */
	public int committedLineCount() {
		synchronized (lock) {
			return committedLineCount;
		}
	}

	private long generationSnapshot() {
		synchronized (lock) {
			return generation;
		}
	}

	private boolean commit(long sinkGeneration, TargetKey.BlockKey targetKey,
		List<EntityBlockGeometryLine> lines) {
		if (lines.isEmpty() || lines.size() > MAX_LINES_PER_TARGET) {
			return false;
		}

		for (EntityBlockGeometryLine line : lines) {
			if (line == null || !line.isFiniteNonZero()) {
				return false;
			}
		}

		synchronized (lock) {
			List<EntityBlockGeometryLine> existing = committed.get(targetKey);
			int existingLineCount = existing == null ? 0 : existing.size();

			// Each source owns one private atomic batch, but a target may have
			// several successful sources in the same frame.  Check both aggregate
			// budgets before constructing the replacement snapshot so a rejected
			// later commit cannot alter an earlier one.
			if (!active || sinkGeneration != generation
				|| existingLineCount > MAX_LINES_PER_TARGET - lines.size()
				|| committedLineCount > MAX_LINES_PER_FRAME - lines.size()) {
				return false;
			}

			int mergedLineCount = existingLineCount + lines.size();
			List<EntityBlockGeometryLine> merged = new ArrayList<>(mergedLineCount);
			if (existing != null) {
				merged.addAll(existing);
			}
			merged.addAll(lines);

			Map<TargetKey.BlockKey, List<EntityBlockGeometryLine>> next =
				new LinkedHashMap<>(committed);
			next.put(targetKey, List.copyOf(merged));
			committed = Collections.unmodifiableMap(next);
			committedLineCount += lines.size();
			return true;
		}
	}

	private static final class BatchSink implements EntityBlockGeometryLineSink {
		private final DeferredEntityBlockGeometryState owner;
		private final long generation;
		private final TargetKey.BlockKey targetKey;
		private final List<EntityBlockGeometryLine> lines = new ArrayList<>();
		private boolean invalid;
		private boolean closed;
		private boolean wasCommitted;

		private BatchSink(DeferredEntityBlockGeometryState owner, long generation,
			TargetKey.BlockKey targetKey) {
			this.owner = owner;
			this.generation = generation;
			this.targetKey = targetKey;
		}

		@Override
		public boolean addLine(double x0, double y0, double z0, double x1, double y1, double z1) {
			if (closed || invalid || lines.size() >= MAX_LINES_PER_TARGET) {
				invalid = true;
				return false;
			}

			EntityBlockGeometryLine line = new EntityBlockGeometryLine(x0, y0, z0, x1, y1, z1);
			if (!line.isFiniteNonZero()) {
				invalid = true;
				return false;
			}

			lines.add(line);
			return true;
		}

		@Override
		public boolean commit() {
			if (closed || invalid) {
				return false;
			}

			closed = true;
			wasCommitted = owner.commit(generation, targetKey, lines);
			return wasCommitted;
		}

		@Override
		public void abort() {
			closed = true;
			lines.clear();
		}

		@Override
		public boolean committed() {
			return wasCommitted;
		}
	}
}
