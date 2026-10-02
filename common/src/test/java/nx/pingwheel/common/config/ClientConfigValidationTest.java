package nx.pingwheel.common.config;

import com.google.gson.Gson;
import nx.pingwheel.common.client.outline.BlockDisplayPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigValidationTest {
	@Test
	void pingDurationIsNotAClientSetting() {
		assertThrows(NoSuchFieldException.class, () -> ClientConfig.class.getDeclaredField("pingDuration"));
	}

	@Test
	void correctionPeriodIsNotExposedOrPersisted() {
		assertThrows(NoSuchFieldException.class, () -> ClientConfig.class.getDeclaredField("correctionPeriod"));
		assertThrows(NoSuchFieldException.class, () -> ClientConfig.class.getDeclaredField("MAX_CORRECTION_PERIOD"));
		assertThrows(NoSuchMethodException.class, () -> ClientConfig.class.getMethod("getCorrectionPeriod"));
		assertThrows(
			NoSuchMethodException.class,
			() -> ClientConfig.class.getMethod("setCorrectionPeriod", float.class));

		ClientConfig config = new Gson().fromJson("{\"correctionPeriod\":4.5}", ClientConfig.class);

		assertFalse(new Gson().toJson(config).contains("\"correctionPeriod\""));
	}

	@Test
	void entityBlockRenderModeDefaultsToAllAndIsSerializedLocally() {
		ClientConfig config = new ClientConfig();

		assertEquals(EntityBlockRenderMode.ALL, config.getEntityBlockRenderMode());
		assertEquals(EntityBlockRenderMode.ALL, EntityBlockRenderMode.get("all"));
		assertEquals("voxel_shape_only", EntityBlockRenderMode.VOXEL_SHAPE_ONLY.toString());
		assertTrue(new Gson().toJson(config).contains("\"entityBlockRenderMode\":\"ALL\""));

		config.setEntityBlockRenderMode(EntityBlockRenderMode.ALL);
		assertEquals(EntityBlockRenderMode.ALL, config.getEntityBlockRenderMode());
		config.setEntityBlockRenderMode(null);
		assertEquals(EntityBlockRenderMode.COMPATIBLE, config.getEntityBlockRenderMode());
		config.setEntityBlockRenderMode(EntityBlockRenderMode.ALL);
		assertTrue(new Gson().toJson(config).contains("\"entityBlockRenderMode\":\"ALL\""));
	}

	@Test
	void entityBlockRenderModeLowercaseIsLocaleIndependent() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("all", EntityBlockRenderMode.ALL.toString());
			assertEquals("voxel_shape_only", EntityBlockRenderMode.VOXEL_SHAPE_ONLY.toString());
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void nullAndUnknownEntityBlockRenderModesRecoverToCompatibleDuringValidation() {
		for (String json : List.of(
			"{\"entityBlockRenderMode\":null}",
			"{\"entityBlockRenderMode\":\"unknown\"}")) {
			ClientConfig config = new Gson().fromJson(json, ClientConfig.class);
			config.validate((key, supplied, effective) -> {});
			assertEquals(EntityBlockRenderMode.COMPATIBLE, config.getEntityBlockRenderMode(), json);
		}
	}

	@Test
	void oldJsonWithoutTheNewListsKeepsTheirDefaults() {
		ClientConfig config = new Gson().fromJson("{\"pingVolume\":42}", ClientConfig.class);

		config.validate((key, suppliedValue, effectiveValue) -> {});

		assertEquals(List.of("*:*"), config.getBlockDisplayWhitelist());
		assertEquals(List.of(), config.getBlockShapeBlacklist());
		assertTrue(new Gson().toJson(config).contains("\"blockDisplayWhitelist\""));
		assertTrue(new Gson().toJson(config).contains("\"blockShapeBlacklist\""));
	}

	@Test
	void oldJsonWithoutMarkerDisplayDurationUsesFollowServerDefault() {
		ClientConfig config = new Gson().fromJson("{\"pingVolume\":42}", ClientConfig.class);

		config.validate((key, suppliedValue, effectiveValue) -> {});

		assertEquals(ClientConfigBounds.FOLLOW_SERVER_MARKER_DISPLAY_DURATION, config.getMarkerDisplayDuration());
		assertTrue(config.isFollowServerMarkerDisplayDuration());
		assertTrue(new Gson().toJson(config).contains("\"markerDisplayDuration\":0"));
	}

	@Test
	void markerDisplayDurationSetterAndValidationKeepSentinelAndCustomBoundsSafe() {
		ClientConfig config = new ClientConfig();

		config.setMarkerDisplayDuration(-1);
		assertEquals(0, config.getMarkerDisplayDuration());
		config.setMarkerDisplayDuration(ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION + 1);
		assertEquals(ClientConfigBounds.MAX_MARKER_DISPLAY_DURATION, config.getMarkerDisplayDuration());

		config.markerDisplayDuration = Integer.MIN_VALUE;
		List<ClampWarning> warnings = new ArrayList<>();
		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(0, config.getMarkerDisplayDuration());
		assertEquals(List.of(new ClampWarning("markerDisplayDuration", Integer.MIN_VALUE, 0)), warnings);
	}

	@Test
	void markerDisplayDurationRoundTripsThroughJsonPersistence() {
		ClientConfig original = new ClientConfig();
		original.setMarkerDisplayDuration(23);

		ClientConfig restored = new Gson().fromJson(new Gson().toJson(original), ClientConfig.class);
		restored.validate((key, suppliedValue, effectiveValue) -> {});

		assertEquals(23, restored.getMarkerDisplayDuration());
		assertFalse(restored.isFollowServerMarkerDisplayDuration());
	}

	@Test
	void nullBlankAndMalformedConfiguredListsAreInvalid() {
		for (String json : List.of(
			"{\"blockDisplayWhitelist\":null}",
			"{\"blockShapeBlacklist\":null}",
			"{\"blockDisplayWhitelist\":[\" \"]}",
			"{\"blockShapeBlacklist\":[\"minecraft:stone:*\"]}")) {
			ClientConfig config = new Gson().fromJson(json, ClientConfig.class);
			assertThrows(IllegalArgumentException.class, () -> config.validate((key, supplied, effective) -> {}), json);
		}
	}

	@Test
	void matcherIsCompiledAtValidationAndSetTimeInsteadOfPerFrame() {
		ClientConfig config = new ClientConfig();
		BlockDisplayPolicy initial = config.getBlockDisplayPolicy();

		config.validate((key, suppliedValue, effectiveValue) -> {});
		assertTrue(initial != config.getBlockDisplayPolicy());

		BlockDisplayPolicy afterValidation = config.getBlockDisplayPolicy();
		config.setBlockShapeBlacklist(List.of("minecraft:stone"));
		assertTrue(afterValidation != config.getBlockDisplayPolicy());
		assertEquals(List.of("minecraft:stone"), config.getBlockShapeBlacklist());
	}

	@Test
	void clampWarningMessageUsesConcreteValuesWithoutPlaceholders() {
		String message = ClientConfig.formatClampWarning("wheelHoldMillis", -1, 100);

		assertEquals(
			"Client config value clamped: key=wheelHoldMillis, supplied=-1, effective=100",
			message);
		assertFalse(message.contains("{}"));
	}

	@Test
	void directJsonValuesAreValidatedBeforeUse() {
		ClientConfig config = new Gson().fromJson(
			"{\"wheelHoldMillis\":" + Integer.MIN_VALUE
				+ ",\"longPressCompatibilityMode\":true,\"longPressCompatibilitySliceMillis\":"
				+ ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS
				+ ",\"wheelTimeoutMillis\":" + Integer.MAX_VALUE
				+ ",\"cancelHalfConeAngleDegrees\":" + Integer.MIN_VALUE
				+ ",\"wheelOpacity\":" + Integer.MIN_VALUE
				+ ",\"wheelFontSize\":" + Integer.MAX_VALUE
				+ ",\"wheelTargetFontSize\":" + Integer.MIN_VALUE + "}",
			ClientConfig.class);
		List<ClampWarning> warnings = new ArrayList<>();

		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS, config.getWheelHoldMillis());
		assertTrue(config.isLongPressCompatibilityMode());
		assertEquals(ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			config.getLongPressCompatibilitySliceMillis());
		assertEquals(ClientConfigBounds.MAX_WHEEL_TIMEOUT_MILLIS, config.getWheelTimeoutMillis());
		assertEquals(ClientConfigBounds.MIN_CANCEL_HALF_CONE_ANGLE_DEGREES, config.getCancelHalfConeAngleDegrees());
		assertEquals(ClientConfigBounds.MIN_WHEEL_OPACITY, config.getWheelOpacity());
		assertEquals(ClientConfigBounds.MAX_WHEEL_FONT_SIZE, config.getWheelFontSize());
		assertEquals(ClientConfigBounds.MIN_WHEEL_TARGET_FONT_SIZE, config.getWheelTargetFontSize());
		assertEquals(
			List.of(
				new ClampWarning("wheelHoldMillis", Integer.MIN_VALUE, ClientConfigBounds.MIN_WHEEL_HOLD_MILLIS),
				new ClampWarning("wheelTimeoutMillis", Integer.MAX_VALUE, ClientConfigBounds.MAX_WHEEL_TIMEOUT_MILLIS),
				new ClampWarning("cancelHalfConeAngleDegrees", Integer.MIN_VALUE,
					ClientConfigBounds.MIN_CANCEL_HALF_CONE_ANGLE_DEGREES),
				new ClampWarning("wheelOpacity", Integer.MIN_VALUE, ClientConfigBounds.MIN_WHEEL_OPACITY),
				new ClampWarning("wheelFontSize", Integer.MAX_VALUE, ClientConfigBounds.MAX_WHEEL_FONT_SIZE),
				new ClampWarning("wheelTargetFontSize", Integer.MIN_VALUE,
					ClientConfigBounds.MIN_WHEEL_TARGET_FONT_SIZE)),
			warnings);
	}

	@Test
	void directJsonCompatibilitySliceIsCappedByAnExplicitValidHold() {
		int holdMillis = 210;
		ClientConfig config = new Gson().fromJson(
			"{\"wheelHoldMillis\":" + holdMillis
				+ ",\"longPressCompatibilitySliceMillis\":" + Integer.MAX_VALUE + "}",
			ClientConfig.class);
		List<ClampWarning> warnings = new ArrayList<>();

		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(holdMillis, config.getWheelHoldMillis());
		assertEquals(holdMillis / 2, config.getLongPressCompatibilitySliceMillis());
		assertEquals(List.of(new ClampWarning("longPressCompatibilitySliceMillis", Integer.MAX_VALUE,
			holdMillis / 2)), warnings);
	}

	@Test
	void validDefaultConfigProducesNoClampWarnings() {
		ClientConfig config = new ClientConfig();
		List<ClampWarning> warnings = new ArrayList<>();

		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(List.of(), warnings);
	}

	@Test
	void legacyWheelFontSizeRemainsTheOptionFontAndTargetFontDefaultsSeparately() {
		ClientConfig baseline = new ClientConfig();
		ClientConfig config = new Gson().fromJson("{\"wheelFontSize\":250}", ClientConfig.class);
		ClientConfig explicitTarget = new Gson().fromJson(
			"{\"wheelFontSize\":250,\"wheelTargetFontSize\":80}", ClientConfig.class);

		config.validate((key, suppliedValue, effectiveValue) -> {});
		explicitTarget.validate((key, suppliedValue, effectiveValue) -> {});

		assertEquals(250, config.getWheelFontSize());
		assertEquals(baseline.getWheelTargetFontSize(), config.getWheelTargetFontSize());
		assertEquals(250, explicitTarget.getWheelFontSize());
		assertEquals(80, explicitTarget.getWheelTargetFontSize());
	}

	@Test
	void legacyRadiusValuesAreNotReadOrPersistedAndNeverSeedSpatialPreferences() {
		ClientConfig config = new Gson().fromJson(
			"{\"wheelInnerRadius\":63,\"wheelOuterRadius\":231}", ClientConfig.class);
		config.validate((key, suppliedValue, effectiveValue) -> {});

		var serialized = new Gson().toJsonTree(config).getAsJsonObject();
		assertFalse(serialized.has("wheelInnerRadius"));
		assertFalse(serialized.has("wheelOuterRadius"));
		assertEquals(new SpatialSelectorSettings(), config.getSpatialSelector());
	}

	@Test
	void channelTruncationDoesNotProduceClampWarnings() {
		String channel = "channel-value-that-must-not-be-logged-".repeat(5);
		ClientConfig config = new Gson().fromJson(
			new Gson().toJson(java.util.Map.of("channel", channel)),
			ClientConfig.class);
		List<ClampWarning> warnings = new ArrayList<>();

		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(ClientConfig.MAX_CHANNEL_LENGTH, config.channel.length());
		assertEquals(List.of(), warnings);
	}

	@Test
	void compatibilitySettersClampEachOtherWhenHoldChanges() {
		ClientConfig config = new ClientConfig();
		int initialHold = 210;
		config.setWheelHoldMillis(initialHold);

		config.setLongPressCompatibilitySliceMillis(Integer.MAX_VALUE);
		assertEquals(initialHold / 2, config.getLongPressCompatibilitySliceMillis());

		config.setWheelHoldMillis(100);
		assertEquals(100, config.getWheelHoldMillis());
		assertEquals(100 / 2, config.getLongPressCompatibilitySliceMillis());

		config.setLongPressCompatibilitySliceMillis(ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS);
		config.setWheelHoldMillis(ClientConfigBounds.MAX_WHEEL_HOLD_MILLIS);
		assertEquals(ClientConfigBounds.MIN_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			config.getLongPressCompatibilitySliceMillis());

		config.longPressCompatibilitySliceMillis = Integer.MAX_VALUE;
		assertEquals(ClientConfigBounds.MAX_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			config.getEffectiveLongPressCompatibilitySliceMillis());
	}

	private record ClampWarning(String key, int suppliedValue, int effectiveValue) {}
}
