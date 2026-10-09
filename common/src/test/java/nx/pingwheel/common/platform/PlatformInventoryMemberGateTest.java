package nx.pingwheel.common.platform;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotSchemas;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInventoryMemberGateTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
	/** Identity-only level token: the recording provider must receive it, never dereference it. */
	private static ServerLevel levelToken() throws ReflectiveOperationException {
		Class<?> type = Class.forName("sun.misc.Unsafe");
		var field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (ServerLevel) type.getMethod("allocateInstance", Class.class).invoke(field.get(null), ServerLevel.class);
	}
	private static final class RecordingAccess implements IPlatformInventoryService.Access {
		final AtomicInteger reads = new AtomicInteger(), observations = new AtomicInteger(), visits = new AtomicInteger();
		Runnable afterSlots = () -> {}, afterRead = () -> {}; boolean stable = true;
		InventorySnapshotLayout layout;
		@Override public Optional<String> alias() { return Optional.of("alias"); }
		@Override public Optional<InventorySnapshotLayout> snapshotLayout() { return Optional.ofNullable(layout); }
		@Override public int slots() { afterSlots.run(); return 2; }
		@Override public boolean stableCursor() { return stable; }
		@Override public OptionalLong version() { return OptionalLong.of(7); }
		@Override public IPlatformInventoryService.Entry read(int slot) {
			reads.incrementAndGet(); afterRead.run(); return new IPlatformInventoryService.Entry(new ItemStack(Items.STONE), slot + 1);
		}
		@Override public IPlatformInventoryService.Budgeted observe(int limit) {
			observations.incrementAndGet(); throw new AssertionError("opaque provider enumeration must not be invoked by the default guard");
		}
		@Override public boolean visit(int limit, java.util.function.Consumer<IPlatformInventoryService.Entry> consumer) {
			visits.incrementAndGet(); throw new AssertionError("opaque provider visit must not be invoked by the default guard");
		}
	}

	@Test void defaultOverloadDeniesRootBeforeCallingSinglePositionProvider() throws ReflectiveOperationException {
		AtomicInteger calls = new AtomicInteger();
		IPlatformInventoryService service = (level, pos, face) -> { calls.incrementAndGet(); return Optional.empty(); };
		assertTrue(service.find(levelToken(), BlockPos.ZERO, Direction.WEST, pos -> false).isEmpty());
		assertEquals(0, calls.get());
	}

	@Test void defaultOverloadForwardsExactLevelPositionAndFrozenFaceWithoutOtherMemberLookup() throws ReflectiveOperationException {
		ServerLevel expectedLevel = levelToken(); BlockPos expectedPosition = new BlockPos(2, 3, 4);
		AtomicInteger calls = new AtomicInteger(), gates = new AtomicInteger();
		IPlatformInventoryService service = (level, pos, face) -> {
			assertSame(expectedLevel, level); assertEquals(expectedPosition, pos); assertEquals(Direction.DOWN, face);
			calls.incrementAndGet(); return Optional.empty();
		};
		assertTrue(service.find(expectedLevel, expectedPosition, Direction.DOWN, pos -> {
			assertEquals(expectedPosition, pos); gates.incrementAndGet(); return true;
		}).isEmpty());
		assertEquals(1, calls.get()); assertEquals(2, gates.get());
		assertThrows(NullPointerException.class, () -> service.find(expectedLevel, expectedPosition, null, pos -> true));
		assertThrows(NullPointerException.class, () -> service.find(expectedLevel, expectedPosition, Direction.DOWN, null));
	}
	@Test void defaultOverloadRejectsRevocationDuringLookupBeforeAnyMetadataOrContentCanEscape() throws ReflectiveOperationException {
		AtomicBoolean member = new AtomicBoolean(true); RecordingAccess raw = new RecordingAccess();
		IPlatformInventoryService service = (level, pos, side) -> { member.set(false); return Optional.of(raw); };
		assertTrue(service.find(levelToken(), BlockPos.ZERO, Direction.NORTH, pos -> member.get()).isEmpty());
		assertEquals(0, raw.reads.get());
	}
	@Test void defaultGuardPreservesSnapshotAndVersionMetadataButRevokedMetadataNeverAuthorizesIndexedContents() throws ReflectiveOperationException {
		AtomicBoolean member = new AtomicBoolean(true); RecordingAccess raw = new RecordingAccess();
		raw.layout = new InventorySnapshotLayout("alias", "layout-data", BlockPos.ZERO, List.of(
			new InventorySnapshotLayout.Member(BlockPos.ZERO, "minecraft:chest", "minecraft:chest", "controller",
				List.of(new InventorySnapshotLayout.Segment("Items", InventorySnapshotSchemas.ITEMS, 2, List.of(0, 1))))));
		IPlatformInventoryService service = (level, pos, side) -> Optional.of(raw);
		var access = service.find(levelToken(), BlockPos.ZERO, Direction.NORTH, pos -> member.get()).orElseThrow();
		assertEquals(Optional.of("alias"), access.alias()); assertEquals(Optional.of(raw.layout), access.snapshotLayout());
		assertEquals(OptionalLong.of(7), access.version()); assertTrue(access.stableCursor()); assertEquals(2, access.slots());
		assertEquals(2, access.observe(2).entries().size()); assertEquals(2, raw.reads.get()); assertTrue(access.valid());
		raw.afterSlots = () -> member.set(false);
		assertThrows(IllegalStateException.class, () -> access.observe(2)); assertEquals(2, raw.reads.get()); assertFalse(access.valid());
		assertThrows(IllegalStateException.class, () -> access.read(0)); assertEquals(2, raw.reads.get());
	}
	@Test void defaultIndexedGuardRechecksBetweenEnumerationAndVisitEntriesAndDoesNotPublishRevokedEntry() {
		for (boolean visit : List.of(false, true)) {
			AtomicBoolean member = new AtomicBoolean(true); RecordingAccess raw = new RecordingAccess();
			var access = raw.guardedBy(member::get); AtomicInteger delivered = new AtomicInteger();
			if (visit) {
				assertThrows(IllegalStateException.class, () -> access.visit(2, entry -> { delivered.incrementAndGet(); member.set(false); }));
				assertEquals(1, delivered.get());
			} else {
				raw.afterRead = () -> member.set(false); assertThrows(IllegalStateException.class, () -> access.observe(2));
			}
			assertEquals(1, raw.reads.get()); assertFalse(access.valid());
		}
	}
	@Test void defaultCursorlessGuardFailsClosedWithoutCallingOpaqueEnumerationOrVisit() {
		RecordingAccess raw = new RecordingAccess(); raw.stable = false; var access = raw.guardedBy(() -> true);
		assertThrows(UnsupportedOperationException.class, () -> access.observe(2));
		assertThrows(UnsupportedOperationException.class, () -> access.visit(2, entry -> fail("no authorized cursorless entry")));
		assertEquals(0, raw.observations.get()); assertEquals(0, raw.visits.get()); assertEquals(0, raw.reads.get());
	}
	@Test void fatalGuardFailuresPropagateInsteadOfBecomingAvailability() {
		AssertionError fatal = new AssertionError("fatal guard");
		var access = new RecordingAccess().guardedBy(() -> { throw fatal; });
		assertSame(fatal, assertThrows(AssertionError.class, access::valid));
		assertSame(fatal, assertThrows(AssertionError.class, () -> access.read(0)));
	}
}
