package nx.pingwheel.common.presentation.inventory.minecraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independently tabulated source-site floors, not production-counter snapshots. */
class InventoryEncodingCostOracleTest {
	private static InventoryEncodingCost cost(boolean json, InventoryItemBudget budget) {
		var result = new InventoryEncodingCost(budget); result.reset(json); return result;
	}
	@Test void graphRowsMatchIndependentDfuDeclaredFieldVectorsForEveryArity() {
		long[][] rows = {{8,19,5},{8,21,5},{8,23,5},{8,25,5}, {16,49,10},{16,51,10},{16,53,10},{16,55,10},
			{24,85,17},{32,115,25},{32,117,25},{32,121,25},{32,123,25},{32,126,25},{32,128,25},{32,132,25}};
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var c = cost(false, budget);
			for (int index = 0; index < rows.length; index++) {
				c.reset(false); c.graph(index + 1); var row = rows[index];
				assertEquals(row[0] + 8 * row[1] + 40 * row[2], c.storage());
			}
		} finally { budget.end(); }
	}
	@Test void groupTenFirstFiveGetterCorrectionIncludesBoldButNotSecondChildEncoding() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var c = cost(true, budget); c.graph(10); c.optional(true); c.repeatedOptionals(1);
			// Independent review G10+two bold Optionals+five curry functions:
			// V=(32,0,117,27), excludes builders/child encodes/defaults.
			assertEquals(2048, c.storage());
			c.reset(false); c.intStream(4);
			// E2 input(51,0,7,3), primitive Success(0,0,2,1),
			// corrected E4 output(20,0,2,3)+conservative generator header.
			assertEquals(71 + 8L * 11 + 40L * 8, c.storage());
		} finally { budget.end(); }
	}
	@Test void universalBuilderRowsDominateIndependentResetGrowthAndOutputFloors() {
		// E3/E4 executable arithmetic, including initial/reset4 arrays, ordinary
		// immutable trims and native outputs. Production also covers lazy errors
		// and source iterators, so equality would be an incorrectly narrow oracle.
		long[][] map = {{1,1799,2381},{3,3214,3910},{6,5338,6301},{7,5950,7002}};
		long[][] list = {{0,831,682},{1,1592,1346},{256,146851,146594}};
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var c = cost(false, budget);
			for (boolean json : new boolean[] {false, true}) {
				for (var row : map) { c.reset(json); c.map((int) row[0]); assertTrue(c.storage() >= row[json ? 2 : 1]); }
				for (var row : list) { c.reset(json); c.list((int) row[0], 0); assertTrue(c.storage() >= row[json ? 2 : 1]); }
			}
			c.reset(false); c.list(256, 1); assertTrue(c.storage() >= 143630);
		} finally { budget.end(); }
	}
	@Test void primitiveArrayConversionPricesItsOwnTerminalNotReferenceListCodec() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var c = cost(false, budget);
			// Arrays.stream(existing int[]) input V35/7R/2O; output fixed node,
			// new int[4], IntArrayTag plus conservative generator header20/2R/4O.
			c.primitiveArray(4, 4); assertEquals(55 + 8L * 9 + 40L * 6, c.storage());
			c.reset(true); c.primitiveArray(4, 8);
			// LongPipeline.Head/spliterator35/7R/2O; primitive mapToObj + JSON
			// terminal96/36R/19O; first ArrayList capacity10/1O.
			assertEquals(131 + 8L * 53 + 40L * 22, c.storage());
		} finally { budget.end(); }
	}
	@Test void ownedCollidingMapIncludesNativeTreeNodeReplacementsBeforeAdmission() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			var c = cost(false, budget); c.beginKeys();
			for (int value = 0; value < 256; value++) {
				var key = new StringBuilder();
				for (int bit = 0; bit < 9; bit++) key.append((value & (1 << bit)) == 0 ? "BB" : "Aa");
				c.mapKey(key.toString());
			}
			c.ownedKeyMap();
			// Independent hash simulator: table1008/6headers, normal11/tree256.
			assertTrue(c.storage() >= 1324L + 8L * 3345 + 40L * 273);
			assertThrows(InventoryItemBudget.RootLimitException.class, c::admit,
				"finite collision work is refused BEFORE a native createMap is allowed");
		} finally { budget.end(); }
	}
	@Test void displayPartitionRefusalIsIndependentAndScratchCannotBeRefundedBySwitching() {
		var budget = InventoryItemBudget.root(4096); budget.begin(4096);
		try {
			budget.allocate(1_900_000, 0, 0, 0);
			budget.beginDisplay(); budget.allocate(900_000, 0, 0, 0);
			assertThrows(InventoryItemBudget.RootLimitException.class, () -> budget.allocate(150_000, 0, 0, 0));
			assertEquals(2_800_000, budget.rootAllocatedBytes());
			assertThrows(IllegalStateException.class, budget::beginDisplay);
		} finally { budget.end(); }
	}
}
