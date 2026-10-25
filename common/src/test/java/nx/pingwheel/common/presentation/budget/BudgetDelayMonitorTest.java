package nx.pingwheel.common.presentation.budget;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor.Count;
import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor.CycleSummary;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Quantity;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.WorkItem;

import static org.junit.jupiter.api.Assertions.*;

/** Oracles use the confirmed server-tick boundaries, not monitor constants. */
class BudgetDelayMonitorTest {

	private static final Module PRESENTATION = Module.PRESENTATION;

	private static final class Fixture {
		final List<CycleSummary> warnings = new ArrayList<>();
		final List<Set<Module>> notices = new ArrayList<>();
		boolean delivered = true;
		final BudgetDelayMonitor monitor = new BudgetDelayMonitor(warnings::add, modules -> {
			notices.add(Set.copyOf(modules));
			return delivered;
		});
		void tick(Module module, long cycle, long now, boolean enabled) {
			monitor.observe(event(module, cycle, now, "job", true, Reason.SOURCE_WORK.bit(), Quantity.unknown()));
			monitor.state(module, cycle, now, DelayState.DELAYED);
			monitor.endCycle(module, cycle, now);
			monitor.finishTick(now, enabled);
		}
	}

	private static DelayEvent event(Module module, long cycle, long now, String identity,
		boolean first, int reasons, Quantity quantity) {
		return new DelayEvent(module, cycle, now, new WorkItem(identity, "context " + identity), first, reasons, quantity);
	}

	@Test void thresholdAtZeroOriginNeedsNewDelayedTickAndWarnsEveryClosedCycle() {
		Fixture f = new Fixture();
		f.tick(PRESENTATION, 0, 0, true);
		f.tick(PRESENTATION, 1, 19, true);
		assertEquals(2, f.warnings.size(), "WARN is per closed budget cycle, not throttled");
		assertTrue(f.notices.isEmpty());
		f.monitor.finishTick(20, true);
		assertTrue(f.notices.isEmpty(), "elapsed time alone is not a delayed tick");
		f.tick(PRESENTATION, 2, 20, true);
		assertEquals(List.of(Set.of(PRESENTATION)), f.notices);
		f.monitor.finishTick(400, true);
		assertEquals(1, f.notices.size(), "an old state is consumed at the final tick flush");
	}

	@Test void actualObservationStartsBeforeFinalStateAtNonzeroServerTick() {
		Fixture f = new Fixture();
		long origin = 1234;
		f.monitor.observe(event(PRESENTATION, 0, origin, "job", true, Reason.CAPTURE_COUNT.bit(), Quantity.unknown()));
		f.monitor.state(PRESENTATION, 0, origin + 2, DelayState.DELAYED);
		f.monitor.finishTick(origin + 2, true);
		f.monitor.state(PRESENTATION, 0, origin + 19, DelayState.DELAYED);
		f.monitor.finishTick(origin + 19, true);
		assertTrue(f.notices.isEmpty());
		f.monitor.state(PRESENTATION, 0, origin + 20, DelayState.DELAYED);
		f.monitor.finishTick(origin + 20, true);
		assertEquals(1, f.notices.size());
	}

	@Test void repeatedIngressAndAccountingCyclesInOneServerTickCannotAdvanceTheStreak() {
		Fixture f = new Fixture();
		for (int cycle = 0; cycle < 100; cycle++) f.tick(PRESENTATION, cycle, 1234, true);
		assertEquals(100, f.warnings.size(), "cycle diagnostics are independent of elapsed server ticks");
		assertTrue(f.notices.isEmpty());
		f.tick(PRESENTATION, 100, 1253, true);
		assertTrue(f.notices.isEmpty());
		f.tick(PRESENTATION, 101, 1254, true);
		assertEquals(1, f.notices.size());
	}

	@Test void firstLateFlushCannotAgeOldEvidenceIntoAQualifyingDelayedTick() {
		Fixture f = new Fixture();
		f.monitor.observe(event(PRESENTATION, 0, 0, "job", true, Reason.MEMORY.bit(), Quantity.unknown()));
		f.monitor.state(PRESENTATION, 0, 0, DelayState.DELAYED);
		f.monitor.finishTick(20, true);
		assertTrue(f.notices.isEmpty(), "no later actual delayed tick occurred before the first flush");
		f.monitor.state(PRESENTATION, 0, 20, DelayState.DELAYED);
		f.monitor.finishTick(20, true);
		assertEquals(1, f.notices.size());
	}

