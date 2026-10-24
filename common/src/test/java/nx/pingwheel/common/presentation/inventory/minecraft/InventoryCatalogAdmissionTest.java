package nx.pingwheel.common.presentation.inventory.minecraft;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import static org.junit.jupiter.api.Assertions.*;

/** Independent byte/block/collision and pre-call refusal witnesses, with no resident codec. */
@NotNullByDefault
class InventoryCatalogAdmissionTest {

	private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
	private static final byte @Unmodifiable [] ABC = {0x61, 0x62, 0x63};

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
	private static InventoryDetachedItem item(String id, @Nullable String json, byte @Nullable [] bytes) {
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
		var admission = grant(); long modeled = catalog.modeledRetainedBytes();
		long collisionGrowth = catalog.retainedGrowthUpperBound(item("test:value", null, ABC.clone()));
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(item("test:value", null, ABC.clone()), admission));
		assertTrue(catalog.modeledRetainedBytes() > modeled, "sticky ambiguity has a retained lifetime even on failure");
		assertTrue(catalog.modeledRetainedBytes() - modeled <= collisionGrowth);
		assertTrue(catalog.retainedCost().ambiguityBytes() > 0);
		long ambiguousCost = catalog.modeledRetainedBytes();
		assertTrue(InventoryCatalogAdmissionTest.<Set<String>>field(catalog, "conflicts").contains(ABC_SHA256));
		known.put(ABC_SHA256, ABC.clone()); // even original evidence cannot unmark ambiguity
		long work = admission.attemptedWork();
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(item("test:value", null, ABC.clone()), admission));
		assertTrue(admission.attemptedWork() > work); assertSame(summary, catalog.reprojectionSummary(1024));
		assertEquals(retained, catalog.retainedBytes()); assertSame(first.display(), catalog.displays().get(first.key()));
		assertEquals(ambiguousCost, catalog.modeledRetainedBytes(), "repeat ambiguity adds no second evidence node");
	}

	@Test void modeledCostsSeparatePayloadCanonicalProjectionAndIndexesWithPureGrowthBounds() {
		var catalog = catalog(); long base = catalog.modeledRetainedBytes();
		assertEquals(0, catalog.retainedBytes()); assertEquals(0, catalog.retainedCost().canonicalBytes());
		var input = item("test:first", json("test:first"), ABC.clone());
		long bound = catalog.retainedGrowthUpperBound(input);
		var first = catalog.intern(input, grant());
		long after = catalog.modeledRetainedBytes();
		assertTrue(after - base <= bound); assertTrue(after > catalog.retainedBytes());
		assertEquals(after, catalog.retainedCost().totalBytes());
		assertSame(first.display(), catalog.retainedDisplay(first.key()));
		assertSame(first, catalog.intern(item("test:first", null, ABC.clone()), grant()), "the first key and projection are shared");
		assertEquals(after, catalog.modeledRetainedBytes());
		long canonicalCost = catalog.retainedCost().canonicalBytes();
		var second = item("test:second", null, ABC.clone()); bound = catalog.retainedGrowthUpperBound(second);
		catalog.intern(second, grant());
		assertEquals(canonicalCost, catalog.retainedCost().canonicalBytes(), "one canonical array per digest across item IDs");
		assertTrue(catalog.modeledRetainedBytes() - after <= bound);
		assertTrue(catalog.retainedCost().projectionBytes() > 0); assertTrue(catalog.retainedCost().indexBytes() > base);
		long oldModeled = catalog.modeledRetainedBytes(); catalog.tightenDisplay(1024, grant());
		assertTrue(catalog.modeledRetainedBytes() <= oldModeled); assertEquals(canonicalCost, catalog.retainedCost().canonicalBytes());
		assertEquals(catalog.modeledRetainedBytes(), catalog.retainedCost().totalBytes());
	}

	@Test void entryAndPayloadExhaustionHaveTypedLegacyCompatibleOutcomesWithoutMutation() {
		var input = item("test:value", json("test:value"), ABC.clone());
		var countLimited = new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 1, 262144, 4096,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
		countLimited.intern(input, grant()); long retained = countLimited.modeledRetainedBytes();
		var failure = assertThrows(InventoryItemCodec.CapacityExceededException.class,
			() -> countLimited.intern(item("test:other", null, new byte[] {1}), grant()));
		assertEquals(InventoryItemCodec.CatalogLimit.ENTRIES, failure.limit());
		assertInstanceOf(InventoryItemCodec.IdentityUnavailableException.class, failure);
		assertEquals("item catalogue entry bound reached", failure.getMessage()); assertEquals(retained, countLimited.modeledRetainedBytes());
		var byteLimited = new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 256, 1, 4096,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
		retained = byteLimited.modeledRetainedBytes();
		failure = assertThrows(InventoryItemCodec.CapacityExceededException.class, () -> byteLimited.intern(input, grant()));
		assertEquals(InventoryItemCodec.CatalogLimit.PAYLOAD_BYTES, failure.limit());
		assertEquals("item catalogue byte bound reached", failure.getMessage()); assertEquals(retained, byteLimited.modeledRetainedBytes());
		assertTrue(byteLimited.displays().isEmpty()); assertEquals(0, byteLimited.retainedBytes());
	}

	@Test void fullCanonicalComparisonIncludesLastByteAndCollisionNeverBecomesCapacity() throws ReflectiveOperationException {
		byte[] canonical = new byte[InventoryItemCodec.MAX_CANONICAL_BYTES]; canonical[canonical.length - 1] = 1;
		var catalog = catalog(); var first = catalog.intern(item("test:value", null, canonical), grant());
		Map<String, byte[]> known = field(catalog, "canonicalByDigest");
		byte[] different = canonical.clone(); different[different.length - 1] = 2;
		known.put(first.key().componentsKey(), different);
		var failure = assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(item("test:value", null, canonical), grant()));
		assertFalse(failure instanceof InventoryItemCodec.CapacityExceededException);
		assertEquals("component digest collision", failure.getMessage());
		known.put(first.key().componentsKey(), canonical.clone());
		failure = assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(item("test:value", null, canonical), grant()));
		assertEquals("component digest is ambiguous", failure.getMessage());
		assertSame(first.display(), catalog.retainedDisplay(first.key())); assertEquals(1, catalog.displays().size());
	}

	@Test void exactPayloadCapAdmitsDuplicateButNotAnotherDefinition() {
		var input = item("test:value", json("test:value"), ABC.clone());
		var sizing = catalog(); sizing.intern(input, grant());
		var catalog = new InventoryItemCodec.Catalog(trap(HolderLookup.Provider.class), 256, sizing.retainedBytes(), 4096,
			trap(InventoryResidentAccess.class), trap(InventoryItemNativeAccess.class));
		var first = catalog.intern(input, grant()); long cost = catalog.modeledRetainedBytes();
		assertEquals(sizing.retainedBytes(), catalog.retainedBytes()); assertSame(first, catalog.intern(input, grant()));
		var failure = assertThrows(InventoryItemCodec.CapacityExceededException.class,
			() -> catalog.intern(item("test:other", null, ABC.clone()), grant()));
		assertEquals(InventoryItemCodec.CatalogLimit.PAYLOAD_BYTES, failure.limit()); assertEquals(cost, catalog.modeledRetainedBytes());
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
