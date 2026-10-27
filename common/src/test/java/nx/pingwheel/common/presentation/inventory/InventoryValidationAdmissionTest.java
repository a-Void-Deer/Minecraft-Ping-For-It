package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryValidationBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
	private static InventorySelection selection() {
		return new InventorySelection(new InventoryScanner.Key("minecraft:stone", "exact"), "minecraft:stone", "stone", null, false, "danger");
	}
}