	@Test void deliveredAtZeroConsumesGlobalCooldownAtExactTwoHundredTickBoundary() {
		Fixture f = new Fixture();
		f.tick(PRESENTATION, 0, -20, true);
		f.tick(PRESENTATION, 1, 0, true);
		assertEquals(1, f.notices.size());
		f.tick(Module.INVENTORY_PREVIEW, 0, 1, true);
		f.tick(Module.INVENTORY_PREVIEW, 1, 21, true);
		f.tick(Module.INVENTORY_PREVIEW, 2, 199, true);
		assertEquals(1, f.notices.size(), "another module shares the delivered cooldown");
		f.tick(Module.INVENTORY_PREVIEW, 3, 200, true);
		assertEquals(List.of(Set.of(PRESENTATION), Set.of(Module.INVENTORY_PREVIEW)), f.notices);
	}

	@Test void allQualifyingModulesAreConsolidatedAndCannotMutateTheNoticeSet() {
		List<Set<Module>> notices = new ArrayList<>();
		BudgetDelayMonitor monitor = new BudgetDelayMonitor(summary -> {}, modules -> {
			assertThrows(UnsupportedOperationException.class, () -> modules.add(PRESENTATION));
			notices.add(Set.copyOf(modules));
			return true;
		});
		for (Module module : Module.values()) monitor.state(module, 0, 0, DelayState.DELAYED);
		monitor.finishTick(0, true);
		for (Module module : Module.values()) monitor.state(module, 0, 20, DelayState.DELAYED);
		monitor.finishTick(20, true);
		assertEquals(List.of(Set.of(PRESENTATION, Module.INVENTORY_PREVIEW, Module.INVENTORY_TRACKING)), notices);
	}

	@Test void disabledNoticesKeepWarnAndStreakAndReenableDoesNotRefundDeliveredCooldown() {
		Fixture f = new Fixture();
		f.tick(PRESENTATION, 0, 0, false);
		f.tick(PRESENTATION, 1, 20, false);
		assertEquals(2, f.warnings.size());
		assertTrue(f.notices.isEmpty());
		f.tick(PRESENTATION, 2, 21, true);
		assertEquals(1, f.notices.size(), "reenable uses the existing delayed streak");
		f.tick(PRESENTATION, 3, 40, false);
		f.tick(PRESENTATION, 4, 41, true);
		f.tick(PRESENTATION, 5, 220, true);
		assertEquals(1, f.notices.size());
		f.tick(PRESENTATION, 6, 221, true);
		assertEquals(2, f.notices.size());
		assertEquals(7, f.warnings.size());
	}

	@Test void noRecipientsOrFailedHandoffRetriesOnlyOnFreshEvidence() {
		Fixture f = new Fixture();
		f.delivered = false;
		f.tick(PRESENTATION, 0, 0, true);
		f.tick(PRESENTATION, 1, 20, true);
		assertEquals(1, f.notices.size());
		f.monitor.finishTick(21, true);
		assertEquals(1, f.notices.size());
		f.delivered = true;
		f.tick(PRESENTATION, 2, 21, true);
		assertEquals(2, f.notices.size(), "failed dispatch consumed no cooldown");
		f.tick(PRESENTATION, 3, 22, true);
		assertEquals(2, f.notices.size());
	}

	@Test void neutralNeitherCreatesNorDispatchesButPreservesAnActualStreak() {
		Fixture f = new Fixture();
		f.monitor.state(PRESENTATION, 0, 0, DelayState.NEUTRAL);
		f.monitor.finishTick(40, true);
		f.monitor.endCycle(PRESENTATION, 0, 40);
		f.tick(PRESENTATION, 1, 40, true);
		assertTrue(f.notices.isEmpty(), "neutral did not start at zero");
		f.monitor.state(PRESENTATION, 2, 60, DelayState.NEUTRAL);
		f.monitor.finishTick(60, true);
		f.monitor.endCycle(PRESENTATION, 2, 60);
		assertTrue(f.notices.isEmpty(), "neutral does not dispatch an old delayed state");
		f.tick(PRESENTATION, 3, 61, true);
		assertEquals(1, f.notices.size(), "neutral did not erase actual continuous delay");
	}

