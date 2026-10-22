package nx.pingwheel.common.presentation.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.ByteMultiplier;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production producer seams; typed diagnostics only, with no Minecraft startup. */
class InventoryBudgetDelayIntegrationTest {

	static final class RecordingSink implements BudgetDelaySink {
		record Event(Module module, long cycleId, WorkItem item, boolean firstItemObservation,
			int newReasonBits, Quantity quantity) {
			boolean hasReason(Reason reason) { return (newReasonBits & reason.bit()) != 0; }
		}
		record StateCall(Module module, long cycleId, DelayState state) {}
		record EndCall(Module module, long cycleId) {}
		final List<Event> events = new ArrayList<>();
		final List<StateCall> states = new ArrayList<>();
		final List<EndCall> ends = new ArrayList<>();
		@Override public void observe(DelayEvent event) {
			events.add(new Event(event.module(), event.cycleId(), event.item(), event.firstItemObservation(), event.newReasonBits(), event.quantity()));
		}
		@Override public void state(Module module, long cycleId, long nowNanos, DelayState state) { states.add(new StateCall(module, cycleId, state)); }
		@Override public void endCycle(Module module, long cycleId, long nowNanos) { ends.add(new EndCall(module, cycleId)); }
		boolean sawReason(Module module, Reason reason) { return events.stream().anyMatch(event -> event.module() == module && event.hasReason(reason)); }
		long endCount(Module module, long cycleId) { return ends.stream().filter(call -> call.module() == module && call.cycleId() == cycleId).count(); }
		DelayState lastState(Module module, long cycleId) {
			DelayState last = null;
			for (StateCall call : states) if (call.module() == module && call.cycleId() == cycleId) last = call.state();
			return last;
		}
	}
	static final class ThrowingSink implements BudgetDelaySink {
		private final boolean linkage;
		ThrowingSink(boolean linkage) { this.linkage = linkage; }
		private void fail() { if (linkage) throw new LinkageError("monitoring failure"); throw new IllegalStateException("monitoring failure"); }
		@Override public void observe(DelayEvent event) { fail(); }
		@Override public void state(Module module, long cycleId, long nowNanos, DelayState state) { fail(); }
		@Override public void endCycle(Module module, long cycleId, long nowNanos) { fail(); }
	}
	private record ScriptResult(int reads, List<Integer> readSlots, List<InventoryS2CPacket.Kind> kinds,
		long retained, long reserved, long previewPeriod, long trackingPeriod) {}

