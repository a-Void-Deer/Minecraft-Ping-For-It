package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real ingress, capture, selection, publication and ledger transitions, not fabricated samples. */
@NotNullByDefault
class InventoryMonitorBackendTest {

	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@NotNullByDefault
	static final class Clock implements LongSupplier {
		long time;
		int reads;
		boolean proofToSend;
		@Override public long getAsLong() { assertFalse(proofToSend, "no monitor clock between final proof and transport"); reads++; return time; }
	}

	@NotNullByDefault
	static final class Fixture implements AutoCloseable {
		final Clock clock = new Clock();
		final ServerInventoryMonitor monitor = new ServerInventoryMonitor(new MonitorTuning(1, 16), clock);
		final InventorySettings settings = InventorySettings.serverDefaults();
		final AtomicInteger reads = new AtomicInteger();
		Runnable markerLookup = () -> {};
		final InventoryBackendTest.Host host = new InventoryBackendTest.Host() {
			@Override public Optional<nx.pingwheel.common.marker.ServerMarker> activeMarker(nx.pingwheel.common.domain.MarkerId marker) {
				markerLookup.run(); return super.activeMarker(marker);
			}
			@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) {
				clock.time += 11; return packet.encodedSize(display, frame);
			}
			@Override public InventorySyncPublisher.SendDecision beforeSend(nx.pingwheel.common.presentation.source.SyncPublisher.Context context,
				InventoryS2CPacket packet, @Nullable InventoryPublicationEvidence evidence) {
				return super.beforeSend(context, packet, evidence);
			}
			@Override public TransportHandoff sendObserved(UUID player, InventoryS2CPacket packet) {
				clock.time += 13; send(player, packet); return TransportHandoff.HANDED_OFF;
			}
		};
		final InventoryRuntime runtime = new InventoryRuntime((input, display) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public boolean valid() { clock.time += 7; return true; }
			@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
				clock.time += 17; reads.incrementAndGet(); return slot == 0 ? InventoryRuntimeTest.item(7) : null;
			}
		}), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
		final InventoryBackend backend;
		Fixture() { this(BudgetDelaySink.NONE); }
		Fixture(BudgetDelaySink sink) { backend = new InventoryBackend(host, runtime, 1, sink); settings.getTracking().setHeartbeatPeriods(0); }
		void hello(long tick) {
			for (UUID player : List.of(InventoryBackendTest.A, InventoryBackendTest.B)) backend.handle(player, InventoryC2SPacket.hello(), tick, settings);
		}
		void open(long request, long tick) {
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1,
				request, InventoryRuntimeTest.TARGET, BlockFace.NORTH), tick, settings);
		}
		InventoryC2SPacket select(long request, long commit) {
			var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW
				&& packet.requestId() == request && !packet.entries().isEmpty()).reduce((first, last) -> last).orElseThrow();
			return InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, commit, request,
				preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger");
		}
		MonitorSample sample(long tick) { return java.util.Objects.requireNonNull(backend.completeMonitorTick(tick)); }
		@Override public void close() { backend.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved()); }
	}

	@Test void disabledClockHasNoReadsThroughOpenTickSelectionRetryCloseAndReconnect() {
		try (var f = new Fixture()) {
			f.monitor.resetGeneration(1); f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			var select = f.select(1, 1); f.host.failResultSend = true;
			f.backend.handle(InventoryBackendTest.A, select, 1, f.settings);
			assertEquals(1, f.host.store.size()); assertTrue(f.host.packets.stream().noneMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.SELECTED));
			f.host.failResultSend = false; f.backend.tick(2, f.settings);
			assertEquals(1, f.host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.SELECTED).count());
			f.backend.handle(InventoryBackendTest.A, select, 2, f.settings);
			f.backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(f.host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 2, f.settings);
			f.backend.disconnect(InventoryBackendTest.A); f.hello(3); f.open(1, 3); f.backend.tick(3, f.settings);
			assertNull(f.backend.completeMonitorTick(3)); assertNull(f.monitor.latest()); assertEquals(0, f.clock.reads);
		}
	}

	@Test void enableMasksDoNotReplayPastWorkAndExistingRequestsAcquireARealBaseline() {
		try (var f = new Fixture()) {
			f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			f.monitor.configure(true, false);
			var waiting = f.sample(0); assertNull(waiting.preview().gauge(Gauge.REQUESTS_OPEN));
			f.backend.tick(1, f.settings); var preview = f.sample(1);
			assertEquals(1, preview.preview().gauge(Gauge.REQUESTS_OPEN).value()); assertNull(preview.tracking());
			assertNull(preview.preview().counter(Counter.REQUESTS_RECEIVED), "the old OPEN is not replayed");
			f.monitor.configure(true, true); f.backend.tick(2, f.settings); var both = f.sample(2);
			assertNotNull(both.preview()); assertNotNull(both.tracking()); assertNotNull(both.shared().memory());
			f.monitor.configure(false, true); f.backend.tick(3, f.settings); var tracking = f.sample(3);
			assertNull(tracking.preview()); assertNotNull(tracking.tracking());
			f.monitor.configure(false, false); int calls = f.clock.reads;
			f.backend.tick(4, f.settings); assertNull(f.backend.completeMonitorTick(4)); assertEquals(calls, f.clock.reads);
			f.monitor.resetGeneration(9); f.monitor.configure(true, false); f.backend.tick(5, f.settings);
			var reopened = f.sample(5); assertEquals(9, reopened.serverGeneration());
			assertEquals(1, reopened.preview().gauge(Gauge.REQUESTS_OPEN).value());
			assertNull(reopened.preview().counter(Counter.REQUESTS_RECEIVED));
		}
	}

	@Test void realNestedIntervalsUseBusinessAdvancedTimeAndRequestsAreNotSessions() {
		try (var f = new Fixture()) {
			f.monitor.configure(true, true); f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			var sample = f.sample(0); var preview = sample.preview(); assertNotNull(preview);
			assertEquals(1, preview.counter(Counter.REQUESTS_RECEIVED).value());
			assertEquals(1, preview.counter(Counter.REQUESTS_ADMITTED).value());
			assertEquals(1, preview.gauge(Gauge.REQUESTS_OPEN).value()); assertEquals(2, sample.shared().gauge(Gauge.SESSIONS).value());
			assertEquals(41, preview.timing(Phase.READ).totalNs(), "two 17ns getters plus the real 7ns source validity check");
			assertEquals(1, preview.timing(Phase.READ).count(), "one bounded source step, not one span per getter");
			assertTrue(preview.timing(Phase.TICK_WORK).totalNs() >= preview.timing(Phase.READ).totalNs(), "inclusive parent contains the read; do not sum them");
			assertEquals(0, preview.timing(Phase.DETACHED_ENCODE).totalNs(), "a clock read does not itself advance fixture time");
			assertEquals(11, preview.timing(Phase.MEASURE).maxNs());
			assertEquals(31, preview.timing(Phase.PUBLISH).maxNs(), "11ns measurement + 7ns final source check + 13ns send");
			assertEquals(f.reads.get(), sample.shared().budget(Budget.PHYSICAL_SLOTS).used());
			assertTrue(sample.shared().budget(Budget.PROCESSING_CHARGE).used() > 0);
			assertNull(preview.budget(Budget.PHYSICAL_SLOTS)); assertNull(sample.tracking().budget(Budget.PROCESSING_CHARGE));
		}
	}

	@Test void actualDetachedCaptureIsTimedOnceAndLaterDecodeDoesNotRecapture() {
		var settings = InventorySettings.serverDefaults(); var clock = new Clock();
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), clock);
		var source = new InventoryRuntimeTest.DiscoverySource(InventoryRuntimeTest.input(InventoryBackendTest.A)) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(
				nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) {
				var plan = super.snapshotPlan(allowance).orElseThrow();
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return plan.memoryUpperBoundBytes(); }
					@Override public nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost validationCost() { return plan.validationCost(); }
					@Override public long validationAllocationUpperBoundBytes() { return plan.validationAllocationUpperBoundBytes(); }
					@Override public int physicalReadUpperBound() { return plan.physicalReadUpperBound(); }
					@Override public int attemptedPhysicalReads() { return plan.attemptedPhysicalReads(); }
					@Override public long itemWorkUpperBoundUnits() { return plan.itemWorkUpperBoundUnits(); }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { throw new AssertionError("actual caller grant required"); }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture(
						nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance grant) {
						clock.time += 29; return plan.capture(grant);
					}
				});
			}
		};
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(source), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(InventoryBackendTest.A)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			runtime.step(consumer, 1).orElseThrow(); runtime.sampleMonitor();
			var sample = java.util.Objects.requireNonNull(monitor.completeTick(0));
			assertEquals(1, sample.preview().timing(Phase.CAPTURE).count()); assertEquals(29, sample.preview().timing(Phase.CAPTURE).totalNs());
			runtime.advance(1, settings); runtime.step(consumer, 1).orElseThrow(); runtime.sampleMonitor();
			var later = java.util.Objects.requireNonNull(monitor.completeTick(1)); assertEquals(1, later.preview().timing(Phase.CAPTURE).count());
			assertEquals(1, source.captures); assertEquals(0, source.reads.get());
		}
	}

	@Test void retryOnlyTickIncludesItsPublicationInTopLevelProcessing() {
		try (var f = new Fixture()) {
			f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			f.host.failResultSend = true; f.backend.handle(InventoryBackendTest.A, f.select(1, 1), 1, f.settings);
			var marker = f.host.store.allMarkers().getFirst().id(); f.backend.remove(marker);
			int firstFrame = f.host.packets.size();
			f.host.failResultSend = false; f.monitor.configure(true, true);
			f.backend.tick(2, f.settings); var sample = f.sample(2); var tracking = sample.tracking();
			var retried = f.host.packets.subList(firstFrame, f.host.packets.size());
			assertEquals(1, retried.size()); assertEquals(InventoryS2CPacket.Kind.SELECTED, retried.getFirst().kind());
			assertEquals(24, tracking.timing(Phase.PUBLISH).totalNs(), "only retained SELECT outcome retries; removed sidecar cannot publish bootstrap");
			assertEquals(24, tracking.timing(Phase.TICK_WORK).totalNs(), "processing summary includes the retry-only handoff");
		}
	}

	@Test void sameTickSinkReentryCannotEraseOuterProcessingSummary() {
		InventoryBackend @Nullable [] backend = {null}; boolean[] reentered = {false};
		var settings = InventorySettings.serverDefaults();
		var sink = new BudgetDelaySink() {
			@Override public void observe(DelayEvent event) {}
			@Override public void endCycle(Module module, long cycle, long tick) {}
			@Override public void state(Module module, long cycle, long tick, DelayState state) {
				if (!reentered[0]) { reentered[0] = true; java.util.Objects.requireNonNull(backend[0]).tick(tick, settings); }
			}
		};
		try (var f = new Fixture(sink)) {
			backend[0] = f.backend; f.monitor.configure(true, true); f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			var sample = f.sample(0); assertTrue(reentered[0]);
			assertTrue(sample.preview().timing(Phase.TICK_WORK).totalNs() >= sample.preview().timing(Phase.READ).totalNs());
			assertEquals(1, sample.preview().timing(Phase.TICK_WORK).count(), "nested tick belongs to the outer timing owner");
		}
	}
	@Test void nestedTickInsideAnActiveLaneDoesNotDoubleCountItsElapsedDuration() {
		try (var f = new Fixture()) {
			f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			f.backend.handle(InventoryBackendTest.A, f.select(1, 1), 1, f.settings); f.backend.tick(2, f.settings);
			f.monitor.configure(false, true); boolean[] nested = {false};
			f.markerLookup = () -> {
				if (!nested[0]) { nested[0] = true; f.clock.time += 101; f.backend.tick(3, f.settings); }
			};
			long before = f.clock.time; f.backend.tick(3, f.settings); var sample = f.sample(3);
			assertTrue(nested[0]); assertEquals(1, sample.tracking().timing(Phase.TICK_WORK).count());
			assertTrue(sample.tracking().timing(Phase.TICK_WORK).totalNs() <= f.clock.time - before,
				"nested inclusive duration cannot exceed the enclosing business-clock interval");
		}
	}

	@Test void deferredRoundCaptureBelongsToCurrentPreparingLaneNotFirstResolver() {
		var settings = InventorySettings.serverDefaults(); var clock = new Clock();
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), clock);
		var source = new InventoryRuntimeTest.DiscoverySource(InventoryRuntimeTest.input(InventoryBackendTest.A));
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(source), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor)) {
			monitor.configure(false, true); runtime.advance(0, settings);
			var preview = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(InventoryBackendTest.A)).orElseThrow();
			var tracking = runtime.attach(source.input, new InventoryRuntime.TrackingSubject(nx.pingwheel.common.marker.TargetKey.from(source.input.target()))).orElseThrow();
			try {
				assertTrue(runtime.claimDetachedItemWork(InventoryRuntimeTest.itemLedger(runtime).remaining(InventoryRuntimeTest.itemWorkCounter())));
			} catch (Exception failure) { throw new AssertionError(failure); }
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(preview));
			runtime.advance(1, settings); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(tracking));
			runtime.sampleMonitor(); var sample = java.util.Objects.requireNonNull(monitor.completeTick(1));
			assertNull(sample.preview()); assertEquals(1, sample.tracking().timing(Phase.CAPTURE).count()); assertEquals(1, source.captures);
		}
	}

	@Test void actualPublicationReservationIsVisibleBeforeResetAndRepeatedTicksDoNotAccumulateUsage() throws Exception {
		try (var f = new Fixture()) {
			f.settings.getPreview().setPeriodTicks(2); f.settings.getTracking().setPeriodTicks(3);
			f.monitor.configure(true, true); f.hello(0); f.open(1, 0); f.backend.tick(0, f.settings);
			f.backend.handle(InventoryBackendTest.A, f.select(1, 1), 1, f.settings); f.backend.tick(2, f.settings);
			var evidence = new InventoryPublicationAuthorizationTest.Evidence();
			try (var grant = f.runtime.reservePublication(evidence).orElseThrow()) {
				var first = f.sample(2); var processing = first.shared().budget(Budget.PROCESSING_CHARGE);
				assertTrue(processing.reserved() > 0);
				assertEquals(evidence.validationCost().workUnits(), processing.reserved());
				assertNull(f.backend.completeMonitorTick(2)); assertSame(first, f.monitor.latest());
				grant.validate();
			}
			evidence.close();
			f.backend.tick(4, f.settings); var next = f.sample(4);
			assertEquals(0, next.shared().budget(Budget.PROCESSING_CHARGE).reserved());
			assertEquals(4, next.shared().budget(Budget.PHYSICAL_SLOTS).periodId());
			assertEquals(2, next.preview().budget(Budget.LOGICAL_SLOTS).periodId()); assertEquals(1, next.tracking().budget(Budget.LOGICAL_SLOTS).periodId());
			CostLedger physical = InventoryRuntimeTest.itemLedger(f.runtime);
			assertEquals(physical.used(InventoryRuntimeTest.itemWorkCounter()), next.shared().budget(Budget.PROCESSING_CHARGE).used());
		}
	}
}
