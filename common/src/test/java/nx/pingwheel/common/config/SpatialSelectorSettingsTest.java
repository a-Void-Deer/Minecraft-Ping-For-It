package nx.pingwheel.common.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialSelectorSettingsTest {

	@Test
	void targetGlideClampsToThePrototypeRange() {
		assertEquals(0, SpatialSelectorSettings.MIN_TARGET_GLIDE
			.compareTo(SpatialSelectorSettings.clampTargetGlide(new BigDecimal("0.1"))));
		assertEquals(0, SpatialSelectorSettings.MAX_TARGET_GLIDE
			.compareTo(SpatialSelectorSettings.clampTargetGlide(new BigDecimal("9"))));
		assertEquals(0, new BigDecimal("1.5")
			.compareTo(SpatialSelectorSettings.clampTargetGlide(new BigDecimal("1.5"))));
	}

	@Test
	void hoverMillisClampsToTheConfirmedDwellRange() {
		assertEquals(SpatialSelectorSettings.MIN_HOVER_MILLIS, SpatialSelectorSettings.clampHoverMillis(0));
		assertEquals(SpatialSelectorSettings.MAX_HOVER_MILLIS, SpatialSelectorSettings.clampHoverMillis(2001));
		assertEquals(750, SpatialSelectorSettings.clampHoverMillis(750));
	}

	@Test
	void nullAndOutOfRangeNestedValuesValidateWithoutThrowing() {
		SpatialSelectorSettings settings = new SpatialSelectorSettings();
		settings.setTargetGlide(null);
		settings.setHoverMillis(Integer.MIN_VALUE);
		settings.setHoverEnabled(true);

		settings.validate();

		assertTrue(settings.getTargetGlide().compareTo(SpatialSelectorSettings.MIN_TARGET_GLIDE) >= 0);
		assertTrue(settings.getTargetGlide().compareTo(SpatialSelectorSettings.MAX_TARGET_GLIDE) <= 0);
		assertEquals(SpatialSelectorSettings.MIN_HOVER_MILLIS, settings.getHoverMillis());
		assertTrue(settings.isHoverEnabled());
	}

	@Test
	void defaultHoverIsDisabledUntilTheUserEnablesIt() {
		assertFalse(new SpatialSelectorSettings().isHoverEnabled());
	}
}
