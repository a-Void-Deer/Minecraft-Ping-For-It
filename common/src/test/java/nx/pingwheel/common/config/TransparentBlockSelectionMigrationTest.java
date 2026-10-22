package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nx.pingwheel.common.math.RaycastPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransparentBlockSelectionMigrationTest {
	private static final String TARGET = "0.5.0-pfi-beta1";
	private static final String LEGACY = "0.4.2-pfi-beta1";
	private static final String OLD_KEY = "passThroughTransparentBlocks";
	private static final String NEW_KEY = "markTransparentBlocks";

	@Test
	void freshConfigRoundTripAndResetKeepTheNewOutlineDefault(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("fresh.json");
		var handler = client(path);
		handler.load();
		assertTrue(handler.getConfig().isMarkTransparentBlocks());
		assertTrue(read(path).get(NEW_KEY).getAsBoolean());
		assertFalse(read(path).has(OLD_KEY));
		handler.getConfig().setMarkTransparentBlocks(false);
		assertTrue(handler.saveSafely());
		var reloaded = client(path);
		reloaded.load();
		assertFalse(reloaded.getConfig().isMarkTransparentBlocks());
		reloaded.resetToDefaults();
		assertTrue(reloaded.getConfig().isMarkTransparentBlocks());
		assertTrue(read(path).get(NEW_KEY).getAsBoolean());
		assertFalse(read(path).has(OLD_KEY));
	}

	@Test
	void clientStepCrossesOnlyItsExistingBeta1BoundaryAndPreservesTheRawSource() {
		for (String old : List.of("0.2.0-pfi-beta1", LEGACY, "0.5.0-pfi-beta0")) {
			JsonObject source = root(old, ",\"passThroughTransparentBlocks\":true,\"wheelTimeoutMillis\":3000");
			JsonObject migrated = update(source, ClientConfig.class, old, TARGET);
			assertFalse(migrated.has(OLD_KEY));
			assertFalse(migrated.get(NEW_KEY).getAsBoolean());
			assertFalse(migrated.has("wheelTimeoutMillis"), "one beta1 step performs both transformations");
			assertEquals(source.get("unknown"), migrated.get("unknown"));
			assertEquals(TARGET, migrated.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
			assertTrue(source.get(OLD_KEY).getAsBoolean());
			assertFalse(source.has(NEW_KEY));
		}
		JsonObject source = root(LEGACY, ",\"passThroughTransparentBlocks\":true");
		assertEquals(source.get(OLD_KEY), update(source, ClientConfig.class, LEGACY,
			"0.5.0-pfi-beta0").get(OLD_KEY));
		assertFalse(update(source, ServerConfig.class, LEGACY, TARGET).has(NEW_KEY));
		assertTrue(update(source, ServerConfig.class, LEGACY, TARGET).has(OLD_KEY));
		for (String old : List.of(TARGET, "00.005.000-pfi-beta1", "0.6.0-pfi-beta1")) {
			JsonObject afterBoundary = update(root(old, ",\"passThroughTransparentBlocks\":true"),
				ClientConfig.class, old, "0.7.0-pfi-beta1");
			assertTrue(afterBoundary.has(OLD_KEY));
			assertFalse(afterBoundary.has(NEW_KEY));
		}
	}

	@Test
	void preTargetHandlerWritebackDoesNotIntroduceTheFutureKeyAndLaterUpgradeKeepsVisualSelection(
		@TempDir Path directory) throws IOException {
		Path path = directory.resolve("intermediate.json");
		JsonObject source = root(LEGACY, ",\"passThroughTransparentBlocks\":true");
		Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
		var intermediate = new ConfigHandler<>(ClientConfig.class, path, "0.4.3-pfi-beta1");
		intermediate.load();
		assertEquals("0.4.3-pfi-beta1", read(path).get(ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(source.get(OLD_KEY), read(path).get(OLD_KEY));
		assertFalse(read(path).has(NEW_KEY), "pre-target typed writeback must not inject a future preference");
		assertFalse(intermediate.getConfig().isMarkTransparentBlocks());
		assertEquals(source.get("unknown"), read(path).get("unknown"));

		var upgraded = client(path);
		upgraded.load();
		assertFalse(upgraded.getConfig().isMarkTransparentBlocks());
		assertEquals(RaycastPolicy.BlockMode.VISUAL,
			RaycastPolicy.from(upgraded.getConfig().isMarkTransparentBlocks(), false, false).blockMode());
		assertFalse(read(path).get(NEW_KEY).getAsBoolean());
		assertFalse(read(path).has(OLD_KEY));
		assertNoBackup(directory);
	}

	@Test
	void preTargetFreshOrdinarySaveAndResetProjectTheSettingBackToTheOldSchema(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("pre-target.json");
		String version = "0.4.3-pfi-beta1";
		var handler = new ConfigHandler<>(ClientConfig.class, path, version);
		handler.load();
		assertFalse(read(path).has(NEW_KEY));
		assertFalse(read(path).get(OLD_KEY).getAsBoolean());
		handler.getConfig().setMarkTransparentBlocks(false);
		assertTrue(handler.saveSafely());
		assertTrue(read(path).get(OLD_KEY).getAsBoolean());
		assertFalse(read(path).has(NEW_KEY));
		var reloaded = new ConfigHandler<>(ClientConfig.class, path, version);
		reloaded.load();
		assertFalse(reloaded.getConfig().isMarkTransparentBlocks());
		reloaded.getConfig().setPingVolume(38);
		assertTrue(reloaded.saveSafely());
		assertTrue(read(path).get(OLD_KEY).getAsBoolean(), "an unrelated ordinary save keeps the loaded effect");
		assertFalse(read(path).has(NEW_KEY));
		Path upgradePath = directory.resolve("pre-target-saved-upgrade.json");
		Files.copy(path, upgradePath);
		var upgraded = client(upgradePath);
		upgraded.load();
		assertFalse(upgraded.getConfig().isMarkTransparentBlocks());

		reloaded.resetToDefaults();
		assertFalse(read(path).get(OLD_KEY).getAsBoolean());
		assertFalse(read(path).has(NEW_KEY));
		var resetUpgraded = client(path);
		resetUpgraded.load();
		assertTrue(resetUpgraded.getConfig().isMarkTransparentBlocks());
		assertNoBackup(directory);
	}

	@Test
	void preTargetGuardedMergeRetainsLegacyRawValuesAndAnAlreadyPresentFutureKey(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("pre-target-unknown.json");
		for (String futureValue : List.of("true", "false", "null", "{\"unknown\":true}")) {
			JsonObject source = root(LEGACY,
				",\"passThroughTransparentBlocks\":\"TrUe\",\"markTransparentBlocks\":" + futureValue);
			Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
			new ConfigHandler<>(ClientConfig.class, path, "0.4.3-pfi-beta1").load();
			assertEquals(source.get(OLD_KEY), read(path).get(OLD_KEY));
			assertEquals(source.get(NEW_KEY), read(path).get(NEW_KEY), "existing unknown data is not a typed default");
			assertEquals(source.get("unknown"), read(path).get("unknown"));
		}
		// The lower handler preserves a pre-existing future preference, and the
		// actual beta1 upgrade still honors its established new-key precedence.
		JsonObject source = root(LEGACY, ",\"passThroughTransparentBlocks\":true,\"markTransparentBlocks\":true");
		Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
		new ConfigHandler<>(ClientConfig.class, path, "0.4.3-pfi-beta1").load();
		var upgraded = client(path);
		upgraded.load();
		assertTrue(upgraded.getConfig().isMarkTransparentBlocks());
		assertFalse(read(path).has(OLD_KEY));
		assertNoBackup(directory);
	}

	@Test
	void guardedMigrationPreservesUnknownNullsWithoutMaterializingOmittedModelNulls(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("unknown-nulls.json");
		JsonObject source = root(LEGACY, ",\"passThroughTransparentBlocks\":true,\"unknownNull\":null,"
			+ "\"unknownObject\":{\"member\":null,\"array\":[null,{\"nestedNull\":null}]},"
			+ "\"spatialSelector\":{\"unknownNull\":null,\"unknownObject\":{\"member\":null}}");
		byte[] original = source.toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		AtomicBoolean failWrite = new AtomicBoolean(true);
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(from, destination, bytes) -> fail("valid migration must not invoke recovery"), TARGET,
			(destination, serialized) -> {
				if (failWrite.get()) throw new IOException("injected migration write failure");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		assertArrayEquals(original, Files.readAllBytes(path));
		// This known nullable field is absent from the raw preservation base. The
		// existing model serializer must keep omitting it rather than emitting null.
		handler.getConfig().setPlayerInfoMode(null);
		failWrite.set(false);
		assertTrue(handler.saveSafely());
		JsonObject persisted = read(path);
		assertFalse(persisted.has("playerInfoMode"));
		assertEquals(source.get("unknownNull"), persisted.get("unknownNull"));
		assertEquals(source.get("unknownObject"), persisted.get("unknownObject"));
		assertEquals(source.getAsJsonObject("spatialSelector").get("unknownNull"),
			persisted.getAsJsonObject("spatialSelector").get("unknownNull"));
		assertEquals(source.getAsJsonObject("spatialSelector").get("unknownObject"),
			persisted.getAsJsonObject("spatialSelector").get("unknownObject"));
		assertFalse(persisted.get(NEW_KEY).getAsBoolean());
		assertFalse(persisted.has(OLD_KEY));

		byte[] migratedBytes = Files.readAllBytes(path);
		var reloaded = client(path);
		reloaded.load();
		assertFalse(reloaded.getConfig().isMarkTransparentBlocks());
		assertNotNull(reloaded.getConfig().getPlayerInfoMode(), "omitted model field keeps its initialized default");
		assertTrue(reloaded.saveSafely());
		assertArrayEquals(migratedBytes, Files.readAllBytes(path));
		assertEquals(persisted, read(path), "reload and untouched save retain every explicit unknown null");
		assertNoBackup(directory);
	}

	@Test
	void preTargetPendingEditProjectsItsNewEffectWhileKeepingTheExistingSourceGuard(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("pre-target-pending.json");
		byte[] original = root(LEGACY, ",\"passThroughTransparentBlocks\":true").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		AtomicBoolean failWrite = new AtomicBoolean(true);
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, destination, bytes) -> fail("valid pre-target migration must not invoke recovery"),
			"0.4.3-pfi-beta1", (destination, serialized) -> {
				if (failWrite.get()) throw new IOException("injected pre-target write failure");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		assertFalse(handler.getConfig().isMarkTransparentBlocks());
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.getConfig().setMarkTransparentBlocks(true);
		failWrite.set(false);
		assertTrue(handler.saveSafely());
		assertFalse(read(path).get(OLD_KEY).getAsBoolean());
		assertFalse(read(path).has(NEW_KEY));
		var upgraded = client(path);
		upgraded.load();
		assertTrue(upgraded.getConfig().isMarkTransparentBlocks());

		Files.write(path, original);
		failWrite.set(true);
		handler.load();
		byte[] external = root(TARGET, ",\"markTransparentBlocks\":false").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, external);
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertArrayEquals(external, Files.readAllBytes(path));
		assertNoBackup(directory);
	}

	@Test
	void preTargetLegacyDecodingStillRejectsInvalidOldTypesEvenBesideAnUnknownNewKey(@TempDir Path directory)
		throws IOException {
		for (String invalid : List.of("1", "{}", "[]", "[true]")) {
			Path path = directory.resolve("pre-target-invalid.json");
			byte[] original = root(LEGACY,
				",\"passThroughTransparentBlocks\":" + invalid + ",\"markTransparentBlocks\":false")
				.toString().getBytes(StandardCharsets.UTF_8);
			Files.write(path, original);
			var handler = new ConfigHandler<>(ClientConfig.class, path,
				(source, destination, bytes) -> { throw new IOException("injected backup failure"); }, "0.4.3-pfi-beta1");
			handler.load();
			assertEquals(new ClientConfig(), handler.getConfig());
			assertFalse(handler.saveSafely());
			assertArrayEquals(original, Files.readAllBytes(path));
		}
	}

	@Test
	void boolMissingNullAndGsonCompatibleStringsKeepTheirActualSelectionEffect(@TempDir Path directory)
		throws IOException {
		for (var probe : List.of(
			new Probe("", true), new Probe(",\"passThroughTransparentBlocks\":null", true),
			new Probe(",\"passThroughTransparentBlocks\":false", true),
			new Probe(",\"passThroughTransparentBlocks\":true", false),
			new Probe(",\"passThroughTransparentBlocks\":\"TrUe\"", false),
			new Probe(",\"passThroughTransparentBlocks\":\"false\"", true),
			new Probe(",\"passThroughTransparentBlocks\":\"not a boolean\"", true),
			new Probe(",\"passThroughTransparentBlocks\":\" true \"", true),
			new Probe(",\"passThroughTransparentBlocks\":\"\"", true))) {
			Path path = directory.resolve("legacy.json");
			JsonObject source = root(LEGACY, probe.fields());
			source.add("spatialSelector", JsonParser.parseString("{\"unknownPreference\":{\"enabled\":true}}"));
			Files.writeString(path, source.toString(), StandardCharsets.UTF_8);
			var handler = client(path);
			handler.load();
			assertEquals(probe.markTransparent(), handler.getConfig().isMarkTransparentBlocks(), probe.fields());
			assertEquals(probe.markTransparent() ? RaycastPolicy.BlockMode.OUTLINE : RaycastPolicy.BlockMode.VISUAL,
				RaycastPolicy.from(handler.getConfig().isMarkTransparentBlocks(), false, false).blockMode());
			assertEquals(37, handler.getConfig().getPingVolume());
			JsonObject persisted = read(path);
			assertEquals(probe.markTransparent(), persisted.get(NEW_KEY).getAsBoolean());
			assertFalse(persisted.has(OLD_KEY));
			assertEquals(source.get("unknown"), persisted.get("unknown"));
			assertEquals(source.getAsJsonObject("spatialSelector").get("unknownPreference"),
				persisted.getAsJsonObject("spatialSelector").get("unknownPreference"));
			var reloaded = client(path);
			reloaded.load();
			assertEquals(probe.markTransparent(), reloaded.getConfig().isMarkTransparentBlocks());
		}
		assertNoBackup(directory);
	}

	@Test
	void presentNewKeyAlwaysWinsIncludingNullAndInvalidLegacyValues(@TempDir Path directory) throws IOException {
		for (var probe : List.of(
			new Probe(",\"markTransparentBlocks\":false,\"passThroughTransparentBlocks\":false", false),
			new Probe(",\"markTransparentBlocks\":true,\"passThroughTransparentBlocks\":true", true),
			new Probe(",\"markTransparentBlocks\":null,\"passThroughTransparentBlocks\":true", true),
			new Probe(",\"markTransparentBlocks\":false,\"passThroughTransparentBlocks\":{}", false))) {
			Path path = directory.resolve("both.json");
			Files.writeString(path, root(LEGACY, probe.fields()).toString(), StandardCharsets.UTF_8);
			var handler = client(path);
			handler.load();
			assertEquals(probe.markTransparent(), handler.getConfig().isMarkTransparentBlocks());
			assertFalse(read(path).has(OLD_KEY));
			assertEquals(37, handler.getConfig().getPingVolume());
		}
		assertNoBackup(directory);
	}

	@Test
	void invalidOldTypesAndAuthoritativeInvalidNewTypesUseTheExistingParserRecovery(@TempDir Path directory)
		throws IOException {
		int index = 0;
		for (String invalid : List.of("1", "0", "{}", "[]", "[true]", "[\"true\"]")) {
			for (String fields : List.of(",\"passThroughTransparentBlocks\":" + invalid,
				",\"markTransparentBlocks\":" + invalid + ",\"passThroughTransparentBlocks\":false")) {
				Path path = directory.resolve("invalid-" + index++ + ".json");
				byte[] original = root(LEGACY, fields).toString().getBytes(StandardCharsets.UTF_8);
				Files.write(path, original);
				Path backup = directory.resolve(path.getFileName() + ".captured-backup");
				var handler = new ConfigHandler<>(ClientConfig.class, path,
					(source, destination, bytes) -> {
						assertArrayEquals(original, bytes);
						Files.write(destination, bytes);
						Files.write(backup, bytes);
					}, TARGET);
				handler.load();
				assertArrayEquals(original, Files.readAllBytes(backup));
				assertEquals(new ClientConfig(), handler.getConfig());
				String recovered = Files.readString(path, StandardCharsets.UTF_8);
				assertTrue(recovered.startsWith("// Previous config had an error."));
				assertTrue(recovered.contains("// Error reason: parser failure"));
				assertFalse(JsonParser.parseString(recovered.split("\\R", 4)[3]).getAsJsonObject().has(OLD_KEY));
			}
		}
	}

	@Test
	void failedInvalidBackupProtectsTheOriginalInsteadOfWritingAConvertedDocument(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("invalid-protected.json");
		byte[] original = root(LEGACY, ",\"passThroughTransparentBlocks\":1").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, destination, bytes) -> { throw new IOException("injected backup failure"); }, TARGET);
		handler.load();
		assertEquals(new ClientConfig(), handler.getConfig());
		handler.getConfig().setMarkTransparentBlocks(false);
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertTrue(handler.getConfig().isMarkTransparentBlocks());
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void sameVersionDevelopmentKeyDoesNotAcquireANewNormalizationRuleButSerializationRemovesIt(
		@TempDir Path directory) throws IOException {
		Path path = directory.resolve("same-version.json");
		byte[] original = root(TARGET, ",\"passThroughTransparentBlocks\":true").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = client(path);
		handler.load();
		assertTrue(handler.getConfig().isMarkTransparentBlocks(), "same-version obsolete key is not migrated");
		assertArrayEquals(original, Files.readAllBytes(path));
		assertTrue(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path), "untouched same-version save is a no-op");
		handler.getConfig().setPingVolume(38);
		assertTrue(handler.saveSafely());
		assertFalse(read(path).has(OLD_KEY));

		// Existing gesture normalization supplies a raw preservation base even at
		// the same version. Serialization must not merge the obsolete key back in.
		Files.writeString(path, root(TARGET, ",\"passThroughTransparentBlocks\":true,"
			+ "\"longPressCompatibilityMode\":true,\"spatialSelector\":{\"gestureSkipHoldEnabled\":true}").toString(),
			StandardCharsets.UTF_8);
		handler.load();
		assertFalse(read(path).has(OLD_KEY));
		assertTrue(read(path).get(NEW_KEY).getAsBoolean());
		assertTrue(read(path).has("unknown"));
		assertNoBackup(directory);
	}

	@Test
	void failedWriteKeepsTheInvertedPolicyAndRetryHonorsTheChangedSourceAndFutureGuard(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("pending.json");
		byte[] original = root(LEGACY, ",\"passThroughTransparentBlocks\":true").toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		AtomicBoolean failWrite = new AtomicBoolean(true);
		AtomicInteger writes = new AtomicInteger();
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, destination, bytes) -> fail("valid migration must not invoke recovery"), TARGET,
			(destination, serialized) -> {
				writes.incrementAndGet();
				if (failWrite.get()) throw new IOException("injected migration write failure");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		assertFalse(handler.getConfig().isMarkTransparentBlocks());
		assertArrayEquals(original, Files.readAllBytes(path));
		failWrite.set(false);
		assertTrue(handler.saveSafely());
		assertFalse(read(path).has(OLD_KEY));
		assertFalse(read(path).get(NEW_KEY).getAsBoolean());
		assertTrue(read(path).has("unknown"));

		Files.write(path, original);
		failWrite.set(true);
		handler.load();
		byte[] external = root("0.6.0-pfi-beta1", ",\"passThroughTransparentBlocks\":true,\"markTransparentBlocks\":1")
			.toString().getBytes(StandardCharsets.UTF_8);
		Files.write(path, external);
		int writesBeforeReload = writes.get();
		assertFalse(handler.saveSafely());
		assertTrue(handler.getConfig().isMarkTransparentBlocks(), "future source uses defaults before typed decoding");
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertEquals(writesBeforeReload, writes.get());
		assertArrayEquals(external, Files.readAllBytes(path));
		assertNoBackup(directory);
	}

	private static ConfigHandler<ClientConfig> client(Path path) {
		return new ConfigHandler<>(ClientConfig.class, path, TARGET);
	}

	private static JsonObject root(String version, String fields) {
		return JsonParser.parseString("{\"pingforit-version\":\"" + version
			+ "\",\"pingVolume\":37,\"unknown\":{\"list\":[1,true,\"x\"]}" + fields + "}").getAsJsonObject();
	}

	private static JsonObject update(JsonObject root, Class<? extends IConfig> type, String old, String current) {
		return ConfigVersionUpdater.update(root, type, PingForItVersion.parse(old), PingForItVersion.parse(current)).root();
	}

	private static JsonObject read(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static void assertNoBackup(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".broken-")));
		}
	}

	private record Probe(String fields, boolean markTransparent) {}
}
