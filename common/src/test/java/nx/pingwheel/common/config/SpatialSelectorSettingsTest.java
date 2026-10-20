package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialSelectorSettingsTest {

	@Test
	void hoverMillisClampsToTheConfirmedDwellRange() {
		assertEquals(SpatialSelectorSettings.MIN_HOVER_MILLIS, SpatialSelectorSettings.clampHoverMillis(0));
		assertEquals(SpatialSelectorSettings.MAX_HOVER_MILLIS, SpatialSelectorSettings.clampHoverMillis(2001));
		assertEquals(750, SpatialSelectorSettings.clampHoverMillis(750));
	}

	@Test
	void nullAndOutOfRangeNestedValuesValidateWithoutThrowing() {
		SpatialSelectorSettings settings = new SpatialSelectorSettings();
		settings.setSubmenuRadiusScale(null);
		settings.setHoverMillis(Integer.MIN_VALUE);
		settings.setHoverEnabled(true);

		settings.validate();

		assertEquals(SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE, settings.getSubmenuRadiusScale());
		assertEquals(SpatialSelectorSettings.MIN_HOVER_MILLIS, settings.getHoverMillis());
		assertTrue(settings.isHoverEnabled());
	}

	@Test
	void defaultHoverIsDisabledUntilTheUserEnablesIt() {
		assertFalse(new SpatialSelectorSettings().isHoverEnabled());
	}

	@Test
	void contentIntentDefaultsToDisabledForFreshMissingAndNullValues() {
		assertFalse(SpatialSelectorSettings.DEFAULT_CONTENT_INTENT_ENABLED);
		assertFalse(new SpatialSelectorSettings().isContentIntentEnabled());
		assertFalse(new SpatialSelectorSettings().snapshot().contentIntentEnabled());

		SpatialSelectorSettings missing = new Gson().fromJson("{}", SpatialSelectorSettings.class);
		assertFalse(missing.isContentIntentEnabled());
		assertFalse(missing.snapshot().contentIntentEnabled());

		SpatialSelectorSettings explicitNull = new Gson().fromJson(
			"{\"contentIntentEnabled\":null}", SpatialSelectorSettings.class);
		explicitNull.validate();
		assertFalse(explicitNull.isContentIntentEnabled());
		assertFalse(explicitNull.snapshot().contentIntentEnabled());
	}

	@Test
	void explicitContentIntentChoicesAreRetainedAndFreezeIntoTheSnapshot() {
		for (boolean saved : List.of(false, true)) {
			SpatialSelectorSettings settings = new Gson().fromJson(
				"{\"contentIntentEnabled\":" + saved + "}", SpatialSelectorSettings.class);
			assertEquals(saved, settings.isContentIntentEnabled());
			SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
			assertEquals(saved, frozen.contentIntentEnabled());

			settings.setContentIntentEnabled(!saved);
			assertEquals(saved, frozen.contentIntentEnabled(),
				"a held snapshot keeps its frozen content-intent choice");
			assertEquals(!saved, settings.snapshot().contentIntentEnabled(),
				"snapshot() supplies the live content-intent choice");
		}
	}

	@Test
	void previousSnapshotConstructorFormsDefaultTheContentIntentChoice() {
		SpatialSelectorSettings.Snapshot eight = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false);
		assertFalse(eight.contentIntentEnabled());

		SpatialSelectorSettings.Snapshot nine = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25);
		assertFalse(nine.contentIntentEnabled());

		SpatialSelectorSettings.Snapshot ten = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2.0"));
		assertFalse(ten.contentIntentEnabled());

		SpatialSelectorSettings.Snapshot twelve = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2.0"), 64, 0);
		assertFalse(twelve.contentIntentEnabled());

		SpatialSelectorSettings.Snapshot canonical = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2.0"), 64, 0, true);
		assertTrue(canonical.contentIntentEnabled());
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
				SpatialSelectorSettings::clampPreciseCapturePeriodTicks),
			new IntBound(SpatialSelectorSettings.MIN_TRAIL_LENGTH, SpatialSelectorSettings.MAX_TRAIL_LENGTH,
				SpatialSelectorSettings::clampTrailLength),
			new IntBound(SpatialSelectorSettings.MIN_TRAIL_OPACITY, SpatialSelectorSettings.MAX_TRAIL_OPACITY,
				SpatialSelectorSettings::clampTrailOpacity))) {
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
		assertEquals(24, SpatialSelectorSettings.DEFAULT_DEADZONE);
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
	void deadzoneDefaultsToTwentyFourAndValidSavedValuesAreRetainedWithoutRescaling() {
		assertEquals(24, new SpatialSelectorSettings().getDeadzone());
		assertEquals(24, new SpatialSelectorSettings().snapshot().deadzone());

		SpatialSelectorSettings missing = new Gson().fromJson("{}", SpatialSelectorSettings.class);
		assertEquals(24, missing.getDeadzone());
		assertEquals(24, missing.snapshot().deadzone());

		SpatialSelectorSettings explicitNull = new Gson().fromJson("{\"deadzone\":null}", SpatialSelectorSettings.class);
		explicitNull.validate();
		assertEquals(24, explicitNull.getDeadzone(), "an explicit JSON null keeps the initialized default");
		assertEquals(24, explicitNull.snapshot().deadzone());

		for (int saved : List.of(18, SpatialSelectorSettings.MIN_DEADZONE, SpatialSelectorSettings.MAX_DEADZONE)) {
			SpatialSelectorSettings explicit = new Gson().fromJson("{\"deadzone\":" + saved + "}",
				SpatialSelectorSettings.class);
			SpatialSelectorSettings.Snapshot frozen = explicit.snapshot();
			explicit.validate();
			assertEquals(saved, explicit.getDeadzone(), "a valid saved deadzone is retained without rescaling");
			assertEquals(saved, explicit.snapshot().deadzone());
			assertEquals(frozen, explicit.snapshot());
			explicit.setDeadzone(SpatialSelectorSettings.DEFAULT_DEADZONE);
			assertEquals(saved, frozen.deadzone(), "a held snapshot keeps its frozen deadzone");
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
			 "hoverEnabled":true,"hoverMillis":-1,"showTrail":false,"reduceMotion":true,
			 "trailLength":-1,"trailOpacity":2147483647}
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
		assertEquals(SpatialSelectorSettings.MIN_TRAIL_LENGTH, settings.getTrailLength());
		assertEquals(SpatialSelectorSettings.MAX_TRAIL_OPACITY, settings.getTrailOpacity());
	}

	@Test
	void trailPreferencesNormalizeMissingNullAndExplicitValuesAndFreezeIntoTheSnapshot() {
		SpatialSelectorSettings missing = new Gson().fromJson("{}", SpatialSelectorSettings.class);
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, missing.getTrailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, missing.getTrailOpacity());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, missing.snapshot().trailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, missing.snapshot().trailOpacity());

		SpatialSelectorSettings explicitNull = new Gson().fromJson(
			"{\"trailLength\":null,\"trailOpacity\":null}", SpatialSelectorSettings.class);
		assertEquals(missing.snapshot(), explicitNull.snapshot(),
			"Gson must retain initialized primitive values for explicit JSON nulls");
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, explicitNull.getTrailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, explicitNull.getTrailOpacity());
		explicitNull.validate();
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, explicitNull.getTrailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, explicitNull.getTrailOpacity());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, explicitNull.snapshot().trailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, explicitNull.snapshot().trailOpacity());

		SpatialSelectorSettings explicit = new Gson().fromJson(
			"{\"trailLength\":64,\"trailOpacity\":0}", SpatialSelectorSettings.class);
		SpatialSelectorSettings.Snapshot frozen = explicit.snapshot();
		explicit.validate();
		assertEquals(frozen, explicit.snapshot());
		assertEquals(64, frozen.trailLength());
		assertEquals(0, frozen.trailOpacity());
		explicit.setTrailLength(SpatialSelectorSettings.MAX_TRAIL_LENGTH);
		explicit.setTrailOpacity(SpatialSelectorSettings.MAX_TRAIL_OPACITY);
		assertEquals(64, frozen.trailLength());
		assertEquals(0, frozen.trailOpacity());
		assertEquals(SpatialSelectorSettings.MAX_TRAIL_LENGTH, explicit.snapshot().trailLength());
		assertEquals(SpatialSelectorSettings.MAX_TRAIL_OPACITY, explicit.snapshot().trailOpacity());
	}

	@Test
	void trailOpacityZeroStaysAValidExplicitChoiceRatherThanBeingClampedAway() {
		SpatialSelectorSettings settings = new SpatialSelectorSettings();
		settings.setTrailOpacity(0);
		assertEquals(0, settings.getTrailOpacity());
		assertEquals(0, settings.snapshot().trailOpacity());
		settings.validate();
		assertEquals(0, settings.getTrailOpacity());
	}

	@Test
	void trailSettersClampIndependentlyAndKeepValuesOutsideTheUiStepGrid() {
		SpatialSelectorSettings settings = explicitSettings();
		settings.setTrailOpacity(73);
		settings.setTrailLength(Integer.MIN_VALUE);
		assertEquals(SpatialSelectorSettings.MIN_TRAIL_LENGTH, settings.getTrailLength());
		assertEquals(73, settings.getTrailOpacity());
		settings.setTrailLength(Integer.MAX_VALUE);
		assertEquals(SpatialSelectorSettings.MAX_TRAIL_LENGTH, settings.getTrailLength());
		settings.setTrailLength(64);
		settings.setTrailOpacity(Integer.MIN_VALUE);
		assertEquals(0, settings.getTrailOpacity());
		settings.setTrailOpacity(Integer.MAX_VALUE);
		assertEquals(SpatialSelectorSettings.MAX_TRAIL_OPACITY, settings.getTrailOpacity());
		assertEquals(64, settings.getTrailLength());
		assertEquals(90, settings.getRootDistance());
		assertTrue(settings.isShowTrail());
		assertFalse(settings.isReduceMotion());
	}

	@Test
	void frozenSessionPreferencesNeverFollowLaterConfigEdits() {
		SpatialSelectorSettings settings = explicitSettings();
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		settings.setDeadzone(30);
		settings.setStroke(80);
		settings.setDwellMillis(250);
		settings.setRootDistance(100);
		settings.setHoverEnabled(false);
		settings.setHoverMillis(1000);
		settings.setShowTrail(false);
		settings.setReduceMotion(true);
		settings.setSubmenuRadiusScale(new BigDecimal("4"));
		settings.setTrailLength(512);
		settings.setTrailOpacity(100);

		assertEquals(new SpatialSelectorSettings.Snapshot(28, 70, 200, 90,
			true, 750, true, false,
			SpatialSelectorSettings.DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS,
			SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE, 64, 25), frozen);
	}

	@Test
	void rootDistanceAndGesturePreferencesAreIndependentInBothMutationDirections() {
		SpatialSelectorSettings settings = explicitSettings();
		settings.setRootDistance(Integer.MAX_VALUE);
		assertEquals(28, settings.getDeadzone());
		assertEquals(70, settings.getStroke());
		assertEquals(200, settings.getDwellMillis());
		assertEquals(750, settings.getHoverMillis());
		assertTrue(settings.isHoverEnabled());

		settings.setRootDistance(90);
		settings.setDeadzone(Integer.MAX_VALUE);
		settings.setStroke(Integer.MIN_VALUE);
		settings.setDwellMillis(Integer.MAX_VALUE);
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
	void submenuRadiusScaleClampsToTheConfirmedRange() {
		assertEquals(0, SpatialSelectorSettings.MIN_SUBMENU_RADIUS_SCALE
			.compareTo(SpatialSelectorSettings.clampSubmenuRadiusScale(new BigDecimal("0.5"))));
		assertEquals(0, SpatialSelectorSettings.MAX_SUBMENU_RADIUS_SCALE
			.compareTo(SpatialSelectorSettings.clampSubmenuRadiusScale(new BigDecimal("9"))));
		assertEquals(0, new BigDecimal("2.5")
			.compareTo(SpatialSelectorSettings.clampSubmenuRadiusScale(new BigDecimal("2.5"))));
	}

	@Test
	void missingAndNullSubmenuRadiusScaleGainTheConfirmedDefault() {
		SpatialSelectorSettings missing = new Gson().fromJson("{}", SpatialSelectorSettings.class);
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE
			.compareTo(missing.getSubmenuRadiusScale()));

		SpatialSelectorSettings explicitNull = new Gson().fromJson(
			"{\"submenuRadiusScale\":null}", SpatialSelectorSettings.class);
		explicitNull.validate();
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE
			.compareTo(explicitNull.getSubmenuRadiusScale()));
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE
			.compareTo(explicitNull.snapshot().submenuRadiusScale()));
	}

	@Test
	void outOfRangeSubmenuRadiusScaleClampsAndFreezesIntoTheSnapshot() {
		SpatialSelectorSettings settings = new Gson().fromJson(
			"{\"submenuRadiusScale\":0.5}", SpatialSelectorSettings.class);
		assertEquals(0, SpatialSelectorSettings.MIN_SUBMENU_RADIUS_SCALE
			.compareTo(settings.snapshot().submenuRadiusScale()));
		settings.validate();
		assertEquals(0, SpatialSelectorSettings.MIN_SUBMENU_RADIUS_SCALE
			.compareTo(settings.getSubmenuRadiusScale()));

		settings.setSubmenuRadiusScale(new BigDecimal("9"));
		assertEquals(0, SpatialSelectorSettings.MAX_SUBMENU_RADIUS_SCALE
			.compareTo(settings.getSubmenuRadiusScale()));

		settings.setSubmenuRadiusScale(new BigDecimal("2.5"));
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		settings.setSubmenuRadiusScale(new BigDecimal("1.0"));
		assertEquals(0, new BigDecimal("2.5").compareTo(frozen.submenuRadiusScale()));
	}

	@Test
	void previousSnapshotConstructorFormsDefaultTheSubmenuRadiusScaleAndTrailPreferences() {
		SpatialSelectorSettings.Snapshot eight = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false);
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE.compareTo(eight.submenuRadiusScale()));
		assertEquals(SpatialSelectorSettings.DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS,
			eight.preciseCapturePeriodTicks());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, eight.trailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, eight.trailOpacity());

		SpatialSelectorSettings.Snapshot nine = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25);
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE.compareTo(nine.submenuRadiusScale()));
		assertEquals(25, nine.preciseCapturePeriodTicks());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, nine.trailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, nine.trailOpacity());

		SpatialSelectorSettings.Snapshot ten = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2.0"));
		assertEquals(25, ten.preciseCapturePeriodTicks());
		assertEquals(0, new BigDecimal("2.0").compareTo(ten.submenuRadiusScale()));
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, ten.trailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, ten.trailOpacity());

		SpatialSelectorSettings.Snapshot canonical = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2.0"), 64, 0);
		assertEquals(64, canonical.trailLength());
		assertEquals(0, canonical.trailOpacity());
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

	@Test
	void inventoryPanelLimitsDefaultIndependentlyForMissingAndNullMembers() {
		SpatialSelectorSettings.Snapshot defaults = new SpatialSelectorSettings().snapshot();
		SpatialSelectorSettings missing = new Gson().fromJson("{}", SpatialSelectorSettings.class);
		assertEquals(defaults, missing.snapshot());

		SpatialSelectorSettings nullWidth = new Gson().fromJson(
			"{\"inventoryMaxWidthFraction\":null,\"inventoryMaxHeightFraction\":0.825}", SpatialSelectorSettings.class);
		assertNull(nullWidth.getInventoryMaxWidthFraction());
		assertEquals(defaults.inventoryMaxWidthFraction(), nullWidth.snapshot().inventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.825"), nullWidth.snapshot().inventoryMaxHeightFraction());
		assertNull(nullWidth.getInventoryMaxWidthFraction(), "taking a safe snapshot does not mutate loaded values");
		nullWidth.validate();
		assertEquals(defaults.inventoryMaxWidthFraction(), nullWidth.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.825"), nullWidth.getInventoryMaxHeightFraction());

		SpatialSelectorSettings nullHeight = new Gson().fromJson(
			"{\"inventoryMaxWidthFraction\":0.475,\"inventoryMaxHeightFraction\":null}", SpatialSelectorSettings.class);
		assertNull(nullHeight.getInventoryMaxHeightFraction());
		assertEquals(new BigDecimal("0.475"), nullHeight.snapshot().inventoryMaxWidthFraction());
		assertEquals(defaults.inventoryMaxHeightFraction(), nullHeight.snapshot().inventoryMaxHeightFraction());
		nullHeight.validate();
		assertEquals(new BigDecimal("0.475"), nullHeight.getInventoryMaxWidthFraction());
		assertEquals(defaults.inventoryMaxHeightFraction(), nullHeight.getInventoryMaxHeightFraction());
	}

	@Test
	void inventoryPanelLimitsClampRawJsonAndSettersIndependentlyWithoutQuantizing() {
		SpatialSelectorSettings settings = new Gson().fromJson("""
			{"inventoryMaxWidthFraction":-1,"inventoryMaxHeightFraction":2,"rootDistance":90}
			""", SpatialSelectorSettings.class);
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		assertEquals(new BigDecimal("0.2"), frozen.inventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.95"), frozen.inventoryMaxHeightFraction());
		settings.validate();
		assertEquals(frozen, settings.snapshot());
		assertEquals(new BigDecimal("0.2"), settings.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.95"), settings.getInventoryMaxHeightFraction());

		settings.setInventoryMaxWidthFraction(new BigDecimal("0.47300"));
		settings.setInventoryMaxHeightFraction(new BigDecimal("-1"));
		assertEquals(new BigDecimal("0.473"), settings.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.3"), settings.getInventoryMaxHeightFraction());
		settings.setInventoryMaxHeightFraction(new BigDecimal("0.8123400"));
		settings.setInventoryMaxWidthFraction(new BigDecimal("2"));
		assertEquals(new BigDecimal("0.8"), settings.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.81234"), settings.getInventoryMaxHeightFraction());
		assertEquals(90, settings.getRootDistance());

		settings.setInventoryMaxWidthFraction(null);
		assertEquals(SpatialSelectorSettings.DEFAULT_INVENTORY_MAX_WIDTH_FRACTION, settings.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.81234"), settings.getInventoryMaxHeightFraction());
		settings.setInventoryMaxWidthFraction(new BigDecimal("0.473"));
		settings.setInventoryMaxHeightFraction(null);
		assertEquals(new BigDecimal("0.473"), settings.getInventoryMaxWidthFraction());
		assertEquals(SpatialSelectorSettings.DEFAULT_INVENTORY_MAX_HEIGHT_FRACTION, settings.getInventoryMaxHeightFraction());
	}

	@Test
	void customInventoryPanelLimitsStayExactThroughValidationAndFreezeIntoTheSnapshot() {
		SpatialSelectorSettings settings = new Gson().fromJson("""
			{"inventoryMaxWidthFraction":0.47300,"inventoryMaxHeightFraction":0.8123400}
			""", SpatialSelectorSettings.class);
		SpatialSelectorSettings.Snapshot frozen = settings.snapshot();
		assertEquals(new BigDecimal("0.473"), frozen.inventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.81234"), frozen.inventoryMaxHeightFraction());
		settings.validate();
		assertEquals(frozen, settings.snapshot());
		assertEquals(new BigDecimal("0.473"), settings.getInventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.81234"), settings.getInventoryMaxHeightFraction());

		settings.setInventoryMaxWidthFraction(new BigDecimal("0.6"));
		settings.setInventoryMaxHeightFraction(new BigDecimal("0.5"));
		assertEquals(new BigDecimal("0.473"), frozen.inventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.81234"), frozen.inventoryMaxHeightFraction());
		assertEquals(new BigDecimal("0.6"), settings.snapshot().inventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.5"), settings.snapshot().inventoryMaxHeightFraction());
	}

	@Test
	void everyPreviousSnapshotConstructorUsesDefaultPanelLimitsWhileCanonicalCarriesCustomValues() {
		SpatialSelectorSettings.Snapshot defaults = new SpatialSelectorSettings().snapshot();
		for (SpatialSelectorSettings.Snapshot previous : List.of(
			new SpatialSelectorSettings.Snapshot(18, 50, 180, 55, false, 500, true, false),
			new SpatialSelectorSettings.Snapshot(18, 50, 180, 55, false, 500, true, false, 25),
			new SpatialSelectorSettings.Snapshot(18, 50, 180, 55, false, 500, true, false, 25,
				new BigDecimal("2")),
			new SpatialSelectorSettings.Snapshot(18, 50, 180, 55, false, 500, true, false, 25,
				new BigDecimal("2"), 64, 25),
			new SpatialSelectorSettings.Snapshot(18, 50, 180, 55, false, 500, true, false, 25,
				new BigDecimal("2"), 64, 25, true),
			new SpatialSelectorSettings.Snapshot(18, 50, 180, 55, false, 500, true, false, 25,
				new BigDecimal("2"), 64, 25, true, true, 100))) {
			assertEquals(defaults.inventoryMaxWidthFraction(), previous.inventoryMaxWidthFraction());
			assertEquals(defaults.inventoryMaxHeightFraction(), previous.inventoryMaxHeightFraction());
			assertFalse(previous.inventorySelectionStayOpenEnabled());
		}

		SpatialSelectorSettings.Snapshot canonical = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2"), 64, 25, true, true, 100,
			new BigDecimal("0.475"), new BigDecimal("0.825"));
		assertEquals(new BigDecimal("0.475"), canonical.inventoryMaxWidthFraction());
		assertEquals(new BigDecimal("0.825"), canonical.inventoryMaxHeightFraction());
		assertTrue(canonical.contentIntentEnabled());
		assertTrue(canonical.gestureSkipHoldEnabled());
		assertEquals(100, canonical.gestureSkipHoldMillis());
	}

	@Test
	void inventorySelectionStayOpenDefaultsClosedAndFreezesIntoTheNextSnapshot() {
		var defaults = new SpatialSelectorSettings();
		assertFalse(defaults.isInventorySelectionStayOpenEnabled());
		assertFalse(defaults.snapshot().inventorySelectionStayOpenEnabled());

		for (String json : List.of("{}", "{\"inventorySelectionStayOpenEnabled\":null}")) {
			var settings = new Gson().fromJson(json, SpatialSelectorSettings.class);
			settings.validate();
			assertFalse(settings.isInventorySelectionStayOpenEnabled(), json);
			assertFalse(settings.snapshot().inventorySelectionStayOpenEnabled(), json);
		}

		var settings = new SpatialSelectorSettings();
		var frozen = settings.snapshot();
		settings.setInventorySelectionStayOpenEnabled(true);
		assertTrue(settings.isInventorySelectionStayOpenEnabled());
		assertFalse(frozen.inventorySelectionStayOpenEnabled(), "an existing hold keeps its frozen choice");
		assertTrue(settings.snapshot().inventorySelectionStayOpenEnabled());

		var canonical = new SpatialSelectorSettings.Snapshot(18, 50, 180, 55,
			false, 500, true, false, 25, new BigDecimal("2"), 64, 25, true, true, 100,
			new BigDecimal("0.475"), new BigDecimal("0.825"), true);
		assertTrue(canonical.inventorySelectionStayOpenEnabled());
	}

	private static SpatialSelectorSettings explicitSettings() {
		return new Gson().fromJson("""
			{"deadzone":28,"stroke":70,"dwellMillis":200,"rootDistance":90,
			 "hoverEnabled":true,"hoverMillis":750,"showTrail":true,"reduceMotion":false,
			 "trailLength":64,"trailOpacity":25}
			""", SpatialSelectorSettings.class);
	}

	private record IntBound(int min, int max, IntUnaryOperator clamp) {}
}
