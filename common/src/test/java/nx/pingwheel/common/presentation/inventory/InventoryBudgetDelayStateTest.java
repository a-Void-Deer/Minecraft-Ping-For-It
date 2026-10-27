package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class InventoryBudgetDelayStateTest {
	private static final WorkItem ITEM = new WorkItem("tracking:round", "marker=7 stage=round");

	private static class Sink implements BudgetDelaySink {
		final List<DelayEvent> events = new ArrayList<>();

		@Override public void observe(DelayEvent event) { events.add(event); }
		@Override public void state(Module module, long cycleId, long serverTick, DelayState state) {
			fail("final module state belongs to the caller");
		}
		@Override public void endCycle(Module module, long cycleId, long serverTick) {
			fail("only the backend closes inventory cycles");
		}
	}

	private static boolean observe(InventoryBudgetDelayState state, BudgetDelaySink sink,
		long cycle, int reasons, Quantity quantity) {
		return state.observe(sink, cycle, 10, reasons, () -> ITEM, () -> quantity);
	}

	@Test
	void zeroFirstCycleDeduplicatesQuantityAndAddsOnlyNewReasons() {
		var state = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		var sink = new Sink();
		Quantity firstQuantity = Quantity.of(Unit.PHYSICAL_SLOT, 3);
		int firstReasons = Reason.PHYSICAL_SLOTS.bit() | Reason.PROVIDER_WORK.bit();

		assertTrue(observe(state, sink, 0, firstReasons, firstQuantity));
		assertFalse(observe(state, sink, 0, firstReasons, Quantity.of(Unit.ENTRY, 99)));
		assertTrue(observe(state, sink, 0, firstReasons | Reason.MEMORY.bit(), Quantity.unknown()));

		assertEquals(2, sink.events.size());
		assertTrue(sink.events.getFirst().firstItemObservation());
		assertEquals(firstReasons, sink.events.getFirst().newReasonBits());
		assertSame(firstQuantity, sink.events.getFirst().quantity());
		assertFalse(sink.events.getLast().firstItemObservation());
		assertEquals(Reason.MEMORY.bit(), sink.events.getLast().newReasonBits());

		var warnings = new ArrayList<BudgetDelayMonitor.CycleSummary>();
		var monitor = new BudgetDelayMonitor(warnings::add, modules -> false);
		sink.events.forEach(monitor::observe);
		monitor.endCycle(Module.INVENTORY_TRACKING, 0, 20);
		var summary = warnings.getFirst();
		assertEquals(1, summary.observedWorkItems().amount());
		assertEquals(1, summary.samples().size());
		assertEquals(3, summary.quantities().get(Unit.PHYSICAL_SLOT).amount());
		assertEquals(1, summary.quantities().size());
		assertEquals(0, summary.unknownQuantities().amount());
		assertEquals(3, summary.reasons().size());
		summary.reasons().values().forEach(count -> assertEquals(1, count.amount()));
	}

	@Test
	void progressRetainsCycleHistoryAndARepeatedRefusalRestoresPendingState() {
		var state = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		var sink = new Sink();
		observe(state, sink, -3, Reason.LOGICAL_GLOBAL.bit(), Quantity.of(Unit.LOGICAL_SLOT, 2));

		state.progress(-3);
		assertFalse(state.outstanding());
		assertTrue(state.eligible());
		state.beginTick(-3);
		assertFalse(state.eligible());
		assertFalse(observe(state, sink, -3, Reason.LOGICAL_GLOBAL.bit(), Quantity.of(Unit.LOGICAL_SLOT, 4)));
		assertTrue(state.outstanding());
		assertTrue(state.eligible());
		assertEquals(1, sink.events.size());

		assertTrue(observe(state, sink, -3, Reason.MEMORY.bit(), Quantity.unknown()));
		assertFalse(sink.events.getLast().firstItemObservation());
	}

	@Test
	void resolvedCycleStillProducesWarningAndNextObservedCycleHasFreshEvidence() {
		var state = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		var warnings = new ArrayList<BudgetDelayMonitor.CycleSummary>();
		var monitor = new BudgetDelayMonitor(warnings::add, modules -> false);
		observe(state, monitor, -1, Reason.MEMORY.bit(), Quantity.unknown());
		state.progress(-1);
		monitor.state(Module.INVENTORY_PREVIEW, -1, 20, DelayState.HEALTHY);
		monitor.endCycle(Module.INVENTORY_PREVIEW, -1, 20);
		assertEquals(1, warnings.size());
		assertEquals(1, warnings.getFirst().unknownQuantities().amount());

		state.beginCycle(8);
		assertFalse(state.outstanding());
		assertFalse(state.eligible());
		observe(state, monitor, 8, Reason.MEMORY.bit(), Quantity.of(Unit.CONSUMER_WORK, 1));
		assertEquals(1, warnings.size(), "the recorder never closes a cycle, including skipped cycles");
		monitor.endCycle(Module.INVENTORY_PREVIEW, 8, 30);
		assertEquals(List.of(-1L, 8L), warnings.stream().map(BudgetDelayMonitor.CycleSummary::cycleId).toList());
		assertEquals(1, warnings.getLast().observedWorkItems().amount());
		assertEquals(1, warnings.getLast().quantities().get(Unit.CONSUMER_WORK).amount());
	}

	@Test
	void pendingDelaySurvivesUnchangedCadenceAndAccountingRolloverUntilCancelled() {
		var state = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		var sink = new Sink();
		observe(state, sink, 4, Reason.QUEUE_CAPACITY.bit(), Quantity.of(Unit.PUBLICATION_WORK, 1));

		state.beginTick(4);
		assertTrue(state.outstanding());
		assertFalse(state.eligible());
		state.beginTick(5);
		assertTrue(state.outstanding());
		assertFalse(state.eligible());
		assertEquals(1, sink.events.size(), "cadence and rollover are not synthetic observations");

		state.cancel();
		assertFalse(state.outstanding());
		assertFalse(state.eligible());
		state.markEligible(5);
		assertTrue(state.eligible());
		assertFalse(state.outstanding());
	}

	@Test
	void cancellationDoesNotEraseAlreadyEmittedCycleHistoryOrAnotherStagesDelay() {
		var first = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		var other = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
		var sink = new Sink();
		observe(first, sink, 0, Reason.MEMORY.bit(), Quantity.unknown());
		other.observe(sink, 0, 10, Reason.WIRE_GLOBAL.bit(),
			() -> new WorkItem("tracking:send", "marker=7 stage=send"), () -> Quantity.of(Unit.BYTE, 42));

		first.cancel();
		assertFalse(first.outstanding());
		assertTrue(other.outstanding());
		assertFalse(observe(first, sink, 0, Reason.MEMORY.bit(), Quantity.unknown()));
		assertEquals(2, sink.events.size());
		assertTrue(sink.events.getFirst().firstItemObservation());
		assertTrue(sink.events.getLast().firstItemObservation());
	}

	@Test
	void noneAndNullSinksSkipDiagnosticFactoriesAndDuplicateReasonsDoNotRebuildThem() {
		var state = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		assertTrue(state.observe(BudgetDelaySink.NONE, 0, 0, Reason.MEMORY.bit(),
			() -> { throw new AssertionError("item constructed for NONE"); },
			() -> { throw new AssertionError("quantity constructed for NONE"); }));
		assertTrue(state.observe(null, 0, 0, Reason.PROVIDER_WORK.bit(), null, null));
		assertFalse(state.observe(new Sink(), 0, 0, Reason.MEMORY.bit(),
			() -> { throw new AssertionError("duplicate item constructed"); }, null));
		assertTrue(state.outstanding());
	}

	@Test
	void recoverableSinkAndDiagnosticConstructionFailuresCannotPreventLaterProgress() {
		for (Throwable failure : List.of(new IllegalStateException("diagnostic"), new LinkageError("diagnostic"))) {
			var state = new InventoryBudgetDelayState(Module.INVENTORY_TRACKING);
			var throwingSink = new Sink() {
				@Override public void observe(DelayEvent event) { throwFailure(failure); }
			};
			assertTrue(observe(state, throwingSink, 1, Reason.MEMORY.bit(), Quantity.unknown()));
			assertTrue(state.outstanding());
			state.progress(1);
			assertFalse(state.outstanding());
			assertFalse(observe(state, throwingSink, 1, Reason.MEMORY.bit(), Quantity.unknown()));

			assertTrue(state.observe(new Sink(), 1, 0, Reason.PROVIDER_WORK.bit(),
				() -> { throwFailure(failure); return ITEM; }, Quantity::unknown));
			state.progress(1);
			assertFalse(state.outstanding());
			assertTrue(state.observe(new Sink(), 1, 0, Reason.PROBE_WORK.bit(),
				() -> ITEM, () -> { throwFailure(failure); return Quantity.unknown(); }));
			state.progress(1);
			assertFalse(state.outstanding());
		}
	}

	@Test
	void fatalSinkAndDiagnosticFailuresPropagate() {
		var state = new InventoryBudgetDelayState(Module.INVENTORY_PREVIEW);
		var fatal = new OutOfMemoryError("test failure");
		var throwingSink = new Sink() {
			@Override public void observe(DelayEvent event) { throw fatal; }
		};
		assertSame(fatal, assertThrows(OutOfMemoryError.class,
			() -> observe(state, throwingSink, 2, Reason.MEMORY.bit(), Quantity.unknown())));
		assertTrue(state.outstanding());
		assertSame(fatal, assertThrows(OutOfMemoryError.class,
			() -> state.observe(new Sink(), 2, 0, Reason.PROVIDER_WORK.bit(), () -> { throw fatal; }, Quantity::unknown)));
	}

	private static void throwFailure(Throwable failure) {
		if (failure instanceof Error error) throw error;
		throw (RuntimeException) failure;
	}
}
