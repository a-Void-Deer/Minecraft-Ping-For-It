package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import lombok.Getter;
import lombok.SneakyThrows;
import nx.pingwheel.common.util.SafeExceptionReport;
import nx.pingwheel.common.platform.IPlatformContextService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_ID;

public class ConfigHandler <T extends IConfig> {

	private final Gson gson;
	private final Class<T> configType;
	private final Path configPath;
	private final boolean recoverInvalidConfig;
	private final BackupWriter backupWriter;
	private boolean preserveOriginalAfterRecoveryFailure;

	@Getter
	private T config;
	private int configHash;

	public static <T extends IConfig> ConfigHandler<T> of(Class<T> configType, String configExtension) {
		return new ConfigHandler<>(configType, IPlatformContextService.INSTANCE.resolveConfigDir(MOD_ID + configExtension));
	}

	public ConfigHandler(Class<T> configType, Path configPath) {
		this(configType, configPath, ConfigHandler::writeBackupFile);
	}

	ConfigHandler(Class<T> configType, Path configPath, BackupWriter backupWriter) {
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.configType = configType;
		this.configPath = configPath;
		this.backupWriter = Objects.requireNonNull(backupWriter, "backupWriter");

		this.configHash = 0;
		this.config = createDefaultConfig();
		this.recoverInvalidConfig = this.config.recoverInvalidOnLoad();
	}

	public Path getConfigPath() {
		return configPath;
	}

	public void save() {
		saveInternal();
	}

	/**
	 * Saves the current config and reports whether the file was written or was
	 * already up to date. Callers that must not close before persistence should
	 * use this result-bearing variant.
	 */
	public boolean saveSafely() {
		return saveInternal();
	}

	/**
	 * Replaces the complete config object with a freshly constructed default
	 * instance and persists it immediately.
	 */
	public void resetToDefaults() {
		config = createDefaultConfig();
		save();
	}

	private boolean saveInternal() {
		if (preserveOriginalAfterRecoveryFailure) {
			LOGGER.warn("Client config save skipped; the original invalid file was preserved after recovery failed");
			return false;
		}

		if (configHash == config.hashCode() && Files.exists(configPath)) {
			return true;
		}

		try {
			config.onUpdate();
			writeSerializedConfig(gson.toJson(config));
		} catch (Exception e) {
			LOGGER.warn("Client config save failed; reason=" + safeLogReason(e));
			return false;
		}

		configHash = config.hashCode();
		LOGGER.info("Saved config type=%s".formatted(configType.getSimpleName()));
		return true;
	}

	@SneakyThrows
	public void load() {
		if (!Files.exists(configPath)) {
			saveInternal();
			return;
		}

		if (recoverInvalidConfig) {
			loadWithClientRecovery();
			return;
		}

		loadWithLegacyRecovery();
	}

	private void loadWithLegacyRecovery() {
		try {
			var reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8);
			config = gson.fromJson(reader, configType);
			reader.close();
		} catch (Exception e) {
			config = null;
			LOGGER.error("Config load failed; reason=" + safeLogReason(e));
		}

		if (config == null) {
			config = createDefaultConfig();
			LOGGER.error("Config is broken -> reset to defaults");

			save();
			return;
		}

