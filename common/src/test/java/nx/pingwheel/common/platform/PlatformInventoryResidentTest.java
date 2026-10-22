package nx.pingwheel.common.platform;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import nx.pingwheel.common.platform.lookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegmentlookupfixturesegment.authorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegmauthorityfixturesegm.RefusalFixture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInventoryResidentTest {
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