	@Test void healthyAndIdleClearDelayEvenWhenThatCycleHadWarnableObservations() {
		for (DelayState cleared : List.of(DelayState.HEALTHY, DelayState.IDLE)) {
			Fixture f = new Fixture();
			f.monitor.observe(event(PRESENTATION, 0, 0, "job", true, Reason.MEMORY.bit(), Quantity.unknown()));
			f.monitor.state(PRESENTATION, 0, 19, cleared);
			f.monitor.endCycle(PRESENTATION, 0, 19);
			f.monitor.finishTick(19, true);
			assertEquals(1, f.warnings.size());
			f.tick(PRESENTATION, 1, 20, true);
			f.tick(PRESENTATION, 2, 39, true);
			assertTrue(f.notices.isEmpty(), cleared + " must restart the streak");
			f.tick(PRESENTATION, 3, 40, true);
			assertEquals(1, f.notices.size());
		}
	}

	@Test void resetDiscardsOpenBuffersStreakAndCooldownAndAcceptsReusedCycleIds() {
		Fixture f = new Fixture();
		f.tick(PRESENTATION, 0, 0, true);
		f.tick(PRESENTATION, 1, 20, true);
		f.monitor.observe(event(Module.INVENTORY_TRACKING, 80, 21, "old", true, Reason.MEMORY.bit(), Quantity.unknown()));
		f.monitor.reset();
		f.monitor.endCycle(Module.INVENTORY_TRACKING, 80, 22);
		assertEquals(2, f.warnings.size(), "reset drops old-world open observations");
		f.tick(PRESENTATION, 0, 22, true);
		f.tick(PRESENTATION, 1, 41, true);
		assertEquals(1, f.notices.size());
		f.tick(PRESENTATION, 2, 42, true);
		assertEquals(2, f.notices.size(), "new lifetime has no old delivered cooldown");
	}

	@Test void producerHintsDeduplicateWorkAndQuantityButAddNewReasonsAndStageWork() {
		Fixture f = new Fixture();
		f.monitor.observe(event(PRESENTATION, 0, 0, "target/logical", true,
			Reason.LOGICAL_GLOBAL.bit(), Quantity.of(Unit.LOGICAL_SLOT, 4)));
		f.monitor.observe(event(PRESENTATION, 0, 1, "target/logical", false,
			Reason.MEMORY.bit(), Quantity.of(Unit.LOGICAL_SLOT, 900)));
		f.monitor.observe(event(PRESENTATION, 0, 2, "target/physical", true,
			Reason.PHYSICAL_SLOTS.bit(), Quantity.of(Unit.PHYSICAL_SLOT, 7)));
		f.monitor.observe(event(PRESENTATION, 0, 3, "target/wire", true,
			Reason.WIRE_GLOBAL.bit() | Reason.QUEUE_CAPACITY.bit(), Quantity.unknown()));
		f.monitor.endCycle(PRESENTATION, 0, 4);
		CycleSummary summary = f.warnings.getFirst();
		assertEquals(new Count(3, false), summary.observedWorkItems());
		assertEquals(new Count(4, false), summary.quantities().get(Unit.LOGICAL_SLOT));
		assertEquals(new Count(7, false), summary.quantities().get(Unit.PHYSICAL_SLOT));
		assertEquals(new Count(1, false), summary.unknownQuantities());
		assertEquals(5, summary.reasons().size());
		assertTrue(summary.reasons().values().stream().allMatch(count -> count.equals(new Count(1, false))));
		assertEquals(List.of("target/logical", "target/physical", "target/wire"), summary.samples().stream().map(WorkItem::identity).toList());
	}

