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
				SpatialSelectorSettings::clampHoverMillis),
			new IntBound(SpatialSelectorSettings.MIN_PRECISE_CAPTURE_PERIOD_TICKS,
				SpatialSelectorSettings.MAX_PRECISE_CAPTURE_PERIOD_TICKS,
				SpatialSelectorSettings::clampPreciseCapturePeriodTicks))) {
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
	void halfScaleDistanceDefaultsAndBoundsStayInsideTheirClamps() {
		// The selector keeps the confirmed half-scale geometry, with the entry
		// stroke default reduced independently to the requested 50 pixels.
		assertEquals(18, SpatialSelectorSettings.DEFAULT_DEADZONE);
		assertEquals(50, SpatialSelectorSettings.DEFAULT_STROKE);
		assertEquals(55, SpatialSelectorSettings.DEFAULT_ROOT_DISTANCE);
		assertEquals(12, SpatialSelectorSettings.MIN_DEADZONE);
		assertEquals(32, SpatialSelectorSettings.MAX_DEADZONE);
		assertEquals(40, SpatialSelectorSettings.MIN_STROKE);
		assertEquals(85, SpatialSelectorSettings.MAX_STROKE);
		assertEquals(32, SpatialSelectorSettings.MIN_ROOT_DISTANCE);
		assertEquals(120, SpatialSelectorSettings.MAX_ROOT_DISTANCE);
		assertEquals(SpatialSelectorSettings.DEFAULT_DEADZONE,
			SpatialSelectorSettings.clampDeadzone(SpatialSelectorSettings.DEFAULT_DEADZONE));
		assertEquals(SpatialSelectorSettings.DEFAULT_STROKE,
			SpatialSelectorSettings.clampStroke(SpatialSelectorSettings.DEFAULT_STROKE));
		assertEquals(SpatialSelectorSettings.DEFAULT_ROOT_DISTANCE,
			SpatialSelectorSettings.clampRootDistance(SpatialSelectorSettings.DEFAULT_ROOT_DISTANCE));
		assertTrue(SpatialSelectorSettings.MIN_STROKE > SpatialSelectorSettings.MAX_DEADZONE);
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
		settings.setDeadzone(30);
		settings.setStroke(80);
		settings.setDwellMillis(250);
		settings.setRootDistance(100);
		settings.setTargetGlide(new BigDecimal("2"));
		settings.setHoverEnabled(false);
		settings.setHoverMillis(1000);
		settings.setShowTrail(false);
		settings.setReduceMotion(true);

		assertEquals(new SpatialSelectorSettings.Snapshot(28, 70, 200, 90,
			new BigDecimal("1.5"), true, 750, true, false), frozen);
	}

	@Test
	void rootDistanceAndGesturePreferencesAreIndependentInBothMutationDirections() {
		SpatialSelectorSettings settings = explicitSettings();
		settings.setRootDistance(Integer.MAX_VALUE);
		assertEquals(28, settings.getDeadzone());
		assertEquals(70, settings.getStroke());
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
	void preciseCapturePeriodDefaultsToOneTickAndFreezesIntoTheSnapshot() {
		assertEquals(1, SpatialSelectorSettings.MIN_PRECISE_CAPTURE_PERIOD_TICKS);
		assertEquals(50, SpatialSelectorSettings.MAX_PRECISE_CAPTURE_PERIOD_TICKS);
		assertEquals(1, SpatialSelectorSettings.DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS);

		SpatialSelectorSettings settings = new SpatialSelectorSettings();
		assertEquals(1, settings.getPreciseCapturePeriodTicks());
		assertEquals(1, settings.snapshot().preciseCapturePeriodTicks());

		settings.setPreciseCapturePeriodTicks(25);
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		settings.setPreciseCapturePeriodTicks(50);
		assertEquals(25, frozen.preciseCapturePeriodTicks());

		settings.setPreciseCapturePeriodTicks(0);
		assertEquals(1, settings.getPreciseCapturePeriodTicks());
		settings.setPreciseCapturePeriodTicks(51);
		assertEquals(50, settings.getPreciseCapturePeriodTicks());
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
			{"deadzone":28,"stroke":70,"dwellMillis":200,"rootDistance":90,"targetGlide":1.5,
			 "hoverEnabled":true,"hoverMillis":750,"showTrail":true,"reduceMotion":false}
			""", SpatialSelectorSettings.class);
	}

	private record IntBound(int min, int max, IntUnaryOperator clamp) {}
}
