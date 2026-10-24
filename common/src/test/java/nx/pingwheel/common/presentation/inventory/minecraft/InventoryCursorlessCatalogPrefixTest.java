package nx.pingwheel.common.presentation.inventory.minecraft;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySelection;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native source/freezer/Catalog junction over a synchronous every-view provider. */
class InventoryCursorlessCatalogPrefixTest {

	private static final int CAP = 4096, ALLOWANCE = 64;
	private static final long AMOUNT = 9007199254740993L;
	private static final InventorySourceInput INPUT = new InventorySourceInput(
		new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:chest"), new UUID(83, 1), BlockFace.NORTH);
	private static final InventorySelection ABSENT = new InventorySelection(
		new InventoryScanner.Key("minecraft:stone", "0".repeat(64)), "minecraft:stone", "block.minecraft.stone", null, false, "danger");

	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	private static final class World implements InventoryMinecraftSources.SourceWorld {
		boolean refuseRegistries;
		Runnable registryRead = () -> {};
		@Override public boolean isLoaded(BlockPos pos) { throw new AssertionError("no world lookup"); }
		@Override public BlockState blockState(BlockPos pos) { throw new AssertionError("no world lookup"); }
		@Override public BlockEntity blockEntity(BlockPos pos) { throw new AssertionError("no world lookup"); }
		@Override public boolean onServerThread() { return true; }
		@Override public ItemStack heldKey() { return ItemStack.EMPTY; }
		@Override public HolderLookup.Provider registries() {
			registryRead.run();
			if (refuseRegistries) throw new InventoryItemBudget.CaptureLimitException("before provider entry");
			return RegistryAccess.EMPTY;
		}
	}
	/** Like the native cursorless contract: one handoff for every physical view, blanks included. */
	private static class Views implements IPlatformInventoryService.Access {
		final int count, blank;
		int reads, calls, receivedLimit, providerFailure = -1;
		Views(int count, int blank) { this.count = count; this.blank = blank; }
		@Override public boolean valid() { return true; }
		@Override public int slots() { return 0; }
		@Override public boolean stableCursor() { return false; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("no Entry copy"); }
		@Override public boolean visit(int limit, Consumer<IPlatformInventoryService.Entry> visitor) { throw new AssertionError("no Entry copy"); }
		@Override public IPlatformInventoryService.ResidentVisit observeResident(int limit, IPlatformInventoryService.ResidentVisitor visitor) {
			calls++; receivedLimit = limit;
			for (int index = 0; index < Math.min(count, limit); index++) {
				reads++; // Attempt before the getter/handoff; a throw cannot execute a later view.
				if (index == providerFailure) throw new IllegalStateException("opaque provider getter");
				var entry = index == blank ? IPlatformInventoryService.ResidentEntry.empty()
					: new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(new ItemStack(Items.STONE)), AMOUNT);
				if (!visitor.visit(entry)) return new IPlatformInventoryService.ResidentVisit(index + 1, false);
			}
			return new IPlatformInventoryService.ResidentVisit(Math.min(count, limit), count <= limit);
		}
	}
	@Test void cursorlessPageAndExactWitnessFreezeBeforeProviderReuseWithMetadataAlreadyAcquired() {
		for (boolean select : new boolean[] {false, true}) for (boolean revoke : new boolean[] {false, true}) {
			var world = new World(); var stack = new ItemStack(Items.STONE, 7);
			var name = net.minecraft.network.chat.Component.literal("getter-time");
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
			var expected = new InventoryItemCodec.Catalog(RegistryAccess.EMPTY).encode(stack);
			var selection = new InventorySelection(expected.key(), "minecraft:stone", expected.display().label(), expected.display().displayJson(), false, "danger");
			var events = new java.util.ArrayList<String>(); boolean[] returned = {false}, valid = {true};
			world.registryRead = () -> { events.add("registries"); if (returned[0]) name.append("-registry"); };
			var views = new Views(1, -1) {
				@Override public boolean valid() { return valid[0]; }
				@Override public IPlatformInventoryService.ResidentVisit observeResident(int limit, IPlatformInventoryService.ResidentVisitor visitor) {
					events.add("getter"); returned[0] = true; reads++;
					boolean more = visitor.visit(new IPlatformInventoryService.ResidentEntry(new InventoryResidentItem.Stack(stack), AMOUNT));
					events.add("postcheck"); stack.setCount(0); name.append("-postcheck");
					valid[0] = !revoke;
					return new IPlatformInventoryService.ResidentVisit(1, more);
				}
			};
			try (var source = source(world, views)) {
				events.clear();
				if (revoke) assertThrows(InventorySourceUnavailableException.class, () -> {
					if (select) source.observeSelection(1, selection); else source.enumerate(1);
				});
				else if (select) { var witness = source.observeSelection(1, selection); assertTrue(witness.present()); assertEquals(1, witness.visited()); }
				else {
					var page = source.enumerate(1); assertTrue(page.complete()); var item = page.slots().getFirst();
					assertEquals(AMOUNT, item.count()); assertEquals(expected.key(), item.key()); assertFalse(item.displayJson().contains("postcheck"));
				}
			}
			assertEquals(List.of("registries", "getter", "postcheck"), events);
			assertEquals(0, stack.getCount()); assertEquals("getter-time-postcheck", name.getString());
		}
	}
	private static InventoryMinecraftSources.Source source(World world, Views views) {
		var view = new InventoryMinecraftSources.View("test:cursorless_prefix", List.of(), List.of(), views);
		return new InventoryMinecraftSources.Source(INPUT, view, () -> true, () -> view,
			entry -> { throw new AssertionError("native resident route only"); }, world, null);
	}
	private static CostLedger ledger(int slots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK));
	}
	private static InventorySourceAccess.InventoryHandle open(InventorySourceAccess.Source source) {
		var access = new InventorySourceAccess(INPUT, ignored -> Optional.of(source));
		var scope = new SourceAccess.ReadScope(INPUT.viewKey(), Set.of("pingforit:inventory.items"));
		var resolved = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget(
			"minecraft:overworld", "block", "minecraft:chest", 0, 0, 0, ""), scope, ledger(0));
		return (InventorySourceAccess.InventoryHandle) ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, ledger(0))).handle();
	}
	private static InventoryDetachedItem stone() {
		return InventoryItemCodec.freeze(new InventoryResidentItem.Stack(new ItemStack(Items.STONE)), RegistryAccess.EMPTY,
			CAP, InventoryItemBudget.root(CAP));
	}
	/** Pay successful operations then this failing operation's scan, but not its hash/map/parser. */
	private static long workThroughRefusedOperation(int successful) {
		var item = stone(); String json = item.display().displayJson();
		long scan = InventoryCatalogAdmission.scanWorkUpperBound(json == null ? 0 : json.length(), item.itemId().length(), item.display().label().length());
		int bytes = InventoryReprojectionSummary.utf8Bytes(json);
		long first = InventoryCatalogAdmission.operationWorkUpperBound(item.canonicalForCatalog().length, bytes, 0);
		long repeat = InventoryCatalogAdmission.operationWorkUpperBound(item.canonicalForCatalog().length, bytes, 1);
		return (successful + 1L) * scan + (successful == 0 ? 0 : first + (successful - 1L) * repeat);
	}
	private static InventoryCatalogAdmission grant(long work) {
		return new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(CAP), work);
	}
	private static void assertPaid(CostLedger ledger, int visits) {
		assertEquals(visits, ledger.used(InventorySourceAccess.PHYSICAL));
		assertEquals(ALLOWANCE - visits, ledger.remaining(InventorySourceAccess.PHYSICAL));
		assertEquals((visits + 1L) * InventorySourceAccess.PROVIDER_CALL_WORK, ledger.used(InventorySourceAccess.PROVIDER_WORK));
		assertEquals(1, ledger.used(InventorySourceAccess.PROBES));
	}

	@ParameterizedTest @ValueSource(ints = {1, 2, 3})
	void pageCatalogRefusalSettlesIncludingFailingNativeViewNotThe64SlotGrant(int failure) {
		var views = new Views(ALLOWANCE, -1); var ledger = ledger(ALLOWANCE);
		long paidWork = workThroughRefusedOperation(failure - 1); var grant = grant(paidWork);
		try (var handle = open(source(new World(), views))) {
			var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant)).result();
			assertEquals(CaptureResult.Availability.READABLE, result.availability());
			assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness());
			assertTrue(result.payload().isEmpty(), "no partial payload or aggregate identity fold");
			assertEquals(failure, views.reads); assertEquals(ALLOWANCE, views.receivedLimit); assertPaid(ledger, failure);
			assertEquals(paidWork, grant.attemptedWork(), "all successful operations and the failed operation's scan stay paid once");
			assertInstanceOf(SourceAccess.StepOutcome.Deferred.class, handle.step(ledger, grant));
			assertEquals(failure, views.reads); assertPaid(ledger, failure); assertEquals(paidWork, grant.attemptedWork());
		}
		assertPaid(ledger, failure); assertEquals(paidWork, grant.attemptedWork(), "close refunds no settled work");
	}

	@ParameterizedTest @ValueSource(ints = {1, 2, 3})
	void selectionCatalogRefusalSettlesTheSameNativeAttemptedPrefix(int failure) {
		var views = new Views(ALLOWANCE, -1); var ledger = ledger(ALLOWANCE);
		long paidWork = workThroughRefusedOperation(failure - 1); var grant = grant(paidWork);
		var access = new InventorySourceAccess(INPUT, ignored -> Optional.of(source(new World(), views)));
		assertFalse(access.selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant));
		assertEquals(failure, views.reads); assertEquals(ALLOWANCE, views.receivedLimit); assertPaid(ledger, failure);
		assertEquals(paidWork, grant.attemptedWork(), "SELECT keeps the one cumulative grant, including paid scan before refusal");
	}

	@Test void zeroCatalogWorkRefusesOnFirstNativeCallbackForPageAndSelection() {
		for (boolean select : List.of(false, true)) {
			var views = new Views(ALLOWANCE, -1); var ledger = ledger(ALLOWANCE); var grant = grant(0);
			var source = source(new World(), views);
			if (select) assertFalse(new InventorySourceAccess(INPUT, ignored -> Optional.of(source)).selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant));
			else try (var handle = open(source)) {
				var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant)).result();
				assertEquals(CaptureResult.Availability.READABLE, result.availability());
				assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness()); assertTrue(result.payload().isEmpty());
			}
			assertEquals(1, views.reads); assertPaid(ledger, 1); assertEquals(0, grant.attemptedWork());
		}
	}

	@Test void admittedNativeFailureCarrierIsStacklessAndLegacyFailureTypeIsUnchanged() {
		var views = new Views(ALLOWANCE, -1);
		try (var source = source(new World(), views)) {
			var failure = assertThrows(InventorySourceAccess.CursorlessReadFailure.class, () -> source.enumerate(ALLOWANCE, grant(0)));
			assertEquals(1, failure.attemptedVisits()); assertEquals(0, failure.getStackTrace().length);
			assertInstanceOf(InventoryItemBudget.CaptureLimitException.class, failure.getCause()); assertEquals(1, views.reads);
		}
		var world = new World(); var legacy = source(world, new Views(ALLOWANCE, -1)); world.refuseRegistries = true;
		try (legacy) { assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> legacy.enumerate(ALLOWANCE)); }
	}

	@Test void blankViewBeforeThirdCatalogRefusalIsStillPhysicalForPageAndSelection() {
		for (boolean select : List.of(false, true)) {
			var views = new Views(ALLOWANCE, 0); var ledger = ledger(ALLOWANCE); long work = workThroughRefusedOperation(1); var grant = grant(work);
			var source = source(new World(), views);
			if (select) assertFalse(new InventorySourceAccess(INPUT, ignored -> Optional.of(source)).selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant));
			else try (var handle = open(source)) {
				var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant)).result();
				assertEquals(CaptureResult.Availability.READABLE, result.availability()); assertTrue(result.payload().isEmpty());
				assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness());
			}
			assertEquals(3, views.reads); assertPaid(ledger, 3); assertEquals(work, grant.attemptedWork());
		}
	}

	@Test void refusalBeforeProviderEntryHasZeroPhysicalPrefix() {
		for (boolean select : List.of(false, true)) {
			var world = new World(); var views = new Views(ALLOWANCE, -1); var source = source(world, views);
			world.refuseRegistries = true; var ledger = ledger(ALLOWANCE); var grant = grant(0);
			if (select) assertFalse(new InventorySourceAccess(INPUT, ignored -> Optional.of(source)).selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant));
			else try (var handle = open(source)) {
				var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant)).result();
				assertEquals(CaptureResult.Availability.READABLE, result.availability());
				assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness()); assertTrue(result.payload().isEmpty());
			}
			assertEquals(0, views.calls); assertEquals(0, views.reads); assertPaid(ledger, 0); assertEquals(0, grant.attemptedWork());
		}
	}

	@Test void zeroPhysicalAdmissionDoesNotEnterNativeEnumerator() {
		for (boolean select : List.of(false, true)) {
			var views = new Views(ALLOWANCE, -1); var source = source(new World(), views); var ledger = ledger(0); var grant = grant(0);
			if (select) assertFalse(new InventorySourceAccess(INPUT, ignored -> Optional.of(source)).selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant));
			else try (var handle = open(source)) { assertInstanceOf(SourceAccess.StepOutcome.Deferred.class, handle.step(ledger, grant)); }
			assertEquals(0, views.calls); assertEquals(0, views.reads); assertEquals(0, ledger.used(InventorySourceAccess.PHYSICAL));
			assertEquals(0, ledger.used(InventorySourceAccess.PROVIDER_WORK)); assertEquals(0, grant.attemptedWork());
		}
	}

	@Test void successKeepsBlankCountLongAmountAndImmediateSelectionStop() {
		var views = new Views(3, 0); var ledger = ledger(ALLOWANCE); InventoryDomainCodec.Item chosen;
		try (var handle = open(source(new World(), views))) {
			var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant(3 * InventoryCatalogAdmission.workUpperBound(CAP)))).result();
			assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
			var page = (CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow();
			assertNull(InventoryDomainCodec.decode(page.entries().get("0"))); chosen = InventoryDomainCodec.decode(page.entries().get("1"));
			assertEquals(AMOUNT, chosen.count()); assertFalse(chosen.aggregateRequired()); assertPaid(ledger, 3);
		}
		var selection = new InventorySelection(chosen.key(), chosen.key().itemId(), chosen.label(), chosen.displayJson(), false, "danger");
		var selectViews = new Views(3, 0); var selectLedger = ledger(ALLOWANCE);
		assertTrue(new InventorySourceAccess(INPUT, ignored -> Optional.of(source(new World(), selectViews)))
			.selectionPresent(selection, 0, ALLOWANCE, selectLedger, grant(3 * InventoryCatalogAdmission.workUpperBound(CAP))));
		assertEquals(2, selectViews.reads); assertPaid(selectLedger, 2);
	}

	@Test void opaqueProviderGetterFailureCannotUseCallbackCountAsPhysicalEvidence() {
		for (boolean select : List.of(false, true)) {
			var views = new Views(ALLOWANCE, 0); views.providerFailure = 1;
			var source = source(new World(), views); var ledger = ledger(ALLOWANCE); var grant = grant(0);
			if (select) assertFalse(new InventorySourceAccess(INPUT, ignored -> Optional.of(source)).selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant));
			else try (var handle = open(source)) {
				var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant)).result();
				assertEquals(CaptureResult.Availability.UNAVAILABLE, result.availability()); assertTrue(result.payload().isEmpty());
			}
			assertEquals(2, views.reads); assertPaid(ledger, ALLOWANCE); assertEquals(0, grant.attemptedWork());
		}
	}

	@Test void legacyOpaqueSourceFailureKeepsConservativeWholeGrant() {
		for (boolean select : List.of(false, true)) {
			var source = new InventorySourceAccess.Source() {
				@Override public SourceKey key() { return new SourceKey("test", "inventory", "opaque", INPUT.viewKey()); }
				@Override public boolean valid() { return true; }
				@Override public boolean stableCursor() { return false; }
				@Override public int slots() { return 0; }
				@Override public InventoryDomainCodec.Item read(int slot) { throw new AssertionError("cursorless only"); }
				@Override public InventorySourceAccess.Page enumerate(int limit) { throw new IllegalStateException("opaque source"); }
			};
			var ledger = ledger(ALLOWANCE);
			if (select) assertFalse(new InventorySourceAccess(INPUT, ignored -> Optional.of(source)).selectionPresent(ABSENT, 0, ALLOWANCE, ledger, grant(0)));
			else try (var handle = open(source)) {
				var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger, grant(0))).result();
				assertEquals(CaptureResult.Availability.UNAVAILABLE, result.availability()); assertTrue(result.payload().isEmpty());
			}
			assertPaid(ledger, ALLOWANCE);
		}
	}
}
