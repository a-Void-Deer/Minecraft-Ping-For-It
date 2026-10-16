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

import static org.junit.jupiter.api.Assertions.*;

class GestureSkipHoldConfigTest {
	private static final String VERSION = "0.5.0-pfi-beta1";

	@Test void freshMissingAndNullPreferencesAreOptInWithSeventyFiveMillis() {
		for (String json : List.of("{}", "{\"gestureSkipHoldEnabled\":null,\"gestureSkipHoldMillis\":null}")) {
			var selector = new Gson().fromJson(json, SpatialSelectorSettings.class);
			selector.validate();
			assertFalse(selector.isGestureSkipHoldEnabled()); assertEquals(75, selector.getGestureSkipHoldMillis());
			assertFalse(selector.snapshot().gestureSkipHoldEnabled()); assertEquals(75, selector.snapshot().gestureSkipHoldMillis());
		}
		var config = new ClientConfig(); assertFalse(config.isGestureSkipHoldEnabled());
		assertEquals(75, config.getSpatialSelector().getGestureSkipHoldMillis());
	}

	@Test void continuousTimeClampsAtTwentyFiveAndTwoHundredAndFreezesWithThePreference() {
		for (int value : List.of(Integer.MIN_VALUE, 24, 25, 26, 74, 75, 76, 199, 200, 201, Integer.MAX_VALUE)) {
			var selector = new Gson().fromJson("{\"gestureSkipHoldEnabled\":true,\"gestureSkipHoldMillis\":" + value + "}",
				SpatialSelectorSettings.class);
			int expected = Math.clamp(value, 25, 200);
			var frozen = selector.snapshot(); selector.validate();
			assertEquals(expected, selector.getGestureSkipHoldMillis()); assertEquals(expected, frozen.gestureSkipHoldMillis());
			assertTrue(frozen.gestureSkipHoldEnabled());
			selector.setGestureSkipHoldMillis(150); selector.setGestureSkipHoldEnabled(false);
			assertEquals(expected, frozen.gestureSkipHoldMillis()); assertTrue(frozen.gestureSkipHoldEnabled());
			selector.setGestureSkipHoldMillis(value); assertEquals(expected, selector.getGestureSkipHoldMillis());
		}
	}

	@Test void previousSnapshotConstructorsKeepTheShortcutDisabled() {
		var prior = new SpatialSelectorSettings.Snapshot(24, 50, 180, 55, java.math.BigDecimal.ONE,
			false, 500, true, false, 1, new java.math.BigDecimal("1.5"), 130, 50, true);
		assertTrue(prior.contentIntentEnabled()); assertFalse(prior.gestureSkipHoldEnabled()); assertEquals(75, prior.gestureSkipHoldMillis());
		var older = new SpatialSelectorSettings.Snapshot(24, 50, 180, 55, java.math.BigDecimal.ONE, false, 500, true, false);
		assertFalse(older.gestureSkipHoldEnabled()); assertEquals(75, older.gestureSkipHoldMillis());
	}

	@Test void compatibilityClearsTheOriginalChoiceAndNeverRestoresIt() {
		var config = new ClientConfig(); config.setGestureSkipHoldEnabled(true);
		config.getSpatialSelector().setGestureSkipHoldMillis(150);
		config.setLongPressCompatibilityMode(true);
		assertFalse(config.isGestureSkipHoldEnabled()); assertFalse(config.getSpatialSelector().isGestureSkipHoldEnabled());
		config.setGestureSkipHoldEnabled(true); assertFalse(config.isGestureSkipHoldEnabled());
		config.setLongPressCompatibilityMode(false); assertFalse(config.isGestureSkipHoldEnabled());
		assertEquals(150, config.getSpatialSelector().getGestureSkipHoldMillis());
		var raw = new Gson().fromJson(conflict(VERSION), ClientConfig.class);
		assertFalse(raw.isGestureSkipHoldEnabled(), "even before validation the pair is inert");
		raw.validate(); assertFalse(raw.getSpatialSelector().isGestureSkipHoldEnabled());
	}

