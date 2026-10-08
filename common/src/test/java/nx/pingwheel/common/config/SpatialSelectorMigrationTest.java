package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Retired wheel-key removal is a release-boundary migration, not same-version normalization. */
class SpatialSelectorMigrationTest {
	private static final String TIMEOUT_TARGET = "0.5.0-pfi-beta1";
	private static final String LEGACY = "0.4.2-pfi-beta1";

	@Test
	void crossingTheExactReleaseBoundaryRemovesRetiredWheelKeysWithoutInjectingDefaults() {
		for (String old : List.of("0.2.0-pfi-beta1", LEGACY, "0.5.0-pfi-beta0")) {
			JsonObject source = legacyRoot(old);
			JsonObject migrated = update(source, ClientConfig.class, old, TIMEOUT_TARGET);
			assertNoRetiredWheelKeys(migrated);
			assertFalse(migrated.has("spatialSelector"), "defaults must come from model initialization");
			assertEquals(source.get("unknown"), migrated.get("unknown"));
			assertEquals(source.get("wheelOpacity"), migrated.get("wheelOpacity"));
			assertEquals(source.get("wheelFontSize"), migrated.get("wheelFontSize"));
			assertEquals(source.get("wheelTargetFontSize"), migrated.get("wheelTargetFontSize"));
			assertEquals(TIMEOUT_TARGET, migrated.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
			assertTrue(source.has("wheelInnerRadius"), "migration must not mutate its source");
			assertTrue(source.has("wheelOuterRadius"));
			assertTrue(source.has("wheelTimeoutMillis"));
		}
	}

	@Test
	void aRunningVersionBelowTheTargetDoesNotRetireTheRadiusKeys() {
		JsonObject source = legacyRoot(LEGACY);
		JsonObject migrated = update(source, ClientConfig.class, LEGACY, "0.5.0-pfi-beta0");
		assertEquals(source.get("wheelInnerRadius"), migrated.get("wheelInnerRadius"));
		assertEquals(source.get("wheelOuterRadius"), migrated.get("wheelOuterRadius"));
		assertEquals(source.get("wheelTimeoutMillis"), migrated.get("wheelTimeoutMillis"));
	}

	@Test
	void migrationDoesNotConsumeKeysFromAtOrAfterTheIntroductionOrFromTheServer() {
		for (String old : List.of(TIMEOUT_TARGET, "00.005.000-pfi-beta1", "0.5.0-pfi-beta2")) {
			JsonObject source = legacyRoot(old);
			JsonObject migrated = update(source, ClientConfig.class, old, "0.6.0-pfi-beta1");
			assertEquals(source.get("wheelInnerRadius"), migrated.get("wheelInnerRadius"));
			assertEquals(source.get("wheelOuterRadius"), migrated.get("wheelOuterRadius"));
			assertEquals(source.get("wheelTimeoutMillis"), migrated.get("wheelTimeoutMillis"));
		}
		JsonObject source = legacyRoot(LEGACY);
		JsonObject migrated = update(source, ServerConfig.class, LEGACY, TIMEOUT_TARGET);
		assertTrue(migrated.has("wheelInnerRadius"));
		assertTrue(migrated.has("wheelOuterRadius"));
		assertTrue(migrated.has("wheelTimeoutMillis"));
	}

	@Test
	void timeoutUpgradePersistsDefaultsAndSaveCannotResurrectTheRetiredKey(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client.json");
		JsonObject source = legacyRoot(LEGACY);
		Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		handler.load();
		assertFalse(read(path).has("wheelTimeoutMillis"));
		assertEquals(source.get("unknown"), read(path).get("unknown"));
		assertEquals(new ClientConfig().getWheelTargetOpacity(), handler.getConfig().getWheelTargetOpacity());
		assertNoRetiredWheelKeys(read(path));
		handler.getConfig().setWheelTargetOpacity(45);
		assertTrue(handler.saveSafely());
		ConfigHandler<ClientConfig> reloaded = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		reloaded.load();
		assertEquals(45, reloaded.getConfig().getWheelTargetOpacity());
		assertNoRetiredWheelKeys(read(path));

		JsonObject stale = read(path);
		stale.addProperty("wheelTimeoutMillis", 3000);
		byte[] original = stale.toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		reloaded.load();
		assertArrayEquals(original, Files.readAllBytes(path), "same-version load must not rewrite the file");
		reloaded.getConfig().setWheelTargetOpacity(65);
		assertTrue(reloaded.saveSafely());
		assertNoRetiredWheelKeys(read(path));
		assertEquals(65, read(path).get("wheelTargetOpacity").getAsInt());
		assertNoBackup(directory);
	}

	@Test
	void targetVersionUpgradePersistsAutomaticDefaultsAndKeepsExistingAppearanceAndUnknownData(
		@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		JsonObject source = legacyRoot(LEGACY);
		Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		handler.load();

		assertEquals(new SpatialSelectorSettings(), handler.getConfig().getSpatialSelector());
		assertEquals(35, handler.getConfig().getWheelOpacity());
		assertEquals(250, handler.getConfig().getWheelFontSize());
		assertEquals(80, handler.getConfig().getWheelTargetFontSize());
		JsonObject persisted = read(path);
		assertNoRetiredWheelKeys(persisted);
		assertEquals(source.get("unknown"), persisted.get("unknown"));
		assertEquals(new Gson().toJsonTree(new SpatialSelectorSettings()), persisted.get("spatialSelector"));
		assertEquals(TIMEOUT_TARGET, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertNoBackup(directory);
	}

	@Test
	void partialSelectorKeepsItsExplicitValuesAndUnknownNestedDataDuringUpgrade(
		@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		JsonObject source = legacyRoot(LEGACY);
		JsonObject selector = JsonParser.parseString("""
			{"targetGlide":1.5,"hoverEnabled":true,"hoverMillis":750,
			 "rootDistance":90,"unknownPreference":{"enabled":true}}
			""").getAsJsonObject();
		source.add("spatialSelector", selector);
		Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		handler.load();

		SpatialSelectorSettings expected = new Gson().fromJson(selector, SpatialSelectorSettings.class);
		assertEquals(expected, handler.getConfig().getSpatialSelector());
		JsonObject persisted = read(path).getAsJsonObject("spatialSelector");
		assertEquals(selector.get("unknownPreference"), persisted.get("unknownPreference"));
		for (var entry : new Gson().toJsonTree(expected).getAsJsonObject().entrySet()) {
			assertEquals(entry.getValue(), persisted.get(entry.getKey()), entry.getKey());
		}
	}

	@Test
	void sameVersionAbsentAndPartialSelectorGainModelDefaultsWithoutALoadRewrite(
		@TempDir Path directory) throws IOException {
		for (String selectorJson : List.of("", ",\"spatialSelector\":{\"targetGlide\":1.5,\"hoverEnabled\":true}")) {
			Path path = directory.resolve("client.json");
			byte[] original = ("{\"pingforit-version\":\"" + TIMEOUT_TARGET + "\",\"pingVolume\":37"
				+ selectorJson + "}\n").getBytes(StandardCharsets.UTF_8);
			Files.write(path, original);
			ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
			handler.load();
			ClientConfig expected = new Gson().fromJson(new String(original, StandardCharsets.UTF_8), ClientConfig.class);
			assertEquals(expected.getSpatialSelector(), handler.getConfig().getSpatialSelector());
			assertEquals(37, handler.getConfig().getPingVolume());
			assertArrayEquals(original, Files.readAllBytes(path));
		}
	}

	@Test
	void preTargetWritebackKeepsOldRadiusDataUntilTheTargetUpgrade(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		JsonObject source = legacyRoot(LEGACY);
		Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
		new ConfigHandler<>(ClientConfig.class, path, "0.4.3-pfi-beta1").load();
		assertEquals(source.get("wheelInnerRadius"), read(path).get("wheelInnerRadius"));
		assertEquals(source.get("wheelOuterRadius"), read(path).get("wheelOuterRadius"));
		new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET).load();
		assertNoRetiredWheelKeys(read(path));
	}

	@Test
	void currentVersionLoadIsNotRetiredKeyNormalizationButATargetSaveCannotResurrectRetiredKeys(
		@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		byte[] original = legacyRoot(TIMEOUT_TARGET).toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		handler.load();
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.getConfig().setPingVolume(37);
		assertTrue(handler.saveSafely());
		assertNoRetiredWheelKeys(read(path));
	}

	@Test
	void everyExplicitSelectorPreferenceRoundTripsIndependentlyOfAppearance(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		SpatialSelectorSettings preferences = new Gson().fromJson("""
			{"deadzone":28,"stroke":70,"dwellMillis":230,"rootDistance":90,"targetGlide":1.75,
			 "hoverEnabled":true,"hoverMillis":750,"showTrail":false,"reduceMotion":true,
			 "preciseCapturePeriodTicks":25}
			""", SpatialSelectorSettings.class);
		handler.getConfig().setSpatialSelector(preferences);
		handler.getConfig().setWheelOpacity(35);
		handler.getConfig().setWheelFontSize(250);
		handler.getConfig().setWheelTargetFontSize(80);
		assertTrue(handler.saveSafely());
		ConfigHandler<ClientConfig> reloaded = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		reloaded.load();
		assertEquals(preferences, reloaded.getConfig().getSpatialSelector());
		assertEquals(35, reloaded.getConfig().getWheelOpacity());
		assertEquals(250, reloaded.getConfig().getWheelFontSize());
		assertEquals(80, reloaded.getConfig().getWheelTargetFontSize());
		assertNoRetiredWheelKeys(read(path));
	}

	@Test
	void aNullNestedSelectorRecoversModelDefaultsWithoutResettingOtherSettings(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		byte[] original = ("{\"pingforit-version\":\"" + TIMEOUT_TARGET
			+ "\",\"pingVolume\":37,\"spatialSelector\":null}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		handler.load();
		assertEquals(new SpatialSelectorSettings(), handler.getConfig().getSpatialSelector());
		assertEquals(37, handler.getConfig().getPingVolume());
		assertArrayEquals(original, Files.readAllBytes(path));
		assertNoBackup(directory);
	}

	@Test
	void futureVersionGuardKeepsAllBytesAndRefusesSaveAndReset(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		byte[] original = legacyRoot("0.5.0-pfi-beta2").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path, TIMEOUT_TARGET);
		handler.load();
		assertEquals(new SpatialSelectorSettings(), handler.getConfig().getSpatialSelector());
		handler.getConfig().getSpatialSelector().setHoverEnabled(true);
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertEquals(new SpatialSelectorSettings(), handler.getConfig().getSpatialSelector());
		assertArrayEquals(original, Files.readAllBytes(path));
		assertNoBackup(directory);
	}

	@Test
	void failedWriteRetainsUsablePreferencesAndRetryDiscardsTheStaleSourceBeforeMigration(
		@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		byte[] original = legacyRoot(LEGACY).toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, backup, bytes) -> fail("valid migration must not enter broken-file recovery"), TIMEOUT_TARGET,
			(destination, serialized) -> { throw new IOException("injected write failure"); });
		handler.load();
		assertEquals(35, handler.getConfig().getWheelOpacity());
		assertEquals(new SpatialSelectorSettings(), handler.getConfig().getSpatialSelector());
		assertArrayEquals(original, Files.readAllBytes(path));

