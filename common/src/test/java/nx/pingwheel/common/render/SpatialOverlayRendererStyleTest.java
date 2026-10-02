package nx.pingwheel.common.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialOverlayRendererStyleTest {

	@Test
	void callerOverridesSurvivePreferenceChangesRatherThanResettingToNativeDefaults() {
		var style = new SpatialOverlayRenderer.Style(37, 0.62, 1.4, false, true);
		assertEquals(37, style.opacityPercent());
		assertEquals(0.62, style.optionTextScale());
		assertEquals(1.4, style.inventoryTextScale());
		assertFalse(style.showTrail());
		assertTrue(style.reduceMotion());
	}

	@Test
	void oldFontPercentagesRemainIndependentAndScaleProportionally() {
		var smallerOptions = SpatialOverlayRenderer.Style.fromLegacyFontSizes(37, 80, 140, 88.0, true, false);
		var largerOptions = SpatialOverlayRenderer.Style.fromLegacyFontSizes(37, 160, 140, 88.0, false, true);
		assertEquals(smallerOptions.optionTextScale() * 2.0, largerOptions.optionTextScale());
		assertEquals(smallerOptions.inventoryTextScale(), largerOptions.inventoryTextScale());
		assertEquals(1.4, largerOptions.inventoryTextScale());
		assertEquals(37, largerOptions.opacityPercent());
		assertEquals(88.0, largerOptions.rootDistance());
	}

	@Test
	void alphaMultiplicationPreservesRgbAndRelativeNativeTranslucency() {
		// Two equal-RGB paints with a 2:1 base alpha must retain that ratio.
		int solid = SpatialOverlayRenderer.withAlpha(0xC0804020, 0.25);
		int translucent = SpatialOverlayRenderer.withAlpha(0x60804020, 0.25);
		assertEquals(48, solid >>> 24);
		assertEquals(24, translucent >>> 24);
		assertEquals(0x804020, solid & 0x00FFFFFF);
		assertEquals(solid & 0x00FFFFFF, translucent & 0x00FFFFFF);
		assertEquals(0, SpatialOverlayRenderer.withAlpha(0xFF804020, 0.0) >>> 24);
	}

	@Test
	void rootSpacingUsesOnlyCallerVisualDistanceAndChildSpacingKeepsItsOwnGeometry() {
		assertEquals(88.0, SpatialOverlayRenderer.nodeRadius(0, 120.0, 88.0, false));
		assertEquals(88.0, SpatialOverlayRenderer.nodeRadius(0, 75.0, 88.0, false));
		assertEquals(120.0, SpatialOverlayRenderer.nodeRadius(1, 120.0, 88.0, false));
		assertEquals(120.0, SpatialOverlayRenderer.nodeRadius(1, 120.0, 180.0, false));
		assertTrue(SpatialOverlayRenderer.nodeRadius(0, 120.0, 88.0, true) > 88.0);
	}
}
