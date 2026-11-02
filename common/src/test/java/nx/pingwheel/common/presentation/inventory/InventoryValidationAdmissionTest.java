package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformFluidService;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryValidationAdmissionTest {
	private static final CostLedger.Counter WORK = new CostLedger.Counter("inventory/item_work", CostLedger.Unit.WORK);
	private static final InventoryValidationBudget.Cost RESOLVE = new InventoryValidationBudget.Cost(100, 100, 11);
	private static final InventoryValidationBudget.Cost VALID = new InventoryValidationBudget.Cost(20, 20, 3);
	private static final InventoryValidationBudget.Cost READ = new InventoryValidationBudget.Cost(40, 40, 5);
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(71, 3), BlockFace.NORTH);
	@NotNullByDefault
	private static final class Resolver implements InventoryRuntime.SourceResolver {
		int calls, metadata, reads, validity;
		boolean denied, throwing;
		final List<InventoryValidationBudget.Allowance> grants = new ArrayList<>();
		@Override public InventoryValidationBudget.Cost resolutionValidationCost(InventorySourceInput input) { return RESOLVE; }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap) { throw new AssertionError("unadmitted resolver"); }
		@Override public Optional<InventorySourceAccess.Source> resolve(InventorySourceInput input, int cap, InventoryValidationBudget.Allowance allowance) {
			allowance.debit(RESOLVE); calls++; grants.add(allowance);
			if (throwing) throw new IllegalStateException("paid resolver prefix");
			return Optional.of(new InventorySourceAccess.Source() {
				@Override public SourceKey key() { return new SourceKey("test", "inventory", "admitted", input.viewKey()); }
				@Override public InventoryValidationBudget.Cost discoveryValidationCost() { return VALID.times(2); }
				@Override public InventoryValidationBudget.Cost evidenceValidationCost() { return VALID; }
				@Override public InventoryValidationBudget.Cost readValidationCost(int slots) { return READ.times(slots); }
				@Override public boolean valid() { throw new AssertionError("unadmitted validity"); }
				@Override public boolean valid(InventoryValidationBudget.Allowance grant) { grant.debit(VALID); validity++; grants.add(grant); return !denied; }
				@Override public int slots() { metadata++; return 1; }
				@Override public boolean stableCursor() { metadata++; return true; }
				@Override public OptionalLong version() { metadata++; return OptionalLong.empty(); }
				@Override public @Nullable InventoryDomainCodec.Item read(int slot) { throw new AssertionError("unadmitted content"); }
				@Override public @Nullable InventoryDomainCodec.Item read(int slot, InventoryCatalogAdmission catalog, InventoryValidationBudget.Allowance grant) {
					grant.debit(READ); reads++; grants.add(grant); return null;
				}
				@Override public boolean matchesSelection(int slot, InventorySelection selection, InventoryCatalogAdmission catalog, InventoryValidationBudget.Allowance grant) {
					read(slot, catalog, grant); return true;
				}
			});
		}
	}
	private static CostLedger ledger(InventoryRuntime runtime) throws ReflectiveOperationException {
		var field = InventoryRuntime.class.getDeclaredField("physical"); field.setAccessible(true); return (CostLedger) field.get(runtime);
	}
	@Test void settledStageCannotSpendRefundedHeadroomAndBoundedResolutionProfilesFitDefaults() {
		var allowance = new InventoryValidationBudget.Allowance(new InventoryValidationBudget.Cost(100, 100, 100));
		allowance.debit(VALID); allowance.releaseUnusedAdmission(); allowance.admitAdditional(VALID);
		allowance.debit(VALID);
		assertThrows(IllegalStateException.class, () -> allowance.debit(VALID));
		assertEquals(6, allowance.attemptedWorkUnits());
		long memory = InventorySettings.serverDefaults().pendingMemoryBytes();
		for (String id : List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:beehive", "create:item_vault")) {
			var cost = InventoryValidationBudget.resolution(id);
			assertTrue(cost.workspaceBytes() + 4_000_000L < memory, id + " resolution must fit beside caller authority scratch");
			assertTrue(cost.workUnits() < 4096L * nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.workUpperBound(4096), id);
		}
	}
	@Test void zeroAndOneShortResolutionGrantDeferBeforeAnyResolverOrMetadataCallback() throws Exception {
		for (int remaining : new int[] {0, 10}) {
			var resolver = new Resolver(); var settings = InventorySettings.serverDefaults();
			try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
				runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
				assertTrue(runtime.claimDetachedItemWork(ledger(runtime).remaining(WORK) - remaining));
				assertTrue(runtime.probe(INPUT).isEmpty()); assertEquals(InventorySourceAccess.Preparation.DEFERRED, runtime.prepare(consumer));
				assertFalse(runtime.selectionPresent(INPUT, selection(), 0));
				assertEquals(0, resolver.calls); assertEquals(0, resolver.metadata); assertEquals(0, resolver.reads);
				assertFalse(consumer.invalidated()); assertEquals(0, runtime.memory().reserved());
				runtime.advance(1, settings); assertEquals(Optional.of(true), runtime.probe(INPUT));
			}
		}
	}
	@Test void inputProbeSharesResolutionAllowanceWithValidityAndDistinguishesDenialFromDefer() throws Exception {
		var resolver = new Resolver(); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); assertEquals(Optional.of(true), runtime.probe(INPUT));
			assertSame(resolver.grants.get(0), resolver.grants.get(1)); assertEquals(14, ledger(runtime).used(WORK));
			resolver.denied = true; assertEquals(Optional.of(false), runtime.probe(INPUT)); assertEquals(28, ledger(runtime).used(WORK));
			resolver.throwing = true; assertEquals(Optional.of(false), runtime.probe(INPUT)); assertEquals(39, ledger(runtime).used(WORK));
			assertEquals(0, runtime.memory().reserved()); assertEquals(0, ledger(runtime).used(InventorySourceAccess.PHYSICAL));
		}
	}
	@Test void liveStepAndRetainedEvidenceUseAdmittedOverloadsAndFreshOperationGrants() throws Exception {
		var resolver = new Resolver(); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			var observation = runtime.step(consumer, 1).orElseThrow(); assertEquals(CaptureResult.Completeness.COMPLETE, observation.result().completeness());
			assertEquals(1, resolver.reads); for (var grant : resolver.grants) assertSame(resolver.grants.getFirst(), grant);
			consumer.retainCompleted(); consumer.restart(); resolver.grants.clear();
			long before = ledger(runtime).used(WORK); assertTrue(runtime.claimDetachedItemWork(ledger(runtime).remaining(WORK) - 2));
			int calls = resolver.validity; assertTrue(runtime.probe(consumer).isEmpty()); assertEquals(calls, resolver.validity); assertFalse(consumer.invalidated());
			runtime.advance(1, settings); assertEquals(Optional.of(true), runtime.probe(consumer)); assertEquals(calls + 1, resolver.validity);
			assertEquals(3, ledger(runtime).used(WORK)); assertEquals(0, runtime.memory().reserved()); assertTrue(before > 0);
		}
	}
	@Test void selectionSharesOneAllowanceFromResolutionThroughImmediateWitness() {
		var resolver = new Resolver(); var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(resolver, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); assertTrue(runtime.selectionPresent(INPUT, selection(), 0));
			assertEquals(3, resolver.grants.size()); for (var grant : resolver.grants) assertSame(resolver.grants.getFirst(), grant);
			assertEquals(19, resolver.grants.getFirst().attemptedWorkUnits()); assertEquals(1, resolver.reads); assertEquals(0, runtime.memory().reserved());
		}
	}
	@Test void exceptionalOuterCloseWithLiveFluidOwnerFreezesAllowanceButStillDrainsRuntimeTickets() throws Exception {
		for (long outerWork : new long[] {1, 11, 1000}) {
			var settings = InventorySettings.serverDefaults();
			try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
				runtime.advance(0, settings);
				var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
				var outer = validation(runtime, new InventoryValidationBudget.Cost(1000, 1000, outerWork), allowance);
				allowance.debit(new InventoryValidationBudget.Cost(1, 1, 1));
				var fluid = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
				var childCost = new InventoryValidationBudget.Cost(128, 64, 8);
				var operation = fluid.enter(new IPlatformFluidService.OperationProfile(childCost, 0, 0, 0));
				operation.allowance().debit(childCost); long fluidWorkspace = 2048 + childCost.workspaceBytes();
				assertTrue(runtime.memory().reserved() > fluidWorkspace);
				assertThrows(IllegalStateException.class, outer::close);
				assertEquals(1, ledger(runtime).used(WORK), "the executed outer prefix stays paid; live child work belongs to its own ticket");
				assertEquals(ledger(runtime).limit(WORK) - 1 - 136, ledger(runtime).remaining(WORK), "the child reservation must remain intact");
				assertEquals(fluidWorkspace, runtime.memory().reserved(), "outer work/workspace must close even when settlement order fails");
				assertThrows(IllegalStateException.class, () -> allowance.debit(READ));
				assertThrows(IllegalStateException.class, fluid::close); operation.close();
				assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
				// Independent expected sum: outer 1 + child bookkeeping 128 + child callback 8.
				assertEquals(137, ledger(runtime).used(WORK)); assertEquals(137, allowance.attemptedWorkUnits());
				assertEquals(136, allowance.independentlyChargedWork());
				assertEquals(ledger(runtime).limit(WORK) - 137, ledger(runtime).remaining(WORK));
				assertThrows(IllegalStateException.class, () -> allowance.admitAdditional(READ));
				fluid.close(); assertEquals(137, ledger(runtime).used(WORK));
			}
		}
	}
	@Test void runtimeAndTwoFluidOwnersChargeTheirOwnPrefixesInEveryCloseOrder() throws Exception {
		for (boolean headroom : new boolean[] {false, true})
		for (@Unmodifiable List<Integer> order : List.of(List.of(2, 1, 0), List.of(2, 0, 1), List.of(1, 2, 0),
			List.of(1, 0, 2), List.of(0, 2, 1), List.of(0, 1, 2))) {
			var settings = InventorySettings.serverDefaults();
			try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
				runtime.advance(0, settings);
				var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
				var outer = validation(runtime, new InventoryValidationBudget.Cost(1000, 1000, headroom ? 1000 : 1), allowance);
				allowance.debit(new InventoryValidationBudget.Cost(1, 1, 1));
				var parent = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
				try (var operation = parent.enter(new IPlatformFluidService.OperationProfile(new InventoryValidationBudget.Cost(128, 64, headroom ? 1000 : 2), 0, 0, 100))) {
					operation.allowance().debit(new InventoryValidationBudget.Cost(128, 64, 2));
				}
				var child = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
				try (var operation = child.enter(new IPlatformFluidService.OperationProfile(new InventoryValidationBudget.Cost(128, 64, headroom ? 1000 : 8), 0, 0, 100))) {
					operation.allowance().debit(new InventoryValidationBudget.Cost(128, 64, 8));
				}
				@Unmodifiable List<AutoCloseable> owners = List.of(outer, parent, child);
				// Each enter costs 128; these callbacks execute 1, 2 and 8 work respectively.
				@Unmodifiable List<Long> ownerWork = List.of(1L, 130L, 136L);
				boolean parentClosed = false, childClosed = false, frozen = false;
				long charged = 0, independentlyCharged = 0;
				for (int index : order) {
					var owner = owners.get(index);
					boolean rejected = index == 0 ? !parentClosed || !childClosed : frozen || index == 1 && !childClosed;
					if (rejected) { assertThrows(IllegalStateException.class, owner::close); frozen = true; }
					else owner.close();
					if (index == 1) parentClosed = true;
					if (index == 2) childClosed = true;
					charged += ownerWork.get(index);
					if (index != 0) independentlyCharged += ownerWork.get(index);
					assertEquals(charged, ledger(runtime).used(WORK), "only the closing owner's executed prefix may settle: " + order);
					assertEquals(independentlyCharged, allowance.independentlyChargedWork());
					if (frozen) assertThrows(IllegalStateException.class, () -> allowance.debit(READ));
				}
				assertEquals(267, ledger(runtime).used(WORK)); assertEquals(267, allowance.attemptedWorkUnits());
				assertEquals(266, allowance.independentlyChargedWork());
				assertEquals(ledger(runtime).limit(WORK) - 267, ledger(runtime).remaining(WORK));
				assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
				parent.close(); child.close(); assertEquals(267, ledger(runtime).used(WORK));
				assertThrows(IllegalStateException.class, () -> allowance.debit(READ));
			}
		}
	}
	@Test void sequentialRuntimeStagesExcludeHistoricalAndLiveFluidWorkWithoutLosingOuterSuffix() throws Exception {
		var settings = InventorySettings.serverDefaults();
		try (var runtime = new InventoryRuntime(input -> Optional.empty(), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings);
			var allowance = new InventoryValidationBudget.Allowance(InventoryValidationBudget.NONE);
			var childCost = new InventoryValidationBudget.Cost(128, 64, 8);
			for (int stage = 1; stage <= 2; stage++) {
				try (var outer = validation(runtime, READ.times(2), allowance)) {
					allowance.debit(READ);
					try (var parent = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
						var operation = parent.enter(new IPlatformFluidService.OperationProfile(VALID, 0, 0, 0))) {
						operation.allowance().debit(VALID);
						try (var child = allowance.fluidGrants().open(allowance, InventoryValidationBudget.NONE, InventoryValidationBudget.NONE);
							var inner = child.enter(new IPlatformFluidService.OperationProfile(childCost, 0, 0, 0))) {
							inner.allowance().debit(childCost);
						}
						assertEquals((stage - 1) * 277 + 136, ledger(runtime).used(WORK));
					}
					allowance.debit(READ);
					assertEquals((stage - 1) * 277 + 267, ledger(runtime).used(WORK));
				}
				// Per stage: outer 5 + 5, parent 128 + 3, child 128 + 8.
				assertEquals(stage * 277, ledger(runtime).used(WORK));
				assertEquals(stage * 277, allowance.attemptedWorkUnits()); assertEquals(stage * 267, allowance.independentlyChargedWork());
				assertEquals(ledger(runtime).limit(WORK) - stage * 277, ledger(runtime).remaining(WORK));
				assertEquals(0, runtime.memory().reserved()); assertEquals(0, runtime.memory().retained());
				assertThrows(IllegalStateException.class, () -> allowance.debit(READ));
			}
		}
	}
	private static AutoCloseable validation(InventoryRuntime runtime, InventoryValidationBudget.Cost cost, InventoryValidationBudget.Allowance allowance) throws ReflectiveOperationException {
		var method = InventoryRuntime.class.getDeclaredMethod("validation", InventoryValidationBudget.Cost.class, InventoryValidationBudget.Allowance.class);
		method.setAccessible(true);
		return (AutoCloseable) ((Optional<?>) method.invoke(runtime, cost, allowance)).orElseThrow();
	}
	private static InventorySelection selection() {
		return new InventorySelection(new InventoryScanner.Key("minecraft:stone", "exact"), "minecraft:stone", "stone", null, false, "danger");
	}
}
