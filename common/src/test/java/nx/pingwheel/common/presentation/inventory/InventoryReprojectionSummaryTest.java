package nx.pingwheel.common.presentation.inventory;

import java.nio.charset.StandardCharsets;
import nx.pingwheel.common.config.InventoryLimits;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemBudget;
import nx.pingwheel.common.presentation.inventory.minecraft.InventoryItemCodec;
import nx.pingwheel.common.presentation.inventory.minecraft.InventorySnapshotLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryReprojectionSummaryTest {
	@Test void actualDuplicateInputsSumWorkAndKeepOnlyPeakParserScratch() {
		String json = "{\"p\":\"\u00e9\ud83d\ude80\"}";
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		var first = new InventoryReprojectionSummary.Builder().entries(2).input(json).input(null).build();
		var both = first.plus(new InventoryReprojectionSummary.Builder().entries(1).input(json).build());
		assertEquals(3, both.entries()); assertEquals(2, both.parserCalls()); assertEquals(2L * bytes, both.inputBytes());
		assertEquals(2L * (InventoryItemBudget.reprojectionWorkUpperBound(bytes) + 64L * bytes), both.parserWorkUnits());
		assertEquals(bytes, both.maxInputBytes());
		assertEquals(InventoryReprojectionSummary.METADATA_BYTES + both.replacementBytes() + InventoryItemBudget.reprojectionWorkspaceUpperBound(bytes), both.workspaceBytes());
		assertEquals(bytes, InventoryReprojectionSummary.utf8Bytes(json));
		assertEquals(1, InventoryReprojectionSummary.utf8Bytes("\ud800"));
	}
	@Test void unknownSummaryIsExplicitAndCannotAuthorizeAnUnboundedCallback() {
		assertSame(InventoryReprojectionSummary.UNKNOWN, InventoryReprojectionSummary.UNKNOWN.plus(InventoryReprojectionSummary.EMPTY));
		assertThrows(IllegalStateException.class, InventoryReprojectionSummary.UNKNOWN::workUnits);
		assertThrows(IllegalStateException.class, InventoryReprojectionSummary.UNKNOWN::workspaceBytes);
	}
	@Test void nullSkipsTheParserButNonNullEmptyStringStillPaysOneAttempt() {
		var summary = new InventoryReprojectionSummary.Builder().entries(2).input(null).input("").build();
		assertEquals(1, summary.parserCalls()); assertEquals(0, summary.inputBytes());
		assertEquals(InventoryItemBudget.reprojectionWorkUpperBound(0), summary.parserWorkUnits());
		assertEquals(InventoryReprojectionSummary.METADATA_BYTES + InventoryReprojectionSummary.replacementBytes(0)
			+ InventoryItemBudget.reprojectionWorkspaceUpperBound(0), summary.workspaceBytes());
	}
	@Test void allNativeOwnedInputCapsFitOneFreshMinimumCeilingTick() {
		// Independent shape/cap argument, not capacity * maximum-sized phantom
		// input calls. Frozen UTF-8 <= 3*its charged UTF-16 ownership; each of
		// the three catalogues already caps total UTF-8 display+canonical bytes.
		long cap = InventoryItemCodec.DEFAULT_MAX_RETAINED_BYTES;
		long inputBytes = 3 * cap + 3 * cap;
		long calls = InventorySourceAccess.MAX_SLOTS + 3L * InventoryItemCodec.DEFAULT_MAX_ENTRIES;
		long visits = calls + InventorySnapshotLayout.MAX_MEMBERS
			+ (long) InventorySnapshotLayout.MAX_MEMBERS * InventorySnapshotLayout.MAX_SEGMENTS_PER_MEMBER;
		int max = InventoryLimits.MAX_DISPLAY_BYTES;
		long fixedParser = InventoryItemBudget.reprojectionWorkUpperBound(0);
		// Derive a loose linear envelope from the public certificate, then verify
		// it independently for every legal byte size (including rounding jumps).
		long slope = 2 * ((InventoryItemBudget.reprojectionWorkUpperBound(max) + max - 1) / max) + 128;
		for (int bytes = 0; bytes <= max; bytes++) {
			assertTrue(InventoryReprojectionSummary.parserWork(bytes) <= fixedParser + slope * bytes, "OLD bytes=" + bytes);
		}
		// Runtime holds/pays the metadata query before the separate source grant.
		// Reconstruct its three owner classifiers, not the helper's expected value.
		int classifierOwners = 3, declaredLookupsPerOwner = 32, publicLookupsPerOwner = 1;
		long scalarQueryWork = 4096L, reflectionLookupWork = 8192L, graphWorkPerOwner = 65536L;
		long queryWork = scalarQueryWork + classifierOwners
			* ((declaredLookupsPerOwner + publicLookupsPerOwner) * reflectionLookupWork + graphWorkPerOwner);
		assertEquals(queryWork, InventoryDisplayProjection.QUERY_WORK,
			"metadata query must include scalar, reflection and bounded owner-classification work");
		long actualCapEnvelope = queryWork + InventoryReprojectionSummary.FIXED_WORK
			+ InventoryReprojectionSummary.ENTRY_WORK * visits + calls * fixedParser + slope * inputBytes;
		long freshTick = (long) InventorySourceAccess.MAX_SLOTS * InventoryItemBudget.workUpperBound(InventoryItemCodec.minimumDisplayBytes());
		assertTrue(actualCapEnvelope < freshTick,
			"actual detached input caps must remain serviceable after coefficient changes: " + actualCapEnvelope + " < " + freshTick);
	}
}
