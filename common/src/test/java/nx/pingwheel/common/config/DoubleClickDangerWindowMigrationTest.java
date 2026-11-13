package nx.pingwheel.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The beta2 window revision only rewrites an exact JSON-number 100 in a client
 * document whose source version is at or before beta1. The step itself never
 * repairs missing, null or mistyped input; typed load and recovery remain the
 * existing owners of those outcomes.
 */
@NotNullByDefault
class DoubleClickDangerWindowMigrationTest {
	private static final String KEY = "doubleClickDangerWindowMs";
	private static final String BETA1 = "0.5.1-pfi-beta1";
	private static final String BETA1A = "0.5.1-pfi-beta1a";
	private static final String BETA2 = "0.5.1-pfi-beta2";
	private static final String LEGACY_CLIENT = "0.4.2-pfi-beta1";
	private static final int LEGACY_WINDOW_MS = 100;
	private static final int REVISED_WINDOW_MS = 160;

	@Test
	void everyLegalClientSourceAtOrBeforeBeta1MigratesAnExactHundred() {
		@Unmodifiable List<String> sourceVersions = List.of(
			"0.1.0-pfi-beta1",
			LEGACY_CLIENT,
			"0.5.0-pfi-beta1",
			BETA1);

		for (String sourceVersion : sourceVersions) {
			JsonObject source = root(sourceVersion, "," + numberMember(LEGACY_WINDOW_MS));
			JsonObject migrated = migrate(source, ClientConfig.class, sourceVersion, BETA2);

			assertEquals(REVISED_WINDOW_MS, element(migrated, KEY).getAsInt(), sourceVersion);
			assertEquals(BETA2, element(migrated, ConfigVersionUpdater.VERSION_KEY).getAsString());
			assertEquals(LEGACY_WINDOW_MS, element(source, KEY).getAsInt(),
				"the raw update must not mutate its source object: " + sourceVersion);
			assertEquals(sourceVersion, element(source, ConfigVersionUpdater.VERSION_KEY).getAsString());
		}
	}

