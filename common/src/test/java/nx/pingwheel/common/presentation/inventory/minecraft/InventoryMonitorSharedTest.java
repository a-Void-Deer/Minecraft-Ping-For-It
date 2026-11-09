package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.InventoryPublicationBackendFixture;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real certified paid-prefix reuse and Backend fan-out through the existing planner. */
@NotNullByDefault
class InventoryMonitorSharedTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	static MonitorSample sample(InventoryRuntime runtime, long tick) throws ReflectiveOperationException {
		var method = InventoryRuntime.class.getDeclaredMethod("sampleMonitor"); method.setAccessible(true); method.invoke(runtime);
		return java.util.Objects.requireNonNull(runtime.monitor().completeTick(tick));
	}
	@Test void replayAddsNoPhysicalChargeTailPaysOnlyNewGettersAndRecipientFanoutEncodesOnce() throws ReflectiveOperationException {
		var planner = new InventoryPooledRuntimeTest.Planner(); var settings = InventoryPooledRuntimeTest.settings(false, false);
		var clock = new AtomicLong(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 16), clock::get);
		planner.duringRead = () -> clock.addAndGet(17);
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true, monitor)) {
			monitor.configure(true, true); runtime.advance(0, settings);
			var first = InventoryPooledRuntimeTest.attach(runtime, InventoryPooledRuntimeTest.input(1, BlockFace.NORTH));
			var peer = InventoryPooledRuntimeTest.attach(runtime, InventoryPooledRuntimeTest.input(2, BlockFace.SOUTH));
			runtime.prepare(first); runtime.prepare(peer);
			assertEquals(3, runtime.step(first, 3).orElseThrow().slots().size());
			var initial = sample(runtime, 0); assertEquals(3, initial.shared().budget(Budget.PHYSICAL_SLOTS).used());
			assertEquals(3, initial.shared().counter(Counter.READ_ATTEMPTS).value());
			runtime.advance(1, settings); assertEquals(3, runtime.step(peer, 3).orElseThrow().slots().size());
			var replay = sample(runtime, 1); assertEquals(0, replay.shared().budget(Budget.PHYSICAL_SLOTS).used());
			assertEquals(3, replay.shared().counter(Counter.READ_ATTEMPTS).value(), "paid replay is not another read attempt");
			assertEquals(3, planner.addresses.size()); assertEquals(51, replay.tracking().timing(Phase.READ).totalNs(), "paid replay invokes no new source read interval");
			assertEquals(initial.tracking().timing(Phase.READ).count(), replay.tracking().timing(Phase.READ).count());
			runtime.advance(2, settings); assertEquals(3, runtime.step(peer, 3).orElseThrow().slots().size());
			var tail = sample(runtime, 2); assertEquals(3, tail.shared().budget(Budget.PHYSICAL_SLOTS).used(), "only three newly visited tail slots are charged");
			assertEquals(6, tail.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(6, planner.addresses.size());
			runtime.advance(3, settings); var idle = sample(runtime, 3);
			assertEquals(0, idle.shared().budget(Budget.PHYSICAL_SLOTS).used(), "a fresh ledger is not a cumulative event counter");
			assertEquals(6, idle.shared().counter(Counter.READ_ATTEMPTS).value());
		}
		var fanoutPlanner = new InventoryPooledRuntimeTest.Planner();
		var resolver = (InventoryRuntime.SourceResolver) (input, cap) -> Optional.of(fanoutPlanner.source(input, cap));
		try (var f = new InventoryPublicationBackendFixture(resolver, settings, nx.pingwheel.common.marker.TargetKey.from(InventoryPooledRuntimeTest.TARGET))) {
			long request = f.open(InventoryPublicationBackendFixture.A, InventoryPooledRuntimeTest.TARGET, BlockFace.NORTH, 0);
			f.backend.tick(0, settings); MarkerId marker = f.select(InventoryPublicationBackendFixture.A, request, 1);
			f.closePreview(InventoryPublicationBackendFixture.A, request, 1);
			f.runtime.monitor().configure(false, true);
			int beforeSeed = fanoutPlanner.addresses.size(); assertEquals(9, beforeSeed);
			// Seed-only tick: physical allowance is spent by competing work. Both recipients share its one detached encode.
			nx.pingwheel.common.presentation.inventory.InventoryPublicationTicks.withoutScanSlots(f.backend, 2, settings);
			for (int tick = 2; tick <= 6; tick++) { if (tick > 2) f.runtime.advance(tick, settings); f.backend.completeMonitorTick(tick); }
			var sample = java.util.Objects.requireNonNull(f.runtime.monitor().latest());
			assertEquals(1, sample.tracking().timing(Phase.DETACHED_ENCODE).count());
			assertEquals(2, f.frames.stream().filter(packet -> marker.equals(packet.markerId()) && !packet.entries().isEmpty()).count());
			for (var recipient : java.util.List.of(InventoryPublicationBackendFixture.A, InventoryPublicationBackendFixture.B)) {
				int frames = 0;
				for (int index = 0; index < f.frames.size(); index++) if (recipient.equals(f.recipients.get(index))
					&& marker.equals(f.frames.get(index).markerId()) && !f.frames.get(index).entries().isEmpty()) frames++;
				assertEquals(1, frames);
			}
			assertEquals(beforeSeed, fanoutPlanner.addresses.size(), "Tracking seed does not recapture completed Preview");
		}
	}
	@ParameterizedTest @CsvSource({"true,0,1", "false,0,1", "true,5,6", "false,5,6"})
	void failedPooledPagePaysAttemptEvenWhenItCommitsOnlyAPrefixOrNothing(boolean capacity, int failAt, long attempts) throws ReflectiveOperationException {
		var planner = new InventoryPooledRuntimeTest.Planner(); planner.failAt = failAt; planner.failCapacity = capacity;
		var settings = InventoryPooledRuntimeTest.settings(false, false);
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 16), () -> 0);
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true, monitor)) {
			monitor.configure(true, true); runtime.advance(0, settings);
			var consumer = InventoryPooledRuntimeTest.attach(runtime, InventoryPooledRuntimeTest.input(1, BlockFace.NORTH));
			runtime.prepare(consumer);
			if (failAt == 5) assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			var failure = runtime.step(consumer, 3).orElseThrow();
			assertEquals(capacity && failAt == 5 ? 2 : 0, failure.slots().size());
			assertEquals(!capacity, consumer.invalidated());
			var sampled = sample(runtime, 0);
			assertEquals(attempts, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(attempts, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used());
			assertEquals(attempts, planner.addresses.size());
		}
	}
	@Test void typedAuthorityReadFailurePaysItsUncommittedGetter() throws ReflectiveOperationException {
		var planner = new InventoryPooledRuntimeTest.Planner(); var settings = InventoryPooledRuntimeTest.settings(false, false);
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		var input = InventoryPooledRuntimeTest.input(1, BlockFace.NORTH);
		planner.duringRead = () -> planner.denied.put(input, true);
		try (var runtime = new InventoryRuntime((value, cap) -> Optional.of(planner.source(value, cap)), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true, monitor)) {
			monitor.configure(false, true); runtime.advance(0, settings);
			var consumer = InventoryPooledRuntimeTest.attach(runtime, input); runtime.prepare(consumer);
			assertTrue(runtime.step(consumer, 3).orElseThrow().slots().isEmpty()); assertTrue(consumer.invalidated());
			assertEquals(1, sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(java.util.List.of(0), planner.addresses);
		}
	}
	@Test void completedSameTickNewConsumerReadsAgainAndWindowExpiryNeverReplaysLedgerSpend() throws ReflectiveOperationException {
		var planner = new InventoryPooledRuntimeTest.Planner(); var settings = InventoryPooledRuntimeTest.settings(false, false);
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 2), () -> 0);
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true, monitor)) {
			monitor.configure(true, true); runtime.advance(0, settings);
			var input = InventoryPooledRuntimeTest.input(1, BlockFace.NORTH);
			assertEquals(27_000_063L, InventoryPooledRuntimeTest.finish(runtime, InventoryPooledRuntimeTest.attach(runtime, input)));
			assertEquals(27_000_063L, InventoryPooledRuntimeTest.finish(runtime, InventoryPooledRuntimeTest.attach(runtime, input)));
			assertEquals(18, sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS).value());
			runtime.advance(1, settings); assertEquals(18, sample(runtime, 1).shared().counter(Counter.READ_ATTEMPTS).value());
			runtime.advance(2, settings); assertEquals(0, sample(runtime, 2).shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(18, planner.addresses.size());
		}
	}
	@ParameterizedTest @CsvSource({"true", "false"})
	void pooledCallbackCollectionChangeDropsOldOperationWithoutReplayingItsPaidPrefix(boolean initiallyEnabled) throws ReflectiveOperationException {
		var planner = new InventoryPooledRuntimeTest.Planner(); var settings = InventoryPooledRuntimeTest.settings(false, false);
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(planner.source(input, cap)), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true, monitor)) {
			monitor.configure(initiallyEnabled, false); runtime.advance(0, settings);
			var first = InventoryPooledRuntimeTest.attach(runtime, InventoryPooledRuntimeTest.input(1, BlockFace.NORTH));
			var peer = InventoryPooledRuntimeTest.attach(runtime, InventoryPooledRuntimeTest.input(2, BlockFace.SOUTH));
			runtime.prepare(first); runtime.prepare(peer);
			planner.duringRead = () -> {
				planner.duringRead = () -> {}; monitor.configure(false, false); monitor.configure(true, false);
			};
			assertEquals(3, runtime.step(first, 3).orElseThrow().slots().size());
			assertEquals(0, sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS).value());
			runtime.advance(1, settings); assertEquals(3, runtime.step(peer, 3).orElseThrow().slots().size());
			assertEquals(0, sample(runtime, 1).shared().counter(Counter.READ_ATTEMPTS).value());
			runtime.advance(2, settings); assertEquals(3, runtime.step(peer, 3).orElseThrow().slots().size());
			assertEquals(3, sample(runtime, 2).shared().counter(Counter.READ_ATTEMPTS).value()); assertEquals(6, planner.addresses.size());
		}
	}
}
