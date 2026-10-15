package nx.pingwheel.common.presentation.inventory;

import java.util.Objects;
import java.util.function.Supplier;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;

/**
 * Producer-local state for one finite inventory work stage.
 *
 * <p>Keep one recorder per owned stage and accounting module, never one per
 * attempted call. The recorder retains no work identity. Its owner supplies
 * detached diagnostic context without reading a source or provider.</p>
 */
final class InventoryBudgetDelayState {
	private final Module module;
	private boolean cycleInitialized;
	private long cycleId;
	private int reasonMask;
	private boolean outstanding;
	private boolean eligible;

	InventoryBudgetDelayState(Module module) {
		this.module = Objects.requireNonNull(module, "module");
	}

	/** Starts the supplied owner cycle without closing, reporting, or synthesizing cycles. */
	void beginCycle(long cycleId) {
		if (cycleInitialized && this.cycleId == cycleId) return;
		cycleInitialized = true;
		this.cycleId = cycleId;
		reasonMask = 0;
		eligible = false;
	}

	/** Records that this stage had an eligible opportunity in the supplied cycle. */
	void markEligible(long cycleId) {
		beginCycle(cycleId);
		eligible = true;
	}

	/** Clears tick-local eligibility without clearing pending delay or cycle history. */
	void beginTick(long cycleId) {
		beginCycle(cycleId);
		eligible = false;
	}

	/**
	 * Records a newly observed limiting reason set. The returned value is true
	 * when at least one reason was new for this stage in this cycle, regardless
	 * of diagnostic delivery. Reasons must use the sink's known reason bits.
	 * Call only for accepted, authorized, due work that remains pending after an
	 * actual resource refusal, never for terminal rejection or ordinary cadence.
	 *
	 * <p>State is committed before diagnostic construction and sink invocation so
	 * fail-soft monitoring cannot cause a producer to retry or lose progress.</p>
	 */
	boolean observe(BudgetDelaySink sink, long cycleId, long nowNanos, int reasons,
		Supplier<WorkItem> item, Supplier<Quantity> quantity) {
		if (reasons == 0) return false;
		markEligible(cycleId);
		outstanding = true;
		int newReasons = reasons & ~reasonMask;
		if (newReasons == 0) return false;

		boolean firstObservation = reasonMask == 0;
		reasonMask |= newReasons;
		if (sink == null || sink == BudgetDelaySink.NONE) return true;
		try {
			sink.observe(new DelayEvent(module, cycleId, nowNanos, item.get(),
				firstObservation, newReasons, quantity.get()));
		}
		catch (RuntimeException | LinkageError ignored) {
			// Diagnostics must not change producer scheduling or publication.
		}
		return true;
	}

	/** Clears delay only when actual progress removes this stage's limiting resource. */
	void progress(long cycleId) {
		beginCycle(cycleId);
		outstanding = false;
		eligible = true;
	}

	/** Clears the stage for cancellation, invalidity, expiry, or revocation. */
	void cancel() {
		outstanding = false;
		eligible = false;
	}

	boolean outstanding() { return outstanding; }

	boolean eligible() { return eligible; }
}
