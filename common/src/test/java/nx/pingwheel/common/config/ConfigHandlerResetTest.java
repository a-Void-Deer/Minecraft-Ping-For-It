package nx.pingwheel.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigHandlerResetTest {
	private static final String CURRENT_VERSION = "1.0.0-pfi-beta1";

	@Test
	void saveRecreatesADeletedConfigEvenWhenTheInMemoryConfigIsUnchanged(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);

		assertTrue(handler.saveSafely());
		Files.delete(configPath);

		assertTrue(handler.saveSafely());
		assertTrue(Files.exists(configPath));
	}

	@Test
	void resetReplacesTheWholeConfigAndPersistsDefaults(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		ClientConfig changed = handler.getConfig();
		changed.setPingVolume(1);
		changed.setDirectionIndicatorVisible(false);
		changed.setWheelFontSize(450);
		changed.setWheelTargetFontSize(20);
		changed.setLongPressCompatibilityMode(true);
		changed.setLongPressCompatibilitySliceMillis(100);
		handler.save();

		handler.resetToDefaults();

		ClientConfig reset = handler.getConfig();
		assertNotSame(changed, reset);
		ClientConfig defaults = new ClientConfig();
		assertEquals(defaults, reset);
		assertEquals(100, reset.getPingVolume());
		assertTrue(reset.isDirectionIndicatorVisible());
		assertEquals(ClientConfigBounds.DEFAULT_WHEEL_FONT_SIZE, reset.getWheelFontSize());
		assertEquals(ClientConfigBounds.DEFAULT_WHEEL_TARGET_FONT_SIZE, reset.getWheelTargetFontSize());
		assertFalse(reset.isLongPressCompatibilityMode());
		assertEquals(ClientConfigBounds.DEFAULT_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			reset.getLongPressCompatibilitySliceMillis());

		com.google.gson.Gson gson = new com.google.gson.Gson();
		ClientConfig persistedConfig = gson.fromJson(Files.readString(configPath), ClientConfig.class);
		assertEquals(defaults, persistedConfig);

		// A stale settings screen may still hold the pre-reset object. Saving the
		// handler after such a mutation must not write that object back.
		changed.setPingVolume(2);
		handler.save();
		persistedConfig = gson.fromJson(Files.readString(configPath), ClientConfig.class);
		assertEquals(defaults, persistedConfig);
	}

	@Test
	void invalidClientConfigIsBackedUpResetWithThreeSafeCommentLinesAndReloads(@TempDir Path tempDir)
		throws IOException {
		Path configPath = tempDir.resolve("client.json");
		byte[] original = invalidConfig();
		Files.write(configPath, original);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		ClientConfig defaults = new ClientConfig();
		assertEquals(defaults, handler.getConfig());
		String reset = Files.readString(configPath, StandardCharsets.UTF_8);
		String[] lines = reset.split("\\R", 4);
		assertTrue(lines.length >= 4);
		assertEquals("// Previous config had an error.", lines[0]);
		assertEquals(
			"// Error reason: validation: blockDisplayWhitelist entry 0 entry has invalid block matcher grammar",
			lines[1]);
		assertTrue(lines[2].startsWith("// Backup file: "));
		assertFalse(lines[0].contains("\n"));
		assertFalse(lines[1].contains("\n"));
		assertFalse(lines[2].contains("\n"));
		assertFalse(lines[3].startsWith("//"));

		Path backup = Path.of(lines[2].substring("// Backup file: ".length()));
		assertTrue(Files.exists(backup));
		assertArrayEquals(original, Files.readAllBytes(backup));

		// The recovery header is accepted on a later normal load.
		handler.load();
		assertEquals(defaults, handler.getConfig());
	}

	@Test
	void parserRecoveryUsesOnlyAStableCategoryInTheResetReason(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		byte[] original = "{\n  \"privateField\": \"do-not-copy-to-diagnostics\"\n"
			.getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		String[] lines = Files.readString(configPath, StandardCharsets.UTF_8).split("\\R", 4);
		assertEquals("// Previous config had an error.", lines[0]);
		assertEquals("// Error reason: parser failure", lines[1]);
		assertTrue(lines[2].startsWith("// Backup file: "));
		assertFalse(lines[1].contains("privateField"));
		assertFalse(lines[1].contains("do-not-copy-to-diagnostics"));
	}

	@Test
	void failedBackupPreservesOriginalAndBlocksNormalSaveAndReset(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		byte[] original = invalidConfig();
		Files.write(configPath, original);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			configPath,
			(source, backupPath, originalBytes) -> {
				throw new IOException("test backup failure with private path " + source);
			},
			CURRENT_VERSION);

		handler.load();
		assertEquals(new ClientConfig(), handler.getConfig());
		assertArrayEquals(original, Files.readAllBytes(configPath));

		handler.getConfig().setPingVolume(1);
		assertFalse(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(configPath));

		handler.resetToDefaults();
		assertArrayEquals(original, Files.readAllBytes(configPath));
		assertEquals(new ClientConfig(), handler.getConfig());
	}

	@Test
	void preservationLockClearsOnlyAfterALaterSuccessfulRecovery(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		byte[] original = invalidConfig();
		Files.write(configPath, original);
		AtomicBoolean failBackup = new AtomicBoolean(true);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			configPath,
			(source, backupPath, originalBytes) -> {
				if (failBackup.get()) {
					throw new IOException("deterministic backup failure");
				}
				Files.write(
					backupPath,
					originalBytes,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE);
			},
			CURRENT_VERSION);

		handler.load();
		handler.resetToDefaults();
		assertArrayEquals(original, Files.readAllBytes(configPath));

		failBackup.set(false);
		handler.load();
		String[] lines = Files.readString(configPath, StandardCharsets.UTF_8).split("\\R", 4);
		assertEquals("// Previous config had an error.", lines[0]);
		assertTrue(lines[2].startsWith("// Backup file: "));
		assertTrue(Files.exists(Path.of(lines[2].substring("// Backup file: ".length()))));
		assertTrue(handler.saveSafely());
	}

	private static byte[] invalidConfig() {
		return ("{\n  \"pingforit-version\": \"" + CURRENT_VERSION + "\",\n"
			+ "  \"blockDisplayWhitelist\": [\"minecraft:stone:*\"]\n}\n")
			.getBytes(StandardCharsets.UTF_8);
	}
}