	@Test void noIdentitySetIsInventedAndTenOrElevenObservedItemsHaveBoundedDetachedSamples() {
		for (int total : List.of(10, 11)) {
			Fixture f = new Fixture();
			for (int i = 0; i < total; i++) f.monitor.observe(event(PRESENTATION, 0, 0, "same target",
				true, Reason.CACHE_CAPACITY.bit(), Quantity.unknown()));
			f.monitor.endCycle(PRESENTATION, 0, 0);
			CycleSummary summary = f.warnings.getFirst();
			assertEquals(total, summary.observedWorkItems().amount(), "producer hints count work, not distinct physical targets");
			assertEquals(10, summary.samples().size());
			assertThrows(UnsupportedOperationException.class, () -> summary.samples().clear());
			assertThrows(UnsupportedOperationException.class, () -> summary.reasons().clear());
			assertThrows(UnsupportedOperationException.class, () -> summary.quantities().clear());
			f.tick(PRESENTATION, 1, 1, false);
			assertEquals(total, summary.observedWorkItems().amount(), "later cycle clearing cannot mutate the old snapshot");
		}
	}

	@Test void quantitiesSaturateWithoutMixingUnitsAndKnownZeroIsNotUnknown() {
		Fixture f = new Fixture();
		f.monitor.observe(event(PRESENTATION, 0, 0, "bytes1", true, Reason.WIRE_GLOBAL.bit(), Quantity.of(Unit.BYTE, Long.MAX_VALUE)));
		f.monitor.observe(event(PRESENTATION, 0, 0, "bytes2", true, Reason.WIRE_GLOBAL.bit(), Quantity.of(Unit.BYTE, 1)));
		f.monitor.observe(event(PRESENTATION, 0, 0, "entries", true, Reason.QUEUE_CAPACITY.bit(), Quantity.of(Unit.ENTRY, 0)));
		f.monitor.observe(event(PRESENTATION, 0, 0, "slots", true, Reason.PHYSICAL_SLOTS.bit(), Quantity.of(Unit.PHYSICAL_SLOT, 8)));
		f.monitor.observe(event(PRESENTATION, 0, 0, "unknown", true, Reason.PROBE_WORK.bit(), Quantity.unknown()));
		f.monitor.endCycle(PRESENTATION, 0, 0);
		CycleSummary summary = f.warnings.getFirst();
		assertEquals(new Count(Long.MAX_VALUE, true), summary.quantities().get(Unit.BYTE));
		assertEquals(new Count(0, false), summary.quantities().get(Unit.ENTRY));
		assertEquals(new Count(8, false), summary.quantities().get(Unit.PHYSICAL_SLOT));
		assertEquals(new Count(1, false), summary.unknownQuantities());
		assertFalse(summary.quantities().containsKey(Unit.UNKNOWN));
	}

	@Test void ownerMustCloseOpenCycleAndClosedOrStaleReportsCannotReopenOrClearStreak() {
		Fixture f = new Fixture();
		f.monitor.observe(event(PRESENTATION, 4, 0, "real", true, Reason.MEMORY.bit(), Quantity.unknown()));
		f.monitor.state(PRESENTATION, 4, 0, DelayState.DELAYED);
		f.monitor.finishTick(0, true);
		f.monitor.observe(event(PRESENTATION, 5, 0, "premature", true, Reason.MEMORY.bit(), Quantity.unknown()));
		f.monitor.endCycle(PRESENTATION, 5, 0);
		f.monitor.state(PRESENTATION, 3, 0, DelayState.HEALTHY);
		f.monitor.endCycle(PRESENTATION, 4, 0);
		f.monitor.endCycle(PRESENTATION, 4, 0);
		f.monitor.observe(event(PRESENTATION, 4, 1, "closed", true, Reason.MEMORY.bit(), Quantity.unknown()));
		f.monitor.state(PRESENTATION, 4, 1, DelayState.IDLE);
		f.tick(PRESENTATION, 5, 20, true);
		assertEquals(2, f.warnings.size());
		assertEquals(4, f.warnings.getFirst().cycleId());
		assertEquals(1, f.warnings.getFirst().observedWorkItems().amount());
		assertEquals(1, f.notices.size(), "stale healthy/idle reports do not reset a current streak");
		f.monitor.state(PRESENTATION, 5, 220, DelayState.DELAYED);
		f.monitor.finishTick(220, true);
		assertEquals(1, f.notices.size(), "closed-cycle delayed state cannot create fresh notice evidence");
		f.monitor.endCycle(Module.INVENTORY_PREVIEW, 100, 20);
		assertEquals(2, f.warnings.size(), "skipped/unopened empty periods manufacture no WARN");
	}

