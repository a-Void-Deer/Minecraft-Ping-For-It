package nx.pingwheel.fabric.platform;

import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import nx.pingwheel.common.domain.BlockFace;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.inventory.InventoryDomainCodec;
import nx.pingwheel.common.presentation.inventory.InventoryScanner;
import nx.pingwheel.common.presentation.inventory.InventorySourceAccess;
import nx.pingwheel.common.presentation.inventory.InventorySourceInput;
import nx.pingwheel.common.presentation.source.CaptureResult;
import nx.pingwheel.common.presentation.source.CostLedger;
import nx.pingwheel.common.presentation.source.SourceAccess;
import nx.pingwheel.common.presentation.source.SourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Transfer access wrappers and lookup path; proxies implement only the native read-only port. */
class PlatformInventoryServiceMemberGateTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static final class NativeStorage {
		final AtomicBoolean member = new AtomicBoolean(true);
		final AtomicInteger slotLookups = new AtomicInteger(), blanks = new AtomicInteger(), amounts = new AtomicInteger(), resources = new AtomicInteger(), nextCalls = new AtomicInteger();
		Runnable afterCount = () -> {}, afterSlot = () -> {}, afterBlank = () -> {}, afterAmount = () -> {}, afterResource = () -> {}, afterHasNext = () -> {}, afterNext = () -> {};
		int count = 3; boolean blank;
		// ItemVariant.of requires Fabric's cache mixin; this headless native port
		// supplies the detached exemplar without pretending the loader was started.
		ItemVariant resource() {
			return (ItemVariant) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ItemVariant.class}, (proxy, method, args) -> {
				if (method.getName().equals("toStack")) return new ItemStack(Items.STONE, ((Number) args[0]).intValue());
				throw new AssertionError("unexpected variant operation: " + method.getName());
			});
		}
		@SuppressWarnings("unchecked") StorageView<ItemVariant> view() {
			return (StorageView<ItemVariant>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SingleSlotStorage.class}, (proxy, method, args) -> {
				assertTrue(member.get(), "native view called after membership revocation");
				return switch (method.getName()) {
					case "isResourceBlank" -> { blanks.incrementAndGet(); afterBlank.run(); yield blank; }
					case "getAmount" -> { amounts.incrementAndGet(); afterAmount.run(); yield 5L; }
					case "getResource" -> { resources.incrementAndGet(); afterResource.run(); yield resource(); }
					default -> throw new AssertionError("unexpected native operation: " + method.getName());
				};
			});
		}
		@SuppressWarnings("unchecked") Storage<ItemVariant> storage(boolean slotted) {
			Class<?> type = slotted ? SlottedStorage.class : Storage.class;
			return (Storage<ItemVariant>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
				assertTrue(member.get(), "native storage called after membership revocation");
				return switch (method.getName()) {
					case "getSlotCount" -> { afterCount.run(); yield count; }
					case "getSlot" -> { slotLookups.incrementAndGet(); afterSlot.run(); yield view(); }
					case "getVersion" -> 11L;
					case "iterator" -> new Iterator<StorageView<ItemVariant>>() {
						int cursor;
						@Override public boolean hasNext() { assertTrue(member.get()); afterHasNext.run(); return cursor < count; }
						@Override public StorageView<ItemVariant> next() { assertTrue(member.get()); cursor++; nextCalls.incrementAndGet(); afterNext.run(); return view(); }
					};
					default -> throw new AssertionError("unexpected native operation: " + method.getName());
				};
			});
		}
		SlottedStorage<ItemVariant> slottedStorage() { return (SlottedStorage<ItemVariant>) storage(true); }
		IPlatformInventoryService.Access slotted() {
			return new PlatformInventoryServiceImpl.SlottedAccess(slottedStorage()).guardedBy(member::get);
		}
		IPlatformInventoryService.Access enumerated() {
			return new PlatformInventoryServiceImpl.EnumeratedAccess(storage(false)).guardedBy(member::get);
		}
	}
	private static PlatformInventoryServiceImpl.World world(NativeStorage nativeStorage, Consumer<String> callback) {
		BlockEntity entity = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		return new PlatformInventoryServiceImpl.World() {
			@Override public boolean loaded(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); callback.accept("loaded"); return true; }
			@Override public BlockEntity blockEntity(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); callback.accept("entity"); return entity; }
			@Override public Storage<ItemVariant> storage(BlockPos pos, Direction side) {
				assertEquals(BlockPos.ZERO, pos); assertEquals(Direction.WEST, side); callback.accept("capability"); return nativeStorage.slottedStorage();
			}
		};
	}
	private static InventorySourceAccess.InventoryHandle openEnumeration(IPlatformInventoryService.Access provider) {
		var input = new InventorySourceInput(new Target.BlockTarget("minecraft:overworld", 0, 0, 0, "minecraft:beehive"), new UUID(7, 7), BlockFace.NORTH);
		var source = new InventorySourceAccess.Source() {
			@Override public SourceKey key() { return new SourceKey("fabric", "block_inventory", "test-storage", input.viewKey()); }
			@Override public boolean valid() { return provider.valid(); }
			@Override public int slots() { return provider.slots(); }
			@Override public boolean stableCursor() { return provider.stableCursor(); }
			@Override public InventoryDomainCodec.Item read(int slot) { throw new AssertionError("cursorless storage"); }
			@Override public InventorySourceAccess.Page enumerate(int limit) {
				var page = provider.observe(limit);
				return new InventorySourceAccess.Page(page.entries().stream().map(entry -> entry.isEmpty() ? null : new InventoryDomainCodec.Item(
					new InventoryScanner.Key("minecraft:stone", "plain"), entry.amount(), "minecraft:stone", null, false)).toList(), page.complete());
			}
		};
		var access = new InventorySourceAccess(input, ignored -> Optional.of(source));
		var scope = new SourceAccess.ReadScope(input.viewKey(), Set.of("pingforit:inventory.items"));
		var resolved = (SourceAccess.ResolveResult.Available) access.resolve(new PresentationAdapter.DetachedTarget("minecraft:overworld", "block", "minecraft:beehive", 0, 0, 0, ""), scope, grant(0));
		return (InventorySourceAccess.InventoryHandle) ((SourceAccess.OpenResult.Started) access.open(resolved.descriptor(), scope, grant(0))).handle();
	}
	private static CostLedger grant(int slots) {
		return new CostLedger(Map.of(InventorySourceAccess.PHYSICAL, (long) slots, InventorySourceAccess.PROBES, 8L,
			InventorySourceAccess.PROVIDER_WORK, InventorySourceAccess.MAX_PROVIDER_WORK_PER_TICK));
	}
	@Test void reentrantLookupRevocationStopsBeforeNextWorldOperationOrNativeContent() {
		for (String revokeAt : List.of("loaded", "entity", "capability")) {
			NativeStorage nativeStorage = new NativeStorage(); var calls = new java.util.ArrayList<String>();
			var world = world(nativeStorage, operation -> {
				assertTrue(nativeStorage.member.get(), "revoked lookup advanced to " + operation); calls.add(operation);
				if (operation.equals(revokeAt)) nativeStorage.member.set(false);
			});
			assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.WEST, pos -> nativeStorage.member.get()).isEmpty());
			assertEquals(revokeAt, calls.getLast()); assertEquals(0, nativeStorage.blanks.get()); assertEquals(0, nativeStorage.slotLookups.get());
		}
	}
	@Test void deniedRootDoesNotReadWorldAndAdmittedLookupReturnsAnInternallyGuardedAccess() {
		NativeStorage nativeStorage = new NativeStorage(); AtomicInteger calls = new AtomicInteger(); var world = world(nativeStorage, ignored -> calls.incrementAndGet());
		assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.WEST, pos -> false).isEmpty()); assertEquals(0, calls.get());
		var access = PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.WEST, pos -> nativeStorage.member.get()).orElseThrow();
		nativeStorage.afterCount = () -> nativeStorage.member.set(false);
		assertThrows(IllegalStateException.class, () -> access.read(0)); assertEquals(0, nativeStorage.slotLookups.get()); assertFalse(access.valid());
	}
	@Test void slottedReadRechecksCountSlotAndEveryNativeViewRead() {
		for (String revokeAt : List.of("count", "slot", "blank", "amount", "resource")) {
			NativeStorage nativeStorage = new NativeStorage(); Runnable revoke = () -> nativeStorage.member.set(false);
			switch (revokeAt) {
				case "count" -> nativeStorage.afterCount = revoke;
				case "slot" -> nativeStorage.afterSlot = revoke;
				case "blank" -> nativeStorage.afterBlank = revoke;
				case "amount" -> nativeStorage.afterAmount = revoke;
				case "resource" -> nativeStorage.afterResource = revoke;
			}
			var access = nativeStorage.slotted(); assertThrows(IllegalStateException.class, () -> access.read(0)); assertFalse(access.valid());
			assertEquals(revokeAt.equals("count") ? 0 : 1, nativeStorage.slotLookups.get());
			assertEquals(List.of("count", "slot").contains(revokeAt) ? 0 : 1, nativeStorage.blanks.get());
			assertEquals(List.of("amount", "resource").contains(revokeAt) ? 1 : 0, nativeStorage.amounts.get());
			assertEquals(revokeAt.equals("resource") ? 1 : 0, nativeStorage.resources.get());
		}
	}
	@Test void cursorlessObserveRechecksIteratorMetadataAndNextBeforeVisitingContent() {
		for (boolean revokeInHasNext : List.of(false, true)) {
			NativeStorage nativeStorage = new NativeStorage(); Runnable revoke = () -> nativeStorage.member.set(false);
			if (revokeInHasNext) nativeStorage.afterHasNext = revoke; else nativeStorage.afterNext = revoke;
			var access = nativeStorage.enumerated(); assertThrows(IllegalStateException.class, () -> access.observe(2));
			assertEquals(revokeInHasNext ? 0 : 1, nativeStorage.nextCalls.get()); assertEquals(0, nativeStorage.blanks.get()); assertFalse(access.valid());
		}
	}
	@Test void cursorlessObserveAndVisitStopBetweenViewsAndRevocationCannotReplayAsValid() {
		for (boolean visit : List.of(false, true)) {
			NativeStorage nativeStorage = new NativeStorage(); var access = nativeStorage.enumerated(); AtomicInteger delivered = new AtomicInteger();
			if (visit) {
				assertThrows(IllegalStateException.class, () -> access.visit(2, entry -> { delivered.incrementAndGet(); nativeStorage.member.set(false); }));
				assertEquals(1, delivered.get());
			} else {
				nativeStorage.afterResource = () -> nativeStorage.member.set(false); assertThrows(IllegalStateException.class, () -> access.observe(2));
			}
			assertEquals(1, nativeStorage.nextCalls.get()); assertEquals(1, nativeStorage.blanks.get()); assertEquals(1, nativeStorage.amounts.get());
			assertFalse(access.valid()); assertThrows(IllegalStateException.class, () -> access.observe(2)); assertEquals(1, nativeStorage.nextCalls.get());
		}
	}
	@Test void cursorlessBlankViewsConsumeBoundsAndOrdinaryUnguardedContentsRemainUnchanged() {
		NativeStorage nativeStorage = new NativeStorage(); nativeStorage.blank = true; var access = nativeStorage.enumerated();
		var bounded = access.observe(2); assertEquals(2, bounded.entries().size()); assertFalse(bounded.complete());
		assertTrue(bounded.entries().stream().allMatch(IPlatformInventoryService.Entry::isEmpty)); assertEquals(2, nativeStorage.nextCalls.get());
		assertEquals(0, nativeStorage.amounts.get()); nativeStorage.blank = false;
		var ordinary = new PlatformInventoryServiceImpl.SlottedAccess(nativeStorage.slottedStorage());
		assertTrue(ordinary.stableCursor()); assertEquals(3, ordinary.slots()); assertEquals(11, ordinary.version().orElseThrow());
		assertEquals(5, ordinary.read(0).amount()); assertEquals(Items.STONE, ordinary.read(0).exemplar().getItem());
	}
	@Test void cursorlessRevokedStepChargesTheAdmittedBoundPublishesNoPartialOrZeroAndFreshHandleRecovers() {
		NativeStorage nativeStorage = new NativeStorage(); nativeStorage.afterResource = () -> nativeStorage.member.set(false);
		try (var handle = openEnumeration(nativeStorage.enumerated())) {
			CostLedger ledger = grant(3); var result = ((SourceAccess.StepOutcome.Captured) handle.step(ledger)).result();
			assertEquals(CaptureResult.Availability.UNAVAILABLE, result.availability()); assertEquals(CaptureResult.Completeness.INCOMPLETE, result.completeness());
			assertTrue(result.payload().isEmpty()); assertFalse(handle.evidenceValid()); assertEquals(1, nativeStorage.nextCalls.get());
			assertEquals(3, ledger.used(InventorySourceAccess.PHYSICAL)); assertEquals(1, ledger.used(InventorySourceAccess.PROBES));
			assertEquals(4 * InventorySourceAccess.PROVIDER_CALL_WORK, ledger.used(InventorySourceAccess.PROVIDER_WORK));
			assertInstanceOf(SourceAccess.StepOutcome.Deferred.class, handle.step(grant(3))); assertEquals(1, nativeStorage.nextCalls.get());
		}
		nativeStorage.member.set(true); nativeStorage.afterResource = () -> {};
		try (var fresh = openEnumeration(nativeStorage.enumerated())) {
			CostLedger ledger = grant(3); var result = ((SourceAccess.StepOutcome.Captured) fresh.step(ledger)).result();
			assertEquals(CaptureResult.Availability.READABLE, result.availability()); assertEquals(CaptureResult.Completeness.COMPLETE, result.completeness());
			assertEquals(3, ((CaptureResult.OpaqueKeyedFragment) result.payload().orElseThrow()).entries().size()); assertTrue(fresh.evidenceValid());
			assertEquals(4, nativeStorage.nextCalls.get()); assertEquals(3, ledger.used(InventorySourceAccess.PHYSICAL));
		}
	}
	@Test void nestedGuardsAreConjoinedAndFatalGuardFailureIsNotSwallowed() {
		NativeStorage nativeStorage = new NativeStorage(); var access = nativeStorage.slotted().guardedBy(() -> false);
		assertThrows(IllegalStateException.class, () -> access.read(0)); assertEquals(0, nativeStorage.slotLookups.get());
		AssertionError fatal = new AssertionError("fatal guard"); BooleanSupplier gate = () -> { throw fatal; };
		assertSame(fatal, assertThrows(AssertionError.class, () -> nativeStorage.enumerated().guardedBy(gate).valid()));
	}
}
