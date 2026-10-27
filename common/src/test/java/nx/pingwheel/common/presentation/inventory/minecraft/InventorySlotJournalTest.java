package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Proxy;

import net.minecraft.core.HolderLookup;
import nx.pingwheel.common.presentation.inventory.InventorySlotJournal;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Catalogue-owned definitions and a consumer's absolute slot replay, with no Minecraft callbacks. */
@NotNullByDefault
class InventorySlotJournalTest {
	private static <T> T trap(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
			(proxy, method, arguments) -> { throw new AssertionError("unexpected callback: " + method); }));
	}
	private static InventoryItemCodec.Catalog catalog() {
		return new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 256, 262144, 4096,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
	}
	private static InventoryCatalogAdmission grant() { return new InventoryCatalogAdmission(Long.MAX_VALUE, Long.MAX_VALUE); }
	private static InventoryDetachedItem item(String id, @Nullable String json, byte @Nullable [] canonical) {
		return new InventoryDetachedItem(id, canonical == null ? InventoryIdentityMode.ALL_VARIANTS : InventoryIdentityMode.EXACT,
			new InventoryItemCodec.Display(id, "item.test.value", json, canonical == null), canonical, 512);
	}
	private static InventoryItemCodec.Encoded intern(InventoryItemCodec.Catalog catalog, String id, int key) {
		return catalog.intern(item(id, "{\"id\":\"" + id + "\"}", new byte[] {(byte) key, (byte) (key >>> 8)}), grant());
	}

	@Test void absoluteSlotsLongBoundariesEmptySentinelAndChunkGrowthHaveIndependentCosts() {
		var catalog = catalog(); var value = intern(catalog, "test:value", 1);
		long catalogCost = catalog.modeledRetainedBytes();
		try (var journal = new InventorySlotJournal(17)) {
			assertEquals(InventorySlotJournal.initialRetainedBytes(), journal.modeledRetainedBytes());
			for (int offset = 0; offset < 70; offset++) {
				long before = journal.modeledRetainedBytes(), bound = journal.appendRetainedGrowthUpperBound();
				long amount = offset == 0 ? (1L << 53) + 1 : offset == 69 ? Long.MAX_VALUE : offset;
				journal.append(17 + offset, offset == 33 ? null : value, offset == 33 ? 0 : amount);
				assertTrue(journal.modeledRetainedBytes() - before <= bound);
				assertEquals((offset / 32 + 1) * 32, journal.allocatedSlotCapacity());
				if (offset == 0) assertTrue(journal.modeledRetainedBytes() - before < 4096, "one small chunk, not a whole encoded page");
			}
			assertEquals(87, journal.endSlot()); assertEquals(70, journal.size()); assertEquals(1, journal.definitionCount());
			assertEquals((1L << 53) + 1, journal.read(17).count()); assertEquals(Long.MAX_VALUE, journal.read(86).count());
			assertNull(journal.read(50)); assertEquals(-1, journal.definitionOrdinal(50));
			assertEquals(0, journal.amount(50)); assertEquals(0, journal.definitionOrdinal(49));
			assertSame(value.key(), journal.definition(0).key()); assertSame(value.display(), journal.definition(0).display());
			assertEquals(catalogCost, catalog.modeledRetainedBytes(), "repeated slots own neither canonical nor display payloads");
			assertThrows(IndexOutOfBoundsException.class, () -> journal.read(16));
			assertThrows(IndexOutOfBoundsException.class, () -> journal.read(87));
			assertThrows(IllegalArgumentException.class, () -> journal.append(88, value, 1));
			assertThrows(IllegalArgumentException.class, () -> journal.append(87, value, -1));
		}
	}

	@Test void sameKeyUsesCatalogFirstProjectionDifferentIdsAndIdentityModesRemainDistinct() {
		var catalog = catalog(); var first = intern(catalog, "test:first", 1);
		var duplicate = catalog.intern(item("test:first", null, new byte[] {1, 0}), grant());
		assertSame(first, duplicate); assertSame(first.key(), duplicate.key());
		var otherId = intern(catalog, "test:second", 1);
		var aggregate = catalog.intern(item("test:first", null, null), grant());
		try (var journal = new InventorySlotJournal()) {
			journal.append(0, first, 0); journal.append(1, duplicate, 1); journal.append(2, otherId, 2); journal.append(3, aggregate, 3);
			assertEquals(3, journal.definitionCount()); assertNotNull(journal.read(0), "observed zero is not an empty slot");
			assertEquals(journal.definitionOrdinal(0), journal.definitionOrdinal(1));
			assertNotEquals(journal.definitionOrdinal(0), journal.definitionOrdinal(2));
			assertNotEquals(journal.definitionOrdinal(0), journal.definitionOrdinal(3));
			assertTrue(journal.read(3).aggregateRequired());
		}
	}

	@Test void definitionCapacityDoesNotLimitRepeatedOrEmptySlotsAndRefusalKeepsPrefix() {
		var catalog = catalog();
		try (var journal = new InventorySlotJournal()) {
			InventoryItemCodec.Encoded first = intern(catalog, "test:value", 0);
			for (int index = 0; index < 256; index++) {
				long before = journal.modeledRetainedBytes(), bound = journal.appendRetainedGrowthUpperBound();
				journal.append(index, intern(catalog, "test:value", index), index);
				assertTrue(journal.modeledRetainedBytes() - before <= bound);
			}
			long before = journal.modeledRetainedBytes();
			var extra = intern(catalog(), "test:extra", 1000);
			assertThrows(InventorySlotJournal.CapacityException.class, () -> journal.append(256, extra, 1));
			assertEquals(256, journal.size()); assertEquals(before, journal.modeledRetainedBytes());
			journal.append(256, first, Long.MAX_VALUE); journal.append(257, null, 0);
			assertEquals(Long.MAX_VALUE, journal.amount(256)); assertNull(journal.read(257));
		}
		var journal = new InventorySlotJournal(4095);
		journal.append(4095, null, 0); assertEquals(4096, journal.endSlot());
		assertThrows(IllegalArgumentException.class, () -> journal.append(4096, null, 0));
		journal.close(); journal.close(); assertEquals(0, journal.modeledRetainedBytes());
		assertThrows(IllegalStateException.class, () -> journal.read(4095));
	}

	@Test void collidingJavaHashesDoNotCollapseDifferentItemDefinitions() {
		var catalog = catalog();
		try (var journal = new InventorySlotJournal()) {
			for (int value = 0; value < 16; value++) {
				StringBuilder id = new StringBuilder("test:");
				for (int bit = 0; bit < 4; bit++) id.append((value & (1 << bit)) == 0 ? "an" : "c0");
				journal.append(value, intern(catalog, id.toString(), 1), value + 1L);
			}
			assertEquals(16, journal.definitionCount());
			for (int slot = 0; slot < 16; slot++) { assertEquals(slot, journal.definitionOrdinal(slot)); assertEquals(slot + 1L, journal.read(slot).count()); }
		}
	}

	@Test void tighteningRefreshesSharedDefinitionOnceAndNeverRestoresTerminalOrUnavailable() {
		var catalog = catalog(); String id = "test:value";
		StringBuilder removals = new StringBuilder("{\"id\":\"test:value\",\"components\":{\"!minecraft:lore\":{}");
		for (int index = 0; index < 80; index++) removals.append(",\"!test:component_").append(index).append("\":{}");
		String json = removals.append("}}").toString();
		var value = catalog.intern(item(id, json, new byte[] {1}), grant());
		assertNotNull(value.display().displayJson(), "ordinary retained suppression envelope fits the initial ceiling");
		var unavailable = catalog.intern(item("test:null", null, new byte[] {2}), grant());
		try (var journal = new InventorySlotJournal()) {
			journal.append(0, value, (1L << 53) + 1); journal.append(1, value, 7); journal.append(2, unavailable, 8);
			long cost = journal.modeledRetainedBytes(), canonical = catalog.retainedCost().canonicalBytes();
			catalog.tightenDisplay(InventoryItemCodec.minimumDisplayBytes(), grant()); journal.refreshDisplays(catalog);
			assertEquals("{\"id\":\"test:value\"}", journal.read(0).displayJson());
			assertSame(catalog.retainedDisplay(value.key()), journal.definition(0).display());
			assertSame(journal.read(0).displayJson(), journal.read(1).displayJson()); assertNull(journal.read(2).displayJson());
			catalog.tightenDisplay(4096); catalog.intern(item(id, json, new byte[] {1}), grant()); journal.refreshDisplays(catalog);
			assertEquals("{\"id\":\"test:value\"}", journal.read(1).displayJson());
			assertEquals((1L << 53) + 1, journal.read(0).count()); assertFalse(journal.read(0).aggregateRequired());
			assertEquals(cost, journal.modeledRetainedBytes()); assertEquals(canonical, catalog.retainedCost().canonicalBytes());
		}
	}

	@Test void ordinaryDisplayTighteningChangesOnlySharedMetadataNotSlotsOrIdentity() {
		var catalog = catalog();
		String json = "{\"id\":\"test:value\",\"components\":{\"!minecraft:lore\":{},\"minecraft:custom_data\":{\"text\":\"" + "x".repeat(1800) + "\"}}}";
		var value = catalog.intern(item("test:value", json, new byte[] {3}), grant());
		assertEquals(json, value.display().displayJson());
		try (var journal = new InventorySlotJournal()) {
			journal.append(0, value, Long.MAX_VALUE); journal.append(1, null, 0);
			long before = catalog.modeledRetainedBytes();
			catalog.tightenDisplay(1024, grant()); journal.refreshDisplays(catalog);
			assertNotEquals(json, journal.read(0).displayJson()); assertNotNull(journal.read(0).displayJson());
			assertFalse(InventoryDisplayWriter.isDefaultIcon("test:value", journal.read(0).displayJson()));
			assertSame(catalog.retainedDisplay(value.key()), journal.definition(0).display());
			assertEquals(value.key(), journal.read(0).key()); assertEquals(Long.MAX_VALUE, journal.read(0).count());
			assertNull(journal.read(1)); assertTrue(catalog.modeledRetainedBytes() < before);
			assertEquals(catalog.modeledRetainedBytes(), catalog.retainedCost().totalBytes());
		}
	}
	@Test void certifiedReplayAllocationIsIndependentOfDisplayLength() {
		var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		assertTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
		long thread = Thread.currentThread().threadId();
		var catalog = new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 256, 262144, 131072,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
		String json = "{\"id\":\"test:value\",\"components\":{\"minecraft:custom_data\":{\"text\":\"" + "x".repeat(24000) + "\"}}}";
		var value = catalog.intern(item("test:value", json, new byte[] {1}), grant());
		try (var journal = new InventorySlotJournal()) {
			journal.append(0, value, Long.MAX_VALUE);
			// Retain each result in an externally observable array so scalar replacement cannot
			// hide Item allocation. The old path additionally allocates a >=24KB UTF8 array each time.
			var reads = new nx.pingwheel.common.presentation.inventory.InventoryDomainCodec.Item[512];
			for (int warm = 0; warm < 10000; warm++) reads[warm & 511] = journal.read(0);
			long before = bean.getThreadAllocatedBytes(thread);
			for (int index = 0; index < reads.length; index++) reads[index] = journal.read(0);
			long allocated = bean.getThreadAllocatedBytes(thread) - before;
			assertTrue(allocated < 512 * 256L, "replay must allocate only fixed cells, not display-sized validation buffers: " + allocated);
			for (var read : reads) { assertEquals(Long.MAX_VALUE, read.count()); assertSame(value.display().displayJson(), read.displayJson()); }
		}
	}
}
