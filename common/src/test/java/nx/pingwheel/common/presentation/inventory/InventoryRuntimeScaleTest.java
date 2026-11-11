package nx.pingwheel.common.presentation.inventory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryRuntimeScaleTest {
	@Test void nativeJournalAndReplayFitThePublicWorkGateAndOneShortRefusesBeforeCallbacks() throws Exception {
		for (boolean exact : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(16);
			var input = InventoryRuntimeTest.input(new UUID(75, 95));
			var reads = new AtomicInteger(); var checks = new AtomicInteger();
			int display = (int) settings.effectiveDisplayBytes();
			var source = new InventoryRuntimeTest.Source(input, reads, 1) {
				@Override public int displayBytes() { return display; }
				@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
				@Override public boolean valid() { checks.incrementAndGet(); return true; }
				@Override public @org.jetbrains.annotations.Nullable InventoryResourceValue readResource(int slot,
					@org.jetbrains.annotations.Nullable nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission admission,
					nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) {
					reads.incrementAndGet(); return null;
				}
			};
			try (var runtime = new InventoryRuntime(ignored -> Optional.of(source), settings.pendingMemoryBytes());
				var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
				nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
					nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
				runtime.advance(0, settings);
				var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
				checks.set(0);
				var budget = runtime.stepBudget(consumer, 1);
				// First batch: 4096 + 8192 + 8 * (472-byte chunk + 152-byte definition).
				// One-cell replay: 4096 + 2048. Empty content still needs this pre-read envelope.
				assertEquals(InventoryItemBudget.workUpperBound(display, 0) + 17280L + 6144L, budget.itemWork());
				assertEquals(0, checks.get()); assertEquals(0, reads.get(), "budget metadata performs no source callbacks");
				var ledger = InventoryRuntimeTest.itemLedger(runtime); var work = InventoryRuntimeTest.itemWorkCounter();
				long leave = budget.itemWork() - (exact ? 0 : 1);
				assertTrue(runtime.claimDetachedItemWork(ledger.remaining(work) - leave));
				long used = ledger.used(work);
				var protection = runtime.reserveOperation(budget);
				if (exact) {
					assertTrue(protection.accepted());
					try (var grant = java.util.Objects.requireNonNull(protection.value())) {
						var result = grant.run(() -> runtime.step(consumer, 1)).orElseThrow();
						assertEquals(CaptureResult.Completeness.COMPLETE, result.result().completeness());
						assertEquals(1, result.slots().size()); assertNull(result.slots().getFirst());
					}
					assertEquals(1, reads.get()); assertTrue(checks.get() > 0);
					assertEquals(used + 17280L + InventoryItemBudget.freezeWorkUpperBound(display) + 6144L + 5L * 4096L,
						ledger.used(work), "batch, attempted freeze, replay and initial paid-subject bookkeeping stay charged");
				} else {
					assertTrue(protection.deferred()); assertEquals(InventoryFailureReason.ITEM_WORK_LIMIT, protection.reason());
					for (int repeat = 0; repeat < 2; repeat++) {
						assertTrue(runtime.step(consumer, 1).isEmpty());
						assertEquals(InventoryFailureReason.ITEM_WORK_LIMIT, consumer.currentReason());
					}
					assertEquals(0, checks.get()); assertEquals(0, reads.get());
					assertEquals(used, ledger.used(work), "callback-free refusal neither charges a batch nor refunds earlier work");
					assertEquals(2, logs.events().stream().filter(event -> event.getMessage().getFormattedMessage().contains("stage=native-read-work")).count());
					assertTrue(logs.messages().contains("requested=" + budget.itemWork()));
					assertFalse(logs.messages().contains("reason=provider-work-limit"));
				}
				assertEquals(0, runtime.memory().reserved());
			}
		}
	}
	@Test void workOnlySnapshotPlanBoundReportsWorkUnitsRatherThanReadCount() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(75, 94);
		long workLimit = 4096L * InventoryItemBudget.workUpperBound((int) settings.effectiveDisplayBytes());
		try (var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return 1024; }
				@Override public int physicalReadUpperBound() { return 1; }
				@Override public long itemWorkUpperBoundUnits() { return workLimit + 1; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { throw new AssertionError("unadmitted capture"); }
			}); }
		}), settings.pendingMemoryBytes()); var logs = new InventoryDiagnosticsTestCapture(org.apache.logging.log4j.Level.DEBUG)) {
			nx.pingwheel.common.diagnostics.Diagnostics.setProfile(nx.pingwheel.common.diagnostics.DiagnosticSide.SERVER,
				nx.pingwheel.common.diagnostics.LoggingProfile.DEBUG);
			runtime.advance(0, settings); var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, runtime.prepare(consumer));
			assertEquals(InventoryFailureReason.ITEM_WORK_LIMIT, consumer.currentReason());
			String message = logs.messages(); assertTrue(message.contains("stage=snapshot-plan-work"));
			assertTrue(message.contains("requested=" + (workLimit + 1))); assertTrue(message.contains("limit=" + workLimit));
			assertFalse(message.contains("stage=snapshot-plan-reads")); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void preparationKeepsExactItemWorkReasonWhenTheMonitorUsesItsCoarseWorkBucket() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(75, 91);
		var resolver = new InventoryRuntime.SourceResolver() {
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted resolution"); }
			@Override public nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost resolutionProbeCost(InventorySourceInput input) {
				return new nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Cost(0, 0, 1);
			}
		};
		try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertTrue(runtime.claimDetachedItemWork(4096L * InventoryItemBudget.workUpperBound((int) settings.effectiveDisplayBytes())));
			var result = runtime.prepareDecision(consumer);
			assertTrue(result.deferred()); assertEquals(InventoryFailureReason.ITEM_WORK_LIMIT, result.reason());
			assertEquals(InventoryFailureReason.ITEM_WORK_LIMIT, consumer.currentReason());
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void catalogRefusalPreservesItsKnownWorkspaceOrWorkReasonThroughPreflight() {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			for (boolean workspace : new boolean[] {true, false}) {
				var admission = new nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission(
					workspace ? 0 : Long.MAX_VALUE, workspace ? Long.MAX_VALUE : 0);
				var decision = runtime.preflightDecision(() -> {
					admission.admitFluid(0, 15, 5, 0, 0); return Optional.of(true);
				});
				assertTrue(decision.deferred());
				assertEquals(workspace ? InventoryFailureReason.MEMORY_LIMIT : InventoryFailureReason.ITEM_WORK_LIMIT, decision.reason());
			}
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void selectionAndNativeDeliveryFitSixteenMiBAtMinimumAndMaximumDisplay() {
		for (String scale : new String[] {"0.25", "32"}) {
			var settings = InventorySettings.serverDefaults(); settings.setPendingMemoryMiB(16);
			settings.setDisplayByteMultiplier(new java.math.BigDecimal(scale));
			settings.setFrameByteMultiplier(new java.math.BigDecimal("16")); settings.validate();
			assertEquals(scale.equals("32") ? 131072 : 1024, settings.effectiveDisplayBytes());
			assertEquals(524288, settings.effectiveFrameBytes());
			var owner = new UUID(75, 1); var reads = new AtomicInteger();
			try (var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 1) {
				@Override public boolean matchesSelection(int slot, InventorySelection selection) { reads.incrementAndGet(); return true; }
				@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
			}), 16L * 1024 * 1024)) {
				runtime.advance(0, settings);
				assertTrue(runtime.selectionPresent(InventoryRuntimeTest.input(owner),
					new InventorySelection(InventoryRuntimeTest.item(1).key(), "minecraft:stone", "stone", null, false, "danger"), 0));
				var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
				var observation = runtime.step(consumer, 1).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, observation.result().completeness()); assertEquals(1, observation.slots().size());
				assertTrue(reads.get() >= 2); consumer.close(); assertEquals(0, runtime.memory().reserved());
			}
		}
	}

	@Test void explicitAbsentLaneDiffersFromUnavailableAndFailedProvider() {
		var owner = new UUID(75, 2); var settings = InventorySettings.serverDefaults();
		for (RuntimeException failure : new RuntimeException[] {new nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException(),
			new nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnavailableException("lost source"), new IllegalStateException("provider")}) {
			try (var runtime = new InventoryRuntime(input -> { throw failure; }, settings.pendingMemoryBytes())) {
				runtime.advance(0, settings);
				var decision = runtime.probeDecision(InventoryRuntimeTest.input(owner));
				if (failure instanceof nx.pingwheel.common.presentation.inventory.minecraft.InventorySourceUnsupportedException) assertTrue(decision.unsupported());
				else { assertTrue(decision.rejected()); assertFalse(decision.unsupported()); }
			}
		}
	}
	@Test void oneShotCapPlusOneIsTerminalBeforeCaptureEvenWhenItsMemoryWouldNeverFit() {
		var settings = InventorySettings.serverDefaults(); var owner = new UUID(73, 2); var captures = new AtomicInteger();
		try (var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, new AtomicInteger(), 1) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					@Override public long memoryUpperBoundBytes() { return Long.MAX_VALUE; }
					@Override public int physicalReadUpperBound() { return 4097; }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { captures.incrementAndGet(); throw new AssertionError("oversized capture"); }
				});
			}
		}), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, runtime.prepare(consumer));
			assertEquals(InventorySourceAccess.Preparation.INCOMPLETE, runtime.prepare(consumer));
			assertEquals(0, captures.get()); assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test void oneThousandConsumersRemainIndependentAndCapPlusOneNamesTheCapacity() {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(input -> { throw new AssertionError("no resolution on attach"); }, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			for (int index = 0; index < 1024; index++) {
				var owner = new UUID(74, index);
				assertTrue(runtime.attachDecision(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).accepted());
			}
			var owner = new UUID(74, 1024);
			assertEquals(InventoryFailureReason.CONSUMER_CAPACITY, runtime.attachDecision(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).reason());
			runtime.close(); assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void sixteenThousandAddressesFinishAcrossTicksWithoutIncreasingTickWork() throws Exception {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(64));
		settings.setPendingMemoryMiB(16); settings.getPreview().setMaxSlotsPerClient(IntLimit.finite(16384));
		settings.getPreview().setMaxSlotsServer(IntLimit.finite(16384));
		var owner = new UUID(73, 1); var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime(input -> Optional.of(new InventoryRuntimeTest.Source(input, reads, 16384) {
			@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
			@Override public @org.jetbrains.annotations.Nullable InventoryResourceValue readResource(int slot,
				@org.jetbrains.annotations.Nullable nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission admission,
				nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget.Allowance allowance) {
				reads.incrementAndGet(); return null;
			}
		}), 16L * 1024 * 1024)) {
			runtime.advance(0, settings);
			var consumer = runtime.attachDecision(InventoryRuntimeTest.input(owner), new InventoryRuntime.PreviewSubject(owner)).value();
			assertNotNull(consumer);
			var ledger = InventoryRuntimeTest.itemLedger(runtime);
			assertEquals(4096L * InventoryItemBudget.workUpperBound((int) settings.effectiveDisplayBytes()), ledger.limit(InventoryRuntimeTest.itemWorkCounter()));
			assertEquals(4096, ledger.limit(new CostLedger.Counter("inventory/capture_reads", CostLedger.Unit.WORK)));
			int consumed = 0;
			for (int tick = 0; tick < 512 && consumed < 16384; tick++) {
				runtime.advance(tick, settings);
				var result = runtime.step(consumer, 64);
				if (result.isPresent()) {
					consumed += result.get().slots().size();
					if (result.get().result().completeness() == CaptureResult.Completeness.COMPLETE) assertEquals(16384, consumed);
				}
				assertTrue(InventoryRuntimeTest.itemLedger(runtime).used(InventorySourceAccess.PHYSICAL) <= 64);
			}
			assertEquals(16384, consumed); assertEquals(16384, reads.get());
			assertEquals(16384, consumer.progress().consumedSlots());
			consumer.close(); assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test void atomicLedgerDecisionRetainsTheFailingCounterAndNothingElse() {
		var first = new CostLedger.Counter("first", CostLedger.Unit.WORK);
		var second = new CostLedger.Counter("second", CostLedger.Unit.WORK);
		var ledger = new CostLedger(Map.of(first, 10L, second, 1L));
		var request = new java.util.LinkedHashMap<CostLedger.Counter, Long>(); request.put(first, 10L); request.put(second, 2L);
		var decision = ledger.tryReserveDecision(request);
		assertFalse(decision.accepted()); assertEquals(second, decision.limitingCounter());
		assertEquals(10, ledger.remaining(first)); assertEquals(1, ledger.remaining(second));
	}

	@Test void operationProtectionIsSingleUseAndUnusedCapacityIsReleasedWithoutRefundingAttempts() throws Exception {
		var settings = InventorySettings.serverDefaults(); var calls = new AtomicInteger();
		try (var runtime = new InventoryRuntime(input -> { throw new AssertionError("no source lookup"); }, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var grant = runtime.reserveOperation(runtime.preflightBudget()).value(); assertNotNull(grant);
			var ledger = InventoryRuntimeTest.itemLedger(runtime);
			assertEquals(255, ledger.remaining(InventorySourceAccess.PROBES));
			assertTrue(grant.run(() -> runtime.preflightDecision(() -> { calls.incrementAndGet(); return Optional.of(true); })).accepted());
			assertEquals(1, calls.get()); assertEquals(1, ledger.used(InventorySourceAccess.PROBES));
			assertThrows(IllegalStateException.class, () -> grant.run(() -> true)); grant.close();
			assertEquals(255, ledger.remaining(InventorySourceAccess.PROBES)); assertEquals(0, runtime.memory().reserved());
			var unused = runtime.reserveOperation(runtime.preflightBudget()).value(); assertNotNull(unused); unused.close();
			assertEquals(255, ledger.remaining(InventorySourceAccess.PROBES));
		}
	}
}
