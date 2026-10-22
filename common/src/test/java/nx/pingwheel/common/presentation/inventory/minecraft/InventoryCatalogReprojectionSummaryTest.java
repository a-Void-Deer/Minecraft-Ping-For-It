package nx.pingwheel.common.presentation.inventory.minecraft;

import java.nio.charset.StandardCharsets;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import nx.pingwheel.common.presentation.inventory.InventoryReprojectionSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual retained OLD inputs, not capacity, slots or guessed duplicate owners. */
class InventoryCatalogReprojectionSummaryTest {
	private static RegistryAccess registries;
	@BeforeAll static void bootstrap() {
		SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
		registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
	private static InventoryItemCodec.Catalog catalog() {
		return new InventoryItemCodec.Catalog(registries, 256, 262144, 98304,
			new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
	}
	private static InventoryDetachedItem detached(String id, String json, int canonical) {
		return new InventoryDetachedItem(id, InventoryIdentityMode.EXACT,
			new InventoryItemCodec.Display(id, "item.test.value", json, false), new byte[] {(byte) canonical}, 512);
	}
	private static long inputWork(int bytes) {
		return InventoryItemBudget.reprojectionWorkUpperBound(bytes) + 64L * bytes;
	}
	@Test void emptyAndNullOnlyHaveNoParsersAndDoNotParseOnTightening() {
		var catalog = catalog(); assertSame(InventoryReprojectionSummary.EMPTY, catalog.reprojectionSummary(4096));
		var encoded = catalog.intern(detached("test:null", null, 1));
		assertEquals(1, catalog.reprojectionSummary(4096).entries()); assertEquals(0, catalog.reprojectionSummary(4096).parserCalls());
		catalog.tightenDisplay(4096);
		assertSame(encoded.display(), catalog.displays().get(encoded.key()));
		assertSame(InventoryReprojectionSummary.EMPTY, catalog.reprojectionSummary(4096));
		assertEquals(0, catalog.reprojectionSummary(1024).parserCalls());
	}
	@Test void duplicateKeyAddsNoParserAndSummaryTracksActualUtf8AndMonotoneReplacement() {
		var catalog = catalog();
		String json = "{\"id\":\"test:value\",\"components\":{\"minecraft:custom_name\":\"" + "é".repeat(5000) + "\",\"!minecraft:lore\":{}}}";
		var original = detached("test:value", json, 2);
		var first = catalog.intern(original);
		String old = first.display().displayJson(); assertNotNull(old);
		assertEquals(json, old, "the OLD envelope already carries mandatory removals and survives strict validation unchanged");
		int oldBytes = old.getBytes(StandardCharsets.UTF_8).length;
		assertTrue(oldBytes > 4096 && oldBytes <= 98304); assertTrue(oldBytes > old.length(), "the oracle measures multibyte UTF-8, not UTF-16 units");
		var summary = catalog.reprojectionSummary(4096);
		assertEquals(1, summary.entries()); assertEquals(1, summary.parserCalls()); assertEquals(oldBytes, summary.maxInputBytes());
		assertEquals(inputWork(oldBytes), summary.parserWorkUnits()); assertEquals(oldBytes, summary.inputBytes());
		assertEquals(1024L + 2L * oldBytes, summary.replacementBytes());
		assertEquals(InventoryItemBudget.reprojectionWorkspaceUpperBound(oldBytes) + InventoryReprojectionSummary.METADATA_BYTES
			+ 1024L + 2L * oldBytes, summary.workspaceBytes());
		assertSame(summary, catalog.reprojectionSummary(4096), "query returns cached object");
		assertSame(first.display(), catalog.intern(original).display()); assertSame(summary, catalog.reprojectionSummary(4096));
		assertEquals(1, catalog.displays().size(), "a duplicate canonical key retains one parser owner");
		catalog.tightenDisplay(4096);
		var after = catalog.displays().get(first.key()); assertFalse(after.aggregateRequired());
		assertNotNull(after.displayJson());
		int afterBytes = after.displayJson().getBytes(StandardCharsets.UTF_8).length;
		assertTrue(afterBytes <= 4096 && afterBytes < oldBytes);
		assertEquals(inputWork(afterBytes), catalog.reprojectionSummary(1024).parserWorkUnits());
		assertEquals(afterBytes, catalog.reprojectionSummary(1024).inputBytes());
		assertEquals(first.key(), catalog.intern(original).key());
		var tightenedSummary = catalog.reprojectionSummary(1024); catalog.tightenDisplay(98304);
		assertSame(tightenedSummary, catalog.reprojectionSummary(1024));
		assertSame(InventoryReprojectionSummary.EMPTY, catalog.reprojectionSummary(98304));
	}
	@Test void twoDistinctKeysShareNoInventedThirdParserAndWorkspaceUsesMaxNotSum() {
		var catalog = catalog();
		String aj = "{\"id\":\"test:first\",\"components\":{\"!minecraft:lore\":{}}}";
		String bj = "{\"id\":\"test:second\",\"components\":{\"!minecraft:lore\":{}}}";
		var a = catalog.intern(detached("test:first", aj, 3));
		var b = catalog.intern(detached("test:second", bj, 4));
		assertNotNull(a.display().displayJson()); assertNotNull(b.display().displayJson());
		assertEquals(aj, a.display().displayJson()); assertEquals(bj, b.display().displayJson());
		assertNotEquals(a.key(), b.key());
		int ab = aj.getBytes(StandardCharsets.UTF_8).length, bb = bj.getBytes(StandardCharsets.UTF_8).length;
		var summary = catalog.reprojectionSummary(4096);
		assertEquals(inputWork(ab) + inputWork(bb), summary.parserWorkUnits()); assertEquals(2, summary.parserCalls());
		assertEquals(2, summary.entries()); assertEquals(ab + bb, summary.inputBytes()); assertEquals(Math.max(ab, bb), summary.maxInputBytes());
		assertEquals(2048L + 2L * (ab + bb), summary.replacementBytes());
		assertEquals(InventoryItemBudget.reprojectionWorkspaceUpperBound(Math.max(ab, bb)) + InventoryReprojectionSummary.METADATA_BYTES
			+ 2048L + 2L * (ab + bb), summary.workspaceBytes());
	}
	@Test void rejectedRetentionKeepsCeilingSummaryAndExistingDisplayOwnership() {
		var catalog = new InventoryItemCodec.Catalog(registries, 1, 262144, 98304,
			new InventoryResidentAccessFields(), new InventoryTestNativeAccess());
		var original = catalog.intern(detached("test:first", "{\"id\":\"test:first\"}", 5));
		var summary = catalog.reprojectionSummary(4096); long retained = catalog.retainedBytes();
		assertThrows(InventoryItemCodec.IdentityUnavailableException.class,
			() -> catalog.intern(detached("test:second", "{\"id\":\"test:second\"}", 6)));
		assertSame(summary, catalog.reprojectionSummary(4096)); assertEquals(98304, catalog.displayBytes());
		assertEquals(retained, catalog.retainedBytes()); assertEquals(1, catalog.displays().size());
		assertSame(original.display(), catalog.displays().get(original.key()));
	}
}
