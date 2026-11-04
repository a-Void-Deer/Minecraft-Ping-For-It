package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Additive persisted preference, no-op-save hashing, reset and version-preservation boundaries. */
@NotNullByDefault
class LoggingProfileConfigTest {
	private static final String VERSION = "0.5.0-pfi-beta1";

	@ParameterizedTest
	@MethodSource("configTypes")
	void freshMissingNullAndUnknownProfilesResolveToNormal(Class<? extends IConfig> type) throws Exception {
		assertEquals(LoggingProfile.NORMAL, profile(type.getDeclaredConstructor().newInstance()));
		for (String json : List.of("{}", "{\"loggingProfile\":null}", "{\"loggingProfile\":\"unknown\"}")) {
			IConfig config = new Gson().fromJson(json, type);
			assertEquals(LoggingProfile.NORMAL, profile(config), "getter must be safe before validation");
			config.validate();
			assertEquals(LoggingProfile.NORMAL, profile(config));
			assertEquals("NORMAL", new Gson().toJsonTree(config).getAsJsonObject().get("loggingProfile").getAsString());
			setProfile(config, LoggingProfile.DEBUG);
			setProfile(config, null);
			assertEquals(LoggingProfile.NORMAL, profile(config));
		}
	}

	@ParameterizedTest
	@MethodSource("configTypes")
	void fallbackAndExplicitValuesNeverRewriteAnUntouchedSameVersionFile(
		Class<? extends IConfig> type, @TempDir Path directory) throws IOException {
		for (String member : List.of("", ",\"loggingProfile\":null", ",\"loggingProfile\":\"unknown\"",
			",\"loggingProfile\":\"NORMAL\"", ",\"loggingProfile\":\"DEEP\"", ",\"loggingProfile\":\"DEBUG\"")) {
			Path path = directory.resolve(type.getSimpleName() + ".json");
			byte @Unmodifiable [] original = ("{\"pingforit-version\":\"" + VERSION
				+ "\",\"unknown\":{\"retained\":true}" + member + "}\n").getBytes(StandardCharsets.UTF_8);
			Files.write(path, original);
			var handler = new ConfigHandler<>(type, path, VERSION);
			handler.load();
			LoggingProfile expected = member.contains("DEEP") ? LoggingProfile.DEEP
				: member.contains("DEBUG") ? LoggingProfile.DEBUG : LoggingProfile.NORMAL;
			assertEquals(expected, profile(handler.getConfig()));
			assertArrayEquals(original, Files.readAllBytes(path));
			assertTrue(handler.saveSafely());
			assertArrayEquals(original, Files.readAllBytes(path));
		}
	}

	@ParameterizedTest
	@MethodSource("configTypes")
	void profileOnlyEditsParticipateInEqualityHashSaveReloadAndReset(
		Class<? extends IConfig> type, @TempDir Path directory) throws Exception {
		Path path = directory.resolve("config.json");
		var handler = new ConfigHandler<>(type, path, VERSION);
		handler.load();
		IConfig normal = type.getDeclaredConstructor().newInstance();
		assertEquals(normal, handler.getConfig());
		for (LoggingProfile selected : List.of(LoggingProfile.DEEP, LoggingProfile.DEBUG)) {
			setProfile(handler.getConfig(), selected);
			handler.getConfig().validate();
			assertNotEquals(normal, handler.getConfig());
			assertNotEquals(normal.hashCode(), handler.getConfig().hashCode());
			assertTrue(handler.saveSafely(), "a logging-only change must not be mistaken for a no-op");
			assertEquals(selected.name(), JsonParser.parseString(Files.readString(path)).getAsJsonObject()
				.get("loggingProfile").getAsString());
			var reloaded = new ConfigHandler<>(type, path, VERSION);
			reloaded.load();
			assertEquals(selected, profile(reloaded.getConfig()));
		}
		IConfig stale = handler.getConfig();
		handler.resetToDefaults();
		assertNotSame(stale, handler.getConfig());
		assertEquals(LoggingProfile.NORMAL, profile(handler.getConfig()));
		setProfile(stale, LoggingProfile.DEBUG);
		assertTrue(handler.saveSafely());
		handler.load();
		assertEquals(LoggingProfile.NORMAL, profile(handler.getConfig()));
	}

	@ParameterizedTest
	@MethodSource("configTypes")
	void existingMigrationPreservesProfilesAndMaterializesMissingDefaultsWithoutLosingUnknownData(
		Class<? extends IConfig> type, @TempDir Path directory) throws IOException {
		for (String member : List.of("", ",\"loggingProfile\":\"DEEP\"", ",\"loggingProfile\":\"DEBUG\"")) {
			Path path = directory.resolve("older.json");
			Files.writeString(path, "{\"pingforit-version\":\"0.4.0-pfi-beta1\","
				+ "\"unknown\":{\"retained\":[1,null]}" + member + "}\n");
			var handler = new ConfigHandler<>(type, path, VERSION);
			handler.load();
			var persisted = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			LoggingProfile expected = member.contains("DEEP") ? LoggingProfile.DEEP
				: member.contains("DEBUG") ? LoggingProfile.DEBUG : LoggingProfile.NORMAL;
			assertEquals(expected, profile(handler.getConfig()));
			assertEquals(expected.name(), persisted.get("loggingProfile").getAsString());
			assertEquals(VERSION, persisted.get("pingforit-version").getAsString());
			assertEquals(JsonParser.parseString("{\"retained\":[1,null]}"), persisted.get("unknown"));
		}
	}

	@ParameterizedTest
	@MethodSource("configTypes")
	void futureVersionLockPreservesBytesAcrossProfileSaveAndReset(
		Class<? extends IConfig> type, @TempDir Path directory) throws IOException {
		Path path = directory.resolve("future.json");
		byte @Unmodifiable [] original = ("{\"pingforit-version\":\"9.0.0-pfi-beta1\","
			+ "\"loggingProfile\":\"DEBUG\",\"future\":true}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(type, path, VERSION);
		handler.load();
		assertEquals(LoggingProfile.NORMAL, profile(handler.getConfig()));
		setProfile(handler.getConfig(), LoggingProfile.DEEP);
		assertFalse(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.resetToDefaults();
		assertEquals(LoggingProfile.NORMAL, profile(handler.getConfig()));
		assertFalse(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	private static Stream<Class<? extends IConfig>> configTypes() {
		return Stream.of(ClientConfig.class, ServerConfig.class);
	}

	private static LoggingProfile profile(IConfig config) {
		return config instanceof ClientConfig client ? client.getLoggingProfile() : ((ServerConfig) config).getLoggingProfile();
	}

	private static void setProfile(IConfig config, @Nullable LoggingProfile profile) {
		if (config instanceof ClientConfig client) client.setLoggingProfile(profile);
		else ((ServerConfig) config).setLoggingProfile(profile);
	}
}
