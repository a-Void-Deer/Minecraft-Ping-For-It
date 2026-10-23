package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import nx.pingwheel.common.presentation.source.CaptureResult;
import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Level;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRuntimeTest {
	static final Target.BlockTarget TARGET = new Target.BlockTarget("minecraft:overworld", 1, 2, 3, "minecraft:chest");
	static InventorySourceInput input(UUID owner) { return new InventorySourceInput(TARGET, owner, BlockFace.NORTH); }
	static InventoryDomainCodec.Item item(long count) { return new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "plain"), count, "stone", null, false); }
	static abstract class Snapshot implements InventorySourceAccess.InventorySnapshot {
		@Override public int displayBytes() { return InventoryItemCodec.MAX_DISPLAY_BYTES; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
	}
	static class Source implements InventorySourceAccess.Source {
		final InventorySourceInput input; final AtomicInteger reads; final int size;
		final AtomicInteger validCalls = new AtomicInteger();
		boolean stable = true, valid = true;
		Source(InventorySourceInput input, AtomicInteger reads, int size) { this.input = input; this.reads = reads; this.size = size; }
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "same-container", input.viewKey()); }
		@Override public boolean valid() { validCalls.incrementAndGet(); return valid; }
		@Override public boolean stableCursor() { return stable; }
		@Override public int slots() { return size; }
		@Override public int displayBytes() { return InventoryItemCodec.MAX_DISPLAY_BYTES; }
		// Synthetic read fixtures retain no source-owned catalogue or parser inputs.
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slot == 0 ? item(7) : null; }
		@Override public InventorySourceAccess.Page enumerate(int limit) {
			var values = new java.util.ArrayList<InventoryDomainCodec.Item>();
			for (int i = 0; i < Math.min(limit, size); i++) values.add(read(i));
			return new InventorySourceAccess.Page(values, size <= limit);
		}
	}
	/** Explicit independent costs: allocation is cumulative, workspace is only peak. */
	static class DiscoverySource extends Source {
		InventoryValidationBudget.Cost cost = new InventoryValidationBudget.Cost(1_000_000_000L, 2048, 30);
		final InventoryValidationBudget.Cost discoveryStep = new InventoryValidationBudget.Cost(1000, 1024, 7);
		final InventoryValidationBudget.Cost validStep = new InventoryValidationBudget.Cost(500, 512, 5);
		final InventoryValidationBudget.Cost captureCost = new InventoryValidationBudget.Cost(2000, 1536, 11);
		final List<InventoryValidationBudget.Allowance> discoveryGrants = new java.util.ArrayList<>();
		InventoryValidationBudget.Allowance captureGrant;
		boolean throwDiscovery, throwCapture;
		int captures, attempted;
		DiscoverySource(InventorySourceInput input) { super(input, new AtomicInteger(), 1); }
		@Override public InventoryValidationBudget.Cost discoveryValidationCost() { return cost; }
		@Override public boolean valid() { throw new AssertionError("discovery allowance was dropped"); }
		@Override public boolean valid(InventoryValidationBudget.Allowance allowance) {
			allowance.debit(validStep); discoveryGrants.add(allowance); return true;
		}
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { throw new AssertionError("discovery allowance was dropped"); }
		@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) {
			allowance.debit(discoveryStep); discoveryGrants.add(allowance);
			if (throwDiscovery) throw new IllegalStateException("after paid discovery prefix");
			return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return 4096; }
				@Override public InventoryValidationBudget.Cost validationCost() { return captureCost; }
				@Override public long validationAllocationUpperBoundBytes() { return captureCost.allocationBytes(); }
				@Override public int physicalReadUpperBound() { return 1; }
				@Override public int attemptedPhysicalReads() { return attempted; }
				@Override public long itemWorkUpperBoundUnits() { return 11; } // already includes capture validation
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { throw new AssertionError("capture allowance was dropped"); }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture(InventoryValidationBudget.Allowance grant) {
					grant.debit(captureCost); captureGrant = grant; captures++; attempted++;
					if (throwCapture) throw new IllegalStateException("after attempted physical read");
					return Optional.of(new Snapshot() {
						@Override public int slots() { return 1; }
						@Override public InventoryDomainCodec.Item read(int slot) { return item(7); }
						@Override public long retainedBytes() { return 128; }
					});
				}
			});
		}
	}
	static CostLedger itemLedger(InventoryRuntime runtime) throws Exception { return (CostLedger) InventoryBackendTest.field(runtime, "physical"); }
	static CostLedger.Counter itemWorkCounter() { return new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK); }
	static long authorityScratch(InventoryRuntime runtime) throws Exception {
		var method = InventoryRuntime.class.getDeclaredMethod("authorityWorkspaceBytes"); method.setAccessible(true); return (long) method.invoke(runtime);
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void discoveryRefusalHasNoValidationOrContentAndRetriesSameRound(boolean memoryRefusal) throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(41, 1); var source = new DiscoverySource(input(owner));
		var events = new java.util.ArrayList<nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent>();
		var sink = new nx.pingwheel.common.presentation.budget.BudgetDelaySink() {
			@Override public void observe(DelayEvent event) { events.add(event); }
			@Override public void state(Module module, long cycle, long now, DelayState state) {}
			@Override public void endCycle(Module module, long cycle, long now) {}
		};
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(source), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), sink)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var ledger = itemLedger(runtime); assertTrue(runtime.claimDetachedItemWork(ledger.remaining(itemWorkCounter())));
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			Object round = InventoryBackendTest.field(consumer, "round"); assertNotNull(round);
			if (memoryRefusal) {
				runtime.advance(settings.getPreview().getPeriodTicks(), settings); events.clear();
				long fixed = authorityScratch(runtime);
				source.cost = new InventoryValidationBudget.Cost(1_000_000_000L, fixed + 2048, 30);
				runtime.memory().setCap(runtime.memory().retained() + fixed + 1023);
				assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			}
			assertTrue(source.discoveryGrants.isEmpty()); assertEquals(0, source.captures); assertEquals(0, source.reads.get());
			assertSame(round, InventoryBackendTest.field(consumer, "round")); assertEquals(0, InventoryBackendTest.field(consumer, "cursor"));
			assertEquals(0, runtime.memory().reserved()); assertFalse(consumer.invalidated());
			assertEquals(1, events.size()); assertFalse(events.getFirst().quantity().known());
			assertEquals((memoryRefusal ? nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason.MEMORY
				: nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason.PUBLICATION_WORK).bit(), events.getFirst().newReasonBits());
			runtime.advance(2L * settings.getPreview().getPeriodTicks(), settings);
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertSame(round, InventoryBackendTest.field(consumer, "round")); assertEquals(0, InventoryBackendTest.field(consumer, "cursor"));
			assertEquals(3, source.discoveryGrants.size());
			for (var grant : source.discoveryGrants) assertSame(source.discoveryGrants.getFirst(), grant);
			assertNotSame(source.discoveryGrants.getFirst(), source.captureGrant);
			assertEquals(17, source.discoveryGrants.getFirst().attemptedWorkUnits());
			assertEquals(28, itemLedger(runtime).used(itemWorkCounter()), "17 discovery + 11 capture, without a second capture-validation debit");
			assertEquals(1, source.captures); assertEquals(0, source.reads.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void snapshotMemoryDeferSettlesDiscoveryPrefixAndRetryPaysOnlyNewAttempts() throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(41, 3); var source = new DiscoverySource(input(owner));
		try (var runtime = new InventoryRuntime(i -> Optional.of(source), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertTrue(runtime.claimDetachedItemWork(itemLedger(runtime).remaining(itemWorkCounter())));
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer)); // retain the opened round
			runtime.advance(1, settings);
			runtime.memory().setCap(runtime.memory().retained() + authorityScratch(runtime) + 512);
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			assertEquals(12, itemLedger(runtime).used(itemWorkCounter())); assertEquals(0, source.captures); assertEquals(0, runtime.memory().reserved());
			assertSame(source.discoveryGrants.get(0), source.discoveryGrants.get(1));
			runtime.memory().setCap(settings.pendingMemoryBytes());
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(33, itemLedger(runtime).used(itemWorkCounter()), "paid 12 + retry's two validity calls 10 + capture 11");
			assertEquals(4, source.discoveryGrants.size()); assertSame(source.discoveryGrants.get(2), source.discoveryGrants.get(3));
			assertNotSame(source.discoveryGrants.get(0), source.discoveryGrants.get(2));
			assertEquals(1, source.captures); assertEquals(0, runtime.memory().reserved());
		}
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void throwingDiscoveryOrCaptureKeepsAttemptedPrefixAndReleasesWorkspace(boolean captureFailure) throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(41, 2); var source = new DiscoverySource(input(owner));
		source.throwDiscovery = !captureFailure; source.throwCapture = captureFailure;
		try (var runtime = new InventoryRuntime(i -> Optional.of(source), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.UNAVAILABLE, runtime.prepare(consumer));
			var ledger = itemLedger(runtime);
			assertEquals(captureFailure ? 23 : 7, ledger.used(itemWorkCounter()), "paid prefix survives failure; unused validation work is released");
			var reads = new CostLedger.Counter("inventory/capture_reads", CostLedger.Unit.WORK);
			assertEquals(captureFailure ? 1 : 0, ledger.used(reads));
			assertEquals(captureFailure ? 1 : 0, source.captures); assertEquals(0, source.reads.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void independentPlanDiscoveryReportsOriginalCastAndConsumerRoundWithoutReadingOrLeaking() throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(41, 50);
		var failure = new ClassCastException("runtime independent plan cannot cast native source");
		var closes = new AtomicInteger();
		var source = new DiscoverySource(input(owner)) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) {
				allowance.debit(discoveryStep); throw failure;
			}
			@Override public void close() { closes.incrementAndGet(); }
		};
		try (var logs = new InventoryCaptureDiagnosticsTest.Capture();
			var runtime = new InventoryRuntime(i -> Optional.of(source), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(source.input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.UNAVAILABLE, runtime.prepare(consumer));
			assertEquals(7, itemLedger(runtime).used(itemWorkCounter()), "throwing discovery's admitted prefix remains paid");
			assertEquals(0, source.captures); assertEquals(0, source.reads.get()); assertEquals(0, runtime.memory().reserved());
			logs.report("runtime/snapshot-prepare", Level.WARN, failure.getMessage(), "java.lang.ClassCastException", "at " + getClass().getName() + ".independentPlanDiscoveryReportsOriginalCast",
				"consumer=1", "round=1", "subject=PreviewSubject[client=" + owner, "position=1,2,3", "source=SourceKey[providerId=test");
			consumer.close(); assertEquals(1, closes.get());
			assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void detachedWorkRefusesBeforeAttemptAndPaysThrowingAttemptsWithoutRootMemoryOrProbes() throws Exception {
		var calls = new AtomicInteger(); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> { throw new AssertionError("no resolver"); }, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); runtime.memory().setCap(0);
			var physicalField = InventoryRuntime.class.getDeclaredField("physical"); physicalField.setAccessible(true);
			var ledger = (nx.pingwheel.common.presentation.source.CostLedger) physicalField.get(runtime);
			var counter = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			long limit = ledger.limit(counter);
			assertTrue(runtime.claimDetachedItemWork(limit - 8192L));
			assertThrows(IllegalStateException.class, () -> {
				assertTrue(runtime.claimDetachedItemWork(8192L)); calls.incrementAndGet(); throw new IllegalStateException("attempted");
			});
			assertEquals(limit, ledger.used(counter));
			if (runtime.claimDetachedItemWork(8192L)) calls.incrementAndGet();
			assertEquals(1, calls.get()); assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
			assertEquals(0, ledger.used(InventorySourceAccess.PROBES));
			runtime.advance(1, settings);
			assertTrue(runtime.claimDetachedItemWork(8192L)); calls.incrementAndGet();
			assertEquals(2, calls.get());
		}
	}
	@Test void rootWorkspaceRefusalPrecedesResolverLockWitnessAndContent() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(40, 1);
		var resolves = new AtomicInteger(); var locks = new AtomicInteger(); var reads = new AtomicInteger(); var witnesses = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			resolves.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { locks.incrementAndGet(); return true; }
				@Override public boolean matchesSelection(int slot, InventorySelection selection) { witnesses.incrementAndGet(); return true; }
			});
		}, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			long available = InventoryItemBudget.workspaceUpperBound((int) settings.effectiveDisplayBytes()) - 1;
			runtime.memory().setCap(runtime.memory().retained() + available);
			assertTrue(runtime.preflight(() -> { locks.incrementAndGet(); return Optional.of(true); }).isEmpty());
			assertTrue(runtime.probe(input(owner)).isEmpty());
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			assertFalse(runtime.selectionPresent(input(owner), new InventorySelection(item(1).key(), "minecraft:stone", "stone", null, false, "danger"), 0));
			assertEquals(0, resolves.get()); assertEquals(0, locks.get()); assertEquals(0, witnesses.get()); assertEquals(0, reads.get());
			runtime.memory().setCap(settings.pendingMemoryBytes());
			assertEquals(List.of(item(7)), runtime.step(consumer, 1).orElseThrow().slots());
			assertEquals(1, reads.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void refusedLiveRootRetriesTheSameCursorAfterRoomReturns() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(40, 2); var visited = new java.util.ArrayList<Integer>();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, new AtomicInteger(), 2) {
			@Override public InventoryDomainCodec.Item read(int slot) { visited.add(slot); return item(slot + 1); }
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			runtime.memory().setCap(runtime.memory().retained() + InventoryItemBudget.workspaceUpperBound(4096) - 1);
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertTrue(visited.isEmpty());
			runtime.memory().setCap(settings.pendingMemoryBytes());
			assertEquals(1, runtime.step(consumer, 1).orElseThrow().slots().getFirst().count());
			assertEquals(List.of(0), visited);
			assertEquals(2, runtime.step(consumer, 1).orElseThrow().slots().getFirst().count());
			assertEquals(List.of(0, 1), visited);
		}
	}
	@Test void selectionDelegatesMappedWitnessAndAggregateSkipsUnsafeComponentRead() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(40, 3); var unsafeCodec = new AtomicInteger(); var mapped = new java.util.ArrayList<Integer>();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, new AtomicInteger(), 3) {
			@Override public InventoryDomainCodec.Item read(int slot) { unsafeCodec.incrementAndGet(); throw new IllegalStateException("unsafe components"); }
			@Override public boolean matchesSelection(int slot, InventorySelection selection) {
				mapped.add(slot);
				if (selection.aggregate()) return slot == 2 && selection.itemId().equals("minecraft:stone"); // authoritative ID and positive long only
				return read(slot) != null;
			}
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var aggregate = new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger");
			var exact = new InventorySelection(item(1).key(), "minecraft:stone", "stone", null, false, "danger");
			assertTrue(runtime.selectionPresent(input(owner), aggregate, 2)); assertEquals(0, unsafeCodec.get());
			assertFalse(runtime.selectionPresent(input(owner), exact, 2)); assertEquals(1, unsafeCodec.get());
			assertEquals(List.of(2, 2), mapped);
		}
	}
	@Test void cursorlessWitnessChargesBlankVisitsAndThrowingAttempts() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2)); var owner = new UUID(40, 4); var calls = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, new AtomicInteger(), 3) {
			@Override public boolean stableCursor() { return false; }
			@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection) {
				calls.incrementAndGet(); assertEquals(2, limit); throw new IllegalStateException("after blank and failed view");
			}
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var aggregate = new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger");
			assertFalse(runtime.selectionPresent(input(owner), aggregate, 0));
			assertFalse(runtime.selectionPresent(input(owner), aggregate, 0)); assertEquals(1, calls.get(), "failed enumeration cannot refund its attempted grant");
			runtime.advance(1, settings); assertFalse(runtime.selectionPresent(input(owner), aggregate, 0)); assertEquals(2, calls.get());
		}
	}
	@Test void captureFailureKeepsAttemptedFixedWorkWithoutSpendingDecodeSlots() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(40, 5); var captures = new AtomicInteger(); var liveReads = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, liveReads, 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					int attempted;
					@Override public long memoryUpperBoundBytes() { return 1024; }
					@Override public int physicalReadUpperBound() { return InventorySourceAccess.MAX_SLOTS; }
					@Override public long itemWorkUpperBoundUnits() { return Math.multiplyExact(InventorySourceAccess.MAX_SLOTS, InventoryItemBudget.workUpperBound(4096)); }
					@Override public int attemptedPhysicalReads() { return attempted; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { captures.incrementAndGet(); attempted = InventorySourceAccess.MAX_SLOTS; throw new nx.pingwheel.common.presentation.inventory.minecraft.InventoryObservationSnapshot.LimitException("capture rollback"); }
				});
			}
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var first = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var failed = runtime.step(first).orElseThrow();
			assertEquals(CaptureResult.Availability.READABLE, failed.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, failed.result().completeness()); assertTrue(failed.slots().isEmpty());
			var next = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(next)); assertEquals(1, captures.get()); assertEquals(0, liveReads.get());
			runtime.advance(1, settings); assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, runtime.prepare(next)); assertEquals(2, captures.get());
		}
	}

	@Test void snapshotDiscoveryCaptureLimitIsIncompleteAndReportsItsOriginalBound() throws ReflectiveOperationException {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(41, 40); var reads = new AtomicInteger();
		// Isolate the existing domain gate, whose lifetime spans runtime fixtures.
		var field = InventoryCaptureDiagnostics.class.getDeclaredField("LAST_LOGGED"); field.setAccessible(true);
		var gate = (java.util.concurrent.atomic.AtomicLongArray) field.get(null);
		long[] previous = new long[gate.length()];
		for (int index = 0; index < gate.length(); index++) previous[index] = gate.getAndSet(index, 0);
		try (var logs = new InventoryDiagnosticsTestCapture(); var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan(InventoryValidationBudget.Allowance allowance) {
				var empty = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(0, 0, 0));
				empty.debit(new InventoryValidationBudget.Cost(0, 0, 1));
				throw new AssertionError("expected refusal");
			}
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, runtime.prepare(consumer));
			assertEquals(0, reads.get()); assertEquals(0, runtime.memory().reserved());
			assertTrue(logs.messages().contains("detail=snapshot-discovery"), logs.messages());
			assertTrue(logs.messages().contains("inventory validation"), logs.messages());
			assertNull(logs.events().getFirst().getThrown());
		} finally {
			for (int index = 0; index < gate.length(); index++) gate.set(index, previous[index]);
		}
	}
	@Test void ordinaryDefaultAdmissionIsStructurallyServiceableWithOneRootScratch() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(40, 6); var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			assertEquals(1, reads.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void oldInputParserGrantPrecedesSourceTighteningEvenWhenNewOutputWouldFit() {
		var settings = InventorySettings.serverDefaults(); settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8));
		var owner = new UUID(40, 7); var oldJson = "x".repeat(30_000); var sourceTightens = new AtomicInteger(); var parsers = new AtomicInteger(); var summaries = new AtomicInteger();
		var sourceSummary = new InventoryReprojectionSummary.Builder().entries(1).input(oldJson).build();
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(new Source(i, new AtomicInteger(), 1) {
			@Override public int displayBytes() { return cap; }
			@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) {
				summaries.incrementAndGet(); return sourceSummary;
			}
			@Override public InventoryDomainCodec.Item read(int slot) { return new InventoryDomainCodec.Item(item(1).key(), 9007199254740993L, "stone", oldJson, false); }
			@Override public void tightenDisplay(int ceiling) {
				sourceTightens.incrementAndGet();
				InventoryItemCodec.reproject(new InventoryItemCodec.Display("minecraft:stone", "stone", oldJson, false), net.minecraft.core.RegistryAccess.EMPTY, ceiling);
			}
		}), settings.pendingMemoryBytes(), (retained, cap) -> { parsers.incrementAndGet(); return new InventoryItemCodec.Display(retained.itemId(), retained.label(), "{}", retained.aggregateRequired()); })) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(consumer).orElseThrow();
			// The metadata query fits; its held grant leaves ample tiny-output room,
			// but not the independently required OLD-input parser workspace.
			runtime.memory().setCap(runtime.memory().retained() + InventoryDisplayProjection.METADATA_BYTES + 100_000);
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(1, summaries.get()); assertEquals(0, sourceTightens.get()); assertEquals(0, parsers.get());
			runtime.memory().setCap(settings.pendingMemoryBytes());
			var projected = runtime.reprojectDisplays(consumer, 1024);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, projected.status()); assertTrue(projected.foldIds().isEmpty());
			assertEquals(1, parsers.get()); assertEquals(1, sourceTightens.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void duplicateDetachedParserCallsAreBothPaidAndWorkRefusalRetriesBeforeCallingEither() throws Exception {
		var settings = InventorySettings.serverDefaults(); settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(24));
		settings.setFrameByteMultiplier(java.math.BigDecimal.valueOf(4)); settings.validate();
		String json = "{\"id\":\"minecraft:stone\"}";
		var summary = new InventoryReprojectionSummary.Builder().entries(2).input(json).input(json).build();
		var owner = new UUID(40, 9); var reads = new AtomicInteger(); var parses = new AtomicInteger(); var sourceTightens = new AtomicInteger();
		InventoryRuntime.DisplayReprojector projector = (retained, cap) -> { parses.incrementAndGet(); return retained; };
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(new Source(i, reads, 1) {
			int ceiling = cap;
			@Override public int displayBytes() { return ceiling; }
			@Override public InventoryReprojectionSummary reprojectionSummary(int next) { return next < ceiling ? summary : InventoryReprojectionSummary.EMPTY; }
			@Override public void tightenDisplay(int next) {
				if (next >= ceiling) return;
				sourceTightens.incrementAndGet();
				for (int copy = 0; copy < 2; copy++) projector.reproject(new InventoryItemCodec.Display("minecraft:stone", "stone", json, false), next);
				ceiling = next;
			}
		}), settings.pendingMemoryBytes(), projector)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer).orElseThrow();
			Object cursor = InventoryBackendTest.field(consumer, "cursor");
			var ledger = (CostLedger) InventoryBackendTest.field(runtime, "physical");
			var counter = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
			// Admit the fixed summary query, then refuse the actual parser operation.
			assertTrue(runtime.claimDetachedItemWork(ledger.remaining(counter) - InventoryDisplayProjection.QUERY_WORK));
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(0, sourceTightens.get()); assertEquals(0, parses.get()); assertEquals(1, reads.get());
			assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor")); assertEquals(0, runtime.memory().reserved());
			settings.setDisplayByteMultiplier(new java.math.BigDecimal("0.25")); runtime.advance(settings.getPreview().getPeriodTicks(), settings);
			ledger = (CostLedger) InventoryBackendTest.field(runtime, "physical");
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(2, parses.get()); assertEquals(1, sourceTightens.get()); assertEquals(1, reads.get());
			int bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
			long independent = InventoryDisplayProjection.QUERY_WORK + InventoryReprojectionSummary.FIXED_WORK + 2 * InventoryReprojectionSummary.ENTRY_WORK
				+ 2 * (InventoryItemBudget.reprojectionWorkUpperBound(bytes) + 64L * bytes);
			assertEquals(independent, ledger.used(counter), "equal strings still caused two parser calls, while no capacity or OLD ceiling was multiplied in");
			assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor")); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void unknownDetachedSummaryIsUnsupportedNotAnUnadmittedParserOrBudgetDelay() throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(40, 10); var calls = new AtomicInteger();
		var events = new java.util.ArrayList<nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent>();
		var sink = new nx.pingwheel.common.presentation.budget.BudgetDelaySink() {
			@Override public void observe(DelayEvent event) { events.add(event); }
			@Override public void state(Module module, long cycle, long now, DelayState state) {}
			@Override public void endCycle(Module module, long cycle, long now) {}
		};
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(new Source(i, new AtomicInteger(), 1) {
			@Override public InventoryReprojectionSummary reprojectionSummary(int next) { return InventoryReprojectionSummary.UNKNOWN; }
			@Override public void tightenDisplay(int next) { calls.incrementAndGet(); }
		}), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), sink)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer).orElseThrow();
			Object round = InventoryBackendTest.field(consumer, "round"), cursor = InventoryBackendTest.field(consumer, "cursor");
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertSame(round, InventoryBackendTest.field(consumer, "round")); assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor"));
			assertEquals(CaptureResult.Completeness.COMPLETE, ((CaptureResult) InventoryBackendTest.field(round, "last")).completeness());
			assertFalse(consumer.invalidated());
			assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(0, calls.get()); assertTrue(events.isEmpty()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void inheritedSourceAndSnapshotNoOpsKeepTheSameObservationWithoutProviderOrParserCalls(boolean detached) throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(42, 1); var input = input(owner);
		var provider = new AtomicInteger(); var captures = new AtomicInteger(); var reads = new AtomicInteger(); var parses = new AtomicInteger();
		var exact = new InventoryDomainCodec.Item(item(1).key(), 9007199254740993L, "stone", null, false);
		var snapshot = new InventorySourceAccess.InventorySnapshot() {
			@Override public int slots() { provider.incrementAndGet(); return 2; }
			@Override public long retainedBytes() { provider.incrementAndGet(); return 512; }
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return exact; }
		};
		var source = new InventorySourceAccess.Source() {
			@Override public SourceKey key() { provider.incrementAndGet(); return new SourceKey("test", "inventory", "legacy-noop", input.viewKey()); }
			@Override public boolean valid() { provider.incrementAndGet(); return true; }
			@Override public boolean stableCursor() { provider.incrementAndGet(); return true; }
			@Override public int slots() { provider.incrementAndGet(); return 2; }
			@Override public OptionalLong version() { provider.incrementAndGet(); return OptionalLong.empty(); }
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return exact; }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				provider.incrementAndGet();
				return detached ? Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 512; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { captures.incrementAndGet(); return Optional.of(snapshot); }
				}) : Optional.empty();
			}
		};
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(source), settings.pendingMemoryBytes(),
			(retained, cap) -> { parses.incrementAndGet(); throw new AssertionError("no retained display parser input"); })) {
			runtime.advance(0, settings); var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(exact, runtime.step(consumer, 1).orElseThrow().slots().getFirst());
			Object round = InventoryBackendTest.field(consumer, "round"), cursor = InventoryBackendTest.field(consumer, "cursor");
			int beforeProvider = provider.get(), beforeReads = reads.get(); long retained = runtime.memory().retained();
			settings.setDisplayByteMultiplier(new java.math.BigDecimal("0.25")); settings.validate(); runtime.advance(1, settings);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertSame(round, InventoryBackendTest.field(consumer, "round")); assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor"));
			assertEquals(beforeProvider, provider.get()); assertEquals(beforeReads, reads.get()); assertEquals(0, parses.get());
			assertEquals(retained, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
			assertEquals(detached ? 1 : 0, captures.get());
			var tail = runtime.step(consumer, 1).orElseThrow(); assertEquals(exact, tail.slots().getFirst());
			assertEquals(CaptureResult.Completeness.COMPLETE, tail.result().completeness()); assertEquals(2, tail.result().coverage().scanned());
			assertEquals(detached ? 1 : 0, captures.get()); assertFalse(consumer.invalidated());
		}
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void summaryQueryHasItsFixedMetadataGrantBeforeAnyExtensionDispatch(boolean memoryRefusal) throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(42, 2); var queries = new AtomicInteger();
		InventoryRuntime[] active = new InventoryRuntime[1];
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(new Source(i, new AtomicInteger(), 1) {
			@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) {
				queries.incrementAndGet(); assertTrue(active[0].memory().reserved() >= InventoryDisplayProjection.METADATA_BYTES);
				return InventoryReprojectionSummary.EMPTY;
			}
		}), settings.pendingMemoryBytes())) {
			active[0] = runtime; runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(consumer).orElseThrow();
			if (memoryRefusal) runtime.memory().setCap(runtime.memory().retained() + InventoryDisplayProjection.METADATA_BYTES - 1);
			else assertTrue(runtime.claimDetachedItemWork(itemLedger(runtime).remaining(itemWorkCounter()) - InventoryDisplayProjection.QUERY_WORK + 1));
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(0, queries.get()); assertEquals(0, runtime.memory().reserved());
			runtime.advance(settings.getPreview().getPeriodTicks(), settings);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(1, queries.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void failedProjectionMetadataOrAdmittedExtensionBecomesIncompleteWithoutTickFailure(boolean failMetadata) throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(42, 5); var reads = new AtomicInteger(); var calls = new AtomicInteger();
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(new Source(i, reads, 2) {
			@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) {
				if (failMetadata) throw new UnsupportedOperationException("no detached cost support");
				return InventoryReprojectionSummary.EMPTY;
			}
			@Override public void tightenDisplay(int ceiling) { calls.incrementAndGet(); throw new IllegalStateException("extension unavailable"); }
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(consumer, 1).orElseThrow(); Object cursor = InventoryBackendTest.field(consumer, "cursor");
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(failMetadata ? 0 : 1, calls.get()); assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor"));
			assertEquals(CaptureResult.Completeness.INCOMPLETE, runtime.step(consumer, 1).orElseThrow().result().completeness());
			assertEquals(1, reads.get()); assertEquals(0, runtime.memory().reserved()); assertFalse(consumer.invalidated());
		}
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void opaqueDefaultSummaryStopsOnlyUnreadContinuationAndStillTightensDecodedPages(boolean snapshotOverride) throws Exception {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(42, 3); var input = input(owner);
		var reads = new AtomicInteger(); var tightens = new AtomicInteger(); var parses = new AtomicInteger();
		var events = new java.util.ArrayList<nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent>();
		var sink = new nx.pingwheel.common.presentation.budget.BudgetDelaySink() {
			@Override public void observe(DelayEvent event) { events.add(event); }
			@Override public void state(Module module, long cycle, long now, DelayState state) {}
			@Override public void endCycle(Module module, long cycle, long now) {}
		};
		var exact = new InventoryDomainCodec.Item(item(1).key(), 9007199254740993L, "stone", "x".repeat(2048), false);
		var snapshot = new InventorySourceAccess.InventorySnapshot() {
			@Override public int slots() { return 2; }
			@Override public long retainedBytes() { return 8192; }
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return exact; }
			@Override public void tightenDisplay(int cap) { tightens.incrementAndGet(); throw new AssertionError("unadmitted snapshot parser"); }
		};
		var source = new InventorySourceAccess.Source() {
			@Override public SourceKey key() { return new SourceKey("test", "inventory", "opaque", input.viewKey()); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 2; }
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return exact; }
			@Override public InventoryReprojectionSummary reprojectionSummary(int cap) {
				return snapshotOverride ? InventoryReprojectionSummary.EMPTY : InventorySourceAccess.Source.super.reprojectionSummary(cap);
			}
			@Override public void tightenDisplay(int cap) { tightens.incrementAndGet(); throw new AssertionError("unadmitted source parser"); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return snapshotOverride ? Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 8192; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { return Optional.of(snapshot); }
				}) : Optional.empty();
			}
		};
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(source), settings.pendingMemoryBytes(), (retained, cap) -> {
			parses.incrementAndGet(); return new InventoryItemCodec.Display(retained.itemId(), retained.label(), null, retained.aggregateRequired());
		}, sink)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(exact, runtime.step(consumer, 1).orElseThrow().slots().getFirst());
			Object round = InventoryBackendTest.field(consumer, "round"), cursor = InventoryBackendTest.field(consumer, "cursor");
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(0, tightens.get()); assertEquals(1, parses.get()); assertEquals(1, reads.get());
			assertSame(round, InventoryBackendTest.field(consumer, "round")); assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor"));
			var slots = (List<?>) InventoryBackendTest.field(round, "slots"); var projected = (InventoryDomainCodec.Item) slots.getFirst();
			assertEquals(exact.key(), projected.key()); assertEquals(exact.count(), projected.count()); assertFalse(projected.aggregateRequired()); assertNull(projected.displayJson());
			for (int tick = 1; tick <= 3; tick++) {
				runtime.advance(tick, settings);
				assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, runtime.reprojectDisplays(consumer, 1024).status());
				var terminal = runtime.step(consumer, 1).orElseThrow();
				assertEquals(CaptureResult.Availability.READABLE, terminal.result().availability());
				assertEquals(CaptureResult.Completeness.INCOMPLETE, terminal.result().completeness()); assertTrue(terminal.slots().isEmpty());
				assertEquals(1, terminal.result().coverage().scanned()); assertEquals(2, terminal.result().coverage().expected().orElseThrow());
			}
			assertEquals(0, tightens.get()); assertEquals(1, reads.get()); assertTrue(events.isEmpty()); assertFalse(consumer.invalidated());
			assertFalse(runtime.delayed(nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module.INVENTORY_PREVIEW));
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void callerTrustedCounterStaysLazyMemberLocalOrderedAndIsolatedThroughRuntimeTightening() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		var owner = new UUID(42, 4); var input = input(owner); long base = 9007199254740993L;
		var calls = new java.util.ArrayList<Integer>(); var captures = new AtomicInteger();
		String schema = "test:projection_counter_" + UUID.randomUUID().toString().replace("-", "");
		assertTrue(nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotSchemas.register(schema,
			(payload, slot, slots, registries) -> {
				assertEquals(3, slots); calls.add(slot);
				var tag = (net.minecraft.nbt.CompoundTag) payload; long amount = tag.getLong("amount") + slot;
				tag.putLong("amount", 0);
				return new nx.pingwheel.common.platform.IPlatformInventoryService.Entry(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE), amount);
			}));
		var root = net.minecraft.core.BlockPos.ZERO; var right = new net.minecraft.core.BlockPos(1, 0, 0);
		var layout = new nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout("test:counter", "north", root, List.of(
			new nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Member(root, "minecraft:chest", "minecraft:chest", "controller", List.of(
				new nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Segment("Custom", schema, 3, List.of(2, 0)))),
			new nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Member(right, "minecraft:chest", "minecraft:chest", "member", List.of(
				new nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout.Segment("Custom", schema, 3, List.of(1, 0))))));
		var leftPayload = new net.minecraft.nbt.CompoundTag(); leftPayload.putLong("amount", base);
		var rightPayload = new net.minecraft.nbt.CompoundTag(); rightPayload.putLong("amount", base + 10);
		var snapshot = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryNbtSnapshot[1];
		var source = new InventorySourceAccess.Source() {
			@Override public SourceKey key() { return new SourceKey("test", "inventory", "counter", input.viewKey()); }
			@Override public boolean valid() { return true; }
			@Override public boolean stableCursor() { return true; }
			@Override public int slots() { return 4; }
			@Override public InventoryDomainCodec.Item read(int slot) { throw new AssertionError("no live fallback"); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return nx.pingwheel.common.presentation.inventory.minecraft.InventoryNbtSnapshot.memoryUpperBound(4, 2); }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						captures.incrementAndGet();
						snapshot[0] = nx.pingwheel.common.presentation.inventory.minecraft.InventoryNbtSnapshot.create(layout, root,
							List.of(leftPayload, rightPayload), net.minecraft.core.RegistryAccess.EMPTY, memoryUpperBoundBytes());
						return Optional.of(snapshot[0]);
					}
				});
			}
		};
		var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(source), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer)); assertTrue(calls.isEmpty());
			assertEquals(InventoryReprojectionSummary.EMPTY, snapshot[0].reprojectionSummary(1024));
			var first = runtime.step(consumer, 1).orElseThrow().slots().getFirst(); assertEquals(base + 2, first.count());
			var summary = snapshot[0].reprojectionSummary(1024); assertTrue(summary.known()); assertEquals(1, summary.parserCalls());
			settings.setDisplayByteMultiplier(new java.math.BigDecimal("0.25")); settings.validate(); runtime.advance(1, settings);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(List.of(2), calls); assertEquals(1024, snapshot[0].displayBytes()); assertEquals(1, captures.get());
			var amounts = new java.util.ArrayList<Long>(); amounts.add(first.count());
			for (int slot = 1; slot < 4; slot++) amounts.add(runtime.step(consumer, 1).orElseThrow().slots().getFirst().count());
			assertEquals(List.of(base + 2, base, base + 11, base + 10), amounts); assertEquals(List.of(2, 0, 1, 0), calls);
			assertEquals(base, ((net.minecraft.nbt.CompoundTag) snapshot[0].payload(0)).getLong("amount"));
			assertEquals(base + 10, ((net.minecraft.nbt.CompoundTag) snapshot[0].payload(1)).getLong("amount"));
			assertEquals(1, captures.get()); assertEquals(0, runtime.memory().reserved()); assertFalse(consumer.invalidated());
		}
	}
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
	void oldInputWorkRefusalKeepsSourceAndSlotCursorUntilNextPeriod(boolean sourceAlreadyTightened) throws Exception {
		var settings = InventorySettings.serverDefaults(); settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8));
		var owner = new UUID(40, 8); var reads = new AtomicInteger(); var sourceTightens = new AtomicInteger(); var parses = new AtomicInteger();
		var events = new java.util.ArrayList<nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent>();
		var sink = new nx.pingwheel.common.presentation.budget.BudgetDelaySink() {
			@Override public void observe(DelayEvent event) { events.add(event); }
			@Override public void state(Module module, long cycle, long now, DelayState state) {}
			@Override public void endCycle(Module module, long cycle, long now) {}
		};
		String old = "x".repeat(30_000); var key = new InventoryScanner.Key("minecraft:stone", "retained-exact");
		try (var runtime = new InventoryRuntime((i, cap) -> Optional.of(new Source(i, reads, 1) {
			@Override public int displayBytes() { return cap; }
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return new InventoryDomainCodec.Item(key, 9007199254740993L, "stone", old, false); }
			@Override public void tightenDisplay(int cap) { sourceTightens.incrementAndGet(); }
		}), settings.pendingMemoryBytes(), (retained, cap) -> {
			parses.incrementAndGet(); return new InventoryItemCodec.Display(retained.itemId(), retained.label(), null, retained.aggregateRequired());
		}, sink)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var observed = runtime.step(consumer).orElseThrow().slots().getFirst();
			Object round = InventoryBackendTest.field(consumer, "round"), cursor = InventoryBackendTest.field(consumer, "cursor");
			if (sourceAlreadyTightened) {
				InventoryBackendTest.field(round, "passActive", true); InventoryBackendTest.field(round, "passCeiling", 1024);
				InventoryBackendTest.field(round, "reprojectEnd", 1);
			}
			var ledger = (nx.pingwheel.common.presentation.source.CostLedger) InventoryBackendTest.field(runtime, "physical");
			var counter = new nx.pingwheel.common.presentation.source.CostLedger.Counter("inventory/item_work", nx.pingwheel.common.presentation.source.CostLedger.Unit.WORK);
			assertTrue(runtime.claimDetachedItemWork(ledger.remaining(counter)), "exhaust the real allowance independently of parser coefficients");
			long retained = runtime.memory().retained(), probes = ledger.used(InventorySourceAccess.PROBES);
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(0, parses.get()); assertEquals(0, sourceTightens.get()); assertEquals(1, reads.get());
			assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor")); assertEquals(sourceAlreadyTightened, InventoryBackendTest.field(round, "passActive"));
			assertEquals(0, InventoryBackendTest.field(round, "reprojectCursor")); assertEquals(retained, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
			assertEquals(probes, ledger.used(InventorySourceAccess.PROBES));
			assertEquals(1, events.size());
			assertEquals(nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason.PUBLICATION_WORK.bit(), events.getFirst().newReasonBits());
			assertFalse(events.getFirst().quantity().known(), "projection keeps its existing observed-quantity contract");
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(1, events.size(), "same pending projection has one observation per accounting cycle");
			runtime.advance(settings.getPreview().getPeriodTicks(), settings);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, runtime.reprojectDisplays(consumer, 1024).status());
			assertEquals(1, parses.get()); assertEquals(sourceAlreadyTightened ? 0 : 1, sourceTightens.get()); assertEquals(1, reads.get());
			var slots = (List<?>) InventoryBackendTest.field(round, "slots"); var tightened = (InventoryDomainCodec.Item) slots.getFirst();
			assertEquals(observed.key(), tightened.key()); assertEquals(observed.count(), tightened.count()); assertFalse(tightened.aggregateRequired());
			assertNull(tightened.displayJson()); assertEquals(cursor, InventoryBackendTest.field(consumer, "cursor")); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void onePhysicalReadAndDistinctClientAndTargetLogicalSubjects() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1);
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 2)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var duplicate = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(new UUID(2, 2))).orElseThrow();
			var tracking = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size());
			assertEquals(1, runtime.step(duplicate).orElseThrow().slots().size());
			assertEquals(1, runtime.step(b).orElseThrow().slots().size());
			assertEquals(1, runtime.step(tracking).orElseThrow().slots().size());
			assertEquals(1, reads.get(), "four consumers share one admitted physical prefix");
			assertTrue(runtime.step(a).isEmpty(), "client quota survives duplicate requests");
			assertTrue(runtime.step(tracking).isEmpty(), "target quota is independent but finite");
			runtime.advance(5, settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(a).orElseThrow().result().completeness());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(tracking).orElseThrow().result().completeness());
			assertEquals(2, reads.get(), "observed blanks are physical slots too");
		}
	}
	@Test void memoryDeferHasNoResolverOrReadAndRetainedLifetimeEndsOnLastClose() {
		AtomicInteger probes = new AtomicInteger(), reads = new AtomicInteger(); var settings = InventorySettings.serverDefaults();
		var owner = new UUID(1, 1);
		try (var runtime = new InventoryRuntime(i -> { probes.incrementAndGet(); return Optional.of(new Source(i, reads, 1)); }, 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.memory().setCap(runtime.memory().retained());
			assertTrue(runtime.step(consumer).isEmpty()); assertEquals(0, probes.get()); assertEquals(0, reads.get());
			runtime.memory().setCap(16000000); assertTrue(runtime.step(consumer).isPresent());
			long live = runtime.memory().retained(); assertTrue(live > 4096);
			consumer.close(); assertEquals(1024, runtime.memory().retained(), "quota subject survives consumer close until the period ends");
			assertEquals(0, runtime.memory().reserved()); runtime.advance(5, settings); assertEquals(0, runtime.memory().retained());
		}
	}
	@Test void cursorlessEnumerationCountsBlankViewsAndCannotInventContinuation() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
		try (var runtime = new InventoryRuntime(i -> { Source source = new Source(i, reads, 3); source.stable = false; return Optional.of(source); }, 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var observation = runtime.step(consumer).orElseThrow();
			assertEquals(2, reads.get()); assertEquals(2, observation.slots().size());
			assertNull(observation.slots().get(1)); assertEquals(CaptureResult.Completeness.INCOMPLETE, observation.result().completeness());
			runtime.advance(5, settings); runtime.step(consumer); assertEquals(2, reads.get());
		}
	}
	@Test void ownerAndFrozenFaceArePhysicalSharingBoundaries() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(new InventorySourceInput(TARGET, owner, BlockFace.SOUTH), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(2, reads.get());
			assertThrows(NullPointerException.class, () -> new InventorySourceInput(TARGET, owner, null));
		}
	}
	@Test void sameOriginalBlockStillResolvesSeparatelyForDifferentOwnersAndFrozenFaces() {
		var owner = new UUID(17, 1); var other = new UUID(17, 2); var resolutions = new AtomicInteger();
		var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet();
			return Optional.of(new Source(i, reads, 2));
		}, 16_000_000)) {
			runtime.advance(0, InventorySettings.serverDefaults());
			var first = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var anotherOwner = runtime.attach(input(other), new InventoryRuntime.PreviewSubject(other)).orElseThrow();
			var anotherFace = runtime.attach(new InventorySourceInput(TARGET, owner, BlockFace.SOUTH), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(first, 1).orElseThrow(); runtime.step(anotherOwner, 1).orElseThrow(); runtime.step(anotherFace, 1).orElseThrow();
			assertEquals(3, resolutions.get()); assertEquals(3, reads.get(), "same-binding shortcut must not bypass the owner/face source key");
			runtime.retireInvalid(input(owner)); assertTrue(first.invalidated());
			assertFalse(anotherOwner.invalidated()); assertFalse(anotherFace.invalidated());
		}
	}
	@Test void repeatedTrackingPingsSharePhysicalAndTargetQuotaAndFreshSelectSkipsOlderPreviewSweep() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 3)), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); runtime.advance(1, settings); runtime.step(preview);
			runtime.advance(2, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size()); assertEquals(2, reads.get(), "new SELECT must not inherit pre-selection preview prefix");
			assertEquals(1, runtime.step(b).orElseThrow().slots().size()); assertEquals(2, reads.get());
			assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(b).isEmpty(), "another Ping is not another TargetKey allowance");
			a.close(); runtime.advance(5, settings); assertEquals(1, runtime.step(b).orElseThrow().slots().size());
		}
	}
	@Test void changedCadenceCannotRefundLogicalUsageAndProviderWorkExhaustionDefersBeforeLookup() {
		AtomicInteger probes = new AtomicInteger(), reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> { probes.incrementAndGet(); return Optional.of(new Source(i, reads, 3)); }, 16000000)) {
			runtime.advance(0, settings); var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(a);
			settings.getPreview().setPeriodTicks(1); runtime.advance(1, settings); assertTrue(runtime.step(a).isEmpty()); assertEquals(1, reads.get());
			for (int i = 0; i < 128; i++) runtime.preflight(() -> Optional.of(true));
			int before = probes.get(); assertTrue(runtime.probe(input(owner)).isEmpty()); assertEquals(before, probes.get());
		}
	}
	@Test void throwingProviderReadAndCloseReleaseAllRetainedAndReservedMemory() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); throw new IllegalStateException("read"); }
			@Override public void close() { throw new IllegalStateException("cleanup"); }
		}), 16000000);
		runtime.advance(0, settings); var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
		assertEquals(CaptureResult.Availability.UNAVAILABLE, runtime.step(a).orElseThrow().result().availability());
		assertEquals(1, reads.get()); assertEquals(0, runtime.memory().reserved()); runtime.close();
		assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
	}
	@Test void restartedSweepCannotReattachAnOlderPeersIncompleteRound() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 3)), 16000000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(1, reads.get());
			runtime.advance(3, settings); a.restart(); runtime.step(a); assertEquals(2, reads.get(), "fresh sweep re-reads instead of consuming the old paid prefix");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void sameTickReopenDoesNotInheritAnOldPreviewWhileExistingTrackingSweepsStillShare() {
		AtomicInteger reads = new AtomicInteger(); UUID owner = new UUID(1, 1); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1)), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(preview);
			var reopen = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(reopen);
			assertEquals(2, reads.get(), "reopen starts fresh even within the same tick");
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a); runtime.step(b); assertEquals(3, reads.get());
			runtime.advance(3, settings); a.restart(); runtime.step(a); b.restart(); runtime.step(b);
			assertEquals(4, reads.get(), "new Pings do not multiply physical reads on periodic tracking sweeps");
		}
	}
	@Test void invalidStepRetiresAllThirtyTwoPhysicalRoundsWithoutClosingConsumersAndUnrelatedWorkProgresses() {
		var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] readable = {true};
		settings.setPendingMemoryMiB(64); // Thirty-two independent live catalogues remain retained with their evidence.
		try (var runtime = new InventoryRuntime(input -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(input, reads, 2) {
				@Override public boolean valid() { return readable[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16000000)) {
			runtime.advance(0, settings); var consumers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(1, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); consumers.add(consumer);
				assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(consumer, 1).orElseThrow().result().completeness());
			}
			assertEquals(32, reads.get()); assertTrue(runtime.memory().retained() > 9_000_000);
			readable[0] = false; runtime.advance(1, settings);
			for (var consumer : consumers) assertEquals(CaptureResult.Availability.INVALID, runtime.step(consumer, 1).orElseThrow().result().availability());
			assertEquals(32, closes.get(), "each shared provider handle is closed exactly once on invalidation, not consumer close");
			assertEquals(32L * (4096 + 1024), runtime.memory().retained(), "only bounded consumers and paid logical quota subjects remain"); assertEquals(0, runtime.memory().reserved());
			readable[0] = true; int resolvedBefore = resolutions.get(); var other = new UUID(2, 2);
			var unrelated = runtime.attach(input(other), new InventoryRuntime.PreviewSubject(other)).orElseThrow(); assertTrue(runtime.step(unrelated, 1).isPresent()); assertEquals(resolvedBefore + 1, resolutions.get());
			consumers.forEach(InventoryRuntime.Consumer::close); assertEquals(32, closes.get(), "retired rounds are not closed twice");
		}
	}
	@Test void invalidProbeRetiresSharedRoundAndPaidQuotasSurviveUntilFreshTrackingRecovery() {
		var settings = InventorySettings.serverDefaults(); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var owner = new UUID(1, 1); var reads = new AtomicInteger(); var closes = new AtomicInteger(); boolean[] readable = {true};
		try (var runtime = new InventoryRuntime(input -> Optional.of(new Source(input, reads, 2) {
			@Override public boolean valid() { return readable[0]; }
			@Override public void close() { closes.incrementAndGet(); }
		}), 16000000)) {
			runtime.advance(0, settings);
			var preview = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(preview); runtime.step(a); runtime.step(b); assertEquals(1, reads.get()); long before = runtime.memory().retained();
			readable[0] = false; runtime.advance(1, settings); assertEquals(Optional.of(false), runtime.probe(input(owner)));
			assertEquals(2, closes.get(), "one probe and one shared physical handle close"); assertTrue(runtime.memory().retained() < before); assertEquals(3L * 4096 + 2L * 1024, runtime.memory().retained());
			assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(a).orElseThrow().result().availability()); assertNotEquals(CaptureResult.Availability.READABLE, runtime.step(b).orElseThrow().result().availability());
			readable[0] = true; a.restart(); b.restart(); preview.restart(); assertTrue(runtime.step(a).isEmpty()); assertTrue(runtime.step(preview).isEmpty(), "retirement never refunds paid logical usage");
			runtime.advance(3, settings); runtime.step(a); runtime.step(b); assertEquals(2, reads.get(), "recovery starts one fresh shared physical observation");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void livePhysicalReductionWithinTickPreservesSpentSlotsAndCannotReadOrSelectPastNewCap() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2)); var reads = new AtomicInteger(); var owner = new UUID(1, 1);
		try (var runtime = new InventoryRuntime(input -> Optional.of(new Source(input, reads, 3)), 16000000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer, 1); assertEquals(1, reads.get());
			settings.setPhysicalSlotsPerTick(IntLimit.finite(1)); runtime.advance(0, settings);
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertFalse(runtime.selectionPresent(input(owner), new InventorySelection(item(7).key(), "minecraft:stone", "Stone", null, false, "danger"), 0)); assertEquals(1, reads.get());
			settings.setPhysicalSlotsPerTick(IntLimit.finite(3)); runtime.advance(0, settings); runtime.step(consumer, 1); assertEquals(2, reads.get());
			assertTrue(runtime.step(consumer, 1).isEmpty(), "raising live cap does not reset this tick's original finite ledger");
			runtime.advance(1, settings); runtime.step(consumer, 1); assertEquals(3, reads.get());
		}
	}
	@Test void snapshotPreparationSurvivesZeroScanAndDecodesFrozenSlotsAcrossPeriods() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		settings.getPreview().setPeriodTicks(1);
		UUID owner = new UUID(3, 3); AtomicInteger liveReads = new AtomicInteger(), captures = new AtomicInteger(), snapshotReads = new AtomicInteger(), snapshotCloses = new AtomicInteger();
		List<InventoryDomainCodec.Item> live = new java.util.ArrayList<>();
		for (int i = 0; i < 54; i++) live.add(item(i + 1));
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, liveReads, 54) {
			@Override public InventoryDomainCodec.Item read(int slot) { liveReads.incrementAndGet(); return live.get(slot); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 1_000_000; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						captures.incrementAndGet(); List<InventoryDomainCodec.Item> frozen = List.copyOf(live);
						return Optional.of(new Snapshot() {
							@Override public int slots() { return frozen.size(); }
							@Override public InventoryDomainCodec.Item read(int index) { snapshotReads.incrementAndGet(); return frozen.get(index); }
							@Override public long retainedBytes() { return frozen.size() * 256L; }
							@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
							@Override public void close() { snapshotCloses.incrementAndGet(); }
						});
					}
				});
			}
		}), 16_000_000)) {
			runtime.advance(0, settings);
			assertTrue(runtime.selectionPresent(input(owner), new InventorySelection(item(1).key(), "minecraft:stone", "stone", null, false, "danger"), 0));
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertTrue(runtime.step(consumer, 2).isEmpty(), "zero remaining scan allowance cannot consume non-empty snapshot slots");
			assertEquals(1, captures.get(), "preparation captures once even after the physical scan allowance is spent");
			assertEquals(1, liveReads.get(), "only the separately admitted selection witness reads the live source");
			live.replaceAll(ignored -> item(999));
			settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
			long consumed = 0;
			for (int tick = 1; tick <= 27; tick++) {
				runtime.advance(tick, settings);
				var observation = runtime.step(consumer, 2).orElseThrow();
				assertEquals(CaptureResult.Consistency.VERIFIED, observation.result().consistency());
				for (var slot : observation.slots()) {
					assertNotNull(slot);
					assertTrue(slot.count() <= 54, "consumption uses detached values, not changed live inventory");
					consumed++;
				}
			}
			assertEquals(54, consumed);
			assertEquals(54, snapshotReads.get());
			assertEquals(1, captures.get());
			assertEquals(1, liveReads.get(), "snapshot consumption never falls back to live slot reads");
		}
		assertEquals(1, snapshotCloses.get(), "last consumer close releases the retained snapshot");
	}
	@Test void cachedEmptySnapshotCompletesIndependentLogicalConsumersWithoutRepeatValidationOrReads() {
		var settings = InventorySettings.serverDefaults(); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(1));
		UUID owner = new UUID(4, 4); AtomicInteger reads = new AtomicInteger(), captures = new AtomicInteger();
		InventorySourceInput quotaInput = input(owner);
		InventorySourceInput emptyInput = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 2, 3, "minecraft:chest"), owner, BlockFace.NORTH);
		java.util.Map<Integer, Source> sources = new java.util.HashMap<>();
		try (var runtime = new InventoryRuntime(i -> {
			int x = i.ordinaryTarget().orElseThrow().x();
			Source source = new Source(i, reads, x == 1 ? 1 : 0) {
				@Override public SourceKey key() { return new SourceKey("test", "inventory", "target-" + x, i.viewKey()); }
				@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return x == 1 ? item(1) : null; }
				@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
					if (x != 2) return Optional.empty();
					return Optional.of(new InventorySourceAccess.SnapshotPlan() {
						@Override public long memoryUpperBoundBytes() { return 1024; }
						@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
							captures.incrementAndGet();
							return Optional.of(new Snapshot() {
								@Override public int slots() { return 0; }
								@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("empty snapshot has no reads"); }
								@Override public long retainedBytes() { return 0; }
								@Override public InventorySourceAccess.SnapshotEvidence evidence() { return InventorySourceAccess.SnapshotEvidence.ATOMIC_DETACHED; }
							});
						}
					});
				}
			};
			sources.put(x, source); return Optional.of(source);
		}, 4_000_000)) {
			runtime.advance(0, settings);
			var subject = new InventoryRuntime.PreviewSubject(owner);
			var quota = runtime.attach(quotaInput, subject).orElseThrow();
			assertEquals(1, runtime.step(quota).orElseThrow().slots().size(), "spend the subject logical allowance on a separate source first");
			var first = runtime.attach(emptyInput, subject).orElseThrow();
			var second = runtime.attach(emptyInput, subject).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(first).orElseThrow().result().completeness());
			int validations = sources.get(2).validCalls.get();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(second).orElseThrow().result().completeness());
			assertEquals(validations, sources.get(2).validCalls.get(), "cached empty delivery does not revalidate or step the handle");
			assertEquals(1, captures.get()); assertEquals(1, reads.get());
		}
	}
	@Test void zeroAllowanceProviderValidationRequiresFixedProviderBudgetAndMemoryDeferredRetriesAreAdmitted() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		settings.setPendingMemoryMiB(16); // Lock/root scratch must fit before testing provider-work and snapshot-memory defers.
		UUID owner = new UUID(5, 5); AtomicInteger reads = new AtomicInteger(), captures = new AtomicInteger(); Source[] source = new Source[1];
		try (var runtime = new InventoryRuntime(i -> {
			source[0] = new Source(i, reads, 0) {
				@Override public boolean valid() { validCalls.incrementAndGet(); return true; }
				@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
					return Optional.of(new InventorySourceAccess.SnapshotPlan() {
						@Override public long memoryUpperBoundBytes() { return 1024; }
						@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
							captures.incrementAndGet();
							return Optional.of(new Snapshot() {
								@Override public int slots() { return 0; }
								@Override public InventoryDomainCodec.Item read(int index) { return null; }
								@Override public long retainedBytes() { return 0; }
							});
						}
					});
				}
			}; return Optional.of(source[0]);
		}, 4_000_000)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertFalse(runtime.selectionPresent(input(owner), new InventorySelection(item(1).key(), "minecraft:stone", "stone", null, false, "danger"), 0));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			for (int i = 0; i < 128; i++) runtime.preflight(() -> Optional.of(true));
			int beforeBudgetFailure = source[0].validCalls.get();
			assertTrue(runtime.step(consumer).isEmpty());
			assertEquals(beforeBudgetFailure, source[0].validCalls.get(), "zero-slot source validation defers after provider-work exhaustion");
			assertTrue(beforeBudgetFailure > 0);
		}

		AtomicInteger memoryCaptures = new AtomicInteger(), memoryValidCalls = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, new AtomicInteger(), 0) {
			@Override public boolean valid() { memoryValidCalls.incrementAndGet(); return true; }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 2 * InventoryItemBudget.workspaceUpperBound(4096); }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						memoryCaptures.incrementAndGet();
						return Optional.of(new Snapshot() {
							@Override public int slots() { return 0; }
							@Override public InventoryDomainCodec.Item read(int index) { return null; }
							@Override public long retainedBytes() { return 0; }
						});
					}
				});
			}
		}), 4_000_000)) {
			settings.setPendingMemoryMiB(32);
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var held = runtime.memory().tryReserve(runtime.memory().remaining() - 3 * InventoryItemBudget.workspaceUpperBound(4096) / 2).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			int afterDefer = memoryValidCalls.get();
			assertTrue(afterDefer > 0, "round and source handle exist before snapshot-memory defer");
			while (runtime.preflight(() -> Optional.of(true)).isPresent()) {}
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
			assertEquals(afterDefer, memoryValidCalls.get(), "exhausted fixed work defers before another snapshot-plan validity check");
			runtime.advance(1, settings); held.close();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(1, memoryCaptures.get()); assertTrue(memoryValidCalls.get() > afterDefer);
		}
	}
	@Test void terminalPublicationUsesRetainedEvidenceNotAFreshSelfConsistentWrapper() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(6, 6);
		var reads = new AtomicInteger(); var resolutions = new AtomicInteger(); var closes = new AtomicInteger(); int[] layout = {1};
		try (var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); int expected = layout[0];
			return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { validCalls.incrementAndGet(); return layout[0] == expected; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			layout[0] = 2;
			assertTrue(runtime.validate(input(owner)), "fresh authority can read the new topology, but does not authorize old data");
			int before = resolutions.get();
			assertEquals(Optional.of(false), runtime.probe(consumer));
			assertEquals(before, resolutions.get(), "publication checks the admitted terminal handle, never a new resolve");
			assertEquals(1, reads.get(), "publication validity never steps or reads items");
			assertTrue(consumer.invalidated()); assertEquals(2, closes.get(), "one fresh authority wrapper and one retained handle");
			layout[0] = 1;
			assertEquals(Optional.of(false), runtime.probe(consumer), "retirement cannot fall back to a now-valid fresh resolve");
			assertEquals(before, resolutions.get());
			consumer.close(); assertEquals(2, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
	}
	@Test void restartedTrackingRetainsOldCompletedEvidenceAndSharedRetirementRevokesPreviouslyCheckedTerminalPeer() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(7, 7);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); int[] layout = {1};
		try (var runtime = new InventoryRuntime(i -> {
			int expected = layout[0]; return Optional.of(new Source(i, reads, 2) {
				@Override public boolean valid() { return layout[0] == expected; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000)) {
			runtime.advance(0, settings);
			var terminal = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var tracking = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(terminal, 1).orElseThrow(); runtime.step(tracking).orElseThrow();
			runtime.step(terminal, 1).orElseThrow(); runtime.step(tracking).orElseThrow(); tracking.retainCompleted(); assertEquals(2, reads.get());
			runtime.advance(3, settings); tracking.restart();
			assertEquals(Optional.of(true), runtime.probe(terminal), "earlier same-tick publication admission");
			layout[0] = 2;
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(tracking, 1).orElseThrow().result().completeness());
			assertEquals(Optional.of(false), runtime.probe(tracking), "new scanning handle is valid; the old completed result is not");
			assertTrue(terminal.invalidated(), "retiring shared evidence reaches terminal/non-scanning consumers too");
			assertEquals(Optional.of(false), runtime.probe(terminal), "an earlier check cannot shield a subsequently retired consumer");
			assertEquals(2, closes.get(), "old shared evidence and the abandoned new scan each close once");
			assertEquals(3, reads.get(), "retirement does not read a fourth slot");
			assertEquals(2L * 4096 + 2L * 1024, runtime.memory().retained());
			terminal.close(); tracking.close(); assertEquals(2, closes.get()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void evidenceLeaseReleasesPagesAtLastScannerRestartAndClosesOnlyWhenReplacedOrLastConsumerLeaves() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(8, 8);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var validations = new AtomicInteger(); var snapshotCloses = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 2) {
			@Override public boolean valid() { validations.incrementAndGet(); return true; }
			@Override public void close() { closes.incrementAndGet(); }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return 1024; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						return Optional.of(new Snapshot() {
							@Override public int slots() { return 2; }
							@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); return slot == 0 ? item(7) : null; }
							@Override public long retainedBytes() { return 256; }
							@Override public void close() { snapshotCloses.incrementAndGet(); }
						});
					}
				});
			}
		}), 16_000_000)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(a).orElseThrow(); a.retainCompleted(); runtime.step(b).orElseThrow(); b.retainCompleted();
			int before = validations.get(); assertEquals(Optional.of(true), runtime.probe(a));
			assertEquals(before + 1, validations.get(), "identical scan/completed references are validated only once");
			long retainedPages = runtime.memory().retained(); a.restart(); b.restart();
			assertTrue(runtime.memory().retained() < retainedPages, "last scanner drops pages while both evidence references stay charged");
			assertEquals(2L * 4096 + 1024 + 4096 + 262144 + 32768, runtime.memory().retained(), "one bounded evidence lease plus paid target quota and two consumers");
			assertEquals(0, closes.get()); assertEquals(1, snapshotCloses.get(), "last scanner releases detached snapshot memory, but not its source evidence");
			assertTrue(runtime.step(a).isPresent()); a.retainCompleted(); assertEquals(4, reads.get(), "restart creates a fresh sweep even in the same tick");
			assertEquals(0, closes.get(), "the slow peer still owns the old completed evidence");
			b.close(); assertEquals(1, closes.get()); a.close(); assertEquals(2, closes.get());
			assertEquals(2, snapshotCloses.get(), "each capture is released once despite scan and completed references sharing a handle");
			assertEquals(1024, runtime.memory().retained(), "replacing or releasing evidence never refunds paid logical usage");
		}
	}
	@Test void publicationEvidenceAdmissionDefersBeforeAnyProviderCallAndNeverInvalidatesOnBudgetPressure() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(9, 9); var reads = new AtomicInteger(); var validations = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public boolean valid() { validations.incrementAndGet(); return true; }
		}), 16_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow(); runtime.step(consumer).orElseThrow();
			int before = validations.get(); runtime.memory().setCap(runtime.memory().retained());
			assertTrue(runtime.probe(consumer).isEmpty()); assertEquals(before, validations.get()); assertFalse(consumer.invalidated());
			runtime.memory().setCap(16_000_000);
			while (runtime.preflight(() -> Optional.of(true)).isPresent()) {}
			assertTrue(runtime.probe(consumer).isEmpty()); assertEquals(before, validations.get()); assertFalse(consumer.invalidated()); assertEquals(1, reads.get());
			runtime.advance(1, settings); assertEquals(Optional.of(true), runtime.probe(consumer)); assertEquals(before + 1, validations.get());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void rejectedCompletedSweepDoesNotReplaceTheEvidenceOfTheStillPublishedPreviousResult() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(10, 10); var reads = new AtomicInteger(); var closes = new AtomicInteger();
		boolean[] oldValid = {true}; var resolutions = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			boolean old = resolutions.incrementAndGet() == 1;
			return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { return !old || oldValid[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000)) {
			runtime.advance(0, settings); var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			runtime.step(consumer).orElseThrow(); consumer.retainCompleted();
			runtime.advance(3, settings); consumer.restart();
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			// The domain may reject this quantity (for example an overflow) and keep publishing its last accepted result.
			assertEquals(0, closes.get(), "step completion alone cannot release the published result's evidence");
			oldValid[0] = false; assertEquals(Optional.of(false), runtime.probe(consumer));
			assertEquals(2, closes.get()); assertTrue(consumer.invalidated());
		}
	}
	@Test void allThirtyTwoRetainedTrackingEvidenceLeasesAllowFreshReplacementWithoutAnUnboundedCache() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var reads = new AtomicInteger(); var closes = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public void close() { closes.incrementAndGet(); }
		}), 32_000_000)) {
			runtime.advance(0, settings); var consumers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(11, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
				runtime.step(consumer).orElseThrow(); consumer.retainCompleted(); consumers.add(consumer);
			}
			assertEquals(32, reads.get()); assertEquals(0, closes.get());
			runtime.advance(3, settings); consumers.forEach(InventoryRuntime.Consumer::restart);
			for (var consumer : consumers) {
				assertTrue(runtime.step(consumer).isPresent(), "bounded replacement headroom prevents evidence retention from deadlocking all 32 sources");
				consumer.retainCompleted();
			}
			assertEquals(64, reads.get()); assertEquals(32, closes.get()); assertEquals(0, runtime.memory().reserved());
			consumers.forEach(InventoryRuntime.Consumer::close); assertEquals(64, closes.get());
			assertEquals(1024, runtime.memory().retained(), "one target quota stays charged until its period ends");
		}
	}
	@Test void secondBatchOfNewConsumersCannotConsumeTheReserveNeededByThirtyTwoRetainedEvidenceReplacements() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 64_000_000);
		try (runtime) {
			runtime.advance(0, settings);
			var existing = new java.util.ArrayList<InventoryRuntime.Consumer>();
			var newcomers = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(12, index);
				var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
				runtime.step(consumer).orElseThrow(); consumer.retainCompleted(); existing.add(consumer);
			}
			existing.forEach(InventoryRuntime.Consumer::restart);
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(13, index);
				newcomers.add(runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow());
			}
			for (int period = 1; period <= 3; period++) {
				runtime.advance(period * 3L, settings); long retained = runtime.memory().retained();
				int before = resolutions.get();
				for (var consumer : newcomers) {
					assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
					assertTrue(runtime.step(consumer).isEmpty());
					consumer.restart(); // denied observation cannot turn into a replacement lease
				}
				assertEquals(before, resolutions.get(), "the count guard rejects new physical observations before resolver/work admission");
				assertEquals(retained, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
				assertTrue(runtime.memory().remaining() > InventoryItemBudget.workspaceUpperBound(4096), "root workspace is available; the new-observation count guard defers");
				// Fill all 32 scan positions concurrently while the 32 old evidences remain charged.
				for (var consumer : existing) assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
				assertEquals(before + 32, resolutions.get());
				assertEquals((period - 1) * 32, closes.get(), "old publication evidence survives until an accepted replacement");
				for (var consumer : existing) {
					assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
					consumer.retainCompleted();
				}
				assertEquals(period * 32, closes.get()); assertEquals((period + 1) * 32, reads.get());
				existing.forEach(InventoryRuntime.Consumer::restart); assertEquals(0, runtime.memory().reserved());
			}
			int before = reads.get(); existing.forEach(InventoryRuntime.Consumer::close);
			assertEquals(128, closes.get());
			runtime.advance(12, settings); // replace the exhausted fixed-work period, not the retained admission state
			for (var consumer : newcomers) {
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
				consumer.retainCompleted();
			}
			assertEquals(before + 32, reads.get(), "new observers become serviceable once prior evidence leases leave");
			newcomers.forEach(InventoryRuntime.Consumer::close); assertEquals(160, closes.get());
			assertEquals(1024, runtime.memory().retained(), "closing evidence never refunds the current target's paid progress");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void compatibleAliasedInputsStillShareOnePhysicalRoundWhenNormalObservationAdmissionIsFull() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		try (var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 2) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 64_000_000)) {
			runtime.advance(0, settings); var existing = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 31; index++) {
				runtime.advance(index * 3L, settings);
				var owner = new UUID(14, index); var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
				runtime.step(consumer).orElseThrow(); consumer.retainCompleted(); consumer.restart(); existing.add(consumer);
			}
			runtime.advance(93, settings);
			var owner = new UUID(14, 31);
			var a = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var aliasedInput = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 2, 3, "minecraft:chest"), owner, BlockFace.NORTH);
			var b = runtime.attach(aliasedInput, new InventoryRuntime.TrackingSubject(TargetKey.from(aliasedInput.target()))).orElseThrow();
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(a, 1).orElseThrow().result().completeness());
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(b), "the 32-round guard counts physical rounds, not a compatible consumer's references");
			runtime.step(b, 1).orElseThrow(); assertEquals(33, resolutions.get()); assertEquals(63, reads.get());
			runtime.advance(94, settings);
			runtime.step(a, 1).orElseThrow(); a.retainCompleted(); runtime.step(b, 1).orElseThrow(); b.retainCompleted();
			runtime.advance(96, settings); a.restart(); b.restart();
			runtime.step(a).orElseThrow(); a.retainCompleted(); runtime.step(b).orElseThrow(); b.retainCompleted();
			assertEquals(35, resolutions.get(), "both hit targets resolve, but replacement scans share one physical round");
			assertEquals(66, reads.get()); assertEquals(3, closes.get(), "two unused alias wrappers and the old shared evidence close once");
			a.close(); assertEquals(3, closes.get()); b.close(); assertEquals(4, closes.get());
			existing.forEach(InventoryRuntime.Consumer::close); assertEquals(35, closes.get()); assertEquals(0, runtime.memory().reserved());
			assertEquals(2048, runtime.memory().retained(), "both hit-target quotas survive shared physical evidence cleanup");
		}
	}
	@Test void thirtyTwoSharedRoundsWithSkewedReplacementAndRestartKeepSlowPeersServiceableAcrossPeriods() {
		var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(64);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(32)); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(32));
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 64_000_000);
		try (runtime) {
			runtime.advance(0, settings);
			var fast = new java.util.ArrayList<InventoryRuntime.Consumer>();
			var slow = new java.util.ArrayList<InventoryRuntime.Consumer>();
			for (int index = 0; index < 32; index++) {
				var owner = new UUID(15, index); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
				var a = runtime.attach(input(owner), subject).orElseThrow(); var b = runtime.attach(input(owner), subject).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(a).orElseThrow().result().completeness()); a.retainCompleted();
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(b).orElseThrow().result().completeness()); b.retainCompleted();
				fast.add(a); slow.add(b);
			}
			assertEquals(32, reads.get()); assertEquals(32, resolutions.get()); assertEquals(0, closes.get());
			fast.forEach(InventoryRuntime.Consumer::restart); slow.forEach(InventoryRuntime.Consumer::restart);
			for (int period = 1; period <= 3; period++) {
				runtime.advance(period * 6L - 3, settings);
				for (var consumer : fast) assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
				for (var consumer : fast) {
					assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
					consumer.retainCompleted();
				}
				assertEquals((period + 1) * 32, reads.get()); assertEquals(64, resolutions.get() - closes.get(), "all 64 physical evidence handles are retained, including the slow peers' old publications");
				assertEquals((period - 1) * 32, closes.get(), "no slow peer's old evidence is discarded to make room");
				long pinned = runtime.memory().retained(); fast.forEach(InventoryRuntime.Consumer::restart);
				assertEquals(pinned, runtime.memory().retained(), "the physical handoff lease keeps its data serviceable after every fast peer detaches");
				assertTrue(runtime.memory().remaining() > InventoryItemBudget.workspaceUpperBound(4096)); assertEquals(0, runtime.memory().reserved());
				runtime.advance(period * 6L, settings);
				int before = resolutions.get();
				for (var consumer : fast) assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer), "a physical handoff cannot fork again before its slow peer advances");
				assertEquals(before, resolutions.get(), "fanout guard defers before resolver/provider work");
				for (var consumer : slow) {
					assertEquals(Optional.of(true), runtime.probe(consumer), "the still-published old evidence remains valid while waiting");
					assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer), "a slow peer remains serviceable even with all 64 retained positions occupied");
					var observed = runtime.step(consumer).orElseThrow();
					assertEquals(CaptureResult.Completeness.COMPLETE, observed.result().completeness()); assertEquals(List.of(item(7)), observed.slots());
					consumer.retainCompleted(); assertFalse(consumer.invalidated());
				}
				assertEquals(before, resolutions.get()); assertEquals((period + 1) * 32, reads.get(), "both peers use one physical replacement, not a new sweep for the lagging peer");
				assertEquals(period * 32, closes.get(), "the last old evidence reference closes each predecessor once");
				slow.forEach(InventoryRuntime.Consumer::restart);
				assertTrue(runtime.memory().retained() > 64L * 4096 + 32L * (4096 + 262144 + 32768) + 1024, "current evidence includes retained live catalogues after handoff completion");
				assertEquals(0, runtime.memory().reserved());
			}
			fast.forEach(InventoryRuntime.Consumer::close); assertEquals(96, closes.get(), "one peer closing cannot release the other's current evidence");
			slow.forEach(InventoryRuntime.Consumer::close); assertEquals(128, closes.get());
			assertEquals(1024, runtime.memory().retained(), "handoff completion and close never refund the target's logical progress");
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void rejectedSharedReplacementReleasesItsHandoffWithoutDroppingOldEvidenceOrRefundingTargetQuota() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(2));
		var owner = new UUID(16, 16); var reads = new AtomicInteger(); var closes = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> Optional.of(new Source(i, reads, 1) {
			@Override public void close() { closes.incrementAndGet(); }
		}), 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var fast = runtime.attach(input(owner), subject).orElseThrow(); var slow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			fast.restart(); slow.restart(); runtime.advance(1, settings);
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(fast).orElseThrow().result().completeness());
			assertEquals(2, reads.get()); assertEquals(0, closes.get(), "an unaccepted completed sweep cannot replace either publication's evidence");
			fast.restart(); // the domain rejected the replacement instead of retaining it
			assertEquals(1, closes.get(), "a successor with only a handoff reference is cancelled, not leaked");
			assertTrue(runtime.memory().retained() > 2L * 4096 + 1024 + 4096 + 262144 + 32768, "the live catalogue remains charged with the evidence");
			assertEquals(Optional.of(true), runtime.probe(fast)); assertEquals(Optional.of(true), runtime.probe(slow));
			assertFalse(fast.invalidated()); assertFalse(slow.invalidated());
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(fast), "the cancelled handoff allows another fresh attempt");
			assertTrue(runtime.step(fast).isEmpty(), "cancelled capture and evidence releases do not refund the two paid target slots");
			assertEquals(2, reads.get()); fast.restart(); assertEquals(2, closes.get());
			runtime.advance(3, settings); runtime.step(fast).orElseThrow(); fast.retainCompleted(); fast.restart();
			assertEquals(0, runtime.memory().reserved()); assertEquals(2, closes.get());
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(slow).orElseThrow().result().completeness()); slow.retainCompleted();
			assertEquals(3, reads.get()); assertEquals(3, closes.get(), "the old shared evidence releases only after the slow peer accepts its successor");
			fast.close(); slow.close(); assertEquals(4, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void invalidSharedSuccessorDetachesItsHandoffAndLeavesUnrelatedOldPublishedEvidenceOwnedBySlowPeer() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(17, 17);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] replacementValid = {true};
		var runtime = new InventoryRuntime(i -> {
			boolean replacement = resolutions.incrementAndGet() == 2;
			return Optional.of(new Source(i, reads, 2) {
				@Override public boolean valid() { return !replacement || replacementValid[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var fast = runtime.attach(input(owner), subject).orElseThrow(); var slow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			fast.restart(); slow.restart(); runtime.advance(3, settings);
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(fast, 1).orElseThrow().result().completeness());
			replacementValid[0] = false; runtime.advance(4, settings);
			assertEquals(CaptureResult.Availability.INVALID, runtime.step(fast, 1).orElseThrow().result().availability());
			assertTrue(fast.invalidated()); assertFalse(slow.invalidated()); assertEquals(1, closes.get());
			assertEquals(Optional.of(true), runtime.probe(slow), "retiring an unaccepted successor cannot revoke independent still-valid old publication evidence");
			assertTrue(runtime.memory().retained() > 2L * 4096 + 1024 + 4096 + 262144 + 32768, "independently owned live catalogue remains charged");
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(slow).orElseThrow().result().completeness()); slow.retainCompleted();
			assertEquals(5, reads.get()); assertEquals(2, closes.get(), "a fresh slow-peer replacement can release the old evidence after successor invalidity");
			fast.close(); slow.close(); assertEquals(3, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void skewedAliasedHandoffKeepsOnePhysicalRoundAndIndependentTargetQuotasAcrossTicks() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		var owner = new UUID(18, 18); var alias = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 2, 2, 3, "minecraft:chest"), owner, BlockFace.NORTH);
		var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings);
			var fast = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			var slow = runtime.attach(alias, new InventoryRuntime.TrackingSubject(TargetKey.from(alias.target()))).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			assertEquals(1, reads.get()); assertEquals(2, resolutions.get()); assertEquals(1, closes.get(), "the unused alias wrapper closes");
			fast.restart(); slow.restart(); runtime.advance(3, settings);
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); fast.restart();
			runtime.advance(4, settings);
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(fast));
			assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(slow).orElseThrow().result().completeness()); slow.retainCompleted(); slow.restart();
			assertEquals(2, reads.get(), "a later alias consumer resolves compatibility but consumes the same pinned physical capture");
			assertEquals(4, resolutions.get()); assertEquals(3, closes.get(), "old shared evidence and both unused alias wrappers close once");
			assertTrue(runtime.memory().retained() > 2L * 4096 + 2048 + 4096 + 262144 + 32768, "the live catalogue and both hit-target quota subjects remain charged");
			assertTrue(runtime.step(slow).isEmpty(), "serving the alias spends its own target allowance even when no new physical read was needed");
			assertEquals(2, reads.get()); fast.close(); slow.close(); assertEquals(5, closes.get());
			assertEquals(2048, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void closingAcceptedPeersCancelsMergedPhysicalHandoffsWithoutLeakingReferencesOrRefundingQuota() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(3));
		var owner = new UUID(19, 19); var reads = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger();
		var runtime = new InventoryRuntime(i -> {
			resolutions.incrementAndGet(); return Optional.of(new Source(i, reads, 1) {
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var first = runtime.attach(input(owner), subject).orElseThrow(); var firstSlow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(first).orElseThrow(); first.retainCompleted(); runtime.step(firstSlow).orElseThrow(); firstSlow.retainCompleted();
			var second = runtime.attach(input(owner), subject).orElseThrow(); var secondSlow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(second).orElseThrow(); second.retainCompleted(); runtime.step(secondSlow).orElseThrow(); secondSlow.retainCompleted();
			assertEquals(2, reads.get(), "a newly attached pair starts its own fresh observation, not the earlier completed sweep");
			first.restart(); firstSlow.restart(); second.restart(); secondSlow.restart(); runtime.advance(1, settings);
			runtime.step(first).orElseThrow(); first.retainCompleted(); runtime.step(second).orElseThrow(); second.retainCompleted();
			assertEquals(3, reads.get()); assertEquals(3, resolutions.get(), "two older evidence rounds hand off into one compatible physical replacement");
			first.restart(); second.restart(); first.close(); assertEquals(0, closes.get()); second.close();
			assertEquals(1, closes.get(), "when the last accepted peer leaves, both handoff-only leases cancel and the successor closes once");
			assertTrue(runtime.memory().retained() > 2L * 4096 + 2L * (4096 + 262144 + 32768) + 1024, "both live catalogues remain charged with old evidence");
			assertEquals(Optional.of(true), runtime.probe(firstSlow)); assertEquals(Optional.of(true), runtime.probe(secondSlow));
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(firstSlow));
			assertTrue(runtime.step(firstSlow).isEmpty(), "closing accepted peers and handoff leases does not refund their target's three paid slots");
			firstSlow.restart(); assertEquals(2, closes.get()); assertEquals(3, reads.get());
			runtime.advance(3, settings);
			runtime.step(firstSlow).orElseThrow(); firstSlow.retainCompleted(); runtime.step(secondSlow).orElseThrow(); secondSlow.retainCompleted();
			assertEquals(4, reads.get()); assertEquals(5, resolutions.get()); assertEquals(4, closes.get(), "both old completed rounds safely release into one new accepted replacement");
			firstSlow.close(); secondSlow.close(); assertEquals(5, closes.get());
			assertEquals(1024, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void cachedHandoffValidityIsAdmittedBeforeSlowPeerConsumesItsCapturedReplacement() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(20, 20);
		var reads = new AtomicInteger(); var validations = new AtomicInteger(); var closes = new AtomicInteger(); var resolutions = new AtomicInteger(); boolean[] replacementValid = {true};
		var runtime = new InventoryRuntime(i -> {
			boolean replacement = resolutions.incrementAndGet() == 2;
			return Optional.of(new Source(i, reads, 1) {
				@Override public boolean valid() { validations.incrementAndGet(); return !replacement || replacementValid[0]; }
				@Override public void close() { closes.incrementAndGet(); }
			});
		}, 16_000_000);
		try (runtime) {
			runtime.advance(0, settings); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET));
			var fast = runtime.attach(input(owner), subject).orElseThrow(); var slow = runtime.attach(input(owner), subject).orElseThrow();
			runtime.step(fast).orElseThrow(); fast.retainCompleted(); runtime.step(slow).orElseThrow(); slow.retainCompleted();
			fast.restart(); slow.restart(); runtime.advance(3, settings); runtime.step(fast).orElseThrow(); fast.retainCompleted(); fast.restart();
			runtime.advance(4, settings); replacementValid[0] = false; int before = validations.get();
			runtime.memory().setCap(runtime.memory().retained());
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(slow));
			assertEquals(before, validations.get()); assertFalse(fast.invalidated()); assertFalse(slow.invalidated());
			runtime.memory().setCap(settings.pendingMemoryBytes()); while (runtime.preflight(() -> Optional.of(true)).isPresent()) {}
			assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(slow));
			assertEquals(before, validations.get()); assertEquals(0, runtime.memory().reserved());
			runtime.advance(5, settings);
			assertEquals(CaptureResult.Availability.UNAVAILABLE, runtime.step(slow).orElseThrow().result().availability(), "a valid old publication cannot authorize the now-invalid cached successor");
			assertEquals(before + 1, validations.get()); assertEquals(2, reads.get(), "handoff validity never reads or redelivers invalid cached items");
			assertEquals(2, resolutions.get(), "the retained successor is checked, not replaced by a fresh self-consistent wrapper");
			assertTrue(fast.invalidated()); assertFalse(slow.invalidated()); assertEquals(1, closes.get());
			assertEquals(Optional.of(true), runtime.probe(slow), "the old evidence remains independently owned until the caller handles the unavailable handoff");
			slow.discardObservation(); assertTrue(slow.invalidated()); assertEquals(2, closes.get());
			fast.close(); slow.close(); assertEquals(2, closes.get()); assertEquals(1024, runtime.memory().retained());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void authoritativeCapThreadsIntoNewObservationsAndLoweringReprojectsRetainedMetadataMonotonically() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8)); // 32 KiB display scale
		var owner = new UUID(20, 20);
		var resolvedCaps = new java.util.ArrayList<Integer>();
		var bigJson = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"p\":\"" + "x".repeat(6000) + "\"}}}";
		var items = List.of(
			new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "big"), 3, "stone", bigJson, false),
			new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:dirt", "plain"), 1, "dirt", null, false));
		var reprojections = new AtomicInteger();
		InventoryRuntime.DisplayReprojector port = (retained, displayBytes) -> {
			reprojections.incrementAndGet();
			assertEquals(4096, displayBytes);
			assertEquals("minecraft:stone", retained.itemId());
			assertEquals("stone", retained.label());
			return new InventoryItemCodec.Display(retained.itemId(), retained.label(), "{\"id\":\"minecraft:stone\"}", retained.aggregateRequired());
		};
		try (var runtime = new InventoryRuntime((input, displayBytes) -> {
			resolvedCaps.add(displayBytes);
			return Optional.of(new Source(input, new AtomicInteger(), 2) {
				@Override public InventoryDomainCodec.Item read(int slot) { return items.get(slot); }
			});
		}, 16_000_000, port)) {
			runtime.advance(0, settings);
			var a = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var b = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var c = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var first = runtime.step(a).orElseThrow();
			assertEquals(List.of(32768), resolvedCaps, "new observations receive the authoritative effective cap");
			assertEquals(bigJson, first.slots().get(0).displayJson(), "a configured higher cap preserves component JSON above 4 KiB");
			assertEquals(0, reprojections.get());
			settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); runtime.advance(1, settings);
			var lowered = runtime.reprojectDisplays(a, (int) settings.effectiveDisplayBytes());
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, lowered.status());
			assertEquals(List.of(new InventoryScanner.Key("minecraft:stone", "big")), lowered.changedKeys());
			assertTrue(lowered.foldIds().isEmpty(), "display simplification never requests an identity fold");
			assertEquals(1, reprojections.get());
			var tightened = runtime.step(b).orElseThrow();
			assertEquals("{\"id\":\"minecraft:stone\"}", tightened.slots().get(0).displayJson());
			assertEquals(new InventoryScanner.Key("minecraft:stone", "big"), tightened.slots().get(0).key(), "tightening never recomputes identity");
			assertEquals(3L, tightened.slots().get(0).count());
			assertEquals("stone", tightened.slots().get(0).label());
			assertFalse(tightened.slots().get(0).aggregateRequired());
			int before = reprojections.get();
			assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, runtime.reprojectDisplays(a, 4096).status(), "the same cap is a no-op");
			assertEquals(before, reprojections.get(), "repeated same cap does no extra work");
			settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8)); runtime.advance(2, settings);
			assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, runtime.reprojectDisplays(a, 32768).status(), "an increase is ignored");
			var afterRaise = runtime.step(c).orElseThrow();
			assertEquals("{\"id\":\"minecraft:stone\"}", afterRaise.slots().get(0).displayJson(), "raising the cap never resurrects old omitted components");
			assertEquals(3L, afterRaise.slots().get(0).count());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void reprojectionMemoryDeferKeepsCursorAndLeavesNoTicketsOrProgressLoss() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8));
		var owner = new UUID(21, 21);
		var bigJson = "d".repeat(6000);
		var items = List.of(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "big"), 5, "stone", bigJson, false));
		var portCalls = new AtomicInteger();
		InventoryRuntime.DisplayReprojector port = (retained, displayBytes) -> {
			portCalls.incrementAndGet();
			return new InventoryItemCodec.Display(retained.itemId(), retained.label(), "{\"id\":\"" + retained.itemId() + "\"}", retained.componentsStripped());
		};
		try (var runtime = new InventoryRuntime((input, displayBytes) -> Optional.of(new Source(input, new AtomicInteger(), 1) {
			@Override public InventoryDomainCodec.Item read(int slot) { return items.get(slot); }
		}), 16_000_000, port)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(bigJson, runtime.step(consumer).orElseThrow().slots().get(0).displayJson());
			settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); runtime.advance(1, settings);
			long held = runtime.memory().remaining() - 2048;
			var pin = runtime.memory().tryReserve(held).orElseThrow();
			var deferred = runtime.reprojectDisplays(consumer, 4096);
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, deferred.status());
			assertEquals(0, portCalls.get(), "memory admission precedes any decode or allocation");
			assertEquals(held, runtime.memory().reserved(), "a denied tightening holds no extra ticket");
			assertTrue(deferred.changedKeys().isEmpty());
			pin.close();
			assertEquals(0, runtime.memory().reserved());
			var completed = runtime.reprojectDisplays(consumer, 4096);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, completed.status());
			assertEquals(1, completed.changedKeys().size());
			assertEquals(1, portCalls.get(), "the deferred batch is retried once, without losing progress");
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void invalidRequestedDisplayCapNormalizesIntoTheBoundedRange() {
		assertEquals(1024, InventoryItemCodec.minimumDisplayBytes(), "the legal effective minimum is the 1 KiB display cap");
		var settings = InventorySettings.serverDefaults();
		var owner = new UUID(22, 22);
		var tightenedCaps = new java.util.ArrayList<Integer>();
		try (var runtime = new InventoryRuntime((input, displayBytes) -> Optional.of(new Source(input, new AtomicInteger(), 1) {
			@Override public void tightenDisplay(int displayBytes) { tightenedCaps.add(displayBytes); }
		}), 16_000_000)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			runtime.step(consumer).orElseThrow();
			var belowMinimum = runtime.reprojectDisplays(consumer, 1023);
			assertEquals(1024, belowMinimum.displayBytes(),
				"a requested cap below the legal minimum normalizes to the minimum instead of reaching the source");
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, belowMinimum.status());
			var aboveMaximum = runtime.reprojectDisplays(consumer, InventoryDomainCodec.MAX_DISPLAY_BYTES + 1);
			assertEquals(InventoryDomainCodec.MAX_DISPLAY_BYTES, aboveMaximum.displayBytes(),
				"a requested cap above the absolute bound normalizes to the bound");
			assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, aboveMaximum.status(),
				"normalizing upward never raises retained metadata");
			assertEquals(List.of(1024), tightenedCaps, "only the normalized tightening reaches the source");
		}
	}
	/** Distinct variant keys whose display JSON exceeds the default 4 KiB cap. */
	static List<InventoryDomainCodec.Item> bigItems(int slots, String displayJson) {
		var items = new java.util.ArrayList<InventoryDomainCodec.Item>();
		for (int index = 0; index < slots; index++)
			items.add(new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "variant-" + index), 1, "stone", displayJson, false));
		return items;
	}
	/** Live source whose read result honors the display cap its resolver received, like a real catalogue. */
	static class CappedSource extends Source {
		final List<InventoryDomainCodec.Item> items;
		int cap;
		CappedSource(InventorySourceInput input, List<InventoryDomainCodec.Item> items, int cap) {
			super(input, new AtomicInteger(), items.size());
			this.items = items; this.cap = cap;
		}
		@Override public InventoryDomainCodec.Item read(int slot) {
			var item = items.get(slot);
			return item.displayJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= cap ? item
				: new InventoryDomainCodec.Item(item.key(), item.count(), item.label(), "{\"id\":\"" + item.key().itemId() + "\"}", item.aggregateRequired());
		}
		@Override public int displayBytes() { return cap; }
		@Override public void tightenDisplay(int displayBytes) { if (displayBytes < cap) cap = displayBytes; }
	}
	@Test void releasedCompletedSweepEndsItsDeferredTighteningPassAndKeepsTheCumulativeSummary() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8)); // 32 KiB display scale
		var owner = new UUID(23, 23);
		int slots = 100;
		var bigJson = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"p\":\"" + "x".repeat(8000) + "\"}}}";
		var items = bigItems(slots, bigJson);
		var reprojections = new AtomicInteger();
		InventoryRuntime.DisplayReprojector port = (retained, displayBytes) -> {
			reprojections.incrementAndGet();
			return new InventoryItemCodec.Display(retained.itemId(), retained.label(), "{\"id\":\"" + retained.itemId() + "\"}", retained.aggregateRequired());
		};
		try (var runtime = new InventoryRuntime((input, displayBytes) -> Optional.of(new CappedSource(input, items, displayBytes)),
			16_000_000, port)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			int observed = 0;
			for (int call = 0; observed < slots && call < slots; call++) observed += runtime.step(consumer).orElseThrow().slots().size();
			assertEquals(slots, observed);
			consumer.retainCompleted();
			settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); runtime.advance(1, settings);
			var deferred = runtime.reprojectDisplays(consumer, 4096);
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, deferred.status(), "a retained page larger than one bounded batch defers");
			assertFalse(deferred.changedKeys().isEmpty());
			assertTrue(deferred.changedKeys().size() < slots, "the deferred pass reports only the batch it already walked");
			assertEquals(0, runtime.memory().reserved(), "a deferred batch holds no workspace ticket");
			consumer.restart(); // the last scanner releases decoded pages; the completed evidence stays
			assertEquals(Optional.of(true), runtime.probe(consumer), "completed evidence stays serviceable after its pages are released");
			assertEquals(0, runtime.memory().reserved());
			int before = reprojections.get();
			var resumed = assertDoesNotThrow(() -> runtime.reprojectDisplays(consumer, 4096), "a released page never resumes a stale walk");
			assertNotEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, resumed.status());
			assertTrue(resumed.changedKeys().containsAll(deferred.changedKeys()), "the cumulative change summary survives the release");
			assertTrue(resumed.foldIds().containsAll(deferred.foldIds()), "the cumulative fold summary survives the release");
			assertEquals(before, reprojections.get(), "a released page performs no further reprojection");
			runtime.advance(2, settings);
			int fresh = 0;
			for (int call = 0; fresh < slots && call < slots; call++) fresh += runtime.step(consumer).orElseThrow().slots().size();
			assertEquals(slots, fresh);
			var continuation = runtime.reprojectDisplays(consumer, 4096);
			assertNotEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, continuation.status());
			assertTrue(continuation.changedKeys().containsAll(deferred.changedKeys()), "a new observation keeps the earlier cumulative summary");
			assertTrue(continuation.foldIds().containsAll(deferred.foldIds()));
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void aTighterCeilingMidPassRestartsTheWalkFromTheFirstRetainedSlot() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8)); // 32 KiB display scale
		var owner = new UUID(24, 24);
		int slots = 70;
		var bigJson = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"p\":\"" + "x".repeat(9000) + "\"}}}";
		var projectedJson = "{\"id\":\"minecraft:stone\",\"p\":\"" + "y".repeat(6000) + "\"}";
		var items = bigItems(slots, bigJson);
		var retainedJson = new java.util.ArrayList<String>();
		InventoryRuntime.DisplayReprojector port = (retained, displayBytes) -> {
			retainedJson.add(retained.displayJson());
			return new InventoryItemCodec.Display(retained.itemId(), retained.label(), projectedJson, retained.componentsStripped());
		};
		try (var runtime = new InventoryRuntime((input, displayBytes) -> Optional.of(new Source(input, new AtomicInteger(), slots) {
			@Override public InventoryDomainCodec.Item read(int slot) { return items.get(slot); }
		}), 16_000_000, port)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			int observed = 0;
			for (int call = 0; observed < slots && call < slots; call++) observed += runtime.step(consumer).orElseThrow().slots().size();
			assertEquals(slots, observed);
			settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(2)); runtime.advance(1, settings); // 8 KiB
			var first = runtime.reprojectDisplays(consumer, 8192);
			assertFalse(first.changedKeys().isEmpty(), "the first tightening reprojects retained displays");
			settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); runtime.advance(2, settings); // 4 KiB, tighter mid-pass
			retainedJson.clear();
			var outcome = runtime.reprojectDisplays(consumer, 4096);
			assertFalse(retainedJson.isEmpty(), "a tighter ceiling restarts the walk over earlier slots");
			assertEquals(projectedJson, retainedJson.getFirst(), "the restarted walk starts at the first retained slot, never a stale cursor");
			for (int call = 0; outcome.status() == InventoryRuntime.ReprojectionStatus.DEFERRED && call < slots; call++)
				outcome = runtime.reprojectDisplays(consumer, 4096);
			assertNotEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, outcome.status());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void tighteningKeepsTheRetainedEncodedPageChargedUntilTheRoundIsReleased() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8)); // 32 KiB display scale
		var owner = new UUID(25, 25);
		var bigJson = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"p\":\"" + "x".repeat(12000) + "\"}}}";
		var big = new InventoryDomainCodec.Item(new InventoryScanner.Key("minecraft:stone", "big"), 3, "stone", bigJson, false);
		var smallJson = "{\"id\":\"minecraft:stone\"}";
		var small = new InventoryDomainCodec.Item(big.key(), big.count(), big.label(), smallJson, false);
		long encodedDifference = InventoryDomainCodec.encode(big).size() - InventoryDomainCodec.encode(small).size();
		long decodedDisplayRefund = 2L * (bigJson.length() - smallJson.length()); // UTF-16 code units, identical metadata
		assertTrue(encodedDifference > 0);
		var reads = new AtomicInteger();
		InventoryRuntime.DisplayReprojector port = (retained, displayBytes) ->
			new InventoryItemCodec.Display(retained.itemId(), retained.label(), smallJson, retained.aggregateRequired());
		java.util.function.Function<InventoryDomainCodec.Item, InventoryRuntime> fixture = value ->
			new InventoryRuntime((input, displayBytes) -> Optional.of(new Source(input, reads, 1) {
				@Override public InventoryDomainCodec.Item read(int slot) { reads.incrementAndGet(); assertEquals(0, slot); return value; }
			}), 16_000_000, port);
		try (var runtime = fixture.apply(big); var control = fixture.apply(small)) {
			runtime.advance(0, settings);
			control.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var peer = runtime.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var controlConsumer = control.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			var controlPeer = control.attach(input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(List.of(big), runtime.step(consumer).orElseThrow().slots());
			assertEquals(List.of(small), control.step(controlConsumer).orElseThrow().slots());
			long chargedWithPage = runtime.memory().retained();
			long controlWithPage = control.memory().retained();
			settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); runtime.advance(1, settings); control.advance(1, settings);
			var lowered = runtime.reprojectDisplays(consumer, 4096);
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, lowered.status());
			assertEquals(InventoryRuntime.ReprojectionStatus.COMPLETED, control.reprojectDisplays(controlConsumer, 4096).status());
			assertEquals(List.of(big.key()), lowered.changedKeys());
			assertTrue(lowered.foldIds().isEmpty(), "display tightening preserves the exact variant instead of folding it");
			long chargedAfterTightening = runtime.memory().retained();
			long controlAfterTightening = control.memory().retained();
			// Both rounds now have identical decoded items and fixed ownership. Only the original encoded displays differ.
			assertTrue(chargedAfterTightening - controlAfterTightening >= encodedDifference,
				"round.last still owns the larger encoded payload after its decoded display is replaced");
			assertEquals(decodedDisplayRefund, (chargedWithPage - controlWithPage) - (chargedAfterTightening - controlAfterTightening),
				"tightening refunds the decoded UTF-16 display difference independently of retained encoded ownership");
			assertEquals(List.of(small), runtime.step(peer).orElseThrow().slots(), "a cached peer sees the same exact key and count with the smaller display");
			assertEquals(List.of(small), control.step(controlPeer).orElseThrow().slots());
			assertTrue(runtime.step(consumer).orElseThrow().slots().isEmpty(), "tightening never rewinds the consumed cursor");
			assertEquals(2, reads.get(), "one original read per fixture; tightening and cached consumption never reread");
			assertEquals(0, runtime.memory().reserved());
			assertEquals(0, control.memory().reserved());
			peer.close(); controlPeer.close();
			assertTrue(runtime.memory().retained() - control.memory().retained() >= encodedDifference,
				"a leaving peer cannot release the encoded page still owned by the remaining consumer");
			consumer.close();
			controlConsumer.close();
			assertEquals(1024, runtime.memory().retained(), "releasing the round drops the retained payload with its ticket");
			assertEquals(runtime.memory().retained(), control.memory().retained(), "the encoded ownership difference ends on final consumer close");
			assertEquals(0, runtime.memory().reserved());
			assertEquals(0, control.memory().reserved());
		}
	}
	@Test void closingAConsumerWithADeferredTighteningPassLeavesNoWorkspaceAndRejectsLaterWork() {
		var settings = InventorySettings.serverDefaults();
		settings.setDisplayByteMultiplier(java.math.BigDecimal.valueOf(8)); // 32 KiB display scale
		var owner = new UUID(26, 26);
		int slots = 70;
		var bigJson = "{\"id\":\"minecraft:stone\",\"components\":{\"minecraft:custom_data\":{\"p\":\"" + "x".repeat(8000) + "\"}}}";
		var items = bigItems(slots, bigJson);
		InventoryRuntime.DisplayReprojector port = (retained, displayBytes) ->
			new InventoryItemCodec.Display(retained.itemId(), retained.label(), "{\"id\":\"" + retained.itemId() + "\"}", retained.aggregateRequired());
		try (var runtime = new InventoryRuntime((input, displayBytes) -> Optional.of(new Source(input, new AtomicInteger(), slots) {
			@Override public InventoryDomainCodec.Item read(int slot) { return items.get(slot); }
		}), 16_000_000, port)) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(input(owner), new InventoryRuntime.TrackingSubject(TargetKey.from(TARGET))).orElseThrow();
			int observed = 0;
			for (int call = 0; observed < slots && call < slots; call++) observed += runtime.step(consumer).orElseThrow().slots().size();
			assertEquals(slots, observed);
			settings.setDisplayByteMultiplier(java.math.BigDecimal.ONE); runtime.advance(1, settings);
			assertEquals(InventoryRuntime.ReprojectionStatus.DEFERRED, runtime.reprojectDisplays(consumer, 4096).status());
			consumer.close();
			assertEquals(1024, runtime.memory().retained(), "cancelling a deferred pass releases its round and pages, not the paid quota");
			assertEquals(0, runtime.memory().reserved());
			assertEquals(InventoryRuntime.ReprojectionStatus.UNCHANGED, runtime.reprojectDisplays(consumer, 4096).status());
			assertEquals(0, runtime.memory().reserved());
		}
	}
}