	@Test void explicitPreferencesRoundTripAndSameVersionDefaultsDoNotRewrite(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String source = "{\"pingforit-version\":\"" + VERSION + "\",\"pingVolume\":37}";
		Files.writeString(path, source, StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION); handler.load();
		assertEquals(source, Files.readString(path)); assertTrue(handler.saveSafely()); assertEquals(source, Files.readString(path));
		handler.getConfig().setGestureSkipHoldEnabled(true); handler.getConfig().getSpatialSelector().setGestureSkipHoldMillis(126);
		assertTrue(handler.saveSafely());
		var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("spatialSelector");
		assertTrue(saved.get("gestureSkipHoldEnabled").getAsBoolean()); assertEquals(126, saved.get("gestureSkipHoldMillis").getAsInt());
		handler.load(); assertTrue(handler.getConfig().isGestureSkipHoldEnabled());
		handler.getConfig().setLongPressCompatibilityMode(true); assertTrue(handler.saveSafely()); handler.load();
		handler.getConfig().setLongPressCompatibilityMode(false); assertFalse(handler.getConfig().isGestureSkipHoldEnabled());
		assertEquals(126, handler.getConfig().getSpatialSelector().getGestureSkipHoldMillis());
	}

	@Test void conflictingPairIsPersistentlyReconciledByGuardedSameVersionWriteback(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json"); Files.writeString(path, conflict(VERSION), StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION); handler.load();
		var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertTrue(saved.get("longPressCompatibilityMode").getAsBoolean());
		assertFalse(saved.getAsJsonObject("spatialSelector").get("gestureSkipHoldEnabled").getAsBoolean());
		assertEquals(150, saved.getAsJsonObject("spatialSelector").get("gestureSkipHoldMillis").getAsInt());
		assertEquals("retained", saved.getAsJsonObject("spatialSelector").get("unknown").getAsString());
		handler.load(); handler.getConfig().setLongPressCompatibilityMode(false); assertFalse(handler.getConfig().isGestureSkipHoldEnabled());
	}

	@Test void failedNormalizationRetainsPendingGuardAndCannotOverwriteAnExternalFutureEdit(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json"); String original = conflict(VERSION);
		Files.writeString(path, original, StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ClientConfig.class, path, (source, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(target, serialized) -> { throw new IOException("write denied"); });
		handler.load(); assertFalse(handler.getConfig().isGestureSkipHoldEnabled()); assertEquals(original, Files.readString(path));
		String future = conflict("99.0.0-pfi-beta1"); Files.writeString(path, future, StandardCharsets.UTF_8);
		assertFalse(handler.saveSafely()); handler.resetToDefaults(); assertEquals(future, Files.readString(path));
	}

	@Test void futureAndInvalidFileProtectionsStillDeclineGestureEdits(@TempDir Path directory) throws IOException {
		for (boolean future : List.of(false, true)) {
			Path path = directory.resolve(future ? "future.json" : "invalid.json");
			String source = future ? conflict("99.0.0-pfi-beta1") : "broken client config";
			Files.writeString(path, source, StandardCharsets.UTF_8);
			var handler = new ConfigHandler<>(ClientConfig.class, path,
				(target, backup, bytes) -> { throw new IOException("backup denied"); }, VERSION);
			handler.load(); handler.getConfig().setGestureSkipHoldEnabled(true); handler.getConfig().setLongPressCompatibilityMode(true);
			assertFalse(handler.saveSafely()); handler.resetToDefaults(); assertEquals(source, Files.readString(path));
		}
	}

	private static String conflict(String version) {
		return "{\"pingforit-version\":\"" + version + "\",\"longPressCompatibilityMode\":true,"
			+ "\"spatialSelector\":{\"gestureSkipHoldEnabled\":true,\"gestureSkipHoldMillis\":150,\"unknown\":\"retained\"}}";
	}
}
