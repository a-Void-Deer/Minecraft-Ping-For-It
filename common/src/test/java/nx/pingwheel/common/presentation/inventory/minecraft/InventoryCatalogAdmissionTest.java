package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Independent byte/block/collision and pre-call refusal witnesses, with no resident codec. */
class InventoryCatalogAdmissionTest {

	private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
	private static final byte[] ABC = {0x61, 0x62, 0x63};

	private static <T> T trap(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
			(proxy, method, arguments) -> { throw new AssertionError("unexpected callback: " + method); }));
	}
	private static InventoryItemCodec.Catalog catalog() {
		return new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 256, 262144, 4096,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
	}
	private static String json(String id) {
		return "{\"id\":\"" + id + "\",\"components\":{\"!minecraft:lore\":{}}}";
	}
	private static InventoryDetachedItem item(String id, String json, byte[] bytes) {
		return new InventoryDetachedItem(id, bytes == null ? InventoryIdentityMode.ALL_VARIANTS : InventoryIdentityMode.EXACT,
			new InventoryItemCodec.Display(id, "item.test.value", json, bytes == null), bytes, 512);
	}
	private static InventoryCatalogAdmission grant() {
		return new InventoryCatalogAdmission(InventoryItemBudget.workspaceUpperBound(4096),
			32 * InventoryCatalogAdmission.workUpperBound(4096));
	}
	@SuppressWarnings("unchecked")
	private static <T> T field(InventoryItemCodec.Catalog catalog, String name) throws ReflectiveOperationException {
		var field = InventoryItemCodec.Catalog.class.getDeclaredField(name); field.setAccessible(true);
		return (T) field.get(catalog);
	}

	@Test void knownCanonicalBytesAndPaddingBoundariesUseWireCompatibleSha256() {
		var result = catalog().intern(item("test:value", json("test:value"), ABC.clone()), grant());
		assertEquals(ABC_SHA256, result.key().componentsKey(), "NIST abc vector, not a production digest oracle");
		assertEquals(64, result.key().componentsKey().length());
		int[][] paddingCases = {{0, 1}, {3, 1}, {55, 1}, {56, 2}, {63, 2}, {64, 2}, {119, 2}, {120, 3}, {65536, 1025}};
		for (int[] row : paddingCases) assertEquals(row[1], InventoryCatalogAdmission.sha256Blocks(row[0]));
		assertThrows(IllegalArgumentException.class, () -> InventoryCatalogAdmission.sha256Blocks(-1));
		assertThrows(IllegalArgumentException.class, () -> InventoryCatalogAdmission.sha256Blocks(65537));
	}

	@Test void failedWorkspaceAdmissionLeavesAllOwnersUnchangedAndKeepsPaidScan() throws ReflectiveOperationException {
		var catalog = catalog(); var original = catalog.intern(item("test:old", json("test:old"), ABC.clone()), grant());
		var summary = catalog.reprojectionSummary(1024); long retained = catalog.retainedBytes();
		String old = json("test:new");
		var refused = new InventoryCatalogAdmission(0, Long.MAX_VALUE);
		assertThrows(InventoryItemBudget.CaptureLimitException.class,
			() -> catalog.intern(item("test:new", old, new byte[] {7}), refused));
		assertTrue(refused.attemptedWork() >= old.length(), "the completed preflight scan is not refunded");
		assertEquals(0, refused.peakWorkspaceBytes());
		assertEquals(1, catalog.displays().size()); assertSame(original.display(), catalog.displays().get(original.key()));
		assertSame(summary, catalog.reprojectionSummary(1024)); assertEquals(retained, catalog.retainedBytes());
		assertEquals(1, InventoryCatalogAdmissionTest.<Map<String, byte[]>>field(catalog, "canonicalByDigest").size());
		assertTrue(InventoryCatalogAdmissionTest.<Set<String>>field(catalog, "conflicts").isEmpty());
		long paid = refused.attemptedWork();
		assertThrows(InventoryItemBudget.CaptureLimitException.class,
			() -> catalog.intern(item("test:new", old, new byte[] {7}), refused));
		assertEquals(2 * paid, refused.attemptedWork(), "another attempted scan is not free");
	}

	@Test void zeroWorkRefusesBeforeHashLookupOrOldParserAndMalformedFittingDisplayStaysNull() {
		var catalog = catalog(); var input = item("test:value", "{\"id\":\"test:value\",bad}", ABC.clone());
		var none = new InventoryCatalogAdmission(Long.MAX_VALUE, 0);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> catalog.intern(input, none));
		assertEquals(0, none.attemptedWork()); assertTrue(catalog.displays().isEmpty());
		var accepted = catalog.intern(input, grant());
		assertEquals(ABC_SHA256, accepted.key().componentsKey()); assertNull(accepted.display().displayJson());
		assertFalse(accepted.display().aggregateRequired(), "OLD refusal cannot fold exact identity");
		assertEquals(1, catalog.reprojectionSummary(1024).entries());
		assertEquals(0, catalog.reprojectionSummary(1024).parserCalls());
	}

	@Test void exactOperationGrantPaysActualMultibyteInputAndRefusesOneShortBeforeMapMutation() {
		String old = "{\"id\":\"test:value\",\"components\":{\"!minecraft:lore\":{},\"minecraft:custom_data\":{\"text\":\"é\"}}}";
		int bytes = old.getBytes(StandardCharsets.UTF_8).length;
		long scan = InventoryCatalogAdmission.scanWorkUpperBound(old.length(), 10, 15);
		long operation = InventoryCatalogAdmission.operationWorkUpperBound(3, bytes, 0);
		long workspace = InventoryCatalogAdmission.workspaceUpperBound(3, bytes, 0);
		var catalog = catalog(); var input = item("test:value", old, ABC.clone());
		var shortGrant = new InventoryCatalogAdmission(workspace, scan + operation - 1);
		assertThrows(InventoryItemBudget.CaptureLimitException.class, () -> catalog.intern(input, shortGrant));
		assertTrue(catalog.displays().isEmpty()); assertEquals(scan, shortGrant.attemptedWork());
		var exact = new InventoryCatalogAdmission(workspace, scan + operation);
		assertEquals(old, catalog.intern(input, exact).display().displayJson());
		assertEquals(scan + operation, exact.attemptedWork()); assertEquals(workspace, exact.peakWorkspaceBytes());
	}

	@Test void oversizedOldDisplayRefusesOnlyDisplayAndFullCanonicalBoundFitsRootWorkspace() {
		for (String old : new String[] {"x".repeat(131073), "é".repeat(65537)}) {
			var result = catalog().intern(item("test:value", old, ABC.clone()), grant());
			assertEquals(ABC_SHA256, result.key().componentsKey()); assertNull(result.display().displayJson());
			assertFalse(result.display().aggregateRequired());
		}
		assertTrue(InventoryCatalogAdmission.workspaceUpperBound(65536, 131072, 256)
			+ InventoryCatalogAdmission.METADATA_BYTES <= InventoryItemBudget.workspaceUpperBound(131072));
		assertThrows(IllegalArgumentException.class, () -> InventoryCatalogAdmission.workspaceUpperBound(65536, 131073, 256));
		assertThrows(IllegalArgumentException.class, () -> InventoryCatalogAdmission.operationWorkUpperBound(65536, 131072, 257));
	}

	@Test void canonicalCloneDuplicateNullAndAggregateKeepTheirExistingMeaning() throws ReflectiveOperationException {
		var catalog = catalog(); byte[] canonical = ABC.clone(); var admission = grant();
		var original = item("test:value", json("test:value"), canonical);
		var first = catalog.intern(original, admission); var summary = catalog.reprojectionSummary(1024);
		byte[] stored = InventoryCatalogAdmissionTest.<Map<String, byte[]>>field(catalog, "canonicalByDigest").get(ABC_SHA256);
		assertNotSame(canonical, stored); assertArrayEquals(ABC, stored);
		long retained = catalog.retainedBytes(), work = admission.attemptedWork();
		var duplicate = catalog.intern(item("test:value", null, ABC.clone()), admission);
		assertSame(first.display(), duplicate.display()); assertEquals(first.key(), duplicate.key());
		assertEquals(retained, catalog.retainedBytes()); assertSame(summary, catalog.reprojectionSummary(1024));
		assertTrue(admission.attemptedWork() > work, "duplicate admission is paid afresh");
		var unavailable = catalog.intern(item("test:null", null, ABC.clone()), admission);
		assertNull(unavailable.display().displayJson()); assertEquals(ABC_SHA256, unavailable.key().componentsKey());
		var aggregate = catalog.intern(item("test:aggregate", null, null), admission);
		assertEquals("", aggregate.key().componentsKey()); assertTrue(aggregate.display().aggregateRequired());
		assertEquals(3, catalog.reprojectionSummary(1024).entries());
		assertEquals(1, catalog.reprojectionSummary(1024).parserCalls());
	}

	@Test void forcedCollisionIsStickyEvenWhenTheExistingDisplayKeyWouldMatch() throws ReflectiveOperationException {
		var catalog = catalog(); var first = catalog.intern(item("test:value", json("test:value"), ABC.clone()), grant());
		var summary = catalog.reprojectionSummary(1024); long retained = catalog.retainedBytes();
		Map<String, byte[]> known = field(catalog, "canonicalByDigest");
		known.put(ABC_SHA256, new byte[] {9, 9, 9}); // deterministic collision evidence; no fake crypto provider
		var admission = grant();
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(item("test:value", null, ABC.clone()), admission));
		assertTrue(InventoryCatalogAdmissionTest.<Set<String>>field(catalog, "conflicts").contains(ABC_SHA256));
		known.put(ABC_SHA256, ABC.clone()); // even original evidence cannot unmark ambiguity
		long work = admission.attemptedWork();
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(item("test:value", null, ABC.clone()), admission));
		assertTrue(admission.attemptedWork() > work); assertSame(summary, catalog.reprojectionSummary(1024));
		assertEquals(retained, catalog.retainedBytes()); assertSame(first.display(), catalog.displays().get(first.key()));
	}

	@Test void equalHashIdsCrossTreeificationWithoutMergingAndOldUtf8IsTheActualInput() {
		var catalog = catalog(); var admission = grant(); int hash = 0;
		// "an" and "c0" have the same Java hash (3117), including after concatenation.
		for (int value = 0; value < 16; value++) {
			StringBuilder suffix = new StringBuilder();
			for (int bit = 0; bit < 4; bit++) suffix.append((value & (1 << bit)) == 0 ? "an" : "c0");
			String id = "test:" + suffix;
			if (value == 0) hash = id.hashCode(); else assertEquals(hash, id.hashCode());
			var encoded = catalog.intern(item(id, json(id), ABC.clone()), admission);
			assertEquals(id, encoded.key().itemId()); assertNotNull(encoded.display().displayJson());
		}
		assertEquals(16, catalog.displays().size()); assertEquals(16, catalog.reprojectionSummary(1024).parserCalls());
		String id = "test:utf8", old = "{\"id\":\"test:utf8\",\"components\":{\"!minecraft:lore\":{},\"minecraft:custom_data\":{\"text\":\"é\"}}}";
		var encoded = catalog.intern(item(id, old, ABC.clone()), admission);
		assertEquals(old, encoded.display().displayJson());
		long bytes = catalog.displays().values().stream().mapToLong(display -> display.displayJson().getBytes(StandardCharsets.UTF_8).length).sum();
		assertEquals(bytes, catalog.reprojectionSummary(1024).inputBytes());
	}

	@Test void tighteningRefusalIsAtomicAndActualSummaryGrantCanBeReusedWithoutResurrection() {
		var catalog = catalog(); var first = catalog.intern(item("test:value", json("test:value"), ABC.clone()), grant());
		var summary = catalog.reprojectionSummary(1024);
		assertThrows(InventoryItemBudget.CaptureLimitException.class,
			() -> catalog.tightenDisplay(1024, new InventoryCatalogAdmission(0, Long.MAX_VALUE)));
		assertSame(summary, catalog.reprojectionSummary(1024)); assertEquals(4096, catalog.displayBytes());
		assertSame(first.display(), catalog.displays().get(first.key()));
		var admission = new InventoryCatalogAdmission(summary.workspaceBytes(), summary.workUnits());
		catalog.tightenDisplay(1024, admission);
		assertEquals(summary.workUnits(), admission.attemptedWork()); assertEquals(1024, catalog.displayBytes());
		catalog.tightenDisplay(4096, admission); assertEquals(summary.workUnits(), admission.attemptedWork());
	}
}
