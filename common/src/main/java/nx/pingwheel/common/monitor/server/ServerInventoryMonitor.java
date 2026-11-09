package nx.pingwheel.common.monitor.server;

import java.util.Objects;
import java.util.function.LongSupplier;

import nx.pingwheel.common.monitor.MonitorAccumulator;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * One server-lifetime, owner-thread collector. No viewer, transport, source or
 * world is retained here. The inventory owners supply already-owned scalars;
 * immutable samples alone may cross the server thread boundary.
 */
@NotNullByDefault
public final class ServerInventoryMonitor {

	private final MonitorAccumulator accumulator;
	private final LongSupplier clock;
	private @Nullable Thread owner;
	private static final int MAX_TIMING_DEPTH = 128, INGRESS = 1, TICK = 2;
	// Preallocated owner-thread scopes, never operation objects or retained business inputs.
	private final int[] timingLanes = new int[MAX_TIMING_DEPTH], boundaries = new int[MAX_TIMING_DEPTH], seenLanes = new int[MAX_TIMING_DEPTH];
	private final @Nullable Phase[] phases = new @Nullable Phase[MAX_TIMING_DEPTH];
	private final long[] generations = new long[MAX_TIMING_DEPTH], durations = new long[MAX_TIMING_DEPTH];
	private final long[] previewNs = new long[MAX_TIMING_DEPTH], trackingNs = new long[MAX_TIMING_DEPTH];
	private final boolean[] phaseRoots = new boolean[MAX_TIMING_DEPTH], boundaryRoots = new boolean[MAX_TIMING_DEPTH], valid = new boolean[MAX_TIMING_DEPTH];
	private int timingDepth, overflowDepth;
	private boolean timingClockValid;
	private long timingNs;

	public ServerInventoryMonitor() { this(MonitorTuning.DEFAULT, System::nanoTime); }
	public ServerInventoryMonitor(MonitorTuning tuning, LongSupplier clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
		accumulator = new MonitorAccumulator(Source.SERVER, tuning, clock);
	}

	/** Authoritative applied booleans only; no subscription or HUD input. */
	public void configure(boolean preview, boolean tracking) {
		checkOwner(); long generation = collectionGeneration(); accumulator.configure(preview, tracking);
		if (generation != collectionGeneration()) timingClockValid = false;
	}
	/** Reset before using a replacement server lifetime; the caller subsequently applies its mask. */
	public void resetGeneration(long generation) { checkOwner(); accumulator.resetGeneration(generation); timingClockValid = false; }
	public long serverGeneration() { return accumulator.serverGeneration(); }
	public long collectionGeneration() { return accumulator.collectionGeneration(); }
	public boolean enabled() { return accumulator.enabled(); }
	public boolean enabled(Lane lane) { return accumulator.enabled(lane); }