	private static InventorySettings explicitSettings(int previewPeriod, int trackingPeriod) {
		var settings = InventorySettings.serverDefaults();
		settings.getPreview().setPeriodTicks(previewPeriod); settings.getTracking().setPeriodTicks(trackingPeriod);
		settings.setPendingMemoryMiB(64); return settings;
	}
	private static InventoryRuntime runtime(InventoryRuntime.SourceResolver resolver, InventorySettings settings, BudgetDelaySink sink) {
		return new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), sink);
	}
	private static long firstObservationCount(RecordingSink sink, Module module, Reason reason) {
		return sink.events.stream().filter(event -> event.module() == module && event.firstItemObservation() && event.hasReason(reason)).count();
	}
	private static long reasonEventCount(RecordingSink sink, Module module, Reason reason) {
		return sink.events.stream().filter(event -> event.module() == module && event.hasReason(reason)).count();
	}
	private static InventorySourceAccess.Source blankInclusiveSource(InventorySourceInput input, AtomicInteger reads) {
		return new InventoryRuntimeTest.Source(input, reads, 3) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slot == 1 ? null : InventoryRuntimeTest.item(slot + 1); }
		};
	}
	private static InventorySourceAccess.Source snapshotSource(InventorySourceInput input, AtomicInteger captures, AtomicInteger reads) {
		return new InventoryRuntimeTest.Source(input, reads, 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 2 * InventoryItemBudget.workspaceUpperBound(4096); }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						captures.incrementAndGet();
						return Optional.of(new InventoryRuntimeTest.Snapshot() {
							@Override public int slots() { return 1; }
							@Override public InventoryDomainCodec.Item read(int index) { reads.incrementAndGet(); return InventoryRuntimeTest.item(5); }
							@Override public long retainedBytes() { return 1024; }
						});
					}
				});
			}
		};
	}
	private static void helloOpen(InventoryBackend backend, InventoryBackendTest.Host host, InventorySettings settings) {
		backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 0, settings);
		backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 1,
			InventoryRuntimeTest.TARGET, BlockFace.NORTH), 0, settings);
		backend.tick(0, settings);
	}
	private static void helloOpenAndSelect(InventoryBackend backend, InventoryBackendTest.Host host, InventorySettings settings) {
		helloOpen(backend, host, settings);
		var preview = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).findFirst().orElseThrow();
		backend.handle(InventoryBackendTest.A, InventoryC2SPacket.select(host.epoch(InventoryBackendTest.A), 100, 1, 1, 1,
			preview.baselineId(), preview.statusRevision(), preview.entries().getFirst().key(), "danger"), 1, settings);
	}
	private static ScriptResult runMonitoredScript(BudgetDelaySink sink) {
		var host = new InventoryBackendTest.Host(); var settings = explicitSettings(20, 20);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		var reads = new AtomicInteger(); var slots = new ArrayList<Integer>();
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); slots.add(slot); return InventoryRuntimeTest.item(slot + 1); }
		}), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpen(backend, host, settings); backend.tick(1, settings); backend.tick(21, settings);
			return new ScriptResult(reads.get(), List.copyOf(slots), host.packets.stream().map(InventoryS2CPacket::kind).toList(),
				runtime.memory().retained(), runtime.memory().reserved(), runtime.previewPeriodId(), runtime.trackingPeriodId());
		}
	}

	@Test void physicalStepRefusalUsesPhysicalUnitsAndBlankRestoreProgresses() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		var owner = new UUID(41, 1); var reads = new AtomicInteger();
		try (var runtime = runtime((input, cap) -> Optional.of(blankInclusiveSource(input, reads)), settings, sink)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var first = runtime.step(consumer).orElseThrow(); assertEquals(1, first.slots().size()); assertEquals(3, first.result().coverage().expected().orElseThrow());
			int before = sink.events.size(); assertTrue(runtime.step(consumer).isEmpty());
			assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.PHYSICAL_SLOTS));
			assertTrue(sink.events.subList(before, sink.events.size()).stream().noneMatch(event -> event.quantity().known() && event.quantity().unit() == Unit.LOGICAL_SLOT));
			runtime.advance(1, settings); var blank = runtime.step(consumer).orElseThrow(); assertEquals(1, blank.slots().size()); assertNull(blank.slots().getFirst());
			runtime.advance(2, settings); assertEquals(3, runtime.step(consumer).orElseThrow().slots().getFirst().count()); assertEquals(3, reads.get());
		}
	}
	@Test void logicalRefusalReportsKnownBlankInclusiveRemainderAndRestoresWithoutRereads() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		var owner = new UUID(41, 2); var reads = new AtomicInteger();
		try (var runtime = runtime((input, cap) -> Optional.of(blankInclusiveSource(input, reads)), settings, sink)) {
			runtime.advance(0, settings); var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(3, runtime.step(consumer).orElseThrow().result().coverage().expected().orElseThrow()); assertTrue(runtime.step(consumer).isEmpty());
			var refusal = sink.events.stream().filter(event -> event.hasReason(Reason.LOGICAL_CLIENT_OR_TARGET)).findFirst().orElseThrow();
			assertTrue(refusal.quantity().known()); assertEquals(Unit.LOGICAL_SLOT, refusal.quantity().unit()); assertEquals(2, refusal.quantity().amount(), "blanks remain logical demand");
			assertFalse(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.PHYSICAL_SLOTS));
			settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(3)); var restored = runtime.step(consumer).orElseThrow();
			assertEquals(2, restored.slots().size()); assertNull(restored.slots().getFirst()); assertEquals(3, restored.slots().getLast().count()); assertEquals(3, reads.get());
		}
	}
	@Test void sharedPhysicalRoundRefusalIsObservedOnceForCompatibleConsumers() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		var owner = new UUID(41, 3); var reads = new AtomicInteger();
		try (var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 3)), settings, sink)) {
			runtime.advance(0, settings);
			var a = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size()); assertEquals(1, runtime.step(b).orElseThrow().slots().size());
			assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(b).isEmpty());
			assertEquals(1, firstObservationCount(sink, Module.INVENTORY_PREVIEW, Reason.PHYSICAL_SLOTS));
			assertEquals(1, reasonEventCount(sink, Module.INVENTORY_PREVIEW, Reason.PHYSICAL_SLOTS)); assertEquals(1, reads.get());
		}
	}
	@Test void sharedRoundRetainsItsFirstRefusingModuleWhenThatConsumerLeaves() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		var owner = new UUID(41, 12); var reads = new AtomicInteger();
		try (var runtime = runtime((input, cap) -> Optional.of(blankInclusiveSource(input, reads)), settings, sink)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var tracking = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.TrackingSubject(nx.pingwheel.common.marker.TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			assertEquals(1, runtime.step(preview).orElseThrow().slots().size()); assertEquals(1, runtime.step(tracking).orElseThrow().slots().size());
			preview.close(); assertTrue(runtime.step(tracking).isEmpty());
			assertEquals(1, reasonEventCount(sink, Module.INVENTORY_PREVIEW, Reason.PHYSICAL_SLOTS));
			assertEquals(0, reasonEventCount(sink, Module.INVENTORY_TRACKING, Reason.PHYSICAL_SLOTS));
			assertTrue(runtime.delayed(Module.INVENTORY_PREVIEW));
			runtime.advance(1, settings); assertNull(runtime.step(tracking).orElseThrow().slots().getFirst());
			runtime.advance(2, settings); assertEquals(3, runtime.step(tracking).orElseThrow().slots().getFirst().count());
			assertFalse(runtime.delayed(Module.INVENTORY_PREVIEW)); assertTrue(runtime.eligible(Module.INVENTORY_PREVIEW)); assertEquals(3, reads.get());
			runtime.advance(3, settings); assertFalse(runtime.eligible(Module.INVENTORY_PREVIEW));
		}
	}
	@Test void separateLogicalRequestsStayDistinctWithoutDuplicatingSharedPhysicalReads() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var owner = new UUID(41, 4); var reads = new AtomicInteger();
		try (var runtime = runtime((input, cap) -> Optional.of(blankInclusiveSource(input, reads)), settings, sink)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var tracking = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.TrackingSubject(nx.pingwheel.common.marker.TargetKey.from(InventoryRuntimeTest.TARGET))).orElseThrow();
			assertEquals(1, runtime.step(preview).orElseThrow().slots().size()); assertEquals(1, runtime.step(tracking).orElseThrow().slots().size()); assertEquals(1, reads.get());
			assertTrue(runtime.step(preview).isEmpty()); assertTrue(runtime.step(tracking).isEmpty());
			var logical = sink.events.stream().filter(event -> event.firstItemObservation() && event.hasReason(Reason.LOGICAL_CLIENT_OR_TARGET)).toList();
			assertEquals(2, logical.size()); assertEquals(2, logical.stream().map(event -> event.item().identity()).distinct().count());
			assertTrue(logical.stream().allMatch(event -> event.quantity().known() && event.quantity().unit() == Unit.LOGICAL_SLOT && event.quantity().amount() == 2));
			assertFalse(sink.events.stream().anyMatch(event -> event.hasReason(Reason.PHYSICAL_SLOTS)));
		}
	}
	@Test void memoryDeferIsRetryableWhileUnavailableUnsafeEmptyAndBoundedContinuationAreNotDelayed() {
		var settings = explicitSettings(20, 20); var sink = new RecordingSink(); var captures = new AtomicInteger(); var reads = new AtomicInteger();
		try (var runtime = runtime((input, cap) -> Optional.of(snapshotSource(input, captures, reads)), settings, sink)) {
			runtime.advance(0, settings); var owner = new UUID(41, 5);
			var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			// Cap the ledger directly: avoids depending on A1's changing root coefficient.
			runtime.memory().setCap(runtime.memory().retained());
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer)); assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.MEMORY)); assertEquals(0, captures.get());
			runtime.advance(1, settings); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(5, runtime.step(consumer).orElseThrow().slots().getFirst().count()); assertEquals(1, captures.get());
		}
		var excluded = new RecordingSink();
		for (int mode = 0; mode < 3; mode++) {
			int sourceMode = mode;
			try (var runtime = runtime((input, cap) -> sourceMode == 0 ? Optional.empty() : Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), sourceMode == 2 ? 0 : 1) {
				@Override public boolean valid() { return sourceMode == 2; }
			}), settings, excluded)) {
				runtime.advance(0, settings); var owner = new UUID(41, 6 + mode);
				var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); assertTrue(runtime.step(consumer).isPresent());
			}
		}
		assertTrue(excluded.events.isEmpty()); var bounded = new RecordingSink(); settings.setPhysicalSlotsPerTick(IntLimit.finite(256));
		try (var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 100)), settings, bounded)) {
			runtime.advance(0, settings); var owner = new UUID(41, 9);
			var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(consumer).orElseThrow().result().completeness());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness()); assertTrue(bounded.events.isEmpty());
		}
	}
	@Test void noAudienceAndCadenceWaitNeverClaimDelay() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(10, 20); var reads = new AtomicInteger();
		host.policies.put(InventoryBackendTest.A, new InventoryBackend.Policy(100, 1, Set.of("block")));
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2)), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpen(backend, host, settings); backend.tick(5, settings); assertEquals(0, reads.get()); assertTrue(sink.events.isEmpty());
			host.policies.put(InventoryBackendTest.A, new InventoryBackend.Policy(100, 1, Set.of("entity_block")));
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 6, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.open(host.epoch(InventoryBackendTest.A), 100, 1, 2, InventoryRuntimeTest.TARGET, BlockFace.NORTH), 6, settings);
			backend.tick(6, settings); int scanned = reads.get(); assertTrue(scanned > 0);
			backend.tick(7, settings); backend.tick(8, settings); assertEquals(scanned, reads.get()); assertTrue(sink.events.isEmpty());
			assertEquals(DelayState.NEUTRAL, sink.lastState(Module.INVENTORY_PREVIEW, runtime.previewPeriodId()));
		}
	}
	@Test void previewAndTrackingCyclesCloseOnceAcrossIngressRepeatedTicksAndSkippedPeriods() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(10, 20);
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1)), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpenAndSelect(backend, host, settings); long previewCycle = runtime.previewPeriodId(), trackingCycle = runtime.trackingPeriodId();
			for (int now = 1; now <= 9; now++) { backend.handle(InventoryBackendTest.A, null, now, settings); backend.tick(now, settings); }
			assertEquals(0, sink.endCount(Module.INVENTORY_PREVIEW, previewCycle)); assertEquals(0, sink.endCount(Module.INVENTORY_TRACKING, trackingCycle));
			backend.handle(InventoryBackendTest.A, null, 10, settings); // ingress observes the real rollover first
			assertEquals(1, sink.endCount(Module.INVENTORY_PREVIEW, previewCycle)); assertEquals(1, runtime.previewPeriodId());
			backend.tick(10, settings); backend.tick(10, settings); backend.tick(11, settings); assertEquals(1, sink.endCount(Module.INVENTORY_PREVIEW, previewCycle));
			backend.tick(45, settings); assertEquals(1, sink.endCount(Module.INVENTORY_PREVIEW, 1));
			assertEquals(0, sink.endCount(Module.INVENTORY_PREVIEW, 2)); assertEquals(0, sink.endCount(Module.INVENTORY_PREVIEW, 3)); assertEquals(4, runtime.previewPeriodId());
			assertEquals(1, sink.endCount(Module.INVENTORY_TRACKING, trackingCycle)); assertEquals(0, sink.endCount(Module.INVENTORY_TRACKING, 1)); assertEquals(2, runtime.trackingPeriodId());
			backend.tick(45, settings); assertEquals(1, sink.endCount(Module.INVENTORY_TRACKING, trackingCycle));
		}
	}
	@Test void duePreviewPublicationDelayPersistsAcrossTicksThenResolvesSameCycleWithActualFrame() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(10, 20);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); var reads = new AtomicInteger();
		var runtime = runtime((input, cap) -> Optional.of(blankInclusiveSource(input, reads)), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpen(backend, host, settings); long cycle = runtime.previewPeriodId(); assertEquals(1, reads.get());
			assertFalse(host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.requestId() == 1 && packet.completeScan()));
			backend.tick(1, settings); assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.LOGICAL_CLIENT_OR_TARGET));
			assertEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, cycle));
			backend.handle(InventoryBackendTest.A, null, 2, settings); backend.tick(2, settings); backend.tick(4, settings);
			assertEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, cycle)); assertEquals(1, firstObservationCount(sink, Module.INVENTORY_PREVIEW, Reason.LOGICAL_CLIENT_OR_TARGET)); assertEquals(1, reads.get());
			settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(3)); backend.tick(5, settings); assertEquals(3, reads.get());
			var completed = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.requestId() == 1 && packet.completeScan() && !packet.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			assertEquals(1, completed.entries().size()); assertEquals(4, completed.entries().getFirst().count());
			assertNotEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, cycle)); backend.tick(10, settings);
			assertEquals(1, sink.endCount(Module.INVENTORY_PREVIEW, cycle)); assertTrue(sink.events.stream().anyMatch(event -> event.cycleId() == cycle && event.hasReason(Reason.LOGICAL_CLIENT_OR_TARGET)));
		}
	}
	@Test void invalidityRemovesOutstandingPreviewDelayBeforeCycleClose() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(10, 20);
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); boolean[] sourceValidity = {true}; var reads = new AtomicInteger();
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2) { @Override public boolean valid() { return sourceValidity[0]; } }), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpen(backend, host, settings); long cycle = runtime.previewPeriodId(); backend.tick(1, settings); assertEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, cycle));
			sourceValidity[0] = false; backend.tick(2, settings); backend.tick(3, settings); assertNotEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, cycle));
			// Preview INVALID is its request-scoped empty PREVIEW control, not marker STATUS.
			assertTrue(host.packets.stream().anyMatch(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.status() == InventoryS2CPacket.Status.INVALID && packet.requestId() == 1));
			backend.tick(10, settings); assertEquals(1, sink.endCount(Module.INVENTORY_PREVIEW, cycle));
		}
	}
	@Test void expiredTrackingLeaseRemovesOutstandingDelayBeforeCycleClose() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); host.expiry = 12;
		var settings = explicitSettings(10, 20); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1)); var reads = new AtomicInteger();
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2)), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpenAndSelect(backend, host, settings);
			// CLOSE releases only preview demand; SELECT's live witness was already read.
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 1, settings);
			int beforeTracking = reads.get(); long cycle = runtime.trackingPeriodId(); backend.tick(1, settings); assertEquals(beforeTracking + 1, reads.get());
			backend.tick(2, settings); assertTrue(sink.sawReason(Module.INVENTORY_TRACKING, Reason.LOGICAL_CLIENT_OR_TARGET)); assertEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_TRACKING, cycle));
			backend.tick(11, settings); int beforeExpiry = reads.get(); backend.tick(12, settings); backend.tick(13, settings);
			assertNotEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_TRACKING, cycle)); assertEquals(beforeExpiry, reads.get());
			backend.tick(20, settings); assertEquals(1, sink.endCount(Module.INVENTORY_TRACKING, cycle));
		}
	}
	@Test void requestScopedResyncRepairsWithoutRecaptureExtraOfferOrDelayClaim() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(20, 20); var reads = new AtomicInteger();
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1)), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpen(backend, host, settings); assertEquals(1, reads.get());
			long offers = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER).count();
			var before = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 1, settings); backend.tick(1, settings);
			var repaired = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			assertEquals(before.baselineId(), repaired.baselineId()); assertTrue(repaired.watermark() > before.watermark());
			assertEquals(offers, host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER).count()); assertEquals(1, reads.get()); assertTrue(sink.events.isEmpty());
			int settled = host.packets.size(); backend.tick(2, settings); assertEquals(settled, host.packets.size());
		}
	}
	@Test void negotiationControlsAndTransportOnlyFailuresAreNeverBudgetDelays() {
		var settings = explicitSettings(20, 20); var sink = new RecordingSink(); var transport = new InventorySyncPublisherTest.Transport();
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16_000_000), transport, sink)) {
			publisher.advance(0, settings); assertTrue(publisher.control(new UUID(1, 1), InventoryS2CPacket.offer(7, publisher.offer(5, 20, 5, 16)).stamp(100, 1)));
			publisher.drainControls(); assertEquals(1, transport.sent.size()); assertTrue(sink.events.isEmpty()); assertTrue(sink.states.isEmpty() && sink.ends.isEmpty());
		}
		var failing = new InventorySyncPublisherTest.Transport() {
			boolean blocked = true;
			@Override public void send(UUID player, InventoryS2CPacket packet) { if (blocked) throw new IllegalStateException("transport refusal"); super.send(player, packet); }
		};
		var guarded = new RecordingSink();
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16_000_000), failing, guarded)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "target", null)));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 1, 64)); publisher.drain();
			assertTrue(failing.sent.isEmpty()); assertTrue(publisher.pending(context)); assertTrue(guarded.events.isEmpty());
			failing.blocked = false; publisher.drain(); assertFalse(failing.sent.isEmpty()); assertTrue(guarded.events.isEmpty());
		}
	}
	@Test void noneRuntimeAndLinkageThrowingSinksKeepReadsFramesAccountingAndPeriodsIdentical() {
		var baseline = assertDoesNotThrow(() -> runMonitoredScript(BudgetDelaySink.NONE));
		assertEquals(baseline, assertDoesNotThrow(() -> runMonitoredScript(new ThrowingSink(false))));
		assertEquals(baseline, assertDoesNotThrow(() -> runMonitoredScript(new ThrowingSink(true))));
	}
	@Test void fatalDiagnosticFailurePropagatesAfterActualPhysicalRefusal() {
		var settings = explicitSettings(20, 20); settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); var reads = new AtomicInteger();
		BudgetDelaySink fatal = new BudgetDelaySink() {
			@Override public void observe(DelayEvent event) { throw new OutOfMemoryError("diagnostic fatal failure"); }
			@Override public void state(Module module, long cycleId, long nowNanos, DelayState state) {}
			@Override public void endCycle(Module module, long cycleId, long nowNanos) {}
		};
		try (var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 2)), settings, fatal)) {
			runtime.advance(0, settings); var owner = new UUID(41, 14);
			var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertThrows(OutOfMemoryError.class, () -> runtime.step(consumer)); assertEquals(1, reads.get());
		}
	}

	@Test void memoryRefusalBeforeCoverageStaysUnknownAndNeverReadsForDiagnostics() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); var resolves = new AtomicInteger(); var reads = new AtomicInteger(); var owner = new UUID(41, 10);
		try (var runtime = runtime((input, cap) -> { resolves.incrementAndGet(); return Optional.of(new InventoryRuntimeTest.Source(input, reads, 3)); }, settings, sink)) {
			runtime.advance(0, settings); var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.memory().setCap(runtime.memory().retained());
			assertTrue(runtime.step(consumer).isEmpty()); assertEquals(0, resolves.get()); assertEquals(0, reads.get());
			assertTrue(sink.events.stream().anyMatch(event -> event.hasReason(Reason.MEMORY) && !event.quantity().known()));
			runtime.advance(1, settings); assertEquals(3, runtime.step(consumer).orElseThrow().slots().size());
		}
	}
	@Test void snapshotPlanMemoryRefusalOccursAfterResolutionBeforeCaptureAndRetries() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); var captures = new AtomicInteger(); var reads = new AtomicInteger(); var resolves = new AtomicInteger(); var owner = new UUID(41, 11);
		try (var runtime = runtime((input, cap) -> { resolves.incrementAndGet(); return Optional.of(snapshotSource(input, captures, reads)); }, settings, sink)) {
			runtime.advance(0, settings); var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var pressure = runtime.memory().tryReserve(runtime.memory().remaining() - 3 * InventoryItemBudget.workspaceUpperBound(4096) / 2).orElseThrow();
			try {
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
				assertEquals(1, resolves.get()); assertEquals(0, captures.get()); assertEquals(0, reads.get());
				var event = sink.events.stream().filter(value -> value.hasReason(Reason.MEMORY)).findFirst().orElseThrow();
				assertTrue(event.item().identity().startsWith("round/")); assertFalse(event.quantity().known());
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer)); assertEquals(1, reasonEventCount(sink, Module.INVENTORY_PREVIEW, Reason.MEMORY));
			} finally { pressure.close(); }
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer)); assertEquals(1, captures.get());
			assertEquals(5, runtime.step(consumer).orElseThrow().slots().getFirst().count()); assertEquals(1, reads.get()); assertFalse(runtime.delayed(Module.INVENTORY_PREVIEW));
		}
	}
	@Test void runtimeAndPublisherPeriodsAgreeAcrossRepeatedInputsSkipsAndCadenceEdits() {
		var settings = explicitSettings(10, 20);
		try (var runtime = runtime((input, cap) -> Optional.empty(), settings, null);
			var publisher = new InventorySyncPublisher(runtime.memory(), new InventorySyncPublisherTest.Transport(), null)) {
			for (int now : new int[] {0, 0, 9, 10, 10, 45, 46, 50, 100}) {
				if (now == 46) { settings.getPreview().setPeriodTicks(30); settings.getTracking().setPeriodTicks(5); }
				runtime.advance(now, settings); publisher.advance(now, settings);
				assertEquals(runtime.previewPeriodId(), publisher.periodId(Module.INVENTORY_PREVIEW));
				assertEquals(runtime.trackingPeriodId(), publisher.periodId(Module.INVENTORY_TRACKING));
				if (now == 50) { assertEquals(4, runtime.previewPeriodId()); assertEquals(2, runtime.trackingPeriodId()); }
			}
		}
	}
	@Test void attemptedCaptureFailureIsTerminalButTheNextUnadmittedCaptureIsDelayed() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); var captures = new AtomicInteger(); var reads = new AtomicInteger(); var owner = new UUID(41, 13);
		try (var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					int attempted;
					@Override public long memoryUpperBoundBytes() { return 1024; }
					@Override public int physicalReadUpperBound() { return InventorySourceAccess.MAX_SLOTS; }
					@Override public long itemWorkUpperBoundUnits() { return Math.multiplyExact(InventorySourceAccess.MAX_SLOTS, InventoryItemBudget.workUpperBound(4096)); }
					@Override public int attemptedPhysicalReads() { return attempted; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						captures.incrementAndGet(); attempted = InventorySourceAccess.MAX_SLOTS;
						throw new nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot.LimitException("capture rollback");
					}
				});
			}
		}), settings, sink)) {
			runtime.advance(0, settings); var first = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.INCOMPLETE, runtime.step(first).orElseThrow().result().completeness()); assertTrue(sink.events.isEmpty());
			var next = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(next)); assertEquals(1, captures.get()); assertEquals(0, reads.get());
			assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.CAPTURE_COUNT)); assertTrue(runtime.delayed(Module.INVENTORY_PREVIEW));
			runtime.advance(1, settings); assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, runtime.prepare(next));
			assertEquals(2, captures.get()); assertFalse(runtime.delayed(Module.INVENTORY_PREVIEW));
		}
	}
	private static SyncPublisher.Outcome offer(InventorySyncPublisher publisher, SyncPublisher.Context context, CaptureResult result, long work) {
		var payload = (CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow();
		return publisher.publish(result, new SyncPublisher.AuthorizedProjection(SyncPublisher.PublicationForm.KEYED_ABSOLUTE, payload.entries().keySet()), context,
			new CostLedger(Map.of(InventorySyncPublisher.PUBLICATIONS, work)));
	}
	@Test void publisherWorkAndPublicationMemoryRefusalsDeduplicateAndResolve() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); var memory = new RetainedMemoryLedger(16_000_000); var transport = new InventorySyncPublisherTest.Transport();
		try (var publisher = new InventorySyncPublisher(memory, transport, sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1);
			assertTrue(publisher.register(context, InventorySyncPublisherTest.route(null, "target"))); var capture = InventorySyncPublisherTest.capture(1, 1, 64);
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(publisher, context, capture, 0));
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(publisher, context, capture, 0));
			memory.setCap(memory.retained()); assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(publisher, context, capture, 1));
			assertEquals(1, firstObservationCount(sink, Module.INVENTORY_PREVIEW, Reason.PUBLICATION_WORK)); assertEquals(1, reasonEventCount(sink, Module.INVENTORY_PREVIEW, Reason.MEMORY));
			assertTrue(publisher.delayed(Module.INVENTORY_PREVIEW)); memory.setCap(16_000_000); assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(publisher, context, capture, 1));
			publisher.drain(); assertFalse(publisher.delayed(Module.INVENTORY_PREVIEW)); assertFalse(transport.sent.isEmpty()); assertTrue(sink.states.isEmpty() && sink.ends.isEmpty());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	@Test void publisherBatchMemoryDelayPersistsAcrossAdvanceAndInvalidationCancelsData() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); var memory = new RetainedMemoryLedger(16_000_000); var transport = new InventorySyncPublisherTest.Transport();
		try (var publisher = new InventorySyncPublisher(memory, transport, sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(null, "target")));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 1, 64)); memory.setCap(memory.retained()); publisher.drain();
			assertTrue(publisher.pending(context)); assertTrue(publisher.delayed(Module.INVENTORY_PREVIEW)); int events = sink.events.size();
			publisher.advance(1, settings); assertTrue(publisher.delayed(Module.INVENTORY_PREVIEW)); publisher.drain(); assertEquals(events, sink.events.size());
			memory.setCap(16_000_000); publisher.status(context, InventoryS2CPacket.Status.INVALID); publisher.drain();
			assertFalse(publisher.pending(context)); assertFalse(publisher.delayed(Module.INVENTORY_PREVIEW));
			assertTrue(transport.sent.stream().allMatch(packet -> packet.entries().isEmpty() && packet.status() == InventoryS2CPacket.Status.INVALID));
		}
	}
	@Test void publisherQueueRefusalKeepsOldCutAndRestoresWithoutNewCapture() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		var memory = new RetainedMemoryLedger(64_000_000); var transport = new InventorySyncPublisherTest.Transport();
		try (var publisher = new InventorySyncPublisher(memory, transport, sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(null, "target")));
			var capture = InventorySyncPublisherTest.capture(1, 32, 1024);
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(publisher, context, capture, 256)); assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.QUEUE_CAPACITY));
			settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(BigDecimal.valueOf(4))); publisher.advance(1, settings);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(publisher, context, capture, 256)); assertFalse(publisher.delayed(Module.INVENTORY_PREVIEW)); assertTrue(publisher.pending(context));
		}
	}
	@Test void publisherMeasuredWireDelayStaysSeparateFromPhysicalSlotsAndClearsOnRestore() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, transport, sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("tracking/1", 1); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(new MarkerId(1), "target")));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 3, 2500)); publisher.drain();
			assertTrue(publisher.pending(context)); assertTrue(sink.sawReason(Module.INVENTORY_TRACKING, Reason.WIRE_CLIENT_OR_STREAM));
			assertTrue(sink.events.stream().filter(event -> event.hasReason(Reason.WIRE_CLIENT_OR_STREAM)).allMatch(event -> event.quantity().known() && event.quantity().unit() == Unit.BYTE));
			assertFalse(sink.events.stream().anyMatch(event -> event.hasReason(Reason.PHYSICAL_SLOTS) || event.hasReason(Reason.LOGICAL_GLOBAL)));
			settings.getTracking().setSnapshotByteMultiplier(ByteMultiplier.finite(BigDecimal.valueOf(4))); publisher.advance(1, settings); publisher.drain();
			assertFalse(publisher.pending(context)); assertFalse(publisher.delayed(Module.INVENTORY_TRACKING));
		}
	}
	@Test void publisherGlobalWireRefusalIsMeasuredAndResolvesAfterBudgetIncrease() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20);
		settings.getPreview().setClientByteMultiplier(ByteMultiplier.finite(BigDecimal.valueOf(8)));
		settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(new BigDecimal("0.25")));
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, transport, sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(null, "target")));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 3, 3000)); publisher.drain();
			assertTrue(publisher.pending(context)); assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.WIRE_GLOBAL));
			assertFalse(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.WIRE_CLIENT_OR_STREAM));
			assertTrue(sink.events.stream().filter(event -> event.hasReason(Reason.WIRE_GLOBAL)).allMatch(event -> event.quantity().known() && event.quantity().unit() == Unit.BYTE));
			settings.getPreview().setGlobalByteMultiplier(ByteMultiplier.finite(BigDecimal.valueOf(4))); publisher.advance(1, settings); publisher.drain();
			assertFalse(publisher.pending(context)); assertFalse(publisher.delayed(Module.INVENTORY_PREVIEW));
		}
	}
	@Test void withdrawingDataPreservesItsIndependentlyBlockedControlUntilAdmission() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, new InventorySyncPublisherTest.Transport(), sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("tracking/1", 1); assertTrue(publisher.register(context, InventorySyncPublisherTest.route(new MarkerId(1), "target")));
			publisher.status(context, InventoryS2CPacket.Status.UPDATING); memory.setCap(memory.retained()); publisher.drain();
			assertTrue(publisher.delayed(Module.INVENTORY_TRACKING)); publisher.withdraw(context);
			assertTrue(publisher.controlPending(context)); assertTrue(publisher.delayed(Module.INVENTORY_TRACKING));
			memory.setCap(16_000_000); publisher.drain(); assertFalse(publisher.controlPending(context)); assertFalse(publisher.delayed(Module.INVENTORY_TRACKING));
		}
	}
	@Test void olderAcceptedDuplicateDoesNotClearNewerPublicationRefusal() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16_000_000), new InventorySyncPublisherTest.Transport(), sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1);
			assertTrue(publisher.register(context, InventorySyncPublisherTest.route(null, "target")));
			var old = InventorySyncPublisherTest.capture(1, 1, 64); var latest = InventorySyncPublisherTest.capture(2, 1, 128);
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(publisher, context, old, 1)); publisher.drain();
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(publisher, context, latest, 0));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(publisher, context, old, 0));
			assertTrue(publisher.delayed(Module.INVENTORY_PREVIEW));
			assertInstanceOf(SyncPublisher.Outcome.Accepted.class, offer(publisher, context, latest, 1));
			assertFalse(publisher.delayed(Module.INVENTORY_PREVIEW));
		}
	}
	@Test void revokingEveryAudienceMemberCancelsLateRegistrationDelay() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(20, 20); var reads = new AtomicInteger();
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1)), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpenAndSelect(backend, host, settings);
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 1, settings);
			backend.handle(InventoryBackendTest.B, InventoryC2SPacket.hello(), 1, settings);
			var pressure = runtime.memory().tryReserve(runtime.memory().remaining()).orElseThrow();
			try {
				backend.tick(2, settings);
				assertTrue(sink.events.stream().anyMatch(event -> event.module() == Module.INVENTORY_TRACKING && event.hasReason(Reason.MEMORY)
					&& event.item().identity().endsWith("/registration")));
				assertEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_TRACKING, runtime.trackingPeriodId()));
				host.policies.put(InventoryBackendTest.A, new InventoryBackend.Policy(100, 2, Set.of()));
				host.policies.put(InventoryBackendTest.B, new InventoryBackend.Policy(100, 2, Set.of()));
				int before = reads.get(); backend.tick(3, settings);
				assertEquals(before, reads.get()); assertEquals(DelayState.IDLE, sink.lastState(Module.INVENTORY_TRACKING, runtime.trackingPeriodId()));
			} finally { pressure.close(); }
		}
	}
	@Test void rejectedIncompleteTrackingPublicationCancelsItsPriorPublicationRefusal() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20);
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16_000_000), new InventorySyncPublisherTest.Transport(), sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("tracking/1", 1);
			assertTrue(publisher.register(context, InventorySyncPublisherTest.route(new MarkerId(1), "target")));
			var complete = InventorySyncPublisherTest.capture(1, 1, 64);
			assertInstanceOf(SyncPublisher.Outcome.Deferred.class, offer(publisher, context, complete, 0));
			var incomplete = new CaptureResult(complete.payload(), complete.coverage(), complete.availability(),
				CaptureResult.Completeness.INCOMPLETE, complete.consistency(), complete.sourceVersion(), complete.nextCursor());
			assertInstanceOf(SyncPublisher.Outcome.Rejected.class, offer(publisher, context, incomplete, 1));
			assertFalse(publisher.delayed(Module.INVENTORY_TRACKING));
			assertEquals(1, reasonEventCount(sink, Module.INVENTORY_TRACKING, Reason.PUBLICATION_WORK));
		}
	}
	@Test void deliveredPreviewSkipsDuplicateEncodingButRepairStillReportsItsNewRevisionAndChecksValidity() {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(100, 100);
		settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
		settings.getPreview().setMaxVariantsPerClientPeriod(IntLimit.finite(256));
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(256));
		settings.getPreview().getClientByteMultiplier().setUnlimited(true); settings.getPreview().getGlobalByteMultiplier().setUnlimited(true);
		var reads = new AtomicInteger(); var validations = new AtomicInteger(); boolean[] sourceValidity = {true};
		var runtime = runtime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 160) {
			@Override public int displayBytes() { return cap; }
			@Override public boolean valid() { validations.incrementAndGet(); return sourceValidity[0]; }
			@Override public InventoryDomainCodec.Item read(int slot) {
				reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "v" + slot),
					slot + 1, "stone", "d".repeat(8192), false);
			}
		}), settings, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			helloOpen(backend, host, settings);
			for (int now = 1; now <= 8; now++) backend.tick(now, settings);
			assertEquals(160, reads.get());
			var complete = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && packet.completeScan()).reduce((a, b) -> b).orElseThrow();
			assertEquals(160, host.packets.stream().flatMap(packet -> packet.entries().stream()).map(InventoryS2CPacket.Entry::key).distinct().count());
			assertTrue(sink.events.isEmpty());
			// Enough for the actual authority workspace, less than cumulative
			// domain encoding (160 independently serviceable 8 KiB displays).
			long headroom = InventoryItemBudget.workspaceUpperBound((int) settings.effectiveDisplayBytes()) + 1024 * 1024;
			var pressure = runtime.memory().tryReserve(runtime.memory().remaining() - headroom).orElseThrow();
			try {
				int frames = host.packets.size(), checked = validations.get();
				backend.tick(9, settings); backend.tick(10, settings);
				assertEquals(frames, host.packets.size()); assertEquals(160, reads.get()); assertTrue(validations.get() > checked);
				assertTrue(sink.events.isEmpty(), "no observed delay means no delayed-cycle WARN input");
				assertNotEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, runtime.previewPeriodId()));
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.resync(host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 11, settings);
				backend.tick(11, settings);
				assertTrue(sink.sawReason(Module.INVENTORY_PREVIEW, Reason.MEMORY));
				assertEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, runtime.previewPeriodId()));
				assertEquals(160, reads.get());
			} finally { pressure.close(); }
			backend.tick(12, settings);
			var replay = host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.PREVIEW && !packet.entries().isEmpty()).reduce((a, b) -> b).orElseThrow();
			assertTrue(replay.watermark() > complete.watermark()); assertEquals(complete.baselineId(), replay.baselineId());
			assertNotEquals(DelayState.DELAYED, sink.lastState(Module.INVENTORY_PREVIEW, runtime.previewPeriodId())); assertEquals(160, reads.get());
			sourceValidity[0] = false; backend.tick(13, settings);
			assertEquals(InventoryS2CPacket.Status.INVALID, host.packets.getLast().status()); assertEquals(160, reads.get());
		}
	}
	private static void reconfigurationEligibilitySequence(boolean tracking, boolean cancel, boolean revoke) {
		var sink = new RecordingSink(); var host = new InventoryBackendTest.Host(); var settings = explicitSettings(100, 100);
		settings.setDisplayByteMultiplier(BigDecimal.valueOf(2));
		settings.getPreview().getClientByteMultiplier().setUnlimited(true); settings.getPreview().getGlobalByteMultiplier().setUnlimited(true);
		if (tracking) host.expiry = cancel && !revoke ? 3 : 80;
		host.projector = (display, cap) -> new nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.Display(
			display.itemId(), display.label(), "small", display.aggregateRequired());
		var reads = new AtomicInteger(); var validations = new AtomicInteger();
		var runtime = new InventoryRuntime((input, cap) -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
			@Override public int displayBytes() { return cap; }
			@Override public boolean valid() { validations.incrementAndGet(); return true; }
			@Override public InventoryDomainCodec.Item read(int slot) {
				reads.incrementAndGet(); return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), 7, "stone", "d".repeat(6000), false);
			}
		}), settings.pendingMemoryBytes(), host.projector, sink);
		try (var backend = new InventoryBackend(host, runtime, 1, sink)) {
			if (tracking) {
				helloOpenAndSelect(backend, host, settings);
				backend.handle(InventoryBackendTest.A, InventoryC2SPacket.close(host.epoch(InventoryBackendTest.A), 1).stamp(100, 1), 1, settings);
				backend.tick(1, settings);
				assertTrue(backend.tracksInventory(host.store.allMarkers().getFirst().id()));
			} else helloOpen(backend, host, settings);
			assertTrue(sink.events.isEmpty());
			settings.setDisplayByteMultiplier(new BigDecimal("0.25"));
			// HELLO drains the new OFFER but does not run a sampling tick or a
			// second reconfiguration pass after its handoff.
			backend.handle(InventoryBackendTest.A, InventoryC2SPacket.hello(), 2, settings);
			assertEquals(settings.effectiveDisplayBytes(), host.packets.stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.OFFER).reduce((a, b) -> b).orElseThrow().offer().effectiveDisplayBytes());
			var pressure = runtime.memory().tryReserve(runtime.memory().remaining()).orElseThrow();
			try {
				if (cancel && revoke) host.policies.put(InventoryBackendTest.A, new InventoryBackend.Policy(100, 2, Set.of()));
				int beforeReads = reads.get(), beforeChecks = validations.get();
				// Ingress-first advance must use the same fresh eligibility as tick.
				backend.handle(InventoryBackendTest.A, null, 3, settings); backend.tick(3, settings);
				Module module = tracking ? Module.INVENTORY_TRACKING : Module.INVENTORY_PREVIEW;
				if (cancel) {
					assertTrue(sink.events.isEmpty()); assertEquals(DelayState.IDLE, sink.lastState(module, tracking ? runtime.trackingPeriodId() : runtime.previewPeriodId()));
					assertEquals(beforeChecks, validations.get());
					if (tracking && !revoke) assertFalse(backend.tracksInventory(host.store.allMarkers().getFirst().id()));
					else if (tracking) {
						assertTrue(backend.tracksInventory(host.store.allMarkers().getFirst().id()));
						Object lease = ((Map<?, ?>) field(backend, "tracking")).values().iterator().next();
						assertTrue(((Map<?, ?>) field(lease, "recipients")).isEmpty());
					}
					else assertEquals(0, ((Map<?, ?>) field(((Map<?, ?>) field(backend, "sessions")).get(InventoryBackendTest.A), "previews")).size());
				} else {
					assertTrue(sink.events.stream().anyMatch(event -> event.module() == module && event.hasReason(Reason.MEMORY) && event.item().identity().endsWith("/projection")));
					assertEquals(DelayState.DELAYED, sink.lastState(module, tracking ? runtime.trackingPeriodId() : runtime.previewPeriodId()));
				}
				assertEquals(beforeReads, reads.get());
			} finally { pressure.close(); }
		}
	}
	@Test void revokedPreviewCapReprojectionProducesNoDelayWhileEligibleCounterpartDoes() {
		reconfigurationEligibilitySequence(false, true, true); reconfigurationEligibilitySequence(false, false, false);
	}
	@Test void expiredTrackingCapReprojectionProducesNoDelayWhileEligibleCounterpartDoes() {
		reconfigurationEligibilitySequence(true, true, false); reconfigurationEligibilitySequence(true, false, false);
	}
	@Test void revokedTrackingCapReprojectionProducesNoDelayAndRemovesRecipient() {
		reconfigurationEligibilitySequence(true, true, true);
	}
	private static Object field(Object owner, String name) {
		try { var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	@Test void heartbeatAdmissionCannotResolveCompletionStatusRefusedAtActualWireBoundary() {
		var sink = new RecordingSink(); var settings = explicitSettings(20, 20); settings.getTracking().setHeartbeatPeriods(0);
		var transport = new InventorySyncPublisherTest.Transport(); var memory = new RetainedMemoryLedger(16_000_000);
		try (var publisher = new InventorySyncPublisher(memory, transport, sink)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("tracking/1", 1); var marker = new MarkerId(1);
			assertTrue(publisher.register(context, InventorySyncPublisherTest.route(marker, "target")));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 1, 64)); publisher.drain();
			assertFalse(publisher.controlPending(context)); assertFalse(publisher.pending(context)); assertTrue(sink.events.isEmpty());
			settings.getTracking().setHeartbeatPeriods(1); publisher.advance(1, settings);
			var status = InventoryS2CPacket.status(7, 0, marker, 1, 1, 1, InventoryS2CPacket.Status.UNCERTAIN, 0).stamp(100, 1);
			var heartbeat = InventoryS2CPacket.heartbeat(7, 0, marker, 1, 1, 1, 0).stamp(100, 1);
			// Independent real writes establish the byte boundary; no copied
			// STATUS/HEARTBEAT size constants or synthetic measurement override.
			int heartbeatBytes = InventorySyncPublisherTest.bytes(heartbeat), statusBytes = InventorySyncPublisherTest.bytes(status);
			assertTrue(statusBytes > heartbeatBytes);
			@SuppressWarnings("unchecked") var streams = (Map<String, Long>) field(publisher, "streamBytes");
			streams.put(context.recipient() + "/target", settings.trackingStreamPeriodBytesPerClientTarget() - heartbeatBytes);
			publisher.status(context, InventoryS2CPacket.Status.UNCERTAIN); int before = transport.sent.size(); publisher.drain();
			assertEquals(List.of(InventoryS2CPacket.Kind.HEARTBEAT), transport.sent.subList(before, transport.sent.size()).stream().map(InventoryS2CPacket::kind).toList());
			assertTrue(publisher.controlPending(context)); assertTrue(publisher.delayed(Module.INVENTORY_TRACKING));
			assertEquals(1, firstObservationCount(sink, Module.INVENTORY_TRACKING, Reason.WIRE_CLIENT_OR_STREAM));
			var event = sink.events.getLast(); assertTrue(event.item().identity().endsWith("/control"));
			assertEquals(Unit.BYTE, event.quantity().unit()); assertEquals(statusBytes, event.quantity().amount());
			publisher.drain(); assertTrue(publisher.controlPending(context)); assertTrue(publisher.delayed(Module.INVENTORY_TRACKING));
			assertEquals(1, reasonEventCount(sink, Module.INVENTORY_TRACKING, Reason.WIRE_CLIENT_OR_STREAM));
			publisher.advance(20, settings); publisher.drain();
			assertFalse(publisher.controlPending(context)); assertFalse(publisher.delayed(Module.INVENTORY_TRACKING));
			assertEquals(1, transport.sent.subList(before, transport.sent.size()).stream().filter(packet -> packet.kind() == InventoryS2CPacket.Kind.STATUS).count());
		}
	}
	@Test void acceptedWatermarkInspectionFollowsRetirementRebaseAndReplacementContext() {
		var settings = explicitSettings(20, 20); var transport = new InventorySyncPublisherTest.Transport();
		try (var publisher = new InventorySyncPublisher(new RetainedMemoryLedger(16_000_000), transport)) {
			publisher.advance(0, settings); var context = InventorySyncPublisherTest.context("preview/1", 1);
			assertTrue(publisher.register(context, InventorySyncPublisherTest.route(null, "target")));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(1, 1, 64)); publisher.drain();
			assertTrue(publisher.accepted(context, 1)); assertFalse(publisher.accepted(context, 2));
			publisher.retireGroup(context, "minecraft:stone"); assertFalse(publisher.accepted(context, 1));
			publisher.rebase(context, context.stateFence()); assertFalse(publisher.accepted(context, 1));
			InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(2, 1, 64));
			assertTrue(publisher.accepted(context, 2)); publisher.cancel(context);
			var replacement = InventorySyncPublisherTest.context("preview/1", 2);
			assertTrue(publisher.register(replacement, InventorySyncPublisherTest.route(null, "target")));
			assertFalse(publisher.accepted(context, 2)); assertFalse(publisher.accepted(replacement, 2));
		}
	}
}