		byte[] external = legacyRoot("0.6.0-pfi-beta1").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, external);
		assertFalse(handler.saveSafely());
		assertArrayEquals(external, Files.readAllBytes(path));
		assertFalse(handler.saveSafely());
		assertNoBackup(directory);
	}

	private static JsonObject legacyRoot(String version) {
		JsonObject root = JsonParser.parseString("""
			{"wheelInnerRadius":63,"wheelOuterRadius":231,"wheelOpacity":35,
			 "wheelTimeoutMillis":3000,"wheelFontSize":250,"wheelTargetFontSize":80,
			 "unknown":{"list":[1,true,"x"]}}
			""").getAsJsonObject();
		root.addProperty(ConfigVersionUpdater.VERSION_KEY, version);
		return root;
	}

	private static JsonObject update(JsonObject root, Class<? extends IConfig> type, String old, String current) {
		return ConfigVersionUpdater.update(root, type, PingForItVersion.parse(old), PingForItVersion.parse(current)).root();
	}

	private static JsonObject read(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static void assertNoRadii(JsonObject root) {
		assertFalse(root.has("wheelInnerRadius"));
		assertFalse(root.has("wheelOuterRadius"));
	}

	private static void assertNoRetiredWheelKeys(JsonObject root) {
		assertNoRadii(root);
		assertFalse(root.has("wheelTimeoutMillis"));
	}

	private static void assertNoBackup(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".broken-")));
		}
	}
}
