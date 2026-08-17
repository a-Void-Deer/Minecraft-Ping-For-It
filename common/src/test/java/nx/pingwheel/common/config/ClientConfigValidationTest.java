package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigValidationTest {

	@Test
	void defaultsMatchTheCurrentWheelLook() {
		ClientConfig config = new ClientConfig();

		assertFalse(config.isLongPressCompatibilityMode());
		assertEquals(20, config.getLongPressCompatibilitySliceMillis());
		assertEquals(20, config.getEffectiveLongPressCompatibilitySliceMillis());
		assertEquals(14, config.getWheelInnerRadius());
		assertEquals(39, config.getWheelOuterRadius());
		assertEquals(100, config.getWheelOpacity());
		assertEquals(100, config.getWheelFontSize());
		assertEquals(100, config.getWheelTargetFontSize());
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
			"{\"wheelHoldMillis\":-1,\"longPressCompatibilityMode\":true,\"longPressCompatibilitySliceMillis\":999,\"wheelTimeoutMillis\":99999,"
				+ "\"cancelHalfConeAngleDegrees\":0,\"wheelInnerRadius\":1000,"
				+ "\"wheelOuterRadius\":-100,\"wheelOpacity\":-5,\"wheelFontSize\":999,"
				+ "\"wheelTargetFontSize\":-1}",
			ClientConfig.class);
		List<ClampWarning> warnings = new ArrayList<>();

		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(100, config.getWheelHoldMillis());
		assertTrue(config.isLongPressCompatibilityMode());
		assertEquals(50, config.getLongPressCompatibilitySliceMillis());
		assertEquals(30000, config.getWheelTimeoutMillis());
		assertEquals(1, config.getCancelHalfConeAngleDegrees());
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());
		assertEquals(0, config.getWheelOpacity());
		assertEquals(500, config.getWheelFontSize());
		assertEquals(10, config.getWheelTargetFontSize());
		assertTrue(config.getWheelOuterRadius() - config.getWheelInnerRadius() >= 8);
		assertEquals(
			List.of(
				new ClampWarning("wheelHoldMillis", -1, 100),
				new ClampWarning("longPressCompatibilitySliceMillis", 999, 50),
				new ClampWarning("wheelTimeoutMillis", 99999, 30000),
				new ClampWarning("cancelHalfConeAngleDegrees", 0, 1),
				new ClampWarning("wheelInnerRadius", 1000, 12),
				new ClampWarning("wheelOuterRadius", -100, 20),
				new ClampWarning("wheelOpacity", -5, 0),
				new ClampWarning("wheelFontSize", 999, 500),
				new ClampWarning("wheelTargetFontSize", -1, 10)),
			warnings);
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
		ClientConfig config = new Gson().fromJson("{\"wheelFontSize\":250}", ClientConfig.class);

		config.validate((key, suppliedValue, effectiveValue) -> {});

		assertEquals(250, config.getWheelFontSize());
		assertEquals(100, config.getWheelTargetFontSize());
	}

	@Test
	void directJsonPairConvergesToTheMinimumValidAnnulus() {
		ClientConfig config = new Gson().fromJson(
			"{\"wheelInnerRadius\":30,\"wheelOuterRadius\":20}",
			ClientConfig.class);
		List<ClampWarning> warnings = new ArrayList<>();

		config.validate((key, suppliedValue, effectiveValue) ->
			warnings.add(new ClampWarning(key, suppliedValue, effectiveValue)));

		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());
		assertEquals(List.of(new ClampWarning("wheelInnerRadius", 30, 12)), warnings);
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
	void radiusSettersKeepEveryLivePairValidInEitherMutationOrder() {
		ClientConfig config = new ClientConfig();

		config.setWheelOuterRadius(20);
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());

		config.setWheelInnerRadius(30);
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());

		config.setWheelOuterRadius(75);
		assertEquals(12, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());

		config.setWheelInnerRadius(30);
		assertEquals(30, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());

		config.setWheelInnerRadius(Integer.MIN_VALUE);
		assertEquals(6, config.getWheelInnerRadius());
		assertEquals(75, config.getWheelOuterRadius());

		config.setWheelOuterRadius(Integer.MIN_VALUE);
		assertEquals(6, config.getWheelInnerRadius());
		assertEquals(20, config.getWheelOuterRadius());
		assertTrue(config.getWheelOuterRadius() - config.getWheelInnerRadius() >= 8);
	}

	@Test
	void compatibilitySettersClampEachOtherWhenHoldChanges() {
		ClientConfig config = new ClientConfig();

		config.setLongPressCompatibilitySliceMillis(Integer.MAX_VALUE);
		assertEquals(150, config.getLongPressCompatibilitySliceMillis());

		config.setWheelHoldMillis(100);
		assertEquals(100, config.getWheelHoldMillis());
		assertEquals(50, config.getLongPressCompatibilitySliceMillis());

		config.setLongPressCompatibilitySliceMillis(10);
		config.setWheelHoldMillis(2000);
		assertEquals(10, config.getLongPressCompatibilitySliceMillis());

		config.longPressCompatibilitySliceMillis = Integer.MAX_VALUE;
		assertEquals(300, config.getEffectiveLongPressCompatibilitySliceMillis());
	}

	private record ClampWarning(String key, int suppliedValue, int effectiveValue) {}
}