	@Test void recoverablePortExceptionsAreIsolatedButFatalResourceErrorsPropagate() {
		for (Throwable failure : List.of(new IllegalStateException("log"), new NoClassDefFoundError("log"))) {
			int[] attempts = {0};
			BudgetDelayMonitor monitor = new BudgetDelayMonitor(summary -> failWith(failure), modules -> {
				if (attempts[0]++ == 0) failWith(failure);
				return true;
			});
			monitor.observe(event(PRESENTATION, 0, 0, "job", true, Reason.MEMORY.bit(), Quantity.unknown()));
			monitor.state(PRESENTATION, 0, 0, DelayState.DELAYED);
			assertDoesNotThrow(() -> monitor.endCycle(PRESENTATION, 0, 0));
			monitor.finishTick(0, true);
			monitor.state(PRESENTATION, 1, 20, DelayState.DELAYED);
			assertDoesNotThrow(() -> monitor.finishTick(20, true));
			monitor.state(PRESENTATION, 1, 21, DelayState.DELAYED);
			monitor.finishTick(21, true);
			assertEquals(2, attempts[0]);
		}
		OutOfMemoryError fatal = new OutOfMemoryError("test resource failure");
		BudgetDelayMonitor monitor = new BudgetDelayMonitor(summary -> { throw fatal; }, modules -> { throw fatal; });
		monitor.observe(event(PRESENTATION, 0, 0, "job", true, Reason.MEMORY.bit(), Quantity.unknown()));
		monitor.state(PRESENTATION, 0, 0, DelayState.DELAYED);
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> monitor.endCycle(PRESENTATION, 0, 0)));
		monitor.finishTick(0, true);
		monitor.state(PRESENTATION, 1, 20, DelayState.DELAYED);
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> monitor.finishTick(20, true)));
	}

	private static void failWith(Throwable failure) {
		if (failure instanceof RuntimeException runtime) throw runtime;
		throw (LinkageError) failure;
	}

	@Test void frozenValueHelpersRejectInvalidEventsAndKeepSafeSingleLineContext() {
		assertEquals(new Quantity(Unit.UNKNOWN, 0, false), Quantity.unknown());
		assertThrows(IllegalArgumentException.class, () -> Quantity.of(Unit.BYTE, -1));
		assertThrows(IllegalArgumentException.class, () -> event(PRESENTATION, 0, 0, "job", true, 0, Quantity.unknown()));
		assertThrows(IllegalArgumentException.class, () -> event(PRESENTATION, 0, 0, "job", true, 1 << 18, Quantity.unknown()));
		assertEquals(1, Reason.CAPTURE_COUNT.bit());
		assertEquals(1 << 17, Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE.bit());
		assertArrayEquals(new Reason[] {Reason.CAPTURE_COUNT, Reason.SOURCE_WORK, Reason.PER_CALL_ALLOWANCE,
			Reason.CAPTURE_DISABLED, Reason.CACHE_CAPACITY, Reason.PHYSICAL_SLOTS, Reason.LOGICAL_CLIENT_OR_TARGET,
			Reason.LOGICAL_GLOBAL, Reason.PROBE_WORK, Reason.PROVIDER_WORK, Reason.MEMORY, Reason.ROUND_CAPACITY,
			Reason.SUBJECT_CAPACITY, Reason.PUBLICATION_WORK, Reason.QUEUE_CAPACITY, Reason.WIRE_CLIENT_OR_STREAM,
			Reason.WIRE_GLOBAL, Reason.FRAME_OR_FRAGMENT_UNSERVICEABLE}, Reason.values(),
			"the frozen public bit layout must remain interoperable across producer packages");
		WorkItem item = new WorkItem("id\n\r\u0000\u2028\u202e" + "x".repeat(300), "description\t" + "y".repeat(300));
		assertEquals(256, item.identity().length());
		assertEquals(256, item.description().length());
		assertTrue(item.identity().startsWith("idxxx"));
		assertFalse(item.description().contains("\t"));
		assertDoesNotThrow(() -> {
			BudgetDelaySink.NONE.observe(null);
			BudgetDelaySink.NONE.state(null, 0, 0, null);
			BudgetDelaySink.NONE.endCycle(null, 0, 0);
		});
	}
}
