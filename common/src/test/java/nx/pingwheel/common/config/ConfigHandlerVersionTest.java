package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigHandlerVersionTest {
	private static final String CURRENT_VERSION = "2.0.0-pfi-beta1";

    @Test
    void defaultClientAndServerSavesContainTheExactVersionKeyAndValue(@TempDir Path tempDir) throws IOException {
        Path clientPath = tempDir.resolve("client.json");
        ConfigHandler<ClientConfig> client = new ConfigHandler<>(ClientConfig.class, clientPath, CURRENT_VERSION);
        assertTrue(client.saveSafely());
        assertTrue(readRoot(clientPath).has("pingforit-version"));
        assertEquals(CURRENT_VERSION, readRoot(clientPath).get(ConfigVersionUpdater.VERSION_KEY).getAsString());

        Path serverPath = tempDir.resolve("server.json");
        ConfigHandler<ServerConfig> server = new ConfigHandler<>(ServerConfig.class, serverPath, CURRENT_VERSION);
        assertTrue(server.saveSafely());
        assertEquals(CURRENT_VERSION, readRoot(serverPath).get(ConfigVersionUpdater.VERSION_KEY).getAsString());
    }

    @Test
    void equalVersionLoadsNormallyAndUnknownFieldsRemainGsonCompatible(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("client.json");
        String serialized = "{\n"
            + "  \"pingforit-version\": \"" + CURRENT_VERSION + "\",\n"
            + "  \"pingVolume\": 37,\n"
            + "  \"unknownFutureField\": {\"value\": true}\n"
            + "}\n";
        Files.writeString(configPath, serialized, StandardCharsets.UTF_8);

        ConfigHandler<ClientConfig> handler = client(configPath);
        handler.load();

        assertEquals(37, handler.getConfig().getPingVolume());
        assertArrayEquals(serialized.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(configPath));
        assertFalse(hasBrokenBackup(tempDir));
    }

    @Test
    void sameVersionServerConfigRenamesLegacyPingDurationOnDisk(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("server-legacy-duration.json");
        Files.writeString(
            configPath,
            "{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"pingDuration\":23}\n",
            StandardCharsets.UTF_8);

        ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
        handler.load();

        assertEquals(23, handler.getConfig().getSyncDuration());
        JsonObject persisted = readRoot(configPath);
        assertEquals(23, persisted.get("syncDuration").getAsInt());
        assertFalse(persisted.has("pingDuration"));
    }

    @Test
    void missingNullNonStringAndNonObjectRootsUseClientRecovery(@TempDir Path tempDir) throws IOException {
        List<String> invalidRoots = List.of(
			"{\"pingVolume\": 37}",
			"{\"pingforit-version\": null}",
			"{\"pingforit-version\": 37}",
			"{\"pingforit-version\": \"not-a-ping-for-it-version\"}",
			"[]");

        for (int index = 0; index < invalidRoots.size(); index++) {
            Path configPath = tempDir.resolve("client-" + index + ".json");
            Files.writeString(configPath, invalidRoots.get(index), StandardCharsets.UTF_8);

            ConfigHandler<ClientConfig> handler = client(configPath);
            handler.load();

            assertEquals(new ClientConfig(), handler.getConfig());
            assertEquals(CURRENT_VERSION, readPayload(configPath).get(ConfigVersionUpdater.VERSION_KEY).getAsString());
        }

        assertTrue(hasBrokenBackup(tempDir));
    }

    @Test
    void invalidServerRootsUseLegacyDefaultsReset(@TempDir Path tempDir) throws IOException {
        List<String> invalidRoots = List.of(
			"{\"pingVolume\": 37}",
			"{\"pingforit-version\": null}",
			"{\"pingforit-version\": false}",
			"{\"pingforit-version\": \"not-a-ping-for-it-version\"}",
			"[]");

        for (int index = 0; index < invalidRoots.size(); index++) {
            Path configPath = tempDir.resolve("server-" + index + ".json");
            Files.writeString(configPath, invalidRoots.get(index), StandardCharsets.UTF_8);

            ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
            handler.load();

            assertEquals(new ServerConfig(), handler.getConfig());
            assertEquals(CURRENT_VERSION, readRoot(configPath).get(ConfigVersionUpdater.VERSION_KEY).getAsString());
            assertFalse(hasBrokenBackup(tempDir));
        }
    }

    @Test
    void olderClientConfigPreservesValuesUpdatesMarkerAndWritesBack(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("client.json");
        Files.writeString(
            configPath,
			"{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"pingVolume\":23,\"unknown\":\"ignored\"}\n",
            StandardCharsets.UTF_8);

        ConfigHandler<ClientConfig> handler = client(configPath);
        handler.load();

        assertEquals(23, handler.getConfig().getPingVolume());
        JsonObject persisted = readPayload(configPath);
        assertEquals(CURRENT_VERSION, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
        assertEquals(23, persisted.get("pingVolume").getAsInt());
        assertTrue(persisted.has("unknown"));
        assertEquals("ignored", persisted.get("unknown").getAsString());
        assertFalse(hasBrokenBackup(tempDir));
    }

    @Test
    void futureClientConfigUsesDefaultsPreservesBytesAndBlocksSaveAndReset(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("client.json");
		byte[] original = "{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"pingVolume\":23,\"future\":true}\n"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(configPath, original);

        ConfigHandler<ClientConfig> handler = client(configPath);
        handler.load();

        assertEquals(new ClientConfig(), handler.getConfig());
        assertArrayEquals(original, Files.readAllBytes(configPath));
        assertFalse(handler.saveSafely());
        handler.resetToDefaults();
        assertArrayEquals(original, Files.readAllBytes(configPath));
        assertFalse(hasBrokenBackup(tempDir));
    }

    @Test
    void futureServerConfigUsesDefaultsPreservesBytesAndBlocksSaveAndReset(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("server-future.json");
        byte[] original = "{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"rateLimit\":23}\n"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(configPath, original);

        ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
        handler.load();

        assertEquals(new ServerConfig(), handler.getConfig());
        assertArrayEquals(original, Files.readAllBytes(configPath));
        assertFalse(handler.saveSafely());
        handler.resetToDefaults();
        assertArrayEquals(original, Files.readAllBytes(configPath));
        assertFalse(hasBrokenBackup(tempDir));
    }

    @Test
    void failedClientMigrationWriteRetainsOriginalBytesAndLoadedValuesUntilRetry(@TempDir Path tempDir)
        throws IOException {
        Path configPath = tempDir.resolve("client-migration-failure.json");
        byte[] original = "{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"pingVolume\":23,\"unknown\":true}\n"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(configPath, original);
        AtomicBoolean failWrite = new AtomicBoolean(true);

        ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
            ClientConfig.class,
            configPath,
            (source, backupPath, originalBytes) -> Files.write(backupPath, originalBytes),
            CURRENT_VERSION,
            (path, serialized) -> {
                if (failWrite.get()) {
                    throw new IOException("deterministic migration write failure");
                }
                Files.writeString(path, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
            });

        handler.load();
        assertEquals(23, handler.getConfig().getPingVolume());
        assertArrayEquals(original, Files.readAllBytes(configPath));

        failWrite.set(false);
        assertTrue(handler.saveSafely());
        JsonObject persisted = readRoot(configPath);
        assertEquals(CURRENT_VERSION, persisted.get("pingforit-version").getAsString());
        assertTrue(persisted.get("unknown").getAsBoolean());
    }

    @Test
    void failedServerMigrationWriteRetainsOriginalBytesAndLoadedValuesUntilRetry(@TempDir Path tempDir)
        throws IOException {
        Path configPath = tempDir.resolve("server-migration-failure.json");
        byte[] original = "{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"rateLimit\":23,\"unknown\":true}\n"
            .getBytes(StandardCharsets.UTF_8);
        Files.write(configPath, original);
        AtomicBoolean failWrite = new AtomicBoolean(true);

        ConfigHandler<ServerConfig> handler = new ConfigHandler<>(
            ServerConfig.class,
            configPath,
            (source, backupPath, originalBytes) -> Files.write(backupPath, originalBytes),
            CURRENT_VERSION,
            (path, serialized) -> {
                if (failWrite.get()) {
                    throw new IOException("deterministic migration write failure");
                }
                Files.writeString(path, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
            });

        handler.load();
        assertEquals(23, handler.getConfig().getRateLimit());
        assertArrayEquals(original, Files.readAllBytes(configPath));

        failWrite.set(false);
        assertTrue(handler.saveSafely());
        JsonObject persisted = readRoot(configPath);
        assertEquals(CURRENT_VERSION, persisted.get("pingforit-version").getAsString());
        assertTrue(persisted.get("unknown").getAsBoolean());
    }

    @Test
    void staleClientMigrationPendingReloadsFutureConfigWithoutOverwritingIt(@TempDir Path tempDir)
        throws IOException {
        Path configPath = tempDir.resolve("client-stale-pending.json");
        Files.writeString(
            configPath,
            "{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"pingVolume\":23}\n",
            StandardCharsets.UTF_8);
        byte[] futureBytes = "{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"pingVolume\":99}\n"
            .getBytes(StandardCharsets.UTF_8);

        ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
            ClientConfig.class,
            configPath,
            (source, backupPath, originalBytes) -> Files.write(backupPath, originalBytes),
            CURRENT_VERSION,
            (path, serialized) -> {
                throw new IOException("migration write should not be reached after replacement");
            });
        handler.load();
        Files.write(configPath, futureBytes);

        handler.resetToDefaults();
        assertEquals(new ClientConfig(), handler.getConfig());
        assertArrayEquals(futureBytes, Files.readAllBytes(configPath));
        assertFalse(handler.saveSafely());
        handler.resetToDefaults();
        assertArrayEquals(futureBytes, Files.readAllBytes(configPath));
    }

    @Test
    void staleServerMigrationPendingReloadsFutureConfigWithoutOverwritingIt(@TempDir Path tempDir)
        throws IOException {
        Path configPath = tempDir.resolve("server-stale-pending.json");
        Files.writeString(
            configPath,
            "{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"rateLimit\":23}\n",
            StandardCharsets.UTF_8);
        byte[] futureBytes = "{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"rateLimit\":99}\n"
            .getBytes(StandardCharsets.UTF_8);

        ConfigHandler<ServerConfig> handler = new ConfigHandler<>(
            ServerConfig.class,
            configPath,
            (source, backupPath, originalBytes) -> Files.write(backupPath, originalBytes),
            CURRENT_VERSION,
            (path, serialized) -> {
                throw new IOException("migration write should not be reached after replacement");
            });
        handler.load();
        Files.write(configPath, futureBytes);

        handler.resetToDefaults();
        assertEquals(new ServerConfig(), handler.getConfig());
        assertArrayEquals(futureBytes, Files.readAllBytes(configPath));
        assertFalse(handler.saveSafely());
        handler.resetToDefaults();
        assertArrayEquals(futureBytes, Files.readAllBytes(configPath));
    }

    @Test
    void explicitClientResetClearsPendingMigrationAndDoesNotMergeOldUnknownFields(@TempDir Path tempDir)
        throws IOException {
        Path configPath = tempDir.resolve("client-pending-reset.json");
        Files.writeString(
            configPath,
            "{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"pingVolume\":23,\"oldUnknown\":true}\n",
            StandardCharsets.UTF_8);
        AtomicBoolean failWrite = new AtomicBoolean(true);
        ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
            ClientConfig.class,
            configPath,
            (source, backupPath, originalBytes) -> Files.write(backupPath, originalBytes),
            CURRENT_VERSION,
            (path, serialized) -> {
                if (failWrite.get()) {
                    throw new IOException("deterministic migration write failure");
                }
                Files.writeString(path, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
            });
        handler.load();
        failWrite.set(false);

        handler.resetToDefaults();

        JsonObject persisted = readRoot(configPath);
        assertEquals(CURRENT_VERSION, persisted.get("pingforit-version").getAsString());
        assertEquals(100, persisted.get("pingVolume").getAsInt());
        assertFalse(persisted.has("oldUnknown"));
    }

    @Test
    void explicitServerResetClearsPendingMigrationAndDoesNotMergeOldUnknownFields(@TempDir Path tempDir)
        throws IOException {
        Path configPath = tempDir.resolve("server-pending-reset.json");
        Files.writeString(
            configPath,
            "{\"pingforit-version\":\"1.0.0-pfi-beta1\",\"rateLimit\":23,\"oldUnknown\":true}\n",
            StandardCharsets.UTF_8);
        AtomicBoolean failWrite = new AtomicBoolean(true);
        ConfigHandler<ServerConfig> handler = new ConfigHandler<>(
            ServerConfig.class,
            configPath,
            (source, backupPath, originalBytes) -> Files.write(backupPath, originalBytes),
            CURRENT_VERSION,
            (path, serialized) -> {
                if (failWrite.get()) {
                    throw new IOException("deterministic migration write failure");
                }
                Files.writeString(path, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
            });
        handler.load();
        failWrite.set(false);

        handler.resetToDefaults();

        JsonObject persisted = readRoot(configPath);
        assertEquals(CURRENT_VERSION, persisted.get("pingforit-version").getAsString());
        assertEquals(5, persisted.get("rateLimit").getAsInt());
        assertFalse(persisted.has("oldUnknown"));
    }

    @Test
    void explicitResetRewritesClientAndServerEvenWhenDefaultHashIsUnchanged(@TempDir Path tempDir)
        throws IOException {
        Path clientPath = tempDir.resolve("client-same-hash.json");
        ConfigHandler<ClientConfig> client = client(clientPath);
        assertTrue(client.saveSafely());
        Files.writeString(
            clientPath,
            "{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"resetSentinel\":true}\n",
            StandardCharsets.UTF_8);
        client.resetToDefaults();
        assertFalse(readRoot(clientPath).has("resetSentinel"));

        Path serverPath = tempDir.resolve("server-same-hash.json");
        ConfigHandler<ServerConfig> server = new ConfigHandler<>(ServerConfig.class, serverPath, CURRENT_VERSION);
        assertTrue(server.saveSafely());
        Files.writeString(
            serverPath,
            "{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"resetSentinel\":true}\n",
            StandardCharsets.UTF_8);
        server.resetToDefaults();
        assertFalse(readRoot(serverPath).has("resetSentinel"));
    }

    @Test
    void invalidCurrentRuntimeVersionsFailFast(@TempDir Path tempDir) {
        assertThrows(
            PingForItVersion.InvalidVersionException.class,
            () -> new ConfigHandler<>(ClientConfig.class, tempDir.resolve("null-version.json"), (String) null));
        assertThrows(
            PingForItVersion.InvalidVersionException.class,
            () -> new ConfigHandler<>(ClientConfig.class, tempDir.resolve("bad-version.json"), "0.2.0-beta1"));
    }

    private static ConfigHandler<ClientConfig> client(Path path) {
        return new ConfigHandler<>(ClientConfig.class, path, CURRENT_VERSION);
    }

    private static JsonObject readRoot(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject readPayload(Path path) throws IOException {
        String serialized = Files.readString(path, StandardCharsets.UTF_8);
        String[] lines = serialized.split("\\R", 4);
        if (lines.length >= 4 && lines[0].startsWith("// Previous config")) {
            serialized = lines[3];
        }
        return JsonParser.parseString(serialized).getAsJsonObject();
    }

    private static boolean hasBrokenBackup(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.anyMatch(path -> path.getFileName().toString().contains(".broken-"));
        }
    }
}
