package nx.pingwheel.common.presentation.inventory.client;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryQuantityFormatTest {
	@Test void boundaryAmountsNeverBecomeAnInventedZeroOrFractionalMillibucket() {
		assertEquals("0", InventoryQuantityFormat.fluid(0, "en").number());
		for (long amount : new long[] {1, 80}) {
			var value = InventoryQuantityFormat.fluid(amount, "en");
			assertEquals("<1", value.number()); assertTrue(value.approximate()); assertTrue(value.unitKey().endsWith("millibuckets"));
		}
		assertEquals("1", InventoryQuantityFormat.fluid(81, "en").number());
		assertFalse(InventoryQuantityFormat.fluid(81, "en").approximate());
		assertEquals("1", InventoryQuantityFormat.fluid(82, "en").number());
		assertTrue(InventoryQuantityFormat.fluid(82, "en").approximate());
		assertEquals("1", InventoryQuantityFormat.fluid(81_000, "en").number());
		assertTrue(InventoryQuantityFormat.fluid(81_000, "en").unitKey().endsWith("buckets"));
		assertEquals("1.001", InventoryQuantityFormat.fluid(81_041, "en").number());
	}
	@Test void largeLongsRetainSixSignificantDigitsAndRoundingCarryUsesOriginalRational() {
		assertEquals("111.200", InventoryQuantityFormat.fluid(9_007_199_254_740_993L, "en").number());
		assertEquals("113.869", InventoryQuantityFormat.fluid(Long.MAX_VALUE, "en").number());
		assertEquals("western_12", InventoryQuantityFormat.fluid(Long.MAX_VALUE, "en").unitKey().substring("pingforit.inventory.quantity.".length()));
		assertEquals("113.869", InventoryQuantityFormat.fluid(Long.MAX_VALUE, "zh_cn").number());
		assertEquals("113,869", InventoryQuantityFormat.fluid(Long.MAX_VALUE, "de_de").number());
		long carry = 80_999_999_960L;
		var value = InventoryQuantityFormat.fluid(carry, "en");
		assertEquals("1.00000", value.number()); assertTrue(value.unitKey().endsWith("western_6")); assertTrue(value.approximate());
		assertThrows(IllegalArgumentException.class, () -> InventoryQuantityFormat.fluid(-1, "en"));
	}
}
