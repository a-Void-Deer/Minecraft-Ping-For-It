package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DoubleClickDangerConfigTest {
	private static final String VERSION = "0.5.0-pfi-beta1";

	@Test
	void boundsStayIndependentFromTheCancelCone() {
		var config = new ClientConfig();
		int cancelHalfCone = 30;
		config.setCancelHalfConeAngleDegrees(cancelHalfCone);

		config.setDoubleClickDangerWindowMs(Integer.MIN_VALUE);
		assertEquals(ClientConfigBounds.MIN_DOUBLE_CLICK_DANGER_WINDOW_MS, config.getDoubleClickDangerWindowMs());
		config.setDoubleClickDangerWindowMs(Integer.MAX_VALUE);
		assertEquals(ClientConfigBounds.MAX_DOUBLE_CLICK_DANGER_WINDOW_MS, config.getDoubleClickDangerWindowMs());
		config.setDoubleClickDangerWindowMs(40);
		assertEquals(40, config.getDoubleClickDangerWindowMs());

		config.setDoubleClickDangerAngleDegrees(-1.0);
		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees());
		config.setDoubleClickDangerAngleDegrees(
			ClientConfigBounds.MAX_DOUBLE_CLICK_DANGER_ANGLE_DEGREES + 0.25);
		assertEquals(ClientConfigBounds.MAX_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			config.getDoubleClickDangerAngleDegrees());
		config.setDoubleClickDangerAngleDegrees(12.5);
		assertEquals(12.5, config.getDoubleClickDangerAngleDegrees());

		assertEquals(cancelHalfCone, config.getCancelHalfConeAngleDegrees(),
			"editing the double-click danger gesture must not reuse or mutate the cancel half-cone");

		config.setCancelHalfConeAngleDegrees(60);
		assertEquals(40, config.getDoubleClickDangerWindowMs());
		assertEquals(12.5, config.getDoubleClickDangerAngleDegrees(),
			"the cancel half-cone is not the source of either danger value");
	}

	@Test
	void missingNullAndOutOfRangeJsonValuesUseDefaultsOrClamp() {
		var defaults = new ClientConfig();
		for (String json : List.of("{}",
			"{\"doubleClickDangerWindowMs\":null,\"doubleClickDangerAngleDegrees\":null}")) {
			var config = new Gson().fromJson(json, ClientConfig.class);
			config.validate((key, supplied, effective) -> {});
			assertEquals(defaults.getDoubleClickDangerWindowMs(), config.getDoubleClickDangerWindowMs(), json);
			assertEquals(defaults.getDoubleClickDangerAngleDegrees(), config.getDoubleClickDangerAngleDegrees(), json);
		}

		int belowMinimumWindow = ClientConfigBounds.MIN_DOUBLE_CLICK_DANGER_WINDOW_MS - 1;
		double aboveMaximumAngle = ClientConfigBounds.MAX_DOUBLE_CLICK_DANGER_ANGLE_DEGREES + 0.25;
		var clamped = new Gson().fromJson(
			"{\"doubleClickDangerWindowMs\":" + belowMinimumWindow
				+ ",\"doubleClickDangerAngleDegrees\":" + aboveMaximumAngle + "}",
			ClientConfig.class);
		clamped.validate((key, supplied, effective) -> {});
		assertEquals(ClientConfigBounds.MIN_DOUBLE_CLICK_DANGER_WINDOW_MS, clamped.getDoubleClickDangerWindowMs());
		assertEquals(ClientConfigBounds.MAX_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			clamped.getDoubleClickDangerAngleDegrees());
	}

	@Test
	void compatibilityClearsTheAngleAndRejectsNonZeroUntilDisabled() {
		var config = new ClientConfig();
		config.setDoubleClickDangerAngleDegrees(7.5);
		config.setLongPressCompatibilityMode(true);

		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees());
		config.setDoubleClickDangerAngleDegrees(7.5);
		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees(),
			"a non-zero setter request is rejected while compatibility is active");
		config.setDoubleClickDangerAngleDegrees(0.0);
		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees());

		config.setLongPressCompatibilityMode(false);
		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees(),
			"disabling compatibility does not restore the previous angle");
		config.setDoubleClickDangerAngleDegrees(2.5);
		assertEquals(2.5, config.getDoubleClickDangerAngleDegrees());
	}

	@Test
	void validationAndUpdateBothEnforceTheCompatibilityZeroAngle() {
		for (String json : List.of(
			"{\"longPressCompatibilityMode\":true}",
			"{\"longPressCompatibilityMode\":true,\"doubleClickDangerAngleDegrees\":null}",
			"{\"longPressCompatibilityMode\":true,\"doubleClickDangerAngleDegrees\":8.5}")) {
			var config = new Gson().fromJson(json, ClientConfig.class);
			config.validate((key, supplied, effective) -> {});
			assertEquals(0.0, config.getDoubleClickDangerAngleDegrees(), json);
		}

		var config = new ClientConfig();
		config.setLongPressCompatibilityMode(true);
		config.setDoubleClickDangerWindowMs(150);
		config.onUpdate();
		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees());
	}

	@Test
	void nanRecoversToDefaultAndInfinityStaysBounded() {
		var config = new ClientConfig();
		config.setDoubleClickDangerAngleDegrees(Double.NaN);
		assertEquals(ClientConfigBounds.DEFAULT_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			config.getDoubleClickDangerAngleDegrees());
		assertTrue(Double.isFinite(config.getDoubleClickDangerAngleDegrees()));

		config.setLongPressCompatibilityMode(true);
		config.setDoubleClickDangerAngleDegrees(Double.NaN);
		assertEquals(0.0, config.getDoubleClickDangerAngleDegrees());

		var loaded = new Gson().fromJson(
			"{\"doubleClickDangerAngleDegrees\":\"NaN\"}", ClientConfig.class);
		loaded.validate((key, supplied, effective) -> {});
		assertEquals(ClientConfigBounds.DEFAULT_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			loaded.getDoubleClickDangerAngleDegrees());
		assertTrue(Double.isFinite(loaded.getDoubleClickDangerAngleDegrees()));

		var infinite = new Gson().fromJson(
			"{\"doubleClickDangerAngleDegrees\":\"Infinity\"}", ClientConfig.class);
		infinite.validate((key, supplied, effective) -> {});
		assertEquals(ClientConfigBounds.MAX_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			infinite.getDoubleClickDangerAngleDegrees());

		var compatible = new Gson().fromJson(
			"{\"longPressCompatibilityMode\":true,\"doubleClickDangerAngleDegrees\":\"NaN\"}",
			ClientConfig.class);
		compatible.validate((key, supplied, effective) -> {});
		assertEquals(0.0, compatible.getDoubleClickDangerAngleDegrees());
	}

	@Test
	void handlerLoadsStringNanAsFiniteDefaultAndSerializesFiniteValue(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\","
			+ "\"doubleClickDangerAngleDegrees\":\"NaN\"}", StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		handler.load();

		assertEquals(ClientConfigBounds.DEFAULT_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			handler.getConfig().getDoubleClickDangerAngleDegrees());
		assertTrue(Double.isFinite(handler.getConfig().getDoubleClickDangerAngleDegrees()));

		handler.getConfig().setDoubleClickDangerWindowMs(150);
		assertTrue(handler.saveSafely());
		var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(ClientConfigBounds.DEFAULT_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			saved.get("doubleClickDangerAngleDegrees").getAsDouble());
	}

	@Test
	void sameVersionCompatibilityNormalizesStringNumbersAndNanButNotUndecodableValues(@TempDir Path directory)
		throws IOException {
		for (String angle : List.of("\"7.5\"", "\"NaN\"", "\"Infinity\"")) {
			Path path = directory.resolve("string-" + angle.hashCode() + ".json");
			Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\","
				+ "\"longPressCompatibilityMode\":true,"
				+ "\"doubleClickDangerAngleDegrees\":" + angle + "}", StandardCharsets.UTF_8);
			var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
			handler.load();

			assertEquals(0.0, handler.getConfig().getDoubleClickDangerAngleDegrees(), angle);
			var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			assertEquals(0.0, saved.get("doubleClickDangerAngleDegrees").getAsDouble(), angle);
		}

		Path malformed = directory.resolve("malformed.json");
		Files.writeString(malformed, "{\"pingforit-version\":\"" + VERSION + "\","
			+ "\"longPressCompatibilityMode\":true,"
			+ "\"doubleClickDangerAngleDegrees\":\"not-a-number\"}", StandardCharsets.UTF_8);
		var recovered = new ConfigHandler<>(ClientConfig.class, malformed, VERSION);
		recovered.load();
		assertEquals(ClientConfigBounds.DEFAULT_DOUBLE_CLICK_DANGER_ANGLE_DEGREES,
			recovered.getConfig().getDoubleClickDangerAngleDegrees());
		try (var files = Files.list(directory)) {
			assertTrue(files.anyMatch(file -> file.getFileName().toString().contains(".broken-")),
				"an undecodable value still follows typed invalid recovery");
		}
	}

	@Test
	void sameVersionCompatibilityNormalizesAngleAndRetainsUnknownData(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String source = "{\"pingforit-version\":\"" + VERSION + "\","
			+ "\"longPressCompatibilityMode\":true,\"doubleClickDangerAngleDegrees\":7.5,"
			+ "\"unknown\":{\"nested\":null},"
			+ "\"spatialSelector\":{\"unknown\":\"retained\"}}";
		Files.writeString(path, source, StandardCharsets.UTF_8);

		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		handler.load();

		assertEquals(0.0, handler.getConfig().getDoubleClickDangerAngleDegrees());
		var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(0.0, saved.get("doubleClickDangerAngleDegrees").getAsDouble());
		assertTrue(saved.getAsJsonObject("unknown").get("nested").isJsonNull());
		assertEquals("retained", saved.getAsJsonObject("spatialSelector").get("unknown").getAsString());

		String normalized = Files.readString(path);
		assertTrue(handler.saveSafely());
		assertEquals(normalized, Files.readString(path), "a repeated save is a no-op");
	}

	@Test
	void sameVersionCompatibilityMaterializesZeroForMissingAndNullAngle(@TempDir Path directory) throws IOException {
		List<String> suffixes = List.of("", ",\"doubleClickDangerAngleDegrees\":null");
		for (int index = 0; index < suffixes.size(); index++) {
			Path path = directory.resolve("client-" + index + ".json");
			Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\","
				+ "\"longPressCompatibilityMode\":true" + suffixes.get(index) + "}", StandardCharsets.UTF_8);

			var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
			handler.load();

			assertEquals(0.0, handler.getConfig().getDoubleClickDangerAngleDegrees());
			var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			assertTrue(saved.has("doubleClickDangerAngleDegrees"), suffixes.get(index));
			assertEquals(0.0, saved.get("doubleClickDangerAngleDegrees").getAsDouble());
		}
	}

	@Test
	void explicitZeroAndDisabledCompatibilityDoNotScheduleARewrite(@TempDir Path directory) throws IOException {
		List<String> sources = List.of(
			"{\"pingforit-version\":\"" + VERSION + "\",\"longPressCompatibilityMode\":true,"
				+ "\"doubleClickDangerAngleDegrees\":0.0}",
			"{\"pingforit-version\":\"" + VERSION + "\"}",
			"{\"pingforit-version\":\"" + VERSION + "\",\"doubleClickDangerAngleDegrees\":7.5}");
		for (int index = 0; index < sources.size(); index++) {
			Path path = directory.resolve("unchanged-" + index + ".json");
			String source = sources.get(index);
			Files.writeString(path, source, StandardCharsets.UTF_8);

			var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
			handler.load();
			assertEquals(source, Files.readString(path));
			assertTrue(handler.saveSafely());
			assertEquals(source, Files.readString(path));
		}

		Path explicit = directory.resolve("explicit.json");
		Files.writeString(explicit, sources.get(2), StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ClientConfig.class, explicit, VERSION);
		handler.load();
		assertEquals(7.5, handler.getConfig().getDoubleClickDangerAngleDegrees());
	}

	@Test
	void windowAndAngleRoundTripThroughAnOrdinarySameVersionSave(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\"}", StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		handler.load();

		handler.getConfig().setDoubleClickDangerWindowMs(250);
		handler.getConfig().setDoubleClickDangerAngleDegrees(12.5);
		assertTrue(handler.saveSafely());

		var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(250, saved.get("doubleClickDangerWindowMs").getAsInt());
		assertEquals(12.5, saved.get("doubleClickDangerAngleDegrees").getAsDouble());

		handler.load();
		assertEquals(250, handler.getConfig().getDoubleClickDangerWindowMs());
		assertEquals(12.5, handler.getConfig().getDoubleClickDangerAngleDegrees());
	}

	@Test
	void failedNormalizationWriteKeepsThePendingRetryAndMemoryState(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String source = "{\"pingforit-version\":\"" + VERSION + "\","
			+ "\"longPressCompatibilityMode\":true,\"doubleClickDangerAngleDegrees\":6.5,"
			+ "\"spatialSelector\":{\"unknown\":\"retained\"}}";
		Files.writeString(path, source, StandardCharsets.UTF_8);
		AtomicBoolean denyWrites = new AtomicBoolean(true);

		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(sourcePath, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(target, serialized) -> {
				if (denyWrites.get()) throw new IOException("write denied");
				Files.writeString(target, serialized, StandardCharsets.UTF_8);
			});
		handler.load();

		assertEquals(0.0, handler.getConfig().getDoubleClickDangerAngleDegrees());
		assertEquals(source, Files.readString(path));
		assertFalse(handler.saveSafely());

		denyWrites.set(false);
		assertTrue(handler.saveSafely(), "the failed normalization stays pending for retry");
		var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(0.0, saved.get("doubleClickDangerAngleDegrees").getAsDouble());
		assertEquals("retained", saved.getAsJsonObject("spatialSelector").get("unknown").getAsString());

		handler.load();
		assertEquals(0.0, handler.getConfig().getDoubleClickDangerAngleDegrees());
		var reloaded = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals("retained", reloaded.getAsJsonObject("spatialSelector").get("unknown").getAsString());
	}

	@Test
	void changedSourceDiscardsPendingNormalizationAndReloadsDiskState(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String original = "{\"pingforit-version\":\"" + VERSION + "\","
			+ "\"longPressCompatibilityMode\":true,\"unknown\":\"original\"}";
		Files.writeString(path, original, StandardCharsets.UTF_8);

		AtomicBoolean denyWrites = new AtomicBoolean(true);
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(sourcePath, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(target, serialized) -> {
				if (denyWrites.get()) throw new IOException("write denied");
				Files.writeString(target, serialized, StandardCharsets.UTF_8);
			});
		handler.load();

		assertEquals(0.0, handler.getConfig().getDoubleClickDangerAngleDegrees(),
			"the loaded model applies the compatibility zero invariant");
		assertEquals(original, Files.readString(path), "the first writeback failed, so the source is retained");
		assertFalse(handler.saveSafely(), "a pending normalization is retried before this guard test");

		String external = "{\"pingforit-version\":\"" + VERSION + "\","
			+ "\"longPressCompatibilityMode\":false,\"doubleClickDangerAngleDegrees\":10.5}";
		Files.writeString(path, external, StandardCharsets.UTF_8);

		assertFalse(handler.saveSafely(), "the changed source discards the pending writeback");
		assertEquals(10.5, handler.getConfig().getDoubleClickDangerAngleDegrees());
		assertEquals(external, Files.readString(path));
	}

	@Test
	void futureVersionProtectionKeepsTheAngleAndDeclinesWrites(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String future = "{\"pingforit-version\":\"99.0.0-pfi-beta1\","
			+ "\"longPressCompatibilityMode\":true,\"doubleClickDangerAngleDegrees\":7.5}";
		Files.writeString(path, future, StandardCharsets.UTF_8);

		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		handler.load();
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertEquals(future, Files.readString(path));
	}

	@Test
	void invalidFileProtectionStillBlocksAngleWrites(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String broken = "broken client config";
		Files.writeString(path, broken, StandardCharsets.UTF_8);

		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(target, backup, bytes) -> { throw new IOException("backup denied"); }, VERSION);
		handler.load();
		handler.getConfig().setDoubleClickDangerAngleDegrees(5.5);
		handler.getConfig().setLongPressCompatibilityMode(true);
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertEquals(broken, Files.readString(path));
	}
}
