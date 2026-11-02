package nx.pingwheel.common.presentation.inventory;

import java.util.Optional;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFluidRuntimeTest {
	private static InventorySourceInput input(UUID owner, BlockFace face) {
		return new InventorySourceInput(InventoryRuntimeTest.TARGET, owner, face, InventoryResourceKind.FLUID);
	}
	@NotNullByDefault
	private static final class Source implements InventorySourceAccess.Source {
		final InventorySourceInput input;
		final AtomicInteger reads;
		final int slots;
		@Nullable InventoryFluidCodec.Catalog catalog;
		boolean valid = true, unique;
		Source(InventorySourceInput input, AtomicInteger reads, int slots) { this.input = input; this.reads = reads; this.slots = slots; }
		@Override public SourceKey key() { return new SourceKey("test", "fluid", "native-root", input.viewKey()); }
		@Override public boolean valid() { return valid; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return slots; }
		@Override public InventorySourceAccess.ReadMode readMode() { return InventorySourceAccess.ReadMode.NATIVE_INCREMENTAL; }
		@Override public boolean certifiedResourceSharing() { return false; }
		@Override public InventorySharedRead.@Nullable Port sharedRead() { throw new AssertionError("fluid must not request an ITEM sharing certificate"); }
		@Override public long nativeResourceRetainedBytes() { return catalog == null ? 0 : catalog.modeledRetainedBytes(); }
		@Override public long nativeCatalogGrowthUpperBound() { return InventoryFluidCodec.Catalog.retainedGrowthUpperBound(); }
		@Override public InventoryReprojectionSummary reprojectionSummary(int cap) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("fluid read passed through Item codec"); }
		@Override public @Nullable InventoryResourceValue readResource(int slot, @Nullable InventoryCatalogAdmission admission, InventoryValidationBudget.Allowance allowance) {
			reads.incrementAndGet(); if (catalog == null) catalog = new InventoryFluidCodec.Catalog();
			byte @Unmodifiable [] canonical = unique ? new byte[] {(byte) slot, (byte) (slot >> 8)} : new byte[] {1, 2, 3};
			return catalog.intern("minecraft:water", 9007199254740993L, InventoryFluidCodec.Identity.exact(canonical),
				new InventoryFluidValue.Display("minecraft:water", "water", null), java.util.Objects.requireNonNull(admission));
		}
		@Override public void releaseReadData() { catalog = null; }
		@Override public void close() { catalog = null; }
	}
	@Test void nativeFluidUsesOneJournalAndGlobalLedgerButNeverPoolOrItemCodec() {
		var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(1));
		var reads = new AtomicInteger();
		var runtime = new InventoryRuntime((request, cap) -> Optional.of(new Source(request, reads, 2)), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(),
			nx.pingwheel.common.presentation.budget.BudgetDelaySink.NONE, true);
		try (runtime) {
			runtime.advance(0, settings); var input = input(new UUID(17, 1), BlockFace.NORTH);
			var preview = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			var first = runtime.step(preview).orElseThrow(); assertEquals(1, reads.get());
			assertEquals(InventoryStep.SweepEvidence.STABLE_SLOT_SWEEP, first.inventorySweepEvidence());
			assertEquals(9007199254740993L, first.slots().getFirst().amount());
			assertEquals(CaptureResult.Completeness.CONTINUE, first.result().completeness());
			runtime.advance(1, settings); var second = runtime.step(preview).orElseThrow();
			assertEquals(CaptureResult.Completeness.COMPLETE, second.result().completeness());
			preview.retainCompleted();
			var tracking = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
			runtime.advance(2, settings); runtime.step(tracking).orElseThrow(); assertEquals(3, reads.get(), "selection begins a fresh physical fluid sweep");
			assertTrue(runtime.memory().retained() > 0); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void faceAndOwnerStayExactEvenWithAllItemPoolPartitionsDisabled() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setDistinguishFaces(false); settings.getTracking().setDistinguishPlayers(false);
		var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime((request, cap) -> Optional.of(new Source(request, reads, 1)), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(),
			nx.pingwheel.common.presentation.budget.BudgetDelaySink.NONE, true)) {
			runtime.advance(0, settings);
			for (var input : java.util.List.of(input(new UUID(18, 1), BlockFace.NORTH), input(new UUID(18, 1), BlockFace.SOUTH), input(new UUID(18, 2), BlockFace.NORTH))) {
				var consumer = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
				assertEquals(CaptureResult.Completeness.COMPLETE, runtime.step(consumer).orElseThrow().result().completeness());
			}
			assertEquals(3, reads.get());
		}
	}
	@Test void tankLogicalSlotQuotaSpansPhysicalMembersWithoutReplacingTheirOwnerOrFace() {
		var settings = InventorySettings.serverDefaults(); settings.getTracking().setMaxSlotsPerTarget(IntLimit.finite(1));
		settings.getTracking().setDistinguishFaces(false); settings.getTracking().setDistinguishPlayers(false);
		var key = new TargetKey.CreateTankKey("minecraft:overworld", 0, 64, 0, "create:fluid_tank");
		var first = new InventorySourceInput(new nx.pingwheel.common.domain.Target.BlockTarget("minecraft:overworld", 1, 64, 0, "create:fluid_tank"),
			new UUID(18, 1), BlockFace.NORTH, InventoryResourceKind.FLUID);
		var second = new InventorySourceInput(new nx.pingwheel.common.domain.Target.BlockTarget("minecraft:overworld", 1, 65, 0, "create:fluid_tank"),
			new UUID(18, 2), BlockFace.SOUTH, InventoryResourceKind.FLUID);
		var opened = new ArrayList<InventorySourceInput>(); var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime((request, cap) -> {
			opened.add(request); return Optional.of(new Source(request, reads, 2));
		}, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(),
			nx.pingwheel.common.presentation.budget.BudgetDelaySink.NONE, true)) {
			runtime.advance(0, settings);
			var a = runtime.attach(first, new InventoryRuntime.TrackingSubject(key)).orElseThrow();
			var b = runtime.attach(second, new InventoryRuntime.TrackingSubject(key)).orElseThrow();
			assertEquals(1, runtime.step(a).orElseThrow().slots().size());
			assertTrue(runtime.step(b).isEmpty(), "another member cannot claim another logical target's allowance");
			assertEquals(1, reads.get());
			runtime.advance(settings.getTracking().getPeriodTicks(), settings);
			assertEquals(1, runtime.step(b).orElseThrow().slots().size());
			assertEquals(2, reads.get()); assertTrue(opened.contains(first)); assertTrue(opened.contains(second));
		}
	}
	@Test void nativeCatalogCapacityKeepsOnlyValidatedPrefixAndPaysFailingVisitAndRetainedEvidence() {
		var settings = InventorySettings.serverDefaults(); var reads = new AtomicInteger();
		try (var runtime = new InventoryRuntime((request, cap) -> {
			var source = new Source(request, reads, 300); source.unique = true; return Optional.of(source);
		}, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var input = input(new UUID(19, 1), BlockFace.NORTH);
			var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
			var values = new ArrayList<@Nullable InventoryResourceValue>();
			InventoryRuntime.@Nullable Observation last = null;
			for (int tick = 0; tick < 50; tick++) {
				runtime.advance(tick, settings); var next = runtime.step(consumer);
				if (next.isEmpty()) continue;
				last = next.get(); values.addAll(last.slots());
				if (last.result().completeness() != CaptureResult.Completeness.CONTINUE) break;
			}
			assertNotNull(last); assertEquals(CaptureResult.Availability.READABLE, last.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, last.result().completeness());
			assertEquals(256, values.size()); assertEquals(257, reads.get(), "the failed catalogue admission is a paid physical attempt");
			assertTrue(values.stream().allMatch(value -> value != null && value.amount() == 9007199254740993L && !value.aggregateRequired()));
			long retained = runtime.memory().retained();
			try (var evidence = runtime.publicationEvidence(consumer, InventoryRuntime.EvidenceScope.SCANNING).orElseThrow()) {
				consumer.close(); assertTrue(runtime.memory().retained() > 0, "publication keeps its metadata owner alive");
				assertTrue(runtime.memory().retained() < retained, "catalogue and journal do not escape into historical evidence");
				assertEquals(InventorySharedRead.Check.VALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
			}
			assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void cursorlessFluidStartsOneBoundedPassAndCannotTurnTruncatedCoverageIntoZero() {
		for (boolean complete : new boolean[] {false, true}) {
			var settings = InventorySettings.serverDefaults(); settings.setPhysicalSlotsPerTick(IntLimit.finite(2));
			var passes = new AtomicInteger(); var visits = new AtomicInteger();
			try (var runtime = new InventoryRuntime(input -> Optional.of(new InventorySourceAccess.Source() {
				@Override public SourceKey key() { return new SourceKey("test", "fluid", "cursorless", input.viewKey()); }
				@Override public boolean valid() { return true; }
				@Override public boolean stableCursor() { return false; }
				@Override public int slots() { return 0; }
				@Override public InventoryDomainCodec.@Nullable Item read(int slot) { throw new AssertionError("cursorless fluid used Item getter"); }
				@Override public InventoryReprojectionSummary reprojectionSummary(int cap) { return InventoryReprojectionSummary.EMPTY; }
				@Override public InventorySourceAccess.ResourcePage enumerateResources(int limit, @Nullable InventoryCatalogAdmission admission,
					InventoryValidationBudget.Allowance allowance) {
					assertEquals(2, limit); passes.incrementAndGet(); visits.addAndGet(limit);
					return new InventorySourceAccess.ResourcePage(java.util.Arrays.asList(null,
						InventoryResourceCodec.value(new InventoryScanner.Key(InventoryResourceKind.FLUID, "minecraft:water", "native"),
							81, "water", null, false)), complete);
				}
			}), settings.pendingMemoryBytes())) {
				var input = input(new UUID(20, 1), BlockFace.NORTH); runtime.advance(0, settings);
				var consumer = runtime.attach(input, new InventoryRuntime.PreviewSubject(input.readOwner())).orElseThrow();
				var result = runtime.step(consumer).orElseThrow();
				assertEquals(complete ? CaptureResult.Completeness.COMPLETE : CaptureResult.Completeness.INCOMPLETE, result.result().completeness());
				assertEquals(InventoryStep.SweepEvidence.NONE, result.inventorySweepEvidence());
				assertNull(result.slots().getFirst()); assertEquals(81, result.slots().getLast().amount());
				assertEquals(complete, result.result().coverage().expected().isPresent());
				assertEquals(1, passes.get()); assertEquals(2, visits.get());
				runtime.advance(1, settings); runtime.step(consumer); assertEquals(1, passes.get(), "no synthetic cross-tick cursor or eager retry");
			}
		}
	}
	@Test void retiringFluidBindingCannotDiscardItemRoundWithTheSameTargetOwnerAndFace() {
		var settings = InventorySettings.serverDefaults(); var itemReads = new AtomicInteger(); var fluidReads = new AtomicInteger();
		try (var runtime = new InventoryRuntime(input -> Optional.of(input.resourceKind() == InventoryResourceKind.FLUID
			? new Source(input, fluidReads, 2) : new InventoryRuntimeTest.Source(input, itemReads, 2) {
				@Override public InventoryDomainCodec.Item read(int slot) { itemReads.incrementAndGet(); return InventoryRuntimeTest.item(7); }
			}), settings.pendingMemoryBytes())) {
			var fluidInput = input(new UUID(21, 1), BlockFace.NORTH);
			var itemInput = new InventorySourceInput(fluidInput.target(), fluidInput.readOwner(), fluidInput.face(), InventoryResourceKind.ITEM);
			runtime.advance(0, settings);
			var fluid = runtime.attach(fluidInput, new InventoryRuntime.PreviewSubject(fluidInput.readOwner())).orElseThrow();
			var item = runtime.attach(itemInput, new InventoryRuntime.PreviewSubject(itemInput.readOwner())).orElseThrow();
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(fluid, 1).orElseThrow().result().completeness());
			assertEquals(CaptureResult.Completeness.CONTINUE, runtime.step(item, 1).orElseThrow().result().completeness());
			runtime.retireInvalid(fluidInput); assertTrue(fluid.invalidated()); assertFalse(item.invalidated());
			var complete = runtime.step(item, 1).orElseThrow(); assertEquals(CaptureResult.Completeness.COMPLETE, complete.result().completeness());
			assertEquals(2, itemReads.get()); assertEquals(1, fluidReads.get());
			assertEquals(InventoryResourceKind.ITEM, complete.slots().getFirst().resourceKind());
		}
	}
}
