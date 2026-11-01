package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import nx.pingwheel.common.config.IntLimit;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.presentation.budget.BudgetDelayMonitor;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import nx.pingwheel.common.presentation.inventory.*;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryPooledChestRuntimeTest {
	@BeforeAll static void bootstrap() { InventoryCertifiedReadTest.bootstrap(); }
	private static InventoryRuntime.SourceResolver resolver(InventoryCertifiedReadTest.World world) {
		return new InventoryRuntime.SourceResolver() {
			@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
			@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
				allowance.debit(resolutionValidationCost(input)); return Optional.of(world.source(input));
			}
		};
	}
	private static InventoryCertifiedReadTest.World singleChests() {
		var world = new InventoryCertifiedReadTest.World();
		for (BlockPos pos : List.of(BlockPos.ZERO, new BlockPos(4, 0, 0))) {
			world.put(pos, Blocks.CHEST.defaultBlockState());
			((ChestBlockEntity) java.util.Objects.requireNonNull(world.entities.get(pos))).setItem(0, new ItemStack(Items.STONE, 7));
		}
		return world;
	}
	private static InventorySettings diagnosticSettings() {
		var settings = InventoryPooledBackendTest.settings(false, false);
		settings.setPhysicalSlotsPerTick(IntLimit.finite(16)); settings.validate(); return settings;
	}
	private static InventoryRuntime.Consumer attach(InventoryRuntime runtime, InventoryCertifiedReadTest.World world, BlockPos pos, long owner) {
		var input = world.input(pos, BlockFace.NORTH, owner);
		var consumer = runtime.attach(input, new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()))).orElseThrow();
		assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
		assertNotNull(InventoryPooledBackendTest.stateField(consumer, "scanningAssociation"), "the production certified route, not the legacy constructor, must be exercised");
		return consumer;
	}
	private static CostLedger physical(InventoryRuntime runtime) { return (CostLedger) InventoryPooledBackendTest.state(runtime, "physical"); }
	private static void spend(CostLedger ledger, CostLedger.Counter counter, long amount) {
		@Unmodifiable Map<CostLedger.Counter, Long> charge = Map.of(counter, amount); ledger.tryReserve(charge).orElseThrow().commit(charge);
	}
	private static void summary(BudgetDelayMonitor.CycleSummary summary, long slots, Reason @Unmodifiable ... reasons) {
		assertEquals(Module.INVENTORY_TRACKING, summary.module()); assertEquals(1, summary.observedWorkItems().amount());
		assertFalse(summary.observedWorkItems().saturated()); assertEquals(1, summary.samples().size());
		assertEquals(java.util.Set.of(reasons), summary.reasons().keySet());
		summary.reasons().values().forEach(count -> { assertEquals(1, count.amount()); assertFalse(count.saturated()); });
		assertEquals(java.util.Set.of(Unit.PHYSICAL_SLOT), summary.quantities().keySet());
		assertEquals(slots, summary.quantities().get(Unit.PHYSICAL_SLOT).amount()); assertEquals(0, summary.unknownQuantities().amount());
	}
	@ParameterizedTest @ValueSource(booleans = {false, true})
	void certifiedPhysicalRefusalReportsOnlyTheExhaustedSlotBudgetWithFreshOrRetainedCoverage(boolean previousPage) {
		var world = singleChests(); var settings = diagnosticSettings(); var warnings = new ArrayList<BudgetDelayMonitor.CycleSummary>();
		var monitor = new BudgetDelayMonitor(warnings::add, modules -> false);
		var runtime = new InventoryRuntime(resolver(world), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), monitor, true);
		try (runtime) {
			runtime.advance(0, settings);
			var first = attach(runtime, world, BlockPos.ZERO, 1); var second = attach(runtime, world, new BlockPos(4, 0, 0), 2);
			if (previousPage) {
				assertEquals(16, runtime.step(second).orElseThrow().slots().size()); runtime.advance(1, settings);
			}
			assertEquals(16, runtime.step(first).orElseThrow().slots().size()); int audits = world.audits;
			assertEquals(16, physical(runtime).used(InventorySourceAccess.PHYSICAL));
			for (int attempt = 0; attempt < 3; attempt++) assertTrue(runtime.step(second).isEmpty());
			assertEquals(audits, world.audits, "refused pages do no native content work");
			assertEquals(previousPage ? 16 : 0, InventoryPooledBackendTest.state(second, "cursor"));
			monitor.endCycle(Module.INVENTORY_TRACKING, runtime.trackingPeriodId(), previousPage ? 1 : 0);
			assertEquals(1, warnings.size()); summary(warnings.getFirst(), previousPage ? 11 : 27, Reason.PHYSICAL_SLOTS);
			monitor.endCycle(Module.INVENTORY_TRACKING, runtime.trackingPeriodId(), previousPage ? 1 : 0); assertEquals(1, warnings.size());
			runtime.advance(previousPage ? 2 : 1, settings);
			var resumed = runtime.step(second).orElseThrow(); assertEquals(previousPage ? 11 : 16, resumed.slots().size());
			if (!previousPage) { runtime.advance(2, settings); resumed = runtime.step(second).orElseThrow(); assertEquals(11, resumed.slots().size()); }
			assertEquals(CaptureResult.Completeness.COMPLETE, resumed.result().completeness()); second.retainCompleted();
			assertEquals(27, InventoryPooledBackendTest.state(second, "cursor")); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@ParameterizedTest @ValueSource(strings = {"memory", "provider", "item", "memory+provider"})
	void certifiedPageMemoryAndWorkRefusalsKeepOnlyObservedReasonsAndResumeWithoutLosingThePrefix(String limit) {
		var world = singleChests(); var settings = diagnosticSettings(); var warnings = new ArrayList<BudgetDelayMonitor.CycleSummary>();
		var monitor = new BudgetDelayMonitor(warnings::add, modules -> false);
		var runtime = new InventoryRuntime(resolver(world), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), monitor, true);
		try (runtime) {
			runtime.advance(0, settings); var consumer = attach(runtime, world, BlockPos.ZERO, 1);
			assertEquals(1, runtime.step(consumer, 1).orElseThrow().slots().size()); int audits = world.audits;
			runtime.advance(1, settings);
			if (limit.contains("memory")) runtime.memory().setCap(runtime.memory().retained() + runtime.stepMemoryUpperBound(consumer, 1) - 1);
			if (limit.contains("provider")) spend(physical(runtime), InventorySourceAccess.PROVIDER_WORK,
				physical(runtime).remaining(InventorySourceAccess.PROVIDER_WORK) - InventorySourceAccess.PROVIDER_CALL_WORK);
			if (limit.equals("item")) {
				var association = InventoryPooledBackendTest.state(consumer, "scanningAssociation");
				var port = (InventorySharedRead.Port) InventoryPooledBackendTest.state(InventoryPooledBackendTest.state(association, "lease"), "port");
				var itemWork = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
				spend(physical(runtime), itemWork, physical(runtime).remaining(itemWork) - port.validationCost().workUnits());
			}
			assertTrue(runtime.step(consumer).isEmpty()); assertEquals(1, InventoryPooledBackendTest.state(consumer, "cursor"));
			assertEquals(audits, world.audits); assertEquals(0, physical(runtime).used(InventorySourceAccess.PHYSICAL));
			monitor.endCycle(Module.INVENTORY_TRACKING, runtime.trackingPeriodId(), 1); assertEquals(1, warnings.size());
			if (limit.equals("memory")) summary(warnings.getFirst(), 26, Reason.MEMORY);
			else if (limit.equals("memory+provider")) summary(warnings.getFirst(), 26, Reason.MEMORY, Reason.PROVIDER_WORK);
			else summary(warnings.getFirst(), 26, Reason.PROVIDER_WORK);
			runtime.advance(2, settings); assertEquals(16, runtime.step(consumer).orElseThrow().slots().size());
			runtime.advance(3, settings); var completed = runtime.step(consumer).orElseThrow();
			assertEquals(10, completed.slots().size()); assertEquals(CaptureResult.Completeness.COMPLETE, completed.result().completeness()); consumer.retainCompleted();
			assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void certifiedPaidJournalReplayNeedsNoPhysicalAllowanceAndProducesNoBudgetWarning() {
		var world = singleChests(); var settings = diagnosticSettings(); var warnings = new ArrayList<BudgetDelayMonitor.CycleSummary>();
		var monitor = new BudgetDelayMonitor(warnings::add, modules -> false);
		var runtime = new InventoryRuntime(resolver(world), settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), monitor, true);
		try (runtime) {
			runtime.advance(0, settings); var first = attach(runtime, world, BlockPos.ZERO, 1); var peer = attach(runtime, world, BlockPos.ZERO, 2);
			assertEquals(16, runtime.step(first).orElseThrow().slots().size()); int audits = world.audits;
			var replayed = runtime.step(peer).orElseThrow(); assertEquals(16, replayed.slots().size());
			assertEquals(7, java.util.Objects.requireNonNull(replayed.slots().getFirst()).count()); assertEquals(audits, world.audits);
			assertEquals(16, physical(runtime).used(InventorySourceAccess.PHYSICAL));
			var logical = (Map<?, ?>) InventoryPooledBackendTest.state(runtime, "logicalUsed");
			assertEquals(List.of(16), List.copyOf(logical.values()), "same target's paid prefix is not charged twice");
			runtime.advance(1, settings); assertEquals(11, runtime.step(first).orElseThrow().slots().size()); audits = world.audits;
			var complete = runtime.step(peer).orElseThrow(); assertEquals(11, complete.slots().size());
			assertEquals(CaptureResult.Completeness.COMPLETE, complete.result().completeness()); assertEquals(audits, world.audits);
			first.retainCompleted(); peer.retainCompleted(); monitor.endCycle(Module.INVENTORY_TRACKING, runtime.trackingPeriodId(), 1);
			assertTrue(warnings.isEmpty()); assertEquals(0, runtime.memory().reserved());
		}
		assertEquals(0, runtime.memory().retained()); assertEquals(0, runtime.memory().reserved());
	}
	@Test void oppositeNativeDoubleChestHitsShareWithoutCapabilitiesAndOldEvidenceRejectsFreshWrapper() {
		for (var block : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			var world = InventoryCertifiedReadTest.pair(block);
			for (var entity : world.entities.values()) for (int slot = 0; slot < 27; slot++) ((ChestBlockEntity) entity).setItem(slot, new ItemStack(Items.STONE, slot + 1));
			var settings = InventoryPooledRuntimeTest.settings(false, false);
			var resolver = new InventoryRuntime.SourceResolver() {
				@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return InventoryMinecraftSources.resolutionValidationCost(input); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted"); }
				@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
					allowance.debit(resolutionValidationCost(input)); return Optional.of(world.source(input));
				}
			};
			var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes(), InventoryRuntime.DisplayReprojector.none(), BudgetDelaySink.NONE, true);
			try (runtime) {
				runtime.advance(0, settings); var input = world.input(BlockPos.ZERO, BlockFace.NORTH, 1); var subject = new InventoryRuntime.TrackingSubject(TargetKey.from(input.target()));
				var a = runtime.attach(input, subject).orElseThrow();
				var b = runtime.attach(world.input(new BlockPos(1, 0, 0), BlockFace.SOUTH, 2), subject).orElseThrow();
				assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(a)); assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(b));
				int now = 0;
				for (var consumer : List.of(a, b)) {
					long total = 0; int slots = 0; boolean complete = false;
					for (int tick = 0; tick < 80 && !complete; tick++) {
						runtime.advance(now++, settings); var observation = runtime.step(consumer, 9);
						if (observation.isEmpty()) continue;
						for (var item : observation.get().slots()) { total += java.util.Objects.requireNonNull(item).count(); slots++; }
						complete = observation.get().result().completeness() == CaptureResult.Completeness.COMPLETE;
					}
					assertTrue(complete); assertEquals(54, slots); assertEquals(756, total); consumer.retainCompleted();
				}
				// One exact route audits both members before and after each physical visit. Metadata validation never audits contents.
				assertEquals(54 * 4, world.audits);
				try (var evidence = runtime.retainEvidence(b, InventoryRuntime.EvidenceScope.COMPLETED).orElseThrow()) {
					world.put(BlockPos.ZERO, world.states.get(BlockPos.ZERO));
					assertEquals(InventorySharedRead.Check.STRUCTURE_INVALID, evidence.validate(new InventoryValidationBudget.Allowance(evidence.validationCost())));
					assertFalse(runtime.probe(b).orElseThrow()); assertTrue(a.invalidated());
				}
			} assertEquals(0, runtime.memory().retained());
		}
	}
}
