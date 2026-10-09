package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySourceSnapshotTest {

	private static final Target.BlockTarget BLOCK = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	private static final InventorySourceInput INPUT = new InventorySourceInput(BLOCK, new UUID(1, 1), BlockFace.NORTH);

	private static InventoryDomainCodec.Item item(int index) {
		return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "k" + index), index + 1L, "stone", null, false);
	}

	private static List<InventoryDomainCodec.Item> items(int count) {
		var values = new ArrayList<InventoryDomainCodec.Item>(count);
		for (int index = 0; index < count; index++) values.add(item(index));
		return values;
	}

	private static CostLedger grant(int physicalSlots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) physicalSlots,
			InventorySourceAccess.PROBES, 8L, InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK));
	}

	private static InventorySourceAccess.InventoryHandle open(TestSource source, CostLedger ledger) {
		var access = new InventorySourceAccess(INPUT, i -> Optional.of(source));
		var scope = new SourceAccess.ReadScope(INPUT.viewKey(), Set.of("pingforit:inventory.items"));
		var target = INPUT.ordinaryTarget().orElseThrow();
		var resolved = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(
			target.dimensionId(), "block", target.blockRegistryId(), target.x(), target.y(), target.z(), ""), scope, ledger);
		return (InventorySourceAccess.InventoryHandle) ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, ledger)).handle();
	}

	private static CaptureResult step(SourceAccess.Handle handle, CostLedger ledger) {
		var outcome = handle.step(ledger);
		assertTrue(outcome instanceof SourceAccess.StepOutcome.Captured, "expected a captured outcome");
		return ((SourceAccess.StepOutcome.Captured) outcome).result();
	}

	private static Map<String, CaptureResult.OpaqueValue> fragment(CaptureResult result) {
		var payload = (CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow();
		assertEquals(InventoryDomainCodec.ID, payload.codecId());
		return payload.entries();
	}

	@Test
	void prepareCapturesOnceAndLaterPrepareReusesTheFrozenSnapshot() {
		var source = new TestSource();
		var snapshot = new TestSnapshot(items(2), 4096);
		source.plan = new TestPlan(8192, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(0));
		assertEquals(0, source.captures.get(), "capture stays lazy until preparation");
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.captures.get());
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.captures.get(), "a prepared route is never recaptured");
		assertEquals(1, source.plans.get(), "a fixed route is never replanned");
		assertEquals(4096, memory.retained());
		assertEquals(0, memory.reserved());
		assertTrue(handle.step(grant(0)) instanceof SourceAccess.StepOutcome.Deferred);
		assertEquals(0, snapshot.reads.get(), "a zero allowance performs no snapshot read");
		assertEquals(2, source.validationCalls.get(), "only the before-and-after capture checks validate; a non-empty zero-slot-budget defer does not");
		var result = step(handle, grant(2));
		assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
		assertEquals(2, snapshot.reads.get());
		assertEquals(0, source.liveReads.get(), "a prepared snapshot never reads the live source");
		handle.close();
		assertEquals(1, snapshot.closes.get());
		assertEquals(0, memory.retained());
		assertEquals(0, memory.reserved());
		assertEquals(1, source.closes.get());
	}

	@Test
	void zeroSlotSnapshotCompletesWithZeroScanAllowance() {
		var source = new TestSource();
		var snapshot = new TestSnapshot(List.of(), 0, InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED);
		source.plan = new TestPlan(0, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(0);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		var ledger = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 1L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.PROVIDER_CALL_WORK));
		var result = step(handle, ledger);
		assertEquals(CaptureResult.Availability.READABLE, result.availability());
		assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
		assertEquals(CaptureResult.Consistency.VERIFIED, result.consistency(), "an atomic empty capture is verified without a version");
		assertTrue(result.payload().isEmpty(), "a completed empty snapshot carries no payload");
		assertTrue(result.sourceVersion().isEmpty(), "a missing source version is never fabricated");
		assertEquals(0, ledger.used(InventorySourceAccess.PHYSICAL), "an empty snapshot is completed at zero scan cost");
		assertEquals(1, ledger.used(InventorySourceAccess.PROBES), "an empty snapshot still admits one validation probe");
		assertEquals(InventorySourceAccess.PROVIDER_CALL_WORK, ledger.used(InventorySourceAccess.PROVIDER_WORK), "empty validation has fixed provider work");
		assertEquals(3, source.validationCalls.get(), "capture validates before and after, then the empty step validates once");
		assertEquals(0, memory.retained());
		assertEquals(0, memory.reserved());
	}

	@Test
	void atomicSnapshotConsumesFrozenDataAcrossStepsAsVerified() {
		var source = new TestSource();
		source.live.addAll(items(54));
		var snapshot = new TestSnapshot(items(54), 8192);
		source.plan = new TestPlan(8192, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		for (int index = 0; index < source.live.size(); index++) source.live.set(index, item(1000 + index));
		var firstLedger = grant(32);
		var first = step(handle, firstLedger);
		assertEquals(CaptureResult.Completeness.CONTINUE, first.completeness());
		assertEquals(CaptureResult.Consistency.VERIFIED, first.consistency(), "atomic evidence survives a partial step");
		assertEquals(54L, first.coverage().expected().orElseThrow());
		assertEquals(32, fragment(first).size());
		assertEquals(32, firstLedger.used(InventorySourceAccess.PHYSICAL), "attempted snapshot reads keep the slot charge");
		var secondLedger = grant(32);
		var second = step(handle, secondLedger);
		assertEquals(CaptureResult.Completeness.COMPLETE, second.completeness());
		assertEquals(CaptureResult.Consistency.VERIFIED, second.consistency(), "atomic evidence survives later steps");
		assertEquals(22, fragment(second).size());
		assertEquals(22, secondLedger.used(InventorySourceAccess.PHYSICAL));
		assertEquals(54, snapshot.reads.get());
		assertEquals(0, source.liveReads.get(), "a frozen snapshot is consumed instead of the live route");
		for (int index = 0; index < 54; index++) {
			var entries = index < 32 ? fragment(first) : fragment(second);
			assertTrue(entries.containsKey(Integer.toString(index)));
			var decoded = InventoryDomainCodec.decode(entries.get(Integer.toString(index)));
			assertEquals(index + 1L, decoded.count(), "frozen count at slot " + index);
			assertEquals("k" + index, decoded.key().componentsKey());
		}
	}

	@Test
	void unknownSnapshotEvidenceStaysEventualAcrossSteps() {
		var source = new TestSource();
		source.version = OptionalLong.of(7);
		var snapshot = new TestSnapshot(items(2), 1024, InventorySourceAccess.SnapshotEvidence.UNKNOWN);
		source.plan = new TestPlan(2048, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		var first = step(handle, grant(1));
		assertEquals(CaptureResult.Completeness.CONTINUE, first.completeness());
		assertEquals(CaptureResult.Consistency.EVENTUAL, first.consistency(), "unknown snapshot evidence is never verified");
		assertEquals(Optional.of("7"), first.sourceVersion(), "a real version stays independent evidence");
		var second = step(handle, grant(1));
		assertEquals(CaptureResult.Completeness.COMPLETE, second.completeness());
		assertEquals(CaptureResult.Consistency.EVENTUAL, second.consistency(), "later steps never promote unknown evidence");
	}

	@Test
	void unsupportedSourceKeepsTheExistingLiveRoute() {
		var source = new TestSource();
		source.live.addAll(items(2));
		source.version = OptionalLong.of(11);
		var handle = open(source, grant(2));
		var memory = new RetainedMemoryLedger(16000);
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.plans.get(), "support is probed during preparation");
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.plans.get(), "the live fallback route is fixed");
		assertEquals(0, source.captures.get());
		var result = step(handle, grant(2));
		assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
		assertEquals(CaptureResult.Consistency.VERIFIED, result.consistency());
		assertEquals(Optional.of("11"), result.sourceVersion());
		assertEquals(2, source.liveReads.get());
		handle.close();
		assertEquals(1, source.closes.get());
	}

	@Test
	void planWithoutCapturedSnapshotFixesTheLiveFallback() {
		var source = new TestSource();
		source.live.addAll(items(1));
		source.plan = new TestPlan(1024, () -> { source.captures.incrementAndGet(); return Optional.empty(); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(1));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.captures.get());
		assertEquals(0, memory.retained());
		assertEquals(0, memory.reserved(), "an unsupported capture releases its reservation");
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.captures.get(), "the live fallback route is fixed");
		assertEquals(CaptureResult.Completeness.COMPLETE, step(handle, grant(1)).completeness());
		assertEquals(1, source.liveReads.get());
	}

	@Test
	void failingCaptureReleasesTheReservationAndReportsTerminalUnavailable() {
		var source = new TestSource();
		source.plan = new TestPlan(4096, () -> { source.captures.incrementAndGet(); throw new IllegalStateException("capture"); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.UNAVAILABLE, handle.prepareSnapshot(memory));
		assertEquals(0, memory.retained());
		assertEquals(0, memory.reserved());
		assertEquals(InventorySourceAccess.Preparation.UNAVAILABLE, handle.prepareSnapshot(memory), "a failed capture is not retried");
		assertEquals(1, source.captures.get());
		var result = step(handle, grant(0));
		assertEquals(CaptureResult.Availability.UNAVAILABLE, result.availability());
		assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness());
		assertTrue(result.payload().isEmpty(), "a failed capture is never an empty snapshot");
		assertEquals(0, source.liveReads.get(), "a failed snapshot never falls back to a live read");
		assertTrue(handle.step(grant(0)) instanceof SourceAccess.StepOutcome.Deferred, "a terminal result is delivered once");
	}

	@Test
	void overBoundSnapshotReleasesRetainedStateAndReportsIncomplete() {
		var retained = new TestSource();
		var priced = new TestSnapshot(items(2), 2048);
		retained.plan = new TestPlan(1024, () -> { retained.captures.incrementAndGet(); return Optional.of(priced); });
		var retainedMemory = new RetainedMemoryLedger(16000);
		var retainedHandle = open(retained, grant(0));
		assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, retainedHandle.prepareSnapshot(retainedMemory));
		assertEquals(1, priced.closes.get(), "an over-bound capture is released");
		assertEquals(0, retainedMemory.retained());
		assertEquals(0, retainedMemory.reserved());
		var retainedResult = step(retainedHandle, grant(0));
		assertEquals(CaptureResult.Availability.READABLE, retainedResult.availability());
		assertEquals(CaptureResult.Completeness.INCOMPLETE, retainedResult.completeness());
		assertTrue(retainedResult.payload().isEmpty(), "an over-bound capture publishes no partial data");
		assertEquals(0, retained.liveReads.get());

		var structural = new TestSource();
		var oversized = new TestSnapshot(items(InventorySourceAccess.MAX_SLOTS + 1), 1024);
		structural.plan = new TestPlan(8192, () -> { structural.captures.incrementAndGet(); return Optional.of(oversized); });
		var structuralMemory = new RetainedMemoryLedger(16000);
		var structuralHandle = open(structural, grant(0));
		assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, structuralHandle.prepareSnapshot(structuralMemory));
		assertEquals(1, oversized.closes.get(), "a structure-guard violation is released");
		assertEquals(0, structuralMemory.retained());
		assertEquals(0, structuralMemory.reserved());
		assertEquals(CaptureResult.Completeness.INCOMPLETE, step(structuralHandle, grant(0)).completeness());
	}

	@Test
	void negativeMemoryBoundIsIncompleteWithoutCapture() {
		var source = new TestSource();
		source.plan = new TestPlan(-1, () -> { source.captures.incrementAndGet(); return Optional.empty(); });
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, handle.prepareSnapshot(new RetainedMemoryLedger(16000)));
		assertEquals(0, source.captures.get(), "an illegal bound never reaches capture");
		assertEquals(CaptureResult.Completeness.INCOMPLETE, step(handle, grant(0)).completeness());
	}

	@Test
	void insufficientRetainedCapacityDefersWithoutCaptureAndRetriesLater() {
		var source = new TestSource();
		var snapshot = new TestSnapshot(items(1), 4096);
		source.plan = new TestPlan(4096, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(2048);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.DEFERRED, handle.prepareSnapshot(memory));
		assertEquals(0, source.captures.get(), "an unadmitted capture never runs");
		assertEquals(0, memory.reserved());
		assertTrue(handle.step(grant(4)) instanceof SourceAccess.StepOutcome.Deferred, "a pending capture never falls back to live reads");
		assertEquals(0, source.liveReads.get());
		memory.setCap(16000);
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(1, source.captures.get());
		assertEquals(4096, memory.retained());
		assertEquals(CaptureResult.Completeness.COMPLETE, step(handle, grant(1)).completeness());
	}

	@Test
	void invalidSourceIsRejectedBeforeAndAfterCapture() {
		var before = new TestSource();
		before.valid = false;
		before.plan = new TestPlan(1024, () -> { before.captures.incrementAndGet(); return Optional.empty(); });
		var beforeHandle = open(before, grant(1));
		assertEquals(InventorySourceAccess.Preparation.INVALID, beforeHandle.prepareSnapshot(new RetainedMemoryLedger(16000)));
		assertEquals(0, before.captures.get(), "an invalid source never captures");
		assertEquals(CaptureResult.Availability.INVALID, step(beforeHandle, grant(0)).availability());

		var after = new TestSource();
		var snapshot = new TestSnapshot(items(1), 1024);
		after.plan = new TestPlan(1024, () -> { after.captures.incrementAndGet(); after.valid = false; return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(16000);
		var afterHandle = open(after, grant(1));
		assertEquals(InventorySourceAccess.Preparation.INVALID, afterHandle.prepareSnapshot(memory));
		assertEquals(1, snapshot.closes.get(), "a source invalidated during capture releases the snapshot");
		assertEquals(0, memory.retained());
		assertEquals(0, memory.reserved());
		assertEquals(CaptureResult.Availability.INVALID, step(afterHandle, grant(0)).availability());
	}

	@Test
	void closeReleasesSnapshotTicketAndSourceEvenWhenCleanupThrows() {
		var source = new TestSource();
		source.throwOnClose = true;
		var snapshot = new TestSnapshot(items(1), 2048);
		snapshot.throwOnClose = true;
		source.plan = new TestPlan(4096, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertEquals(2048, memory.retained());
		handle.close();
		assertEquals(1, snapshot.closes.get());
		assertEquals(1, source.closes.get());
		assertEquals(0, memory.retained(), "a throwing snapshot close still releases its retained charge");
		assertEquals(0, memory.reserved());
		handle.close();
		assertEquals(1, snapshot.closes.get(), "close stays idempotent");
		assertEquals(1, source.closes.get());
	}

	@Test
	void liveStableVersionStillVerifiesAndADowngradeStaysEventual() {
		var source = new TestSource();
		source.live.addAll(items(2));
		source.version = OptionalLong.of(42);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(new RetainedMemoryLedger(16000)));
		var first = step(handle, grant(1));
		assertEquals(CaptureResult.Completeness.CONTINUE, first.completeness());
		assertEquals(CaptureResult.Consistency.VERIFIED, first.consistency());
		assertEquals(Optional.of("42"), first.sourceVersion());
		source.version = OptionalLong.of(43);
		var second = step(handle, grant(1));
		assertEquals(CaptureResult.Completeness.COMPLETE, second.completeness());
		assertEquals(CaptureResult.Consistency.EVENTUAL, second.consistency(), "a mid-sweep version change downgrades live verification");
		assertEquals(Optional.of("42"), second.sourceVersion());
	}

	@Test
	void snapshotStepFailurePublishesNoPartialDataAndReleasesRetainedState() {
		var source = new TestSource();
		var snapshot = new TestSnapshot(items(2), 2048) {
			@Override public InventoryDomainCodec.Item read(int index) {
				if (index == 1) throw new IllegalStateException("read");
				return super.read(index);
			}
		};
		source.plan = new TestPlan(4096, () -> { source.captures.incrementAndGet(); return Optional.of(snapshot); });
		var memory = new RetainedMemoryLedger(16000);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		var ledger = grant(2);
		var result = step(handle, ledger);
		assertEquals(CaptureResult.Availability.UNAVAILABLE, result.availability());
		assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness());
		assertTrue(result.payload().isEmpty(), "a page that failed mid-read is never published");
		assertEquals(2, ledger.used(InventorySourceAccess.PHYSICAL), "an attempted throwing snapshot read still charges");
		assertEquals(1, snapshot.closes.get(), "a terminal snapshot read failure releases the retained snapshot");
		assertEquals(0, memory.retained());
		assertEquals(0, memory.reserved());
	}

	@Test
	void nonEmptyZeroBudgetDoesNotValidateAndEmptySnapshotRequiresValidationAdmission() {
		var nonEmptySource = new TestSource();
		var nonEmpty = new TestSnapshot(items(1), 1024);
		nonEmptySource.plan = new TestPlan(1024, () -> Optional.of(nonEmpty));
		var nonEmptyHandle = open(nonEmptySource, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, nonEmptyHandle.prepareSnapshot(new RetainedMemoryLedger(4096)));
		int validationsBeforeStep = nonEmptySource.validationCalls.get();
		var noBudget = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 0L,
			InventorySourceAccess.PROVIDER_WORK, 0L));
		assertTrue(nonEmptyHandle.step(noBudget) instanceof SourceAccess.StepOutcome.Deferred);
		assertEquals(validationsBeforeStep, nonEmptySource.validationCalls.get(), "a non-empty zero-budget step defers before live validation");
		assertEquals(0, noBudget.used(InventorySourceAccess.PROBES));

		var emptySource = new TestSource();
		emptySource.plan = new TestPlan(0, () -> Optional.of(new TestSnapshot(List.of(), 0)));
		var emptyHandle = open(emptySource, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, emptyHandle.prepareSnapshot(new RetainedMemoryLedger(0)));
		var missingValidation = new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, 0L, InventorySourceAccess.PROBES, 0L,
			InventorySourceAccess.PROVIDER_WORK, 0L));
		assertTrue(emptyHandle.step(missingValidation) instanceof SourceAccess.StepOutcome.Deferred,
			"zero-slot completion still requires admission for its validation probe");
		assertEquals(2, emptySource.validationCalls.get(), "only the before-and-after capture checks validate; denied step admission does not");
	}

	@Test
	void fatalPrepareErrorsReleaseReservationAndCapturedSnapshotWithoutSwallowingError() {
		var captureSource = new TestSource();
		captureSource.plan = new TestPlan(4096, () -> { throw new AssertionError("fatal capture"); });
		var captureMemory = new RetainedMemoryLedger(4096);
		var captureHandle = open(captureSource, grant(0));
		assertThrows(AssertionError.class, () -> captureHandle.prepareSnapshot(captureMemory));
		assertEquals(0, captureMemory.reserved(), "fatal capture errors release the provisional ticket");
		assertEquals(0, captureMemory.retained());

		var metadataSource = new TestSource();
		var metadataSnapshot = new TestSnapshot(items(1), 512) {
			@Override public int slots() { throw new AssertionError("fatal slots"); }
		};
		metadataSnapshot.throwFatalOnClose = true;
		metadataSource.plan = new TestPlan(1024, () -> Optional.of(metadataSnapshot));
		var metadataMemory = new RetainedMemoryLedger(2048);
		var metadataHandle = open(metadataSource, grant(0));
		assertThrows(AssertionError.class, () -> metadataHandle.prepareSnapshot(metadataMemory));
		assertEquals(1, metadataSnapshot.closes.get(), "fatal metadata errors close the detached snapshot");
		assertEquals(0, metadataMemory.reserved());
		assertEquals(0, metadataMemory.retained());

		var retainedSource = new TestSource();
		var retainedSnapshot = new TestSnapshot(items(1), 512) {
			@Override public long retainedBytes() { throw new AssertionError("fatal retainedBytes"); }
		};
		retainedSource.plan = new TestPlan(1024, () -> Optional.of(retainedSnapshot));
		var retainedMemory = new RetainedMemoryLedger(2048);
		var retainedHandle = open(retainedSource, grant(0));
		assertThrows(AssertionError.class, () -> retainedHandle.prepareSnapshot(retainedMemory));
		assertEquals(1, retainedSnapshot.closes.get(), "fatal retained-cost errors close the detached snapshot");
		assertEquals(0, retainedMemory.reserved());
		assertEquals(0, retainedMemory.retained());
	}

	@Test
	void fatalSnapshotAndSourceCloseErrorsStillReleaseTicketAndBothResources() {
		var source = new TestSource();
		source.throwFatalOnClose = true;
		var snapshot = new TestSnapshot(items(1), 512);
		snapshot.throwFatalOnClose = true;
		source.plan = new TestPlan(1024, () -> Optional.of(snapshot));
		var memory = new RetainedMemoryLedger(2048);
		var handle = open(source, grant(0));
		assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
		assertThrows(AssertionError.class, handle::close);
		assertEquals(1, snapshot.closes.get());
		assertEquals(1, source.closes.get(), "a fatal snapshot close must not prevent source close");
		assertEquals(0, memory.retained(), "a fatal close still releases retained memory");
		assertEquals(0, memory.reserved());
	}

	static final class TestSource implements InventorySourceAccess.Source {
		final AtomicInteger liveReads = new AtomicInteger(), plans = new AtomicInteger(), captures = new AtomicInteger(), closes = new AtomicInteger(), validationCalls = new AtomicInteger();
		final List<InventoryDomainCodec.Item> live = new ArrayList<>();
		InventorySourceAccess.SnapshotPlan plan;
		boolean valid = true;
		boolean stableCursor = true;
		boolean throwOnClose;
		boolean throwFatalOnClose;
		OptionalLong version = OptionalLong.empty();

		@Override public SourceKey key() { return new SourceKey("test", "inventory", "same-container", INPUT.viewKey()); }
		@Override public boolean valid() { validationCalls.incrementAndGet(); return valid; }
		@Override public boolean stableCursor() { return stableCursor; }
		@Override public int slots() { return live.size(); }
		@Override public InventoryDomainCodec.Item read(int slot) { liveReads.incrementAndGet(); return live.get(slot); }
		@Override public OptionalLong version() { return version; }
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
			plans.incrementAndGet();
			return Optional.ofNullable(plan);
		}
		@Override public void close() {
			closes.incrementAndGet();
			if (throwFatalOnClose) throw new AssertionError("fatal source close");
			if (throwOnClose) throw new IllegalStateException("source close");
		}
	}

	static final class TestPlan implements InventorySourceAccess.SnapshotPlan {
		private final long upperBound;
		private final Supplier<Optional<InventorySourceAccess.InventorySnapshot>> capture;
		TestPlan(long upperBound, Supplier<Optional<InventorySourceAccess.InventorySnapshot>> capture) {
			this.upperBound = upperBound; this.capture = capture;
		}
		@Override public long memoryUpperBoundBytes() { return upperBound; }
		@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { return capture.get(); }
	}

	static class TestSnapshot implements InventorySourceAccess.InventorySnapshot {
		private final List<InventoryDomainCodec.Item> slots;
		private final long retained;
		private final InventorySourceAccess.SnapshotEvidence evidence;
		final AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
		boolean throwOnClose;
		boolean throwFatalOnClose;
		TestSnapshot(List<InventoryDomainCodec.Item> slots, long retained, InventorySourceAccess.SnapshotEvidence evidence) {
			this.slots = slots; this.retained = retained; this.evidence = evidence;
		}
		TestSnapshot(List<InventoryDomainCodec.Item> slots, long retained) {
			this(slots, retained, InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED);
		}
		@Override public int slots() { return slots.size(); }
		@Override public InventoryDomainCodec.Item read(int index) { reads.incrementAndGet(); return slots.get(index); }
		@Override public long retainedBytes() { return retained; }
		@Override public InventorySourceAccess.SnapshotEvidence evidence() { return evidence; }
		@Override public void close() {
			closes.incrementAndGet();
			if (throwFatalOnClose) throw new AssertionError("fatal snapshot close");
			if (throwOnClose) throw new IllegalStateException("snapshot close");
		}
	}
}
