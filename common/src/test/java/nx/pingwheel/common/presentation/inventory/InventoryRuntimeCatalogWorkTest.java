package nx.pingwheel.common.presentation.inventory;

import java.util.Optional;
import java.util.UUID;
import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryCatalogAdmission;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UNRUN. Refusal before callbacks and permanent work settlement through public Runtime behavior. */
class InventoryRuntimeCatalogWorkTest {
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(83, 1), BlockFace.NORTH);
	private static final InventoryScanner.Key KEY = new InventoryScanner.Key("test:value", "exact");
	private static class Source implements InventorySourceAccess.Source {
		int reads, witnesses;
		@Override public SourceKey key() { return new SourceKey("test", "inventory", "work", INPUT.viewKey()); }
		@Override public boolean valid() { return true; }
		@Override public boolean stableCursor() { return true; }
		@Override public int slots() { return 1; }
		@Override public int displayBytes() { return 4096; }
		@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("Catalog allowance dropped"); }
		@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission grant) { reads++; throw new IllegalStateException("throwing attempted source read"); }
		@Override public boolean matchesSelection(int index, InventorySelection selection, InventoryCatalogAdmission grant) { witnesses++; return true; }
	}
	private static long periodWork() { return InventorySourceAccess.MAX_SLOTS * InventoryItemBudget.workUpperBound(4096); }
	@Test void exhaustedReadWorkPrecedesGetterAndSelectAndReturnsNextTickWithoutReadInflation() {
		var source = new Source(); var settings = InventorySettings.serverDefaults(); int[] resolves = {0};
		try (var runtime = new InventoryRuntime(ignored -> { resolves[0]++; return Optional.of(source); }, settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			assertEquals(InventorySourceAccess.Preparation.READY, runtime.prepare(consumer));
			assertTrue(runtime.claimDetachedItemWork(periodWork()));
			assertTrue(runtime.step(consumer, 1).isEmpty()); assertEquals(0, source.reads);
			assertFalse(runtime.selectionPresent(INPUT, new InventorySelection(KEY, KEY.itemId(), "item.test.value", null, false, "danger"), 0));
			assertEquals(0, source.witnesses); assertEquals(1, resolves[0]); assertEquals(0, runtime.memory().reserved());
			runtime.advance(1, settings);
			var failed = runtime.step(consumer, 1).orElseThrow(); assertEquals(CaptureResult.Availability.UNAVAILABLE, failed.result().availability());
			assertEquals(1, source.reads); assertEquals(0, runtime.memory().reserved());
			assertTrue(runtime.claimDetachedItemWork(periodWork() - InventoryItemBudget.freezeWorkUpperBound(4096)),
				"exactly one failed attempted slot remains settled, no extra Catalog debit when none ran");
			assertFalse(runtime.claimDetachedItemWork(1));
		}
	}
	@Test void lazyReadKeepsSourceWorkEnvelopeWithoutDoubleCatalogOrPhysicalProgress() {
		var settings = InventorySettings.serverDefaults(); int[] snapshotReads = {0};
		var source = new Source() {
			@Override public Optional<InventorySourceAccess.SnapshotPlan> snapshotPlan() { return Optional.of(new InventorySourceAccess.SnapshotPlan() {
				@Override public long memoryUpperBoundBytes() { return 128; }
				@Override public Optional<InventorySourceAccess.InventorySnapshot> capture() { return Optional.of(new InventorySourceAccess.InventorySnapshot() {
					@Override public int slots() { return 1; }
					@Override public int displayBytes() { return 4096; }
					@Override public long retainedBytes() { return 128; }
					@Override public InventoryDomainCodec.Item read(int index) { throw new AssertionError("grant dropped"); }
					@Override public InventoryDomainCodec.Item read(int index, InventoryCatalogAdmission grant) {
						snapshotReads[0]++; return new InventoryDomainCodec.Item(KEY, 7, "item.test.value", null, false);
					}
				}); }
			}); }
		};
		try (var runtime = new InventoryRuntime(ignored -> Optional.of(source), settings.pendingMemoryBytes())) {
			runtime.advance(0, settings); var consumer = runtime.attach(INPUT, new InventoryRuntime.PreviewSubject(INPUT.readOwner())).orElseThrow();
			var result = runtime.step(consumer, 1).orElseThrow(); assertEquals(1, result.result().coverage().scanned()); assertEquals(1, snapshotReads[0]);
			assertTrue(runtime.claimDetachedItemWork(periodWork() - InventoryItemBudget.freezeWorkUpperBound(4096)),
				"one source/page work envelope, no Catalog operation or duplicate debit in this synthetic lazy read");
			assertFalse(runtime.claimDetachedItemWork(1)); assertEquals(0, runtime.memory().reserved());
		}
	}
}
