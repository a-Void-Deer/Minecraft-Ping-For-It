package nx.pingwheel.forge.platform;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import nx.pingwheel.common.platform.IPlatformInventoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Forge handler wrapper and lookup path, with a recording native handler. */
class PlatformInventoryServiceMemberGateTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	private static final class NativeHandler {
		final AtomicBoolean member = new AtomicBoolean(true);
		final AtomicInteger counts = new AtomicInteger(), reads = new AtomicInteger();
		Runnable afterCount = () -> {}, afterRead = () -> {};
		IItemHandler handler() {
			return (IItemHandler) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IItemHandler.class}, (proxy, method, args) -> {
				assertTrue(member.get(), "native handler called after membership revocation");
				return switch (method.getName()) {
					case "getSlots" -> { counts.incrementAndGet(); afterCount.run(); yield 2; }
					case "getStackInSlot" -> { reads.incrementAndGet(); afterRead.run(); yield new ItemStack(Items.STONE, 5); }
					default -> throw new AssertionError("unexpected native operation: " + method.getName());
				};
			});
		}
		IPlatformInventoryService.Access access() { return new PlatformInventoryServiceImpl.HandlerAccess(handler()).guardedBy(member::get); }
	}
	private static PlatformInventoryServiceImpl.World world(NativeHandler nativeHandler, Consumer<String> callback) {
		BlockEntity entity = new BeehiveBlockEntity(BlockPos.ZERO, Blocks.BEEHIVE.defaultBlockState());
		return new PlatformInventoryServiceImpl.World() {
			@Override public boolean loaded(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); callback.accept("loaded"); return true; }
			@Override public BlockEntity blockEntity(BlockPos pos) { assertEquals(BlockPos.ZERO, pos); callback.accept("entity"); return entity; }
			@Override public Optional<IItemHandler> handler(BlockEntity current, Direction side) {
				assertSame(entity, current); assertEquals(Direction.EAST, side); callback.accept("capability"); return Optional.of(nativeHandler.handler());
			}
		};
	}
	@Test void reentrantLookupRevocationStopsBeforeNextWorldOperationOrNativeContent() {
		for (String revokeAt : List.of("loaded", "entity", "capability")) {
			NativeHandler nativeHandler = new NativeHandler(); var calls = new java.util.ArrayList<String>();
			var world = world(nativeHandler, operation -> {
				assertTrue(nativeHandler.member.get(), "revoked lookup advanced to " + operation); calls.add(operation);
				if (operation.equals(revokeAt)) nativeHandler.member.set(false);
			});
			assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.EAST, pos -> nativeHandler.member.get()).isEmpty());
			assertEquals(revokeAt, calls.getLast()); assertEquals(0, nativeHandler.counts.get()); assertEquals(0, nativeHandler.reads.get());
		}
	}
	@Test void deniedRootDoesNotReadWorldAndReturnedAccessGatesAfterNativeSlotCount() {
		NativeHandler nativeHandler = new NativeHandler(); AtomicInteger calls = new AtomicInteger(); var world = world(nativeHandler, ignored -> calls.incrementAndGet());
		assertTrue(PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.EAST, pos -> false).isEmpty()); assertEquals(0, calls.get());
		var access = PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.EAST, pos -> nativeHandler.member.get()).orElseThrow();
		nativeHandler.afterCount = () -> nativeHandler.member.set(false);
		assertThrows(IllegalStateException.class, () -> access.read(0)); assertEquals(1, nativeHandler.counts.get());
		assertEquals(0, nativeHandler.reads.get()); assertFalse(access.valid());
	}
	@Test void reentrantContentRevocationCannotReturnAnEntryAndRevokedWrapperCannotReplayIt() {
		NativeHandler nativeHandler = new NativeHandler(); var access = nativeHandler.access(); nativeHandler.afterRead = () -> nativeHandler.member.set(false);
		assertThrows(IllegalStateException.class, () -> access.read(0)); assertEquals(1, nativeHandler.reads.get()); assertFalse(access.valid());
		assertThrows(IllegalStateException.class, () -> access.read(1)); assertEquals(1, nativeHandler.reads.get());
	}
	@Test void indexedObserveAndVisitStopBeforeSecondNativeReadAfterFirstEntryRevokes() {
		for (boolean visit : List.of(false, true)) {
			NativeHandler nativeHandler = new NativeHandler(); var access = nativeHandler.access(); AtomicInteger delivered = new AtomicInteger();
			if (visit) {
				assertThrows(IllegalStateException.class, () -> access.visit(2, entry -> { delivered.incrementAndGet(); nativeHandler.member.set(false); }));
				assertEquals(1, delivered.get());
			} else {
				nativeHandler.afterRead = () -> nativeHandler.member.set(false); assertThrows(IllegalStateException.class, () -> access.observe(2));
			}
			assertEquals(1, nativeHandler.reads.get()); assertFalse(access.valid());
		}
	}
	@Test void ordinaryMetadataAndContentsRemainUnchangedWhileNestedGuardsConjoin() {
		NativeHandler nativeHandler = new NativeHandler(); var ordinary = new PlatformInventoryServiceImpl.HandlerAccess(nativeHandler.handler());
		assertTrue(ordinary.stableCursor()); assertEquals(2, ordinary.slots()); assertTrue(ordinary.version().isEmpty());
		assertEquals(5, ordinary.read(0).amount()); assertEquals(Items.STONE, ordinary.read(0).exemplar().getItem());
		var guarded = ordinary.guardedBy(nativeHandler.member::get).guardedBy(() -> false);
		assertThrows(IllegalStateException.class, () -> guarded.read(0)); assertEquals(2, nativeHandler.reads.get());
	}
	@Test void fatalLookupAndGuardFailuresAreNotSwallowed() {
		NativeHandler nativeHandler = new NativeHandler(); AssertionError fatal = new AssertionError("fatal provider");
		var world = world(nativeHandler, operation -> { if (operation.equals("capability")) throw fatal; });
		assertSame(fatal, assertThrows(AssertionError.class, () -> PlatformInventoryServiceImpl.find(world, BlockPos.ZERO, Direction.EAST, pos -> true)));
		assertSame(fatal, assertThrows(AssertionError.class, () -> nativeHandler.access().guardedBy(() -> { throw fatal; }).valid()));
	}
}
