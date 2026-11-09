package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Source-step attempts are independent of returned cells, READ intervals and whole-capture work. */
@NotNullByDefault
class InventoryMonitorReadAttemptsTest {

	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	static final UUID OWNER = new UUID(53, 1);
	static MonitorSample sample(InventoryRuntime runtime, long tick) {
		runtime.sampleMonitor(); return java.util.Objects.requireNonNull(runtime.monitor().completeTick(tick));
	}
	static InventoryRuntime runtime(InventorySourceAccess.Source source, InventorySettings settings, ServerInventoryMonitor monitor) {
		return new InventoryRuntime((input, cap) -> Optional.of(source), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
	}
	static InventoryRuntime.Consumer attach(InventoryRuntime runtime, boolean preview) {
		var input = InventoryRuntimeTest.input(OWNER);
		return runtime.attach(input, preview ? new InventoryRuntime.PreviewSubject(OWNER)
			: new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
	}
	@NotNullByDefault
	static class Reads extends InventoryRuntimeTest.Source {
		final boolean nativeMode;
		final List<Integer> trace = new ArrayList<>();
		Runnable callback = () -> {};
		int failAt = -1;
		Reads(boolean nativeMode) { this(nativeMode, InventoryRuntimeTest.input(OWNER)); }
		Reads(boolean nativeMode, InventorySourceInput input) { super(input, new AtomicInteger(), 6); this.nativeMode = nativeMode; }
		@Override public InventorySourceAccess.ReadMode readMode() {
			return nativeMode ? InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL : InventorySourceAccess.ReadMode.ADAPTER;
		}
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
			trace.add(slot); reads.incrementAndGet(); callback.run();
			if (slot == failAt) throw new IllegalStateException("getter failure");
			return null;
		}
	}
	@NotNullByDefault
	static final class Rotation implements AutoCloseable {
		final InventorySettings settings = InventorySettings.serverDefaults();
		final ServerInventoryMonitor monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		final InventorySourceInput peerInput = InventoryRuntimeTest.input(new UUID(53, 2));
		final Reads peer = new Reads(true, peerInput);
		final Reads inner = new Reads(false, InventoryRuntimeTest.input(new UUID(53, 3))) {
			@Override public boolean matchesSelection(int slot, InventorySelection selection) { reads.incrementAndGet(); innerWitness.run(); return true; }
		};
		final Reads outer = new Reads(false) {
			@Override public boolean matchesSelection(int slot, InventorySelection selection) { reads.incrementAndGet(); witness.run(); return true; }
		};
		Runnable witness = () -> {}, innerWitness = () -> {};
		final InventoryRuntime runtime;
		final InventoryRuntime.Consumer consumer;
		final CostLedger originalLedger;
		Rotation(boolean enabled) throws Exception {
			runtime = new InventoryRuntime((input, cap) -> Optional.of(input.readOwner().equals(OWNER) ? outer
				: input.readOwner().equals(inner.input.readOwner()) ? inner : peer), settings.pendingMemoryBytes(),
				InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor);
			monitor.configure(enabled, false); runtime.advance(0, settings);
			consumer = runtime.attach(peerInput, new InventoryRuntime.TrackingSubject(TargetKey.from(peerInput.target()))).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer)); originalLedger = InventoryRuntimeTest.itemLedger(runtime);
		}
		void rotate(boolean readPeer) {
			runtime.advance(1, settings);
			if (readPeer) assertEquals(2, runtime.step(consumer, 2).orElseThrow().slots().size());
		}
		boolean select() { return select(outer); }
		boolean select(Reads source) { return runtime.selectionPresent(source.input, new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger"), 0); }
		void assertAttempts(boolean enabled, long expected) {
			runtime.sampleMonitor(); @Nullable MonitorSample sampled = monitor.completeTick(1);
			if (enabled) { assertNotNull(sampled); assertEquals(expected, sampled.shared().counter(Counter.READ_ATTEMPTS).value()); }
			else assertNull(sampled);
		}
		@Override public void close() { runtime.close(); }
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void selectionRotationCountsOldWitnessAndNewPeerLedgerExactlyOnce(boolean enabled) throws Exception {
		try (var f = new Rotation(enabled)) {
			f.witness = () -> f.rotate(true); assertTrue(f.select());
			assertEquals(1, f.originalLedger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(2, InventoryRuntimeTest.itemLedger(f.runtime).used(InventorySourceAccess.PHYSICAL));
			assertEquals(List.of(0, 1), f.peer.trace); assertEquals(1, f.outer.reads.get()); f.assertAttempts(enabled, 3);
		}
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void priorLedgerSpendCannotMakeRotationMonitoringNegativeOrChangeSelection(boolean enabled) throws Exception {
		try (var f = new Rotation(enabled)) {
			assertEquals(1, f.runtime.step(f.consumer, 1).orElseThrow().slots().size());
			f.witness = () -> f.rotate(true); assertTrue(f.select());
			assertEquals(2, f.originalLedger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(2, InventoryRuntimeTest.itemLedger(f.runtime).used(InventorySourceAccess.PHYSICAL));
			assertEquals(List.of(0, 1, 2), f.peer.trace); f.assertAttempts(enabled, 4);
		}
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void rotationWithoutPeerStillCountsTheWitnessChargedToTheOldLedger(boolean enabled) throws Exception {
		try (var f = new Rotation(enabled)) {
			f.witness = () -> f.rotate(false); assertTrue(f.select());
			assertEquals(1, f.originalLedger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(0, InventoryRuntimeTest.itemLedger(f.runtime).used(InventorySourceAccess.PHYSICAL));
			assertTrue(f.peer.trace.isEmpty()); f.assertAttempts(enabled, 1);
		}
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void rotationMustPreserveTheOriginalFatalWitnessObject(boolean enabled) throws Exception {
		try (var f = new Rotation(enabled)) {
			assertEquals(1, f.runtime.step(f.consumer, 1).orElseThrow().slots().size());
			var original = new AssertionError("fatal witness after ledger rotation");
			f.witness = () -> { f.rotate(true); throw original; };
			assertSame(original, assertThrows(AssertionError.class, f::select));
			assertEquals(2, f.originalLedger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(2, InventoryRuntimeTest.itemLedger(f.runtime).used(InventorySourceAccess.PHYSICAL));
			assertEquals(List.of(0, 1, 2), f.peer.trace); f.assertAttempts(enabled, 4);
		}
	}
	@Test void nestedSelectionScopesDistinguishThreeLiveLedgerGenerations() throws Exception {
		try (var f = new Rotation(true)) {
			@Nullable CostLedger[] middle = {null};
			f.witness = () -> {
				f.runtime.advance(1, f.settings);
				try { middle[0] = InventoryRuntimeTest.itemLedger(f.runtime); } catch (Exception failure) { throw new AssertionError(failure); }
				assertTrue(f.select(f.inner));
			};
			f.innerWitness = () -> { f.runtime.advance(2, f.settings); assertEquals(2, f.runtime.step(f.consumer, 2).orElseThrow().slots().size()); };
			assertTrue(f.select());
			assertEquals(1, f.originalLedger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(1, java.util.Objects.requireNonNull(middle[0]).used(InventorySourceAccess.PHYSICAL));
			assertEquals(2, InventoryRuntimeTest.itemLedger(f.runtime).used(InventorySourceAccess.PHYSICAL));
			f.assertAttempts(true, 4); assertNull(InventoryBackendTest.field(f.runtime, "selectionReadScope"));
		}
	}
	@Test void nestedSelectionDuringAnUnsettledNativeStepDoesNotConsumeItsFutureCredit() throws Exception {
		try (var f = new Rotation(true)) {
			f.peer.callback = () -> { f.peer.callback = () -> {}; assertTrue(f.select(f.inner)); };
			f.witness = () -> assertEquals(2, f.runtime.step(f.consumer, 2).orElseThrow().slots().size());
			assertTrue(f.select()); f.assertAttempts(true, 4);
			assertEquals(4, f.originalLedger.used(InventorySourceAccess.PHYSICAL)); assertEquals(List.of(0, 1), f.peer.trace);
			assertNull(InventoryBackendTest.field(f.runtime, "selectionReadScope"));
		}
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void failingValidationCloseCannotLoseItsSettledWitnessOrChangeTheBusinessResult(boolean enabled) throws Exception {
		var closed = new AtomicInteger();
		var source = new Reads(false) {
			@Override public boolean matchesSelection(int slot, InventorySelection selection, @Nullable InventoryCatalogAdmission catalog,
				InventoryValidationBudget.Allowance allowance) {
				reads.incrementAndGet();
				// Deliberately violate the callback's genuine grant: Runtime.close detects the extra outer debit after the witness settles.
				var unbacked = new InventoryValidationBudget.Cost(0, 0, 1); allowance.admitAdditional(unbacked); allowance.debit(unbacked);
				return true;
			}
			@Override public void close() { closed.incrementAndGet(); }
		};
		var settings = InventorySettings.serverDefaults(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(enabled, false); runtime.advance(0, settings);
			assertFalse(runtime.selectionPresent(source.input, new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger"), 0));
			assertEquals(1, source.reads.get()); assertEquals(1, closed.get());
			assertEquals(1, InventoryRuntimeTest.itemLedger(runtime).used(InventorySourceAccess.PHYSICAL));
			if (enabled) assertEquals(1, sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS).value());
			assertNull(InventoryBackendTest.field(runtime, "selectionReadScope")); assertEquals(0, runtime.memory().reserved());
		}
	}
	@ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
	void enableMasksObserveGlobalReadsFromEitherConsumerWithoutChangingBusiness(boolean preview, boolean tracking) {
		for (boolean nativeMode : List.of(false, true)) for (boolean previewConsumer : List.of(false, true)) {
			var source = new Reads(nativeMode); var settings = InventorySettings.serverDefaults(); var clockReads = new AtomicInteger();
			var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 4), () -> { clockReads.incrementAndGet(); return 0; });
			try (var runtime = runtime(source, settings, monitor)) {
				monitor.configure(preview, tracking); runtime.advance(0, settings); var consumer = attach(runtime, previewConsumer);
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
				var result = runtime.step(consumer, 3).orElseThrow(); assertEquals(3, result.slots().size());
				assertTrue(result.slots().stream().allMatch(java.util.Objects::isNull));
				assertEquals(List.of(0, 1, 2), source.trace);
				runtime.sampleMonitor(); @Nullable MonitorSample sampled = monitor.completeTick(0);
				if (preview || tracking) {
					assertNotNull(sampled); assertEquals(3, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
					assertEquals(3, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used());
				} else { assertNull(sampled); assertEquals(0, clockReads.get()); }
			}
		}
	}
	@ParameterizedTest @CsvSource({"true,0,1", "true,5,6", "false,0,1", "false,5,6"})
	void failedIndexedGetterCountsEvenWhenReturnedObservationIsEmpty(boolean nativeMode, int failAt, long expected) {
		var source = new Reads(nativeMode); source.failAt = failAt;
		var settings = InventorySettings.serverDefaults(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, true); runtime.advance(0, settings); var consumer = attach(runtime, true); runtime.prepare(consumer);
			if (failAt == 5) assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			assertTrue(runtime.step(consumer, 3).orElseThrow().slots().isEmpty());
			assertEquals(expected, source.trace.size());
			var sampled = sample(runtime, 0); assertEquals(expected, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(expected, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used());
		}
	}
	@ParameterizedTest @CsvSource({"1,1", "2,2", "3,3", "0,64"})
	void cursorlessFailureUsesItsKnownPrefixOrTheExistingConservativeGrant(int knownPrefix, long expected) {
		var source = new Reads(false) {
			@Override public boolean stableCursor() { return false; }
			@Override public InventorySourceAccess.ResourcePage enumerateResources(int limit, @Nullable InventoryCatalogAdmission catalog,
				InventoryValidationBudget.Allowance allowance) {
				assertEquals(64, limit); reads.addAndGet(2);
				if (knownPrefix > 0) throw new InventorySourceAccess.CursorlessReadFailure(knownPrefix, new IllegalStateException("known failed visit"));
				throw new IllegalStateException("opaque provider traversal");
			}
		};
		var settings = InventorySettings.serverDefaults(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings); var consumer = attach(runtime, true); runtime.prepare(consumer);
			assertTrue(runtime.step(consumer, 64).orElseThrow().slots().isEmpty()); assertEquals(2, source.reads.get());
			var sampled = sample(runtime, 0); assertEquals(expected, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(expected, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used());
		}
	}
	@ParameterizedTest @CsvSource({"true,false,1", "false,true,1", "false,false,6"})
	void failedSelectionWitnessCountsItsExistingAttemptedChargeDespiteReturningFalse(boolean indexed, boolean knownPrefix, long expected) {
		var source = new Reads(false) {
			@Override public boolean stableCursor() { return indexed; }
			@Override public boolean matchesSelection(int slot, InventorySelection selection) { reads.incrementAndGet(); throw new IllegalStateException("failed witness"); }
			@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection selection) {
				assertEquals(6, limit); reads.incrementAndGet();
				if (knownPrefix) throw new InventorySourceAccess.CursorlessReadFailure(1, new IllegalStateException("failed witness prefix"));
				throw new IllegalStateException("opaque witness");
			}
		};
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(6));
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings);
			assertFalse(runtime.selectionPresent(source.input, new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger"), 0));
			assertEquals(1, source.reads.get()); var sampled = sample(runtime, 0);
			assertEquals(expected, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(expected, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used());
		}
	}
	@Test void wholeCaptureAndValidationAreExcludedAndOnlyLaterDetachedDecodeCounts() {
		var source = new InventoryRuntimeTest.DiscoverySource(InventoryRuntimeTest.input(OWNER)) {
			@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return validStep; }
		};
		var settings = InventorySettings.serverDefaults(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings); var consumer = attach(runtime, true);
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			var captured = sample(runtime, 0); assertEquals(0, captured.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(1, captured.shared().budget(Budget.CAPTURE_READS).used()); assertEquals(0, captured.shared().budget(Budget.PHYSICAL_SLOTS).used());
			runtime.advance(1, settings); assertEquals(7, runtime.step(consumer, 1).orElseThrow().slots().getFirst().count());
			var decoded = sample(runtime, 1); assertEquals(1, decoded.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(0, decoded.shared().budget(Budget.CAPTURE_READS).used()); assertEquals(1, source.captures); assertEquals(0, source.reads.get());
		}
	}
	@ParameterizedTest @CsvSource({"true,false", "false,false", "true,true", "false,true"})
	void callbackCollectionChangeCannotImportAnInflightReadIntoTheNewGeneration(boolean initiallyEnabled, boolean nativeMode) {
		var source = new Reads(nativeMode); var settings = InventorySettings.serverDefaults();
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(initiallyEnabled, false); runtime.advance(0, settings); var consumer = attach(runtime, true); runtime.prepare(consumer);
			source.callback = () -> { source.callback = () -> {}; monitor.configure(false, false); monitor.configure(true, false); };
			assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			assertEquals(0, sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS).value(), "the prior operation is not replayed after a generation transition");
			runtime.advance(1, settings); assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			assertEquals(3, sample(runtime, 1).shared().counter(Counter.READ_ATTEMPTS).value()); assertEquals(List.of(0, 1, 2, 3, 4, 5), source.trace);
		}
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void collectionChangeDuringStepPreparationFencesTheEntireOperation(boolean nativeMode) {
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		var source = new Reads(nativeMode) {
			boolean changed;
			@Override public boolean valid() {
				if (!changed) { changed = true; monitor.configure(false, false); monitor.configure(true, false); }
				return super.valid();
			}
		};
		var settings = InventorySettings.serverDefaults();
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings); var consumer = attach(runtime, true);
			assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			assertEquals(0, sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS).value());
			runtime.advance(1, settings); assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			assertEquals(3, sample(runtime, 1).shared().counter(Counter.READ_ATTEMPTS).value());
		}
	}
	@Test void selectionCallbackCannotImportItsFailedAttemptIntoAReplacementGeneration() {
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		var source = new Reads(false) {
			@Override public boolean matchesSelection(int slot, InventorySelection selection) {
				reads.incrementAndGet(); monitor.configure(false, false); monitor.configure(true, false);
				throw new IllegalStateException("failed after monitor replacement");
			}
		};
		var settings = InventorySettings.serverDefaults();
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings);
			assertFalse(runtime.selectionPresent(source.input, new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger"), 0));
			var sampled = sample(runtime, 0); assertEquals(0, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(1, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used()); assertEquals(1, source.reads.get());
		}
	}
	@Test void enablingAfterPaidWorkStartsKnownZeroAndDoesNotImportTheCurrentLedger() {
		var source = new Reads(false); var settings = InventorySettings.serverDefaults();
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			runtime.advance(0, settings); var consumer = attach(runtime, true); runtime.step(consumer, 3).orElseThrow();
			monitor.configure(true, false); var enabled = sample(runtime, 0);
			assertEquals(0, enabled.shared().counter(Counter.READ_ATTEMPTS).value()); assertEquals(3, enabled.shared().budget(Budget.PHYSICAL_SLOTS).used());
			runtime.advance(1, settings); assertEquals(0, sample(runtime, 1).shared().counter(Counter.READ_ATTEMPTS).value());
		}
	}
	@Test void uninitializedRuntimeCannotClaimAnObservedReadZero() {
		var source = new Reads(false); var settings = InventorySettings.serverDefaults();
		var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false);
			assertNull(sample(runtime, 0).shared().counter(Counter.READ_ATTEMPTS));
			runtime.advance(1, settings); assertEquals(0, sample(runtime, 1).shared().counter(Counter.READ_ATTEMPTS).value());
		}
	}
	@Test void selectionPhysicalDeltaDoesNotCountAReentrantSourceStepTwice() {
		@Nullable InventoryRuntime[] runtimeRef = {null};
		@Nullable InventoryRuntime.Consumer[] consumerRef = {null};
		var source = new Reads(false) {
			@Override public boolean matchesSelection(int slot, InventorySelection selection) {
				reads.incrementAndGet();
				assertEquals(2, java.util.Objects.requireNonNull(runtimeRef[0]).step(java.util.Objects.requireNonNull(consumerRef[0]), 2).orElseThrow().slots().size());
				return selection.aggregate();
			}
		};
		var peerInput = InventoryRuntimeTest.input(new UUID(53, 2)); var peer = new Reads(false, peerInput);
		var settings = InventorySettings.serverDefaults(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = new InventoryRuntime((input, cap) -> Optional.of(input.readOwner().equals(OWNER) ? source : peer), settings.pendingMemoryBytes(),
			InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, false, monitor)) {
			runtimeRef[0] = runtime; monitor.configure(true, false); runtime.advance(0, settings);
			consumerRef[0] = runtime.attach(peerInput, new InventoryRuntime.PreviewSubject(peerInput.readOwner())).orElseThrow();
			runtime.prepare(java.util.Objects.requireNonNull(consumerRef[0]));
			assertTrue(runtime.selectionPresent(source.input, new InventorySelection(null, "minecraft:stone", "stone", null, true, "danger"), 0));
			var sampled = sample(runtime, 0); assertEquals(3, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(3, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used()); assertEquals(1, source.reads.get());
			assertEquals(List.of(0, 1), peer.trace); assertTrue(source.trace.isEmpty(), "aggregate witness adds a charge without a content getter");
		}
	}
	@ParameterizedTest @CsvSource({"0,1", "5,6"})
	void detachedDecodeFailureKeepsAttemptsWithoutAddingWholeCaptureReads(int failAt, long expected) {
		int failingSlot = failAt;
		var decoded = new ArrayList<Integer>(); var captures = new AtomicInteger();
		var source = new Reads(false) {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() {
				return Optional.of(new InventorySourceAccess.SnapshotPlan() {
					int attempted;
					@Override public long memoryUpperBoundBytes() { return 4096; }
					@Override public int physicalReadUpperBound() { return 6; }
					@Override public int attemptedPhysicalReads() { return attempted; }
					@Override public long itemWorkUpperBoundUnits() { return 6L * InventoryItemBudget.workUpperBound(displayBytes()); }
					@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
						captures.incrementAndGet(); attempted += 6;
						return Optional.of(new InventoryRuntimeTest.Snapshot() {
							@Override public int slots() { return 6; }
							@Override public long retainedBytes() { return 256; }
							@Override public InventoryDomainCodec.@Nullable Item read(int slot) {
								decoded.add(slot); if (slot == failingSlot) throw new IllegalStateException("detached decode failure"); return null;
							}
						});
					}
				});
			}
		};
		var settings = InventorySettings.serverDefaults(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), () -> 0);
		try (var runtime = runtime(source, settings, monitor)) {
			monitor.configure(true, false); runtime.advance(0, settings); var consumer = attach(runtime, true);
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer)); assertEquals(1, captures.get());
			if (failAt == 5) assertEquals(3, runtime.step(consumer, 3).orElseThrow().slots().size());
			var failure = runtime.step(consumer, 3).orElseThrow();
			assertTrue(failure.slots().isEmpty(), () -> "decode=" + decoded + " capture=" + captures + " result=" + failure);
			var sampled = sample(runtime, 0); assertEquals(expected, sampled.shared().counter(Counter.READ_ATTEMPTS).value());
			assertEquals(expected, sampled.shared().budget(Budget.PHYSICAL_SLOTS).used()); assertEquals(6, sampled.shared().budget(Budget.CAPTURE_READS).used());
			assertEquals(expected, decoded.size()); assertEquals(1, captures.get()); assertEquals(0, source.reads.get());
		}
	}
}
