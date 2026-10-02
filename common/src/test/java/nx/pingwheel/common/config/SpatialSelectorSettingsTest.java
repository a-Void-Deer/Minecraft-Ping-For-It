package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.IntUnaryOperator;

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

	@Test
	void distanceAndTimingClampsKeepTheValidIntervalAndSaturateOnBothSides() {
		for (IntBound bound : List.of(
			new IntBound(SpatialSelectorSettings.MIN_DEADZONE, SpatialSelectorSettings.MAX_DEADZONE,
				SpatialSelectorSettings::clampDeadzone),
			new IntBound(SpatialSelectorSettings.MIN_STROKE, SpatialSelectorSettings.MAX_STROKE,
				SpatialSelectorSettings::clampStroke),
			new IntBound(SpatialSelectorSettings.MIN_DWELL_MILLIS, SpatialSelectorSettings.MAX_DWELL_MILLIS,
				SpatialSelectorSettings::clampDwellMillis),
			new IntBound(SpatialSelectorSettings.MIN_ROOT_DISTANCE, SpatialSelectorSettings.MAX_ROOT_DISTANCE,
				SpatialSelectorSettings::clampRootDistance),
			new IntBound(SpatialSelectorSettings.MIN_HOVER_MILLIS, SpatialSelectorSettings.MAX_HOVER_MILLIS,
				SpatialSelectorSettings::clampHoverMillis))) {
			assertEquals(bound.min(), bound.clamp().applyAsInt(bound.min() - 1));
			assertEquals(bound.max(), bound.clamp().applyAsInt(bound.max() + 1));
			assertEquals(bound.min(), bound.clamp().applyAsInt(Integer.MIN_VALUE));
			assertEquals(bound.max(), bound.clamp().applyAsInt(Integer.MAX_VALUE));
			for (int value = bound.min(); value <= bound.max(); value++) {
				assertEquals(value, bound.clamp().applyAsInt(value));
			}
		}
	}

	@Test
	void everySupportedStrokeCanLeaveEverySupportedCenterDeadzone() {
		assertTrue(SpatialSelectorSettings.MIN_STROKE > SpatialSelectorSettings.MAX_DEADZONE);
	}

	@Test
	void rawJsonValidationAndSnapshotClampAllValuesWithoutResettingBooleanChoices() {
		SpatialSelectorSettings settings = new Gson().fromJson("""
			{"deadzone":-1,"stroke":2147483647,"dwellMillis":-1,"rootDistance":2147483647,
			 "targetGlide":null,"hoverEnabled":true,"hoverMillis":-1,"showTrail":false,"reduceMotion":true}
			""", SpatialSelectorSettings.class);
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		settings.validate();
		assertEquals(frozen, settings.snapshot());
		assertEquals(SpatialSelectorSettings.MIN_DEADZONE, settings.getDeadzone());
		assertEquals(SpatialSelectorSettings.MAX_STROKE, settings.getStroke());
		assertEquals(SpatialSelectorSettings.MIN_DWELL_MILLIS, settings.getDwellMillis());
		assertEquals(SpatialSelectorSettings.MAX_ROOT_DISTANCE, settings.getRootDistance());
		assertEquals(SpatialSelectorSettings.MIN_HOVER_MILLIS, settings.getHoverMillis());
		assertTrue(settings.isHoverEnabled());
		assertFalse(settings.isShowTrail());
		assertTrue(settings.isReduceMotion());
	}

	@Test
	void frozenSessionPreferencesNeverFollowLaterConfigEdits() {
		SpatialSelectorSettings settings = explicitSettings();
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		settings.setDeadzone(50);
		settings.setStroke(150);
		settings.setDwellMillis(250);
		settings.setRootDistance(200);
		settings.setTargetGlide(new BigDecimal("2"));
		settings.setHoverEnabled(false);
		settings.setHoverMillis(1000);
		settings.setShowTrail(false);
		settings.setReduceMotion(true);

		assertEquals(new SpatialSelectorSettings.Snapshot(40, 120, 200, 90,
			new BigDecimal("1.5"), true, 750, true, false), frozen);
	}

	@Test
	void rootDistanceAndGesturePreferencesAreIndependentInBothMutationDirections() {
		SpatialSelectorSettings settings = explicitSettings();
		settings.setRootDistance(Integer.MAX_VALUE);
		assertEquals(40, settings.getDeadzone());
		assertEquals(120, settings.getStroke());
		assertEquals(200, settings.getDwellMillis());
		assertEquals(new BigDecimal("1.5"), settings.getTargetGlide());
		assertEquals(750, settings.getHoverMillis());
		assertTrue(settings.isHoverEnabled());

		settings.setRootDistance(90);
		settings.setDeadzone(Integer.MAX_VALUE);
		settings.setStroke(Integer.MIN_VALUE);
		settings.setDwellMillis(Integer.MAX_VALUE);
		settings.setTargetGlide(BigDecimal.TEN);
		settings.setHoverMillis(Integer.MAX_VALUE);
		settings.setHoverEnabled(false);
		settings.validate();
		assertEquals(90, settings.getRootDistance());
	}

	@Test
	void freshDefaultsNeverAliasAnotherClientsMutablePreferences() {
		ClientConfig first = new ClientConfig();
		ClientConfig second = new ClientConfig();
		var untouched = second.getSpatialSelector().snapshot();
		first.getSpatialSelector().setRootDistance(90);
		first.getSpatialSelector().setHoverEnabled(true);
		assertEquals(untouched, second.getSpatialSelector().snapshot());
	}

	private static SpatialSelectorSettings explicitSettings() {
		return new Gson().fromJson("""
			{"deadzone":40,"stroke":120,"dwellMillis":200,"rootDistance":90,"targetGlide":1.5,
			 "hoverEnabled":true,"hoverMillis":750,"showTrail":true,"reduceMotion":false}
			""", SpatialSelectorSettings.class);
	}

	private record IntBound(int min, int max, IntUnaryOperator clamp) {}
}