		config.validate();
		configHash = config.hashCode();
		LOGGER.info("Loaded config type=%s".formatted(configType.getSimpleName()));
	}

	private void loadWithClientRecovery() {
		byte[] originalBytes = null;

		try {
			originalBytes = Files.readAllBytes(configPath);
			String serialized = new String(originalBytes, StandardCharsets.UTF_8);
			config = gson.fromJson(stripRecoveryHeader(serialized), configType);

			if (config == null) {
				throw new IllegalStateException("config JSON is empty");
			}

			config.validate();
			configHash = config.hashCode();
			preserveOriginalAfterRecoveryFailure = false;
			LOGGER.info("Loaded config type=%s".formatted(configType.getSimpleName()));
		} catch (Exception failure) {
			recoverClientConfig(originalBytes, failure);
		}
	}

	private void recoverClientConfig(byte[] originalBytes, Exception failure) {
		String reason = safeReason(failure);
		LOGGER.warn("Client config load failed; defaults will be restored; reason=" + safeLogReason(failure));

		BackupResult backup = backupOriginal(originalBytes);
		config = createDefaultConfig();

		if (backup.failure() != null) {
			preserveOriginalAfterRecoveryFailure = true;
			configHash = config.hashCode();
			LOGGER.warn(
				"Client config backup failed; original file was preserved and defaults remain in memory; reason="
					+ safeLogReason(backup.failure()));
			return;
		}

		String header = recoveryHeader(reason, backup.path());
		try {
			config.onUpdate();
			writeSerializedConfig(header + gson.toJson(config));
			configHash = config.hashCode();
			preserveOriginalAfterRecoveryFailure = false;
			LOGGER.warn("Client config was reset after load failure; backup was written");
		} catch (Exception writeFailure) {
			preserveOriginalAfterRecoveryFailure = true;
			configHash = config.hashCode();
			LOGGER.warn(
				"Client config reset file could not be written; original backup is available; reason="
					+ safeLogReason(writeFailure));
		}
	}

	private T createDefaultConfig() {
		try {
			return configType.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("creating default config failed", e);
		}
	}

	private void writeSerializedConfig(String serialized) throws IOException {
		Path parent = configPath.toAbsolutePath().normalize().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path temporaryPath = uniqueSibling(".tmp");
		try {
			Files.writeString(
				temporaryPath,
				serialized + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);

			try {
				Files.move(
					temporaryPath,
					configPath,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				Files.move(
					temporaryPath,
					configPath,
					StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryPath);
		}
	}

	private BackupResult backupOriginal(byte[] originalBytes) {
		Path backupPath = uniqueSibling(".broken-" + UUID.randomUUID() + ".bak");

		try {
			backupWriter.write(configPath, backupPath, originalBytes);
			if (!Files.exists(backupPath)) {
				throw new IOException("backup file was not created");
			}
			if (originalBytes != null && !Arrays.equals(originalBytes, Files.readAllBytes(backupPath))) {
				throw new IOException("backup bytes did not match the original");
			}
			return new BackupResult(backupPath, null);
		} catch (Exception failure) {
			try {
				Files.deleteIfExists(backupPath);
			} catch (IOException ignored) {
				// Keep the original warning privacy-safe and do not mask the cause.
			}
			return new BackupResult(null, failure);
		}
	}

	private Path uniqueSibling(String suffix) {
		Path absolutePath = configPath.toAbsolutePath().normalize();
		Path parent = absolutePath.getParent();
		String fileName = absolutePath.getFileName() == null ? "config" : absolutePath.getFileName().toString();
		return (parent == null ? Path.of(fileName + suffix) : parent.resolve(fileName + suffix));
	}

	private static String recoveryHeader(String reason, Path backupPath) {
		String backup = backupPath == null
			? "unavailable (backup failed; original preserved)"
			: backupPath.toAbsolutePath().normalize().toString();
		return "// Previous config had an error." + System.lineSeparator()
			+ "// Error reason: " + sanitizeComment(reason) + System.lineSeparator()
			+ "// Backup file: " + sanitizeComment(backup) + System.lineSeparator();
	}

	private static String stripRecoveryHeader(String serialized) {
		String[] lines = serialized.split("\\R", 4);
		if (lines.length >= 4
			&& "// Previous config had an error.".equals(lines[0])
			&& lines[1].startsWith("// Error reason: ")
			&& lines[2].startsWith("// Backup file: ")) {
			return lines[3];
		}
		return serialized;
	}

	private static String safeReason(Throwable failure) {
		if (failure instanceof ConfigValidationException validationFailure) {
			return sanitizeComment(validationFailure.safeSummary());
		}

		return parserOrIoReason(failure);
	}

	private static String safeLogReason(Throwable failure) {
		if (failure instanceof ConfigValidationException validationFailure) {
			return sanitizeComment(validationFailure.safeSummary());
		}

		return sanitizeComment(SafeExceptionReport.format(failure).replaceAll("\\R+", " "));
	}

	private static String parserOrIoReason(Throwable failure) {
		if (hasCause(failure, JsonIOException.class)
			|| (hasCause(failure, IOException.class) && !hasCause(failure, JsonParseException.class))) {
			return "I/O failure";
		}
		if (hasCause(failure, JsonParseException.class) || hasCause(failure, IllegalStateException.class)) {
			return "parser failure";
		}
		return "configuration load failure";
	}

	private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = failure;

		while (current != null && visited.add(current)) {
			if (type.isInstance(current)) {
				return true;
			}

			try {
				current = current.getCause();
			} catch (Throwable ignored) {
				return false;
			}
		}

		return false;
	}

	private static String sanitizeComment(String value) {
		StringBuilder sanitized = new StringBuilder();

		for (int offset = 0; offset < value.length() && sanitized.length() < 240; offset++) {
			char character = value.charAt(offset);
			if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
				sanitized.append(' ');
			} else {
				sanitized.append(character);
			}
		}

		return sanitized.toString().isBlank() ? "unknown" : sanitized.toString();
	}

	private record BackupResult(Path path, Exception failure) {}

	private static void writeBackupFile(Path source, Path backupPath, byte[] originalBytes) throws IOException {
		if (originalBytes != null) {
			Files.write(
				backupPath,
				originalBytes,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
		} else {
			Files.copy(source, backupPath, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}
}

@FunctionalInterface
interface BackupWriter {
	void write(Path source, Path backupPath, byte[] originalBytes) throws Exception;
}
