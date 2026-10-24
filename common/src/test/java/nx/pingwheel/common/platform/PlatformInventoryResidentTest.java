package nx.pingwheel.common.platform;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import nx.pingwheel.common.platform.lookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegment.authorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegm.RefusalFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInventoryResidentTest {
	@Test void guardedConsumptionDetachesBeforeTheNextGateAndStillRejectsRevocation() {
		net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
		for (boolean revoke : new boolean[] {false, true}) {
			var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 7);
			var name = net.minecraft.network.chat.Component.literal("getter-time");
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);
			AtomicBoolean returned = new AtomicBoolean(); AtomicInteger mutations = new AtomicInteger();
			var access = new OldAccess() {
				@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) {
					returned.set(true);
					return new IPlatformInventoryService.ResidentEntry(
						new nx.pingwheel.common.presentation.inventory.minecraft.InventoryResidentItem.Stack(stack), 9007199254740993L);
				}
			}.guardedBy(() -> {
				if (!returned.get()) return true;
				mutations.incrementAndGet(); stack.setCount(0); name.append("-mutated"); return !revoke;
			});
			@org.jetbrains.annotations.Nullable nx.pingwheel.common.presentation.inventory.minecraft.InventoryDetachedItem[] frozen =
				new nx.pingwheel.common.presentation.inventory.minecraft.InventoryDetachedItem[1];
			IPlatformInventoryService.ResidentVisitor visitor = entry -> {
				assertEquals(0, mutations.get()); assertEquals(9007199254740993L, entry.amount());
				assertEquals(7, stack.getCount());
				frozen[0] = nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec.freeze(entry.item(),
					net.minecraft.core.RegistryAccess.EMPTY, 4096,
					nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget.root(4096));
				return false;
			};
			if (revoke) assertThrows(IllegalStateException.class, () -> access.consumeResident(0, visitor));
			else assertFalse(access.consumeResident(0, visitor));
			assertEquals(1, mutations.get()); assertEquals(0, stack.getCount());
			assertEquals("getter-time-mutated", name.getString());
			assertNotNull(frozen[0]); assertTrue(frozen[0].display().displayJson().contains("getter-time"));
			assertFalse(frozen[0].display().displayJson().contains("mutated"));
		}
	}
	@Test void reflectionShapeRefusesOversizedLookupNamesBeforeConstructingMissingMethodMessages() {
		String oversized = "missing".repeat(100);
		assertFalse(IPlatformInventoryService.ResidentReads.reflectionShape(String.class, oversized));
		assertFalse(IPlatformInventoryService.ResidentReads.inherits("", String.class, oversized));
		assertNull(IPlatformInventoryService.ResidentReads.field("", String.class, oversized, Object.class));
		assertTrue(IPlatformInventoryService.ResidentReads.inherits("", String.class, "length"), "ordinary audited metadata still resolves");
	}
	private static class OldAccess implements IPlatformInventoryService.Access {
		@Override public int slots() { return 3; }
		@Override public boolean stableCursor() { return true; }
		@Override public OptionalLong version() { return OptionalLong.empty(); }
		@Override public IPlatformInventoryService.Entry read(int slot) { throw new AssertionError("detached bridge invoked"); }
		@Override public boolean visit(int limit, Consumer<IPlatformInventoryService.Entry> consumer) { throw new AssertionError("detached bridge invoked"); }
	}

	@Test void legacyImplementationsNeverBridgeToDetachedReadOrInventEmpty() {
		var access = new OldAccess();
		assertFalse(access.supportsDetachedSlotCapture());
		assertThrows(UnsupportedOperationException.class, () -> access.readResident(0));
		assertThrows(UnsupportedOperationException.class, () -> access.observeResident(1, entry -> true));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformInventoryService.ResidentEntry(null, -1));
		assertThrows(IllegalArgumentException.class, () -> new IPlatformInventoryService.ResidentEntry(null, 1));
		assertNull(IPlatformInventoryService.ResidentEntry.empty().item());
		assertEquals(0, IPlatformInventoryService.ResidentEntry.empty().amount());
	}

	@Test void emptySlotsCountAndEachCallbackFinishesBeforeTheNextGetter() {
		AtomicInteger reads = new AtomicInteger(), callbacks = new AtomicInteger();
		var access = new OldAccess() {
			@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) {
				assertEquals(callbacks.get(), reads.get(), "next getter preceded immediate consumption");
				assertEquals(reads.getAndIncrement(), slot);
				return IPlatformInventoryService.ResidentEntry.empty();
			}
		};
		var result = access.observeResident(2, entry -> { callbacks.incrementAndGet(); return true; });
		assertEquals(2, result.visited()); assertFalse(result.complete()); assertEquals(2, reads.get());
	}

	@Test void stoppingOnLastSlotStillDoesNotProveCompletionAndLimitsPrecedeMetadata() {
		AtomicInteger getters = new AtomicInteger();
		var access = new OldAccess() {
			@Override public int slots() { getters.incrementAndGet(); return 1; }
			@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) {
				getters.incrementAndGet(); return IPlatformInventoryService.ResidentEntry.empty();
			}
		};
		assertThrows(IllegalArgumentException.class, () -> access.observeResident(-1, entry -> true));
		assertEquals(0, getters.get());
		var stopped = access.observeResident(1, entry -> false);
		assertEquals(1, stopped.visited()); assertFalse(stopped.complete());
		assertTrue(access.observeResident(1, entry -> true).complete());
	}

	@Test void guardedMetadataRevocationStopsBeforeContentAndCallbackRevocationStopsNextRead() {
		AtomicBoolean member = new AtomicBoolean(true); AtomicInteger reads = new AtomicInteger();
		var metadataRevokes = new OldAccess() {
			@Override public int slots() { member.set(false); return 3; }
			@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) {
				reads.incrementAndGet(); return IPlatformInventoryService.ResidentEntry.empty();
			}
		}.guardedBy(member::get);
		assertThrows(IllegalStateException.class, () -> metadataRevokes.observeResident(3, entry -> true));
		assertEquals(0, reads.get());
		member.set(true);
		var callbackRevokes = new OldAccess() {
			@Override public IPlatformInventoryService.ResidentEntry readResident(int slot) {
				reads.incrementAndGet(); return IPlatformInventoryService.ResidentEntry.empty();
			}
		}.guardedBy(member::get);
		assertThrows(IllegalStateException.class, () -> callbackRevokes.observeResident(3, entry -> { member.set(false); return true; }));
		assertEquals(1, reads.get());
	}

	@Test void cursorlessLegacyDecoratorFailsBeforeEnumerationAndCapabilityIsForwarded() {
		var cursorless = new OldAccess() { @Override public boolean stableCursor() { return false; } }.guardedBy(() -> true);
		assertThrows(UnsupportedOperationException.class, () -> cursorless.observeResident(1, entry -> true));
		var audited = new OldAccess() { @Override public boolean supportsDetachedSlotCapture() { return true; } }.guardedBy(() -> true);
		assertTrue(audited.supportsDetachedSlotCapture());
	}

	static final class Overriding {
		@Override public String toString() { return "override"; }
	}

	@Test void methodOwnershipSeparatesProvenDeclarationsFromBoundedRefusal() {
		assertEquals(IPlatformInventoryService.ResidentReads.MethodOwnership.PROVEN_INHERITED,
			IPlatformInventoryService.ResidentReads.methodOwnership(new Object(), Object.class, "toString"));
		assertEquals(IPlatformInventoryService.ResidentReads.MethodOwnership.PROVEN_INHERITED,
			IPlatformInventoryService.ResidentReads.methodOwnership(new Overriding(), Overriding.class, "toString"));
		assertEquals(IPlatformInventoryService.ResidentReads.MethodOwnership.PROVEN_OVERRIDE,
			IPlatformInventoryService.ResidentReads.methodOwnership(new Overriding(), Object.class, "toString"));
		assertFalse(IPlatformInventoryService.ResidentReads.inherits(new Overriding(), Object.class, "toString"));
		var overlong = new RefusalFixture();
		assertTrue(overlong.getClass().getName().length() > IPlatformInventoryService.ResidentReads.MAX_TYPE_NAME_CHARS,
			"the fixture really exceeds the bounded lookup shape");
		assertEquals(IPlatformInventoryService.ResidentReads.MethodOwnership.UNCLASSIFIED,
			IPlatformInventoryService.ResidentReads.methodOwnership(overlong, Object.class, "toString"));
		assertFalse(IPlatformInventoryService.ResidentReads.inherits(overlong, Object.class, "toString"),
			"a bounded refusal must not read as a proven override");
		assertEquals(IPlatformInventoryService.ResidentReads.MethodOwnership.UNCLASSIFIED,
			IPlatformInventoryService.ResidentReads.methodOwnership(new Object(), Object.class, "length"));
	}
}