	@Test
	void crossingTheExistingClientBeta1StepStillMigratesTheWindowAndPreservesUnknownData() {
		JsonObject source = json("""
			{"pingforit-version":"0.2.0-pfi-beta1",
			 "wheelInnerRadius":63,"wheelOuterRadius":231,"wheelTimeoutMillis":3000,
			 "passThroughTransparentBlocks":true,
			 "doubleClickDangerWindowMs":100,
			 "unknownRoot":{"list":[1,true,"x"],"nullValue":null}}
			""");

		JsonObject migrated = migrate(source, ClientConfig.class, "0.2.0-pfi-beta1", BETA2);

		assertEquals(REVISED_WINDOW_MS, element(migrated, KEY).getAsInt());
		assertFalse(migrated.has("wheelInnerRadius"));
		assertFalse(migrated.has("wheelOuterRadius"));
		assertFalse(migrated.has("wheelTimeoutMillis"));
		assertFalse(migrated.has("passThroughTransparentBlocks"));
		assertFalse(element(migrated, "markTransparentBlocks").getAsBoolean());
		assertEquals(element(source, "unknownRoot"), element(migrated, "unknownRoot"));
		assertEquals(BETA2, element(migrated, ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(LEGACY_WINDOW_MS, element(source, KEY).getAsInt());
		assertTrue(source.has("wheelInnerRadius"), "the previous migration also works on a deep copy");
	}

	@Test
	void exactHundredSpellingsMigrateWhileOtherNumbersKeepTheirSourceForm() {
		@Unmodifiable List<String> hundredForms = List.of("100", "100.0", "1e2", "100e0", "1.0E2");
		for (String form : hundredForms) {
			JsonObject source = root(BETA1, "," + numberMember(form));
			JsonObject migrated = migrate(source, ClientConfig.class, BETA1, BETA2);

			assertEquals(REVISED_WINDOW_MS, element(migrated, KEY).getAsInt(), form);
			assertEquals(form, element(source, KEY).toString(), "the raw source keeps its original spelling");
		}

		@Unmodifiable List<String> otherNumbers = List.of(
			"25",
			"99",
			"99.9",
			"99.999999999999999999",
			"100.0001",
			"100.0000000000000000001",
			"100.5",
			"250",
			"300");
		for (String form : otherNumbers) {
			JsonObject source = root(BETA1, "," + numberMember(form));
			JsonObject migrated = migrate(source, ClientConfig.class, BETA1, BETA2);
			JsonElement migratedWindow = element(migrated, KEY);

			assertTrue(migratedWindow.isJsonPrimitive() && migratedWindow.getAsJsonPrimitive().isNumber(), form);
			assertEquals(0, new BigDecimal(form).compareTo(migratedWindow.getAsBigDecimal()), form);
			assertEquals(form, migratedWindow.toString(),
				"a non-100 value is not truncated, rescaled or reserialized: " + form);
		}
	}

	@Test
	void aSourceQualifierBetweenBeta1AndBeta2IsNotTreatedAsTheLegacyBoundary() {
		JsonObject source = root(BETA1A, "," + numberMember(LEGACY_WINDOW_MS));

		JsonObject migrated = migrate(source, ClientConfig.class, BETA1A, BETA2);

		assertEquals(LEGACY_WINDOW_MS, element(migrated, KEY).getAsInt(),
			"the document is older than beta2 but newer than the legacy beta1 boundary");
		assertEquals(BETA2, element(migrated, ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(LEGACY_WINDOW_MS, element(source, KEY).getAsInt());
	}

	@Test
	void sourcesAtOrAfterBeta2KeepAnIntentionalHundred() {
		JsonObject sameVersionSource = root(BETA2, "," + numberMember(LEGACY_WINDOW_MS));
		JsonObject sameVersion = migrate(sameVersionSource, ClientConfig.class, BETA2, BETA2);
		assertEquals(LEGACY_WINDOW_MS, element(sameVersion, KEY).getAsInt());
		assertEquals(BETA2, element(sameVersion, ConfigVersionUpdater.VERSION_KEY).getAsString());

		JsonObject laterSource = root(BETA2, "," + numberMember(LEGACY_WINDOW_MS));
		JsonObject later = migrate(laterSource, ClientConfig.class, BETA2, "0.5.2-pfi-beta1");
		assertEquals(LEGACY_WINDOW_MS, element(later, KEY).getAsInt(),
			"a beta2 source is not retroactively treated as a legacy default");
		assertEquals("0.5.2-pfi-beta1", element(later, ConfigVersionUpdater.VERSION_KEY).getAsString());
	}

	@Test
	void runningBeforeBeta2AndServerConfigLeaveTheWindowUnchanged() {
		for (String runningVersion : List.of("0.5.0-pfi-beta1", BETA1)) {
			JsonObject source = root(LEGACY_CLIENT, "," + numberMember(LEGACY_WINDOW_MS));
			JsonObject migrated = migrate(source, ClientConfig.class, LEGACY_CLIENT, runningVersion);

			assertEquals(LEGACY_WINDOW_MS, element(migrated, KEY).getAsInt(), runningVersion);
			assertEquals(runningVersion, element(migrated, ConfigVersionUpdater.VERSION_KEY).getAsString());
		}

		JsonObject serverSource = root("0.2.0-pfi-beta1", "," + numberMember(LEGACY_WINDOW_MS));
		JsonObject serverMigrated = migrate(serverSource, ServerConfig.class, "0.2.0-pfi-beta1", BETA2);

		assertEquals(LEGACY_WINDOW_MS, element(serverMigrated, KEY).getAsInt());
		assertEquals(BETA2, element(serverMigrated, ConfigVersionUpdater.VERSION_KEY).getAsString());
	}

	@Test
	void missingNullAndNonNumberValuesAreLeftForTheTypedLoadOrRecoveryPath() {
		JsonObject missing = root(BETA1, "");
		assertFalse(migrate(missing, ClientConfig.class, BETA1, BETA2).has(KEY),
			"the raw step must not inject the revised default");

		@Unmodifiable List<String> nonNumberValues = List.of(
			"null",
			"\"100\"",
			"\"1e2\"",
			"true",
			"false",
			"{\"value\":100}",
			"[100]");
		for (String value : nonNumberValues) {
			JsonObject source = root(BETA1, ",\"" + KEY + "\":" + value);
			JsonObject migrated = migrate(source, ClientConfig.class, BETA1, BETA2);

			assertEquals(element(source, KEY), element(migrated, KEY),
				"the raw step must not coerce or repair " + value);
			assertEquals(value, element(migrated, KEY).toString(), value);
		}
	}

	@Test
	void handlerPersistsTheMigratedWindowAndMarkerWithUnknownRootAndNestedData(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client.json");
		String source = """
			{"pingforit-version":"0.5.1-pfi-beta1",
			 "pingVolume":37,
			 "doubleClickDangerWindowMs":100,
			 "doubleClickDangerAngleDegrees":7.5,
			 "unknownRoot":{"nested":{"enabled":true},"list":[1,null,"x"]},
			 "spatialSelector":{"unknownPreference":{"enabled":true}}}
			""";
		Files.writeString(path, source, StandardCharsets.UTF_8);

		ConfigHandler<ClientConfig> handler = client(path);
		handler.load();

		assertEquals(REVISED_WINDOW_MS, handler.getConfig().getDoubleClickDangerWindowMs());
		assertEquals(37, handler.getConfig().getPingVolume());
		assertEquals(7.5, handler.getConfig().getDoubleClickDangerAngleDegrees());

		JsonObject persisted = read(path);
		assertEquals(BETA2, element(persisted, ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(REVISED_WINDOW_MS, element(persisted, KEY).getAsInt());
		assertEquals(json("{\"nested\":{\"enabled\":true},\"list\":[1,null,\"x\"]}"),
			element(persisted, "unknownRoot"));
		assertEquals(json("{\"enabled\":true}"), element(
			element(persisted, "spatialSelector").getAsJsonObject(), "unknownPreference"));
		assertNoBackup(directory);

		ConfigHandler<ClientConfig> reloaded = client(path);
		reloaded.load();
		assertEquals(REVISED_WINDOW_MS, reloaded.getConfig().getDoubleClickDangerWindowMs());
	}

	@Test
	void olderSourceKeepsAnExplicitNonHundredWindowThroughTheHandlerWriteback(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client-custom-window.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + BETA1 + "\","
			+ "\"doubleClickDangerWindowMs\":250,\"unknown\":true}", StandardCharsets.UTF_8);

		ConfigHandler<ClientConfig> handler = client(path);
		handler.load();

		assertEquals(250, handler.getConfig().getDoubleClickDangerWindowMs());
		JsonObject persisted = read(path);
		assertEquals(BETA2, element(persisted, ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(250, element(persisted, KEY).getAsInt());
		assertTrue(element(persisted, "unknown").getAsBoolean());

		ConfigHandler<ClientConfig> reloaded = client(path);
		reloaded.load();
		assertEquals(250, reloaded.getConfig().getDoubleClickDangerWindowMs());
	}

	@Test
	void sameVersionBeta2IntentionalHundredSurvivesSaveAndReload(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client-beta2.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + BETA2 + "\","
			+ "\"doubleClickDangerWindowMs\":250}", StandardCharsets.UTF_8);

		ConfigHandler<ClientConfig> handler = client(path);
		handler.load();
		assertEquals(250, handler.getConfig().getDoubleClickDangerWindowMs());

		handler.getConfig().setDoubleClickDangerWindowMs(LEGACY_WINDOW_MS);
		assertTrue(handler.saveSafely());

		assertEquals(LEGACY_WINDOW_MS, element(read(path), KEY).getAsInt(),
			"a same-version intentional 100 is not a migration input");
		ConfigHandler<ClientConfig> reloaded = client(path);
		reloaded.load();
		assertEquals(LEGACY_WINDOW_MS, reloaded.getConfig().getDoubleClickDangerWindowMs());
		assertEquals(BETA2, element(read(path), ConfigVersionUpdater.VERSION_KEY).getAsString());
	}

	@Test
	void failedMigrationWriteRetainsTheRevisedValueInMemoryAndRetriesTheOriginalBytes(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client-write-failure.json");
		byte @Unmodifiable [] original = ("{\"pingforit-version\":\"" + BETA1 + "\","
			+ "\"doubleClickDangerWindowMs\":100,\"unknownRoot\":{\"value\":true}}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		AtomicBoolean failWrite = new AtomicBoolean(true);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> fail("a valid migration must not enter invalid-file recovery"),
			BETA2,
			(destination, serialized) -> {
				if (failWrite.get()) throw new IOException("injected migration write failure");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();

		assertEquals(REVISED_WINDOW_MS, handler.getConfig().getDoubleClickDangerWindowMs());
		assertArrayEquals(original, Files.readAllBytes(path),
			"a failed migration write retains the source bytes until retry");
		assertNoBackup(directory);

		failWrite.set(false);
		assertTrue(handler.saveSafely());

		JsonObject persisted = read(path);
		assertEquals(BETA2, element(persisted, ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(REVISED_WINDOW_MS, element(persisted, KEY).getAsInt());
		assertTrue(element(element(persisted, "unknownRoot").getAsJsonObject(), "value").getAsBoolean());
		assertNoBackup(directory);

		ConfigHandler<ClientConfig> reloaded = client(path);
		reloaded.load();
		assertEquals(REVISED_WINDOW_MS, reloaded.getConfig().getDoubleClickDangerWindowMs());
	}

	@Test
	void pendingMigrationReloadsASameVersionReplacementInsteadOfOverwritingIt(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client-pending-same-version.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + BETA1 + "\","
			+ "\"doubleClickDangerWindowMs\":100}", StandardCharsets.UTF_8);
		AtomicBoolean denyWrites = new AtomicBoolean(true);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> fail("a valid migration must not enter invalid-file recovery"),
			BETA2,
			(destination, serialized) -> {
				if (denyWrites.get()) throw new IOException("migration write denied");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		denyWrites.set(false);

		byte @Unmodifiable [] replacement = ("{\"pingforit-version\":\"" + BETA2 + "\","
			+ "\"doubleClickDangerWindowMs\":100,\"external\":true}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, replacement);

		assertFalse(handler.saveSafely());
		assertArrayEquals(replacement, Files.readAllBytes(path));
		assertEquals(LEGACY_WINDOW_MS, handler.getConfig().getDoubleClickDangerWindowMs(),
			"the same-version replacement is reread instead of retaining the pending 160");
		assertTrue(element(read(path), "external").getAsBoolean());
	}

	@Test
	void pendingMigrationReloadsAFutureVersionReplacementAndKeepsItsBytes(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client-pending-future.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + BETA1 + "\","
			+ "\"doubleClickDangerWindowMs\":100}", StandardCharsets.UTF_8);
		AtomicBoolean denyWrites = new AtomicBoolean(true);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> fail("a valid migration must not enter invalid-file recovery"),
			BETA2,
			(destination, serialized) -> {
				if (denyWrites.get()) throw new IOException("migration write denied");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		denyWrites.set(false);

		byte @Unmodifiable [] future = ("{\"pingforit-version\":\"99.0.0-pfi-beta1\","
			+ "\"doubleClickDangerWindowMs\":100}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, future);

		assertFalse(handler.saveSafely());
		assertArrayEquals(future, Files.readAllBytes(path),
			"the future replacement is reread under future-version protection");
		assertFalse(handler.saveSafely());
		assertArrayEquals(future, Files.readAllBytes(path));
	}

	private static ConfigHandler<ClientConfig> client(Path path) {
		return new ConfigHandler<>(ClientConfig.class, path, BETA2);
	}

	private static JsonObject migrate(
		JsonObject source,
		Class<? extends IConfig> configType,
		String sourceVersion,
		String runningVersion) {
		return ConfigVersionUpdater.update(
			source,
			configType,
			PingForItVersion.parse(sourceVersion),
			PingForItVersion.parse(runningVersion)).root();
	}

	private static JsonObject root(String version, String extraMembers) {
		return json("{\"pingforit-version\":\"" + version + "\"" + extraMembers + "}");
	}

	private static String numberMember(int value) {
		return "\"" + KEY + "\":" + value;
	}

	private static String numberMember(String value) {
		return "\"" + KEY + "\":" + value;
	}

	private static JsonObject json(String value) {
		return JsonParser.parseString(value).getAsJsonObject();
	}

	private static JsonElement element(JsonObject root, String key) {
		@Nullable JsonElement value = root.get(key);
		assertNotNull(value, "missing JSON member " + key);
		return Objects.requireNonNull(value);
	}

	private static JsonObject read(Path path) throws IOException {
		return json(Files.readString(path, StandardCharsets.UTF_8));
	}

	private static void assertNoBackup(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".broken-")));
		}
	}
}
