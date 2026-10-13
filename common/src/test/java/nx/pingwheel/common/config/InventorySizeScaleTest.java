package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model-level boundaries for the two finite inventory size scales. The
 * expectations come from the confirmed display/frame grids, the quarter-
 * multiplier wire quantum and the frame metadata reserve, not from copied
 * implementation constants.
 */
class InventorySizeScaleTest {

	private static BigDecimal decimal(String value) {
		return new BigDecimal(value);
	}

	private static void assertValue(String expected, BigDecimal actual) {
		assertEquals(0, decimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
	}

	@Test
	void displayGridHasExactlyTheConfirmed44Positions() {
		List<BigDecimal> values = InventorySizeScale.DISPLAY.legalValues();
		assertEquals(44, values.size());
		assertValue("0.25", values.get(0));
		assertValue("32", values.get(values.size() - 1));
		for (int index = 1; index < values.size(); index++) {
			assertTrue(values.get(index - 1).compareTo(values.get(index)) < 0, "ordered at " + index);
		}
		assertValue("2", InventorySizeScale.DISPLAY.valueAt(7));
		assertValue("2.5", InventorySizeScale.DISPLAY.valueAt(8));
		assertValue("8", InventorySizeScale.DISPLAY.valueAt(19));
		assertValue("9", InventorySizeScale.DISPLAY.valueAt(20));
		assertValue("32", InventorySizeScale.DISPLAY.valueAt(43));
		assertThrows(UnsupportedOperationException.class, () -> values.add(BigDecimal.ONE));
	}

	@Test
	void frameGridHasExactlyTheConfirmed64QuarterStepPositions() {
		List<BigDecimal> values = InventorySizeScale.FRAME.legalValues();
		assertEquals(64, values.size());
		assertValue("0.25", values.get(0));
		assertValue("16", values.get(values.size() - 1));
		assertValue("0.5", InventorySizeScale.FRAME.valueAt(1));
		assertValue("4.25", InventorySizeScale.FRAME.valueAt(16));
		assertValue("16", InventorySizeScale.FRAME.valueAt(63));
	}

	@Test
	void legalValuesRequireExactQuarterMultipliersInsideTheConfirmedRange() {
		assertTrue(InventorySizeScale.DISPLAY.isLegal(decimal("0.25")));
		assertTrue(InventorySizeScale.DISPLAY.isLegal(decimal("2.0")));
		assertTrue(InventorySizeScale.DISPLAY.isLegal(decimal("2.5")));
		assertTrue(InventorySizeScale.DISPLAY.isLegal(decimal("32")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(decimal("2.25")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(decimal("8.25")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(decimal("32.25")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(decimal("0.125")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(decimal("0")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(decimal("-1")));
		assertFalse(InventorySizeScale.DISPLAY.isLegal(null));

		assertTrue(InventorySizeScale.FRAME.isLegal(decimal("0.25")));
		assertTrue(InventorySizeScale.FRAME.isLegal(decimal("4.25")));
		assertTrue(InventorySizeScale.FRAME.isLegal(decimal("16")));
		assertFalse(InventorySizeScale.FRAME.isLegal(decimal("0.3")));
		assertFalse(InventorySizeScale.FRAME.isLegal(decimal("16.25")));
		assertFalse(InventorySizeScale.FRAME.isLegal(decimal("0")));
	}

	@Test
	void normalizeDefaultsNullClampsFiniteValuesAndSnapsHalfUp() {
		assertValue("1", InventorySizeScale.DISPLAY.normalize(null));
		assertValue("1", InventorySizeScale.FRAME.normalize(null));
		assertValue("0.25", InventorySizeScale.DISPLAY.normalize(decimal("0")));
		assertValue("0.25", InventorySizeScale.DISPLAY.normalize(decimal("-9")));
		assertValue("32", InventorySizeScale.DISPLAY.normalize(decimal("9999")));
		assertValue("16", InventorySizeScale.FRAME.normalize(decimal("9999")));

		// 2.4 KiB sits nearest the 10 KiB position; 2.2 KiB sits nearest 8 KiB.
		assertValue("2.5", InventorySizeScale.DISPLAY.normalize(decimal("2.4")));
		assertValue("2", InventorySizeScale.DISPLAY.normalize(decimal("2.2")));
		// A tie between the 8 KiB and 10 KiB positions breaks upward.
		assertValue("2.5", InventorySizeScale.DISPLAY.normalize(decimal("2.25")));
		assertValue("1", InventorySizeScale.FRAME.normalize(decimal("1.124")));
		assertValue("0.5", InventorySizeScale.FRAME.normalize(decimal("0.375")));
		assertValue("1.25", InventorySizeScale.FRAME.normalize(decimal("1.3")));
	}

	@Test
	void bytesAndQuantaAreExactOnTheQuarterMultiplierQuantum() {
		assertEquals(1024, InventorySizeScale.DISPLAY.bytes(decimal("0.25")));
		assertEquals(4096, InventorySizeScale.DISPLAY.bytes(decimal("1")));
		assertEquals(30720, InventorySizeScale.DISPLAY.bytes(decimal("7.5")));
		assertEquals(131072, InventorySizeScale.DISPLAY.bytes(decimal("32")));
		assertEquals(8192, InventorySizeScale.FRAME.bytes(decimal("0.25")));
		assertEquals(32768, InventorySizeScale.FRAME.bytes(decimal("1")));
		assertEquals(139264, InventorySizeScale.FRAME.bytes(decimal("4.25")));
		assertEquals(524288, InventorySizeScale.FRAME.bytes(decimal("16")));

		assertEquals(1, InventorySizeScale.DISPLAY.quantaOf(decimal("0.25")));
		assertEquals(6, InventorySizeScale.DISPLAY.quantaOf(decimal("1.5")));
		assertEquals(128, InventorySizeScale.DISPLAY.quantaOf(decimal("32")));
		assertEquals(64, InventorySizeScale.FRAME.quantaOf(decimal("16")));
		assertThrows(ArithmeticException.class, () -> InventorySizeScale.DISPLAY.quantaOf(decimal("0.3")));
		assertThrows(IllegalArgumentException.class, () -> InventorySizeScale.DISPLAY.bytes(decimal("2.25")));

		assertValue("0.25", InventorySizeScale.DISPLAY.fromQuanta(1));
		assertValue("32", InventorySizeScale.DISPLAY.fromQuanta(128));
		assertValue("16", InventorySizeScale.FRAME.fromQuanta(64));
		assertThrows(IllegalArgumentException.class, () -> InventorySizeScale.DISPLAY.fromQuanta(9));
		assertThrows(IllegalArgumentException.class, () -> InventorySizeScale.DISPLAY.fromQuanta(0));
		assertThrows(IllegalArgumentException.class, () -> InventorySizeScale.FRAME.fromQuanta(65));
	}

	@Test
	void maximumDisplayMultiplierFloorsToTheFrameReserve() {
		assertValue("7.5", InventorySizeScale.maximumDisplayMultiplier(decimal("1")));
		assertValue("1.5", InventorySizeScale.maximumDisplayMultiplier(decimal("0.25")));
		assertValue("3.5", InventorySizeScale.maximumDisplayMultiplier(decimal("0.5")));
		assertValue("32", InventorySizeScale.maximumDisplayMultiplier(decimal("4.25")));
		assertValue("32", InventorySizeScale.maximumDisplayMultiplier(decimal("16")));
		assertValue("7.5", InventorySizeScale.maximumDisplayMultiplier(null));
		assertEquals(30720, InventorySizeScale.DISPLAY.bytes(
			InventorySizeScale.maximumDisplayMultiplier(decimal("1"))));
	}

	@Test
	void reconcileNormalizesFrameFirstReducesDisplayOnlyAndNeverRaisesFrame() {
		var pair = InventorySizeScale.reconcile(decimal("32"), decimal("1"));
		assertValue("7.5", pair.displayMultiplier());
		assertValue("1", pair.frameMultiplier());
		assertTrue(pair.displayReduced());

		pair = InventorySizeScale.reconcile(decimal("1"), decimal("0.25"));
		assertValue("1", pair.displayMultiplier());
		assertValue("0.25", pair.frameMultiplier());
		assertFalse(pair.displayReduced());

		pair = InventorySizeScale.reconcile(decimal("32"), decimal("4.25"));
		assertValue("32", pair.displayMultiplier());
		assertFalse(pair.displayReduced());

		pair = InventorySizeScale.reconcile(null, null);
		assertValue("1", pair.displayMultiplier());
		assertValue("1", pair.frameMultiplier());
		assertFalse(pair.displayReduced());

		// Out-of-range inputs clamp independently; the frame is never raised.
		pair = InventorySizeScale.reconcile(decimal("9999"), decimal("0.1"));
		assertValue("1.5", pair.displayMultiplier());
		assertValue("0.25", pair.frameMultiplier());
		assertTrue(pair.displayReduced());
	}

	@Test
	void pairLegalityRequiresBothLeavesAndTheMetadataReserve() {
		assertTrue(InventorySizeScale.isPairLegal(decimal("7.5"), decimal("1")));
		assertTrue(InventorySizeScale.isPairLegal(decimal("32"), decimal("4.25")));
		assertFalse(InventorySizeScale.isPairLegal(decimal("32"), decimal("4")));
		assertFalse(InventorySizeScale.isPairLegal(decimal("32"), decimal("1")));
		assertFalse(InventorySizeScale.isPairLegal(decimal("2.25"), decimal("1")));
		assertFalse(InventorySizeScale.isPairLegal(decimal("1"), decimal("0.3")));
		assertFalse(InventorySizeScale.isPairLegal(null, decimal("1")));
	}

	@Test
	void orderedPositionsStepAndSaturateOnEachScale() {
		assertEquals(0, InventorySizeScale.DISPLAY.indexOf(decimal("0.25")));
		assertEquals(3, InventorySizeScale.DISPLAY.indexOf(decimal("1")));
		assertEquals(43, InventorySizeScale.DISPLAY.indexOf(decimal("32")));
		assertValue("2.5", InventorySizeScale.DISPLAY.next(decimal("2")));
		assertValue("2", InventorySizeScale.DISPLAY.previous(decimal("2.5")));
		assertValue("32", InventorySizeScale.DISPLAY.next(decimal("32")));
		assertValue("0.25", InventorySizeScale.DISPLAY.previous(decimal("0.25")));

		assertEquals(0, InventorySizeScale.FRAME.indexOf(decimal("0.25")));
		assertEquals(63, InventorySizeScale.FRAME.indexOf(decimal("16")));
		assertValue("4.5", InventorySizeScale.FRAME.next(decimal("4.25")));
		assertValue("4", InventorySizeScale.FRAME.previous(decimal("4.25")));
		assertValue("16", InventorySizeScale.FRAME.next(decimal("16")));
	}
}
