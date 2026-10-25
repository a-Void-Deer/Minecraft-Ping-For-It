package nx.pingwheel.common.presentation.budget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Server-thread monitor with fixed counters and one bounded open buffer per module. */
public final class BudgetDelayMonitor implements BudgetDelaySink {

	@FunctionalInterface public interface WarnSink { void warn(CycleSummary summary); }
	@FunctionalInterface public interface NoticeSink { boolean notify(Set<Module> modules); }

	/** A saturated value is a lower bound, not an exact count. */
	public record Count(long amount, boolean saturated) {
		public Count {
			if (amount < 0) throw new IllegalArgumentException("negative counter");
		}
	}

	public record CycleSummary(Module module, long cycleId, Count observedWorkItems,
		Map<Reason, Count> reasons, Map<Unit, Count> quantities, Count unknownQuantities,
		List<WorkItem> samples) {
		public CycleSummary {
			Objects.requireNonNull(module, "module");
			Objects.requireNonNull(observedWorkItems, "work count");
			Objects.requireNonNull(unknownQuantities, "unknown count");
			EnumMap<Reason, Count> reasonCopy = new EnumMap<>(Reason.class);
			reasonCopy.putAll(reasons);
			reasons = Collections.unmodifiableMap(reasonCopy);
			EnumMap<Unit, Count> quantityCopy = new EnumMap<>(Unit.class);
			quantityCopy.putAll(quantities);
			quantities = Collections.unmodifiableMap(quantityCopy);
			samples = List.copyOf(samples);
			if (samples.size() > 10) throw new IllegalArgumentException("too many diagnostic samples");
		}
	}

	private static final long STREAK_TICKS = 20;
	private static final long COOLDOWN_TICKS = 200;
	private static final Reason[] REASONS = Reason.values();
	private static final Unit[] UNITS = Unit.values();
	private final ModuleState[] modules = new ModuleState[Module.values().length];
	private final WarnSink warnings;
	private final NoticeSink notices;
	private boolean delivered;
	private long deliveredAt;

	public BudgetDelayMonitor(WarnSink warnings, NoticeSink notices) {
		this.warnings = Objects.requireNonNull(warnings, "warnings");
		this.notices = Objects.requireNonNull(notices, "notices");
		for (int i = 0; i < modules.length; i++) modules[i] = new ModuleState();
	}

	@Override public void observe(@Nullable DelayEvent event) {
		if (event == null) return;
		ModuleState state = modules[event.module().ordinal()];
		if (!state.accept(event.cycleId())) return;
		state.observedDelay = true;
		state.startStreak(event.serverTick());
		if (event.firstItemObservation()) {
			state.work.add(1);
			if (state.samples.size() < 10) state.samples.add(event.item());
			if (event.quantity().known()) state.quantities.add(event.quantity().unit().ordinal(), event.quantity().amount());
			else state.unknown.add(1);
		}
		for (Reason reason : REASONS)
			if ((event.newReasonBits() & reason.bit()) != 0) state.reasons.add(reason.ordinal(), 1);
	}

	@Override public void state(@Nullable Module module, long cycleId, long serverTick, @Nullable DelayState finalState) {
		if (module == null || finalState == null) return;
		ModuleState state = modules[module.ordinal()];
		if (!state.accept(cycleId)) return;
		state.finalState = finalState;
		state.freshState = true;
		if (finalState == DelayState.DELAYED) {
			state.startStreak(serverTick);
			state.delayedEvidenceAt = serverTick;
		}
		else if (finalState == DelayState.HEALTHY || finalState == DelayState.IDLE) state.started = false;
	}

	@Override public void endCycle(@Nullable Module module, long cycleId, long serverTick) {
		if (module == null) return;
		ModuleState state = modules[module.ordinal()];
		if (!state.open || state.cycleId != cycleId) return;
		@Nullable CycleSummary summary = state.observedDelay ? state.summary(module) : null;
		state.closed = true;
		state.lastClosed = cycleId;
		state.open = false;
		state.clearBuffer();
		if (summary != null) {
			try { warnings.warn(summary); }
			catch (RuntimeException | LinkageError ignored) { /* Diagnostics cannot break scheduling. */ }
		}
	}

