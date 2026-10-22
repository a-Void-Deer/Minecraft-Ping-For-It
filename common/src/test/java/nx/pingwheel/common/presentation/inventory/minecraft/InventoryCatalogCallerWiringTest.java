package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryRuntime;
import nx.pingwheel.common.presentation.inventory.InventorySelection;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UNRUN. Detached/trap fixtures deliberately avoid native services and Minecraft bootstrap. */
class InventoryCatalogCallerWiringTest {

	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(81, 1), BlockFace.NORTH);
	private static final String ID = "test:value", LABEL = "item.test.value";
	private static final long AMOUNT = 9007199254740993L;
	private static <T> T trap(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
			(proxy, method, arguments) -> { throw new AssertionError("unexpected callback: " + method); }));
	}
	private static InventoryItemCodec.Catalog catalog(int cap) {
		return new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 256, 262144, cap,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
	}
	private static InventoryDetachedItem detached(String json) {
		return new InventoryDetachedItem(ID, InventoryIdentityMode.EXACT,
			new InventoryItemCodec.Display(ID, LABEL, json, false), new byte[] {97, 98, 99}, 512);
	}
	private static InventoryDomainCodec.Item intern(InventoryItemCodec.Catalog catalog, InventoryDetachedItem item, InventoryCatalogAdmission grant) {
		var encoded = catalog.intern(item, grant); var display = encoded.display();
		return new InventoryDomainCodec.Item(encoded.key(), AMOUNT, display.label(), display.displayJson(), display.aggregateRequired());
	}
	private static CostLedger slots(int slots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK));
	}
	private static InventorySourceAccess.InventoryHandle open(InventorySourceAccess.Source source) {
		var access = new InventorySourceAccess(INPUT, ignored -> Optional.of(source));
		var scope = new SourceAccess.ReadScope(INPUT.viewKey(), Set.of("pingforit:inventory.items"));
		var descriptor = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(
			"minecraft:overworld", "block", "minecraft:chest", 0, 0, 0, ""), scope, slots(0));
		return (InventorySourceAccess.InventoryHandle) ((SourceAccess.OpenResult.Started) access.open(descriptor.descriptor(), scope, slots(0))).handle();
	}
	private static class Source implements InventorySourceAccess.Source {
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "catalog", INPUT.viewKey()); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 2; }
		@Override public int displayBytes() { return 4096; }
		@Override public InventoryReprojectionSummary reprojectionSummary(int ceiling) { return InventoryReprojectionSummary.EMPTY; }
		@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("caller grant dropped"); }
	}

	@Test void repeatedSnapshotItemsShareOneAllowanceAndFailedPrefixIsNotRefundedOrPublished() {
		var catalog = catalog(4096); var item = detached(null); var grants = new ArrayList<InventoryCatalogAdmission>();
		long scan = InventoryCatalogAdmission.scanWorkUpperBound(0, ID.length(), LABEL.length());
		long operation = InventoryCatalogAdmission.operationWorkUpperBound(3, 0, 0);
		var snapshot = new InventorySourceAccess.InventorySnapshot() {
			@Override public int slots() { return 2; }
			@Override public long retainedBytes() { return 128; }
			@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("caller grant dropped"); }
			@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission allowance) {
				grants.add(allowance); return intern(catalog, item, allowance);
			}
		};
		var source = new Source() {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return 128; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { return Optional.of(snapshot); }
			}); }
		};
		var memory = new RetainedMemoryLedger(128); var ledger = slots(2);
		try (var handle = open(source)) {
			assertEquals(InventorySourceAccess.Preparation.READY, handle.prepareSnapshot(memory));
			var allowance = new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), scan + operation + scan);
			var outcome = (SourceAccess.StepOutcome.Captured) handle.step(ledger, allowance);
			assertEquals(2, grants.size()); assertSame(allowance, grants.get(0)); assertSame(allowance, grants.get(1));
			assertEquals(scan + operation + scan, allowance.attemptedWork(), "first call and second paid scan persist");
			assertEquals(2, ledger.used(InventorySourceAccess.PHYSICAL), "exact attempted read count, not Catalog work");
			assertEquals(1, outcome.result().coverage().scanned()); assertTrue(outcome.result().payload().isEmpty());
			assertEquals(CaptureResult.Availability.READABLE, outcome.result().availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, outcome.result().completeness());
			assertEquals(1, catalog.displays().size()); assertFalse(catalog.displays().values().iterator().next().aggregateRequired());
		}
		assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}

	@Test void zeroCatalogSpaceOrWorkRefusesBeforeOwnedMutationThroughLiveHandle() {
		for (boolean noSpace : List.of(false, true)) {
			var catalog = catalog(4096); var input = detached("{malformed}");
			var source = new Source() {
				@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission allowance) { return intern(catalog, input, allowance); }
			};
			var allowance = new InventoryCatalogAdmission(noSpace ? 0 : InventoryItemBudget.workspaceUpperBound(4096), noSpace ? Long.MAX_VALUE : 0);
			var ledger = slots(2);
			try (var handle = open(source)) {
				var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, allowance)).result();
				assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness()); assertTrue(result.payload().isEmpty());
				assertTrue(catalog.displays().isEmpty()); assertEquals(1, ledger.used(InventorySourceAccess.PHYSICAL));
				assertEquals(0, allowance.peakWorkspaceBytes()); assertEquals(noSpace, allowance.attemptedWork() > 0);
			}
		}
	}

	@Test void runtimeLazyReadsHoldNewScratchAfterCaptureAndPermissionRecheckKeepsSameReadGrant() {
		var settings = InventorySettings.serverDefaults(); var catalog = catalog(4096); var detached = detached(null);
		InventoryRuntime[] host = new InventoryRuntime[1]; var grants = new ArrayList<InventoryCatalogAdmission>();
		boolean[] allowed = {true}; int[] captures = {0}, reads = {0};
		var source = new Source() {
			@Override public boolean valid() { return allowed[0]; }
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return InventoryItemBudget.workspaceUpperBound(4096) + 128; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() {
					captures[0]++; assertTrue(host[0].memory().reserved() >= memoryUpperBoundBytes());
					return Optional.of(new InventorySourceAccess.InventorySnapshot() {
						@Override public int slots() { return 2; }
						@Override public int displayBytes() { return 4096; }
						@Override public long retainedBytes() { return 128; }
						@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("lazy grant dropped"); }
						@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission allowance) {
							assertTrue(host[0].memory().reserved() >= InventoryItemBudget.workspaceUpperBound(4096) + InventoryCatalogAdmission.METADATA_BYTES);
							grants.add(allowance); reads[0]++; return intern(catalog, detached, allowance);
						}
					});
				}
			}); }
		};
		try (var runtime = new InventoryRuntime(ignored -> Optional.of(source), settings.pendingMemoryBytes())) {
			host[0] = runtime; runtime.advance(0, settings);
			var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertEquals(1, captures[0]); assertEquals(0, reads[0]); assertEquals(0, runtime.memory().reserved(), "capture scratch has CLOSED");
			var first = runtime.step(consumer, 1).orElseThrow(); assertEquals(AMOUNT, first.slots().getFirst().count());
			assertEquals(0, runtime.memory().reserved()); assertEquals(1, reads[0]);
			allowed[0] = false;
			assertEquals(CaptureResult.Availability.INVALID, runtime.step(consumer, 1).orElseThrow().result().availability());
			assertEquals(1, reads[0], "authority denial precedes lazy intern"); assertEquals(1, captures[0]);
		}
	}

	@Test void runtimeOldReportedMaximumSurvivesNewLowerCeilingAndFreshStepsDoNotReuseExpiredGrant() {
		var settings = InventorySettings.serverDefaults(); var grants = new ArrayList<InventoryCatalogAdmission>();
		String old = "{\"id\":\"test:value\",\"components\":{\"!minecraft:lore\":{},\"minecraft:custom_data\":{\"text\":\"" + "x".repeat(70000) + "\"}}}";
		var catalog = catalog(131072); var input = detached(old); InventoryRuntime[] host = new InventoryRuntime[1];
		var source = new Source() {
			@Override public int displayBytes() { return 131072; }
			@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission allowance) {
				assertTrue(host[0].memory().reserved() >= InventoryItemBudget.workspaceUpperBound(131072));
				grants.add(allowance); return intern(catalog, input, allowance);
			}
		};
		try (var runtime = new InventoryRuntime(ignored -> Optional.of(source), settings.pendingMemoryBytes())) {
			host[0] = runtime; runtime.advance(0, settings);
			var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			assertEquals(old, runtime.step(consumer, 1).orElseThrow().slots().getFirst().displayJson());
			settings.setDisplayByteMultiplier(new java.math.BigDecimal("0.25")); runtime.advance(1, settings);
			assertEquals(old, runtime.step(consumer, 1).orElseThrow().slots().getFirst().displayJson());
			assertEquals(2, grants.size()); assertNotSame(grants.get(0), grants.get(1)); assertEquals(0, runtime.memory().reserved());
		}
	}

	@Test void runtimeSelectionAndCursorlessReadForwardOneGrantInsteadOfCompatibilityCallbacks() {
		var settings = InventorySettings.serverDefaults(); var catalog = catalog(4096); var input = detached(null);
		var grants = new ArrayList<InventoryCatalogAdmission>(); InventoryRuntime[] host = new InventoryRuntime[1];
		var key = catalog.intern(input, new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096), InventoryCatalogAdmission.workUpperBound(4096))).key();
		var selection = new InventorySelection(key, ID, LABEL, null, false, "danger");
		var source = new Source() {
			@Override public boolean stableCursor() { return false; }
			@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection requested) { throw new AssertionError("selection grant dropped"); }
			@Override public InventorySourceAccess.SelectionWitness observeSelection(int limit, InventorySelection requested, InventoryCatalogAdmission allowance) {
				assertTrue(host[0].memory().reserved() >= InventoryItemBudget.workspaceUpperBound(4096));
				grants.add(allowance); return new InventorySourceAccess.SelectionWitness(intern(catalog, input, allowance).key().equals(requested.exact()), 1);
			}
			@Override public InventorySourceAccess.Page enumerate(int limit, InventoryCatalogAdmission allowance) {
				grants.add(allowance); return new InventorySourceAccess.Page(List.of(intern(catalog, input, allowance), intern(catalog, input, allowance)), true);
			}
		};
		try (var runtime = new InventoryRuntime(ignored -> Optional.of(source), settings.pendingMemoryBytes())) {
			host[0] = runtime; runtime.advance(0, settings);
			assertTrue(runtime.selectionPresent(INPUT, selection, 0));
			var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			assertEquals(2, runtime.step(consumer, 2).orElseThrow().slots().size());
			assertEquals(2, grants.size()); assertNotSame(grants.get(0), grants.get(1)); assertTrue(grants.get(1).attemptedWork() > grants.get(0).attemptedWork());
			assertEquals(0, runtime.memory().reserved());
		}
	}
}