	/**
	 * A phase remains inclusive of its same-lane detail, not a synchronous transfer
	 * to another lane. Repeated same-phase nesting records one union, not summed
	 * parents. Disabled lanes still fence ownership while their peer is enabled.
	 */
	public long beginIngress(Lane lane) { return begin(lane.mask(), null, INGRESS); }
	/** Neutral boundary: session refresh has no invented lane, but its known-lane cleanup can enter one. */
	public long beginTick() { return begin(0, null, TICK); }
	public long beginLaneWork(Lane lane) { return begin(lane.mask(), null, 0); }
	public long beginPhase(Lane lane, Phase phase) { return begin(lane.mask(), phase, 0); }
	private long begin(int lane, @Nullable Phase phase, int boundary) {
		if (!enabled()) return 0;
		checkOwner(); advanceTiming();
		if (timingDepth == MAX_TIMING_DEPTH || overflowDepth != 0) {
			// Never turn hostile synchronous recursion into a business exception or a fabricated partial timing.
			for (int index = 0; index < timingDepth; index++) valid[index] = false;
			overflowDepth++; return -1;
		}
		int index = timingDepth++;
		timingLanes[index] = lane; phases[index] = phase; boundaries[index] = boundary; generations[index] = collectionGeneration();
		durations[index] = previewNs[index] = trackingNs[index] = 0; seenLanes[index] = lane; valid[index] = true;
		phaseRoots[index] = true; boundaryRoots[index] = true;
		for (int parent = 0; parent < index; parent++) if (generations[parent] == generations[index]) {
			if (phase != null && timingLanes[parent] == lane && phases[parent] == phase) phaseRoots[index] = false;
			if (boundary != 0 && boundaries[parent] == boundary) boundaryRoots[index] = false;
		}
		return timingDepth;
	}
	public void end(long token) {
		if (token == 0) return;
		checkOwner();
		if (enabled()) advanceTiming();
		if (token < 0) { overflowDepth--; return; }
		int index = --timingDepth;
		if (token != index + 1L || generations[index] != collectionGeneration() || !valid[index]) return;
		@Nullable Phase phase = phases[index];
		Lane lane = timingLanes[index] == Lane.PREVIEW.mask() ? Lane.PREVIEW : Lane.TRACKING;
		if (phase != null && phaseRoots[index] && enabled(lane)) accumulator.recordDuration(lane, phase, durations[index]);
		if (boundaries[index] != 0 && boundaryRoots[index]) {
			Phase summary = boundaries[index] == INGRESS ? Phase.INGRESS : Phase.TICK_WORK;
			if (enabled(Lane.PREVIEW) && (summary == Phase.TICK_WORK || (seenLanes[index] & Lane.PREVIEW.mask()) != 0))
				accumulator.recordDuration(Lane.PREVIEW, summary, previewNs[index]);
			if (enabled(Lane.TRACKING) && (summary == Phase.TICK_WORK || (seenLanes[index] & Lane.TRACKING.mask()) != 0))
				accumulator.recordDuration(Lane.TRACKING, summary, trackingNs[index]);
		}
	}
	private void advanceTiming() {
		long now = clock.getAsLong(), elapsed = timingClockValid ? now - timingNs : 0;
		timingNs = now; timingClockValid = true;
		if (elapsed < 0 || timingDepth == 0 || overflowDepth != 0) return;
		long generation = collectionGeneration();
		int lane = generations[timingDepth - 1] == generation ? timingLanes[timingDepth - 1] : 0;
		if (lane == 0) return;
		int boundary = 0, root = -1;
		for (int index = timingDepth - 1; index >= 0; index--) if (generations[index] == generation) {
			if (timingLanes[index] == lane && phases[index] != null && phaseRoots[index]) durations[index] = saturatedAdd(durations[index], elapsed);
			if (boundary == 0 && boundaries[index] != 0) boundary = boundaries[index];
			if (boundary != 0 && boundaries[index] == boundary && boundaryRoots[index]) root = index;
		}
		if (root >= 0) {
			seenLanes[root] |= lane;
			if (lane == Lane.PREVIEW.mask()) previewNs[root] = saturatedAdd(previewNs[root], elapsed);
			else trackingNs[root] = saturatedAdd(trackingNs[root], elapsed);
		}
	}
	public void count(Lane lane, Counter counter, long delta) {
		if (!enabled(lane)) return;
		checkOwner(); accumulator.recordCount(lane, counter, delta);
	}
	public void gauge(Lane lane, Gauge gauge, long value) {
		if (!enabled(lane)) return;
		checkOwner(); accumulator.setGauge(lane, gauge, value);
	}
	public void invalidateGauge(Lane lane, Gauge gauge) {
		if (!enabled(lane)) return;
		checkOwner(); accumulator.invalidateGauge(lane, gauge);
	}
	public void budget(Lane lane, Budget budget, long period, long cap, long used, long remaining) {
		if (!enabled(lane)) return;
		checkOwner(); accumulator.setBudget(lane, budget,
			new BudgetUsage(true, period, cap, used, 0, remaining, Math.max(0, remaining)));
	}
	/** Read this ledger once, not root allocations plus leases plus the same ledger. */
	public void memory(RetainedMemoryLedger ledger) {
		if (!enabled()) return;
		checkOwner(); accumulator.setSharedMemory(new MemoryUsage(ledger.retained(), ledger.reserved(), ledger.cap(), ledger.remaining()));
	}
	public void sharedGauge(Gauge gauge, long value) {
		if (!enabled()) return;
		checkOwner(); accumulator.setSharedGauge(gauge, value);
	}
	public void sharedCount(Counter counter, long delta) {
		if (!enabled()) return;
		checkOwner(); accumulator.recordSharedCount(counter, delta);
	}
	/** Fixed code-owned counter reads, including outstanding reservations, not an entry traversal. */
	public void sharedBudget(Budget budget, long period, CostLedger ledger, CostLedger.Counter counter, long admissible) {
		if (!enabled()) return;
		checkOwner();
		long limit = ledger.limit(counter), used = ledger.used(counter), remaining = ledger.remaining(counter);
		accumulator.setSharedBudget(budget, new BudgetUsage(true, period, limit, used,
			limit - used - remaining, remaining, Math.max(0, Math.min(remaining, admissible))));
	}
	/** Regular inventory-v2 only. Unknown candidates are never reported as zero observed bytes. */
	public void sent(Lane lane, @Nullable Counter frameKind, long bytes, TransportHandoff handoff) {
		if (!enabled(lane)) return;
		checkOwner();
		accumulator.recordCount(lane, Counter.BUDGET_CHARGED_BYTES, bytes);
		if (frameKind != null) accumulator.recordCount(lane, frameKind, 1);
		switch (handoff) {
			case HANDED_OFF -> {
				accumulator.recordCount(lane, Counter.HANDOFF_FRAMES, 1);
				accumulator.recordCount(lane, Counter.HANDOFF_BYTES, bytes);
			}
			case NOT_HANDED_OFF -> accumulator.recordCount(lane, Counter.NOT_HANDED_OFF_FRAMES, 1);
			case UNOBSERVED -> {
				accumulator.recordCount(lane, Counter.UNOBSERVED_HANDOFF_FRAMES, 1);
				accumulator.recordCount(lane, Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES, bytes);
			}
		}
	}
	/** Invoke after Backend.completeMonitorTick has captured the final business scalars. */
	public @Nullable MonitorSample completeTick(long tick) {
		if (!enabled()) return null;
		checkOwner(); return accumulator.completeTick(tick);
	}
	public @Nullable MonitorSample latest() { return accumulator.latest(); }
	private void checkOwner() {
		Thread current = Thread.currentThread();
		if (owner == null) owner = current;
		else if (owner != current) throw new IllegalStateException("inventory monitor owner thread mismatch");
	}
	private static long saturatedAdd(long first, long second) { return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second; }
}
