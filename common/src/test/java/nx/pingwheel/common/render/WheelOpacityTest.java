package nx.pingwheel.common.render;

import java.util.List;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.wheel.WheelGeometry;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.interaction.wheel.WheelSelection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelOpacityTest {

	@Test
	void zeroOpacityMakesEveryBaseAlphaInvisible() {
		assertEquals(0x00010203, WheelOpacity.apply(0xFF010203, 0));
		assertEquals(0x00010203, WheelOpacity.apply(0x50010203, 0));
		assertEquals(0x00010203, WheelOpacity.apply(0x00010203, 0));
	}

	@Test
	void halfOpacityRoundsEachBaseAlphaIndependently() {
		assertEquals(0x80010203, WheelOpacity.apply(0xFF010203, 50));
		assertEquals(0x28010203, WheelOpacity.apply(0x50010203, 50));
		assertEquals(0x44010203, WheelOpacity.apply(0x88010203, 50));
		assertEquals(0x4D010203, WheelOpacity.apply(0x99010203, 50));
	}

	@Test
	void fullOpacityPreservesTheOriginalArgbExactly() {
		assertEquals(0xFF010203, WheelOpacity.apply(0xFF010203, 100));
		assertEquals(0x50010203, WheelOpacity.apply(0x50010203, 100));
		assertEquals(0x00010203, WheelOpacity.apply(0x00010203, 100));
	}

	@Test
	void textOpacityPromotesOnlyFontAlphaBelowMinecraftMinimum() {
		int baseColor = 0xFF123456;

		assertEquals(0x00123456, WheelOpacity.applyText(baseColor, 0));
		assertEquals(0x04123456, WheelOpacity.applyText(baseColor, 1));
		assertEquals(0x05123456, WheelOpacity.applyText(baseColor, 2));
		assertEquals(0x08123456, WheelOpacity.applyText(baseColor, 3));
		assertEquals(0x0A123456, WheelOpacity.applyText(baseColor, 4));
		assertEquals(0x80123456, WheelOpacity.applyText(baseColor, 50));
		assertEquals(baseColor, WheelOpacity.applyText(baseColor, 100));

		for (int opacity : new int[] {0, 1, 2, 3, 4, 50, 100}) {
			assertEquals(0x123456, WheelOpacity.applyText(baseColor, opacity) & 0x00FFFFFF);
		}
	}

	@Test
	void zeroOpacitySkipsVisualWorkWithoutChangingWheelSelectionSemantics() {
		assertFalse(WheelOpacity.shouldRender(0));
		assertFalse(WheelOpacity.shouldRender(-1));
		assertTrue(WheelOpacity.shouldRender(1));

		WheelGeometry geometry = new WheelGeometry(6.0, 20.0);
		List<PingType> pingTypes = List.of(
			PingTypeCatalog.builtIn().entries().get(0));
		WheelSelection selection = geometry.select(0.0, -20.0, pingTypes);

		assertTrue(selection instanceof WheelSelection.Sector);
		assertEquals(selection, geometry.select(0.0, -20.0, pingTypes));
	}
}