	/** Consumes final producer evidence once; time alone is never fresh evidence. */
	public void finishTick(long serverTick, boolean notificationsEnabled) {
		EnumSet<Module> qualified = EnumSet.noneOf(Module.class);
		for (Module module : Module.values()) {
			ModuleState state = modules[module.ordinal()];
			if (state.freshState && state.finalState == DelayState.DELAYED && state.started
				&& state.delayedEvidenceAt - state.startedAt >= STREAK_TICKS) qualified.add(module);
			state.freshState = false;
		}
		if (!notificationsEnabled || qualified.isEmpty()
			|| delivered && serverTick - deliveredAt < COOLDOWN_TICKS) return;
		try {
			if (notices.notify(Collections.unmodifiableSet(qualified))) {
				delivered = true;
				deliveredAt = serverTick;
			}
		} catch (RuntimeException | LinkageError ignored) { /* Failed handoffs consume no cooldown. */ }
	}

	public void reset() {
		for (ModuleState state : modules) state.reset();
		delivered = false;
		deliveredAt = 0;
	}

	private static final class Counter {
		long amount;
		boolean saturated;
		void add(long value) {
			if (value > Long.MAX_VALUE - amount) { amount = Long.MAX_VALUE; saturated = true; }
			else amount += value;
		}
		Count snapshot() { return new Count(amount, saturated); }
		void clear() { amount = 0; saturated = false; }
	}

	private static final class Counters {
		final long[] amounts;
		final boolean[] saturated;
		final boolean[] observed;
		Counters(int size) {
			amounts = new long[size]; saturated = new boolean[size]; observed = new boolean[size];
		}
		void add(int index, long value) {
			observed[index] = true;
			if (value > Long.MAX_VALUE - amounts[index]) { amounts[index] = Long.MAX_VALUE; saturated[index] = true; }
			else amounts[index] += value;
		}
		Count snapshot(int index) { return new Count(amounts[index], saturated[index]); }
		void clear() { Arrays.fill(amounts, 0); Arrays.fill(saturated, false); Arrays.fill(observed, false); }
	}

	private static final class ModuleState {
		boolean open, closed, observedDelay, started, freshState;
		long cycleId, lastClosed, startedAt, delayedEvidenceAt;
		DelayState finalState = DelayState.NEUTRAL;
		final Counter work = new Counter();
		final Counter unknown = new Counter();
		final Counters reasons = new Counters(REASONS.length);
		final Counters quantities = new Counters(UNITS.length);
		final List<WorkItem> samples = new ArrayList<>(10);

		boolean accept(long cycle) {
			if (open) return cycleId == cycle;
			if (closed && cycle <= lastClosed) return false;
			open = true;
			cycleId = cycle;
			return true;
		}
		void startStreak(long serverTick) {
			if (!started) { started = true; startedAt = serverTick; }
		}
		CycleSummary summary(Module module) {
			EnumMap<Reason, Count> reasonCounts = new EnumMap<>(Reason.class);
			for (Reason reason : REASONS)
				if (reasons.observed[reason.ordinal()]) reasonCounts.put(reason, reasons.snapshot(reason.ordinal()));
			EnumMap<Unit, Count> unitCounts = new EnumMap<>(Unit.class);
			for (Unit unit : UNITS)
				if (quantities.observed[unit.ordinal()]) unitCounts.put(unit, quantities.snapshot(unit.ordinal()));
			return new CycleSummary(module, cycleId, work.snapshot(), reasonCounts, unitCounts, unknown.snapshot(), samples);
		}
		void clearBuffer() {
			observedDelay = false;
			work.clear(); unknown.clear(); reasons.clear(); quantities.clear(); samples.clear();
		}
		void reset() {
			open = false; closed = false; started = false; freshState = false;
			cycleId = 0; lastClosed = 0; startedAt = 0; delayedEvidenceAt = 0;
			finalState = DelayState.NEUTRAL;
			clearBuffer();
		}
	}
}
