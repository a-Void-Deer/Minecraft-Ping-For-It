package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
	private final PingForItVersion modVersion;
	private final AtomicConfigWriter atomicConfigWriter;
	private SaveProtection saveProtection;
	private PendingMigration pendingMigration;

	@Getter
	private T config;
	private int configHash;

	public static <T extends IConfig> ConfigHandler<T> of(Class<T> configType, String configExtension) {
		return new ConfigHandler<>(configType, IPlatformContextService.INSTANCE.resolveConfigDir(MOD_ID + configExtension));
	}

	public ConfigHandler(Class<T> configType, Path configPath) {
		this(
			configType,
			configPath,
			ConfigHandler::writeBackupFile,
			IPlatformContextService.INSTANCE.getSelfModVersion(),
			ConfigHandler::writeAtomically);
	}

	ConfigHandler(Class<T> configType, Path configPath, BackupWriter backupWriter) {
		this(
			configType,
			configPath,
			backupWriter,
			IPlatformContextService.INSTANCE.getSelfModVersion(),
			ConfigHandler::writeAtomically);
	}

	ConfigHandler(Class<T> configType, Path configPath, String modVersion) {
		this(configType, configPath, ConfigHandler::writeBackupFile, modVersion, ConfigHandler::writeAtomically);
	}

	ConfigHandler(Class<T> configType, Path configPath, BackupWriter backupWriter, String modVersion) {
		this(configType, configPath, backupWriter, modVersion, ConfigHandler::writeAtomically);
	}

	ConfigHandler(
		Class<T> configType,
		Path configPath,
		BackupWriter backupWriter,
		String modVersion,
		AtomicConfigWriter atomicConfigWriter) {
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.configType = Objects.requireNonNull(configType, "configType");
		this.configPath = Objects.requireNonNull(configPath, "configPath");
		this.backupWriter = Objects.requireNonNull(backupWriter, "backupWriter");
		this.modVersion = PingForItVersion.parse(modVersion);
		this.atomicConfigWriter = Objects.requireNonNull(atomicConfigWriter, "atomicConfigWriter");

		this.configHash = 0;
		this.config = createDefaultConfig();
		this.recoverInvalidConfig = this.config.recoverInvalidOnLoad();
		this.saveProtection = SaveProtection.NONE;
		this.pendingMigration = null;
	}

	public Path getConfigPath() {
		return configPath;
	}

	public synchronized void save() {
		saveInternal();
	}

	/**
	 * Saves the current config and reports whether the file was written or was
	 * already up to date. Callers that must not close before persistence should
	 * use this result-bearing variant.
	 */
	public synchronized boolean saveSafely() {
		return saveInternal();
	}

	/**
	 * Replaces the complete config object with a freshly constructed default
	 * instance and persists it immediately.
	 */
	public synchronized void resetToDefaults() {
		if (pendingMigration != null && !ensurePendingMigrationSourceCurrent()) {
			config = createDefaultConfig();
			if (saveProtection != SaveProtection.NONE) {
				return;
			}
		}

		pendingMigration = null;
		config = createDefaultConfig();
		saveInternal(true);
	}

	private boolean saveInternal() {
		return saveInternal(false);
	}

	private boolean saveInternal(boolean force) {
		if (saveProtection != SaveProtection.NONE) {
			LOGGER.warn(
				"Config save skipped; type=%s path=%s reason=%s; the original file was preserved"
					.formatted(configType.getSimpleName(), configPath, saveProtection.description));
			return false;
		}

		if (pendingMigration != null) {
			return savePendingMigration();
		}

		if (!force && configHash == config.hashCode() && Files.exists(configPath)) {
			return true;
		}

		try {
			config.onUpdate();
			writeSerializedConfig(serializeConfig());
		} catch (Exception e) {
			LOGGER.warn(
				"Config save failed; type=%s path=%s reason=%s"
					.formatted(configType.getSimpleName(), configPath, safeLogReason(e)));
			return false;
		}

		configHash = config.hashCode();
		LOGGER.info("Saved config type=%s".formatted(configType.getSimpleName()));
		return true;
	}

	@SneakyThrows
	public synchronized void load() {
		pendingMigration = null;
		if (!Files.exists(configPath)) {
			config = createDefaultConfig();
			saveProtection = SaveProtection.NONE;
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
			byte[] sourceBytes = Files.readAllBytes(configPath);
			VersionedConfig versionedConfig = readVersionedConfig(
				new String(sourceBytes, StandardCharsets.UTF_8),
				sourceBytes);
			if (versionedConfig.future()) {
				rejectFutureConfig(versionedConfig.configVersion());
				return;
			}

			config = deserializeConfig(versionedConfig.root());
			config.validate();
			saveProtection = SaveProtection.NONE;
			configHash = config.hashCode();
			if (versionedConfig.migrated()) {
				pendingMigration = new PendingMigration(
					versionedConfig.root(),
					versionedConfig.configVersion(),
					versionedConfig.migrationUpdates(),
					versionedConfig.sourceBytes());
				savePendingMigration();
			}
		} catch (Exception e) {
			config = null;
			LOGGER.error(
				"Config load failed; type=%s path=%s reason=%s"
					.formatted(configType.getSimpleName(), configPath, safeLogReason(e)));
		}

		if (config == null) {
			config = createDefaultConfig();
			saveProtection = SaveProtection.NONE;
			LOGGER.error("Config is broken -> reset to defaults");

			save();
			return;
		}

		config.validate();
		configHash = config.hashCode();
		logLoadedConfig();
	}

	private void loadWithClientRecovery() {
		byte[] originalBytes = null;

		try {
			originalBytes = Files.readAllBytes(configPath);
			String serialized = new String(originalBytes, StandardCharsets.UTF_8);
			VersionedConfig versionedConfig = readVersionedConfig(stripRecoveryHeader(serialized), originalBytes);
			if (versionedConfig.future()) {
				rejectFutureConfig(versionedConfig.configVersion());
				return;
			}

			config = deserializeConfig(versionedConfig.root());
			config.validate();
			saveProtection = SaveProtection.NONE;
			configHash = config.hashCode();
			if (versionedConfig.migrated()) {
				pendingMigration = new PendingMigration(
					versionedConfig.root(),
					versionedConfig.configVersion(),
					versionedConfig.migrationUpdates(),
					versionedConfig.sourceBytes());
				savePendingMigration();
			}
			logLoadedConfig();
		} catch (Exception failure) {
			recoverClientConfig(originalBytes, failure);
		}
	}

	private void recoverClientConfig(byte[] originalBytes, Exception failure) {
		pendingMigration = null;
		String reason = safeReason(failure);
		LOGGER.warn("Client config load failed; defaults will be restored; reason=" + safeLogReason(failure));

		BackupResult backup = backupOriginal(originalBytes);
		config = createDefaultConfig();

		if (backup.failure() != null) {
			saveProtection = SaveProtection.INVALID_FILE;
			configHash = config.hashCode();
			LOGGER.warn(
				"Client config backup failed; original file was preserved and defaults remain in memory; reason="
					+ safeLogReason(backup.failure()));
			return;
		}

		String header = recoveryHeader(reason, backup.path());
		try {
			config.onUpdate();
			writeSerializedConfig(header + serializeConfig());
			configHash = config.hashCode();
			saveProtection = SaveProtection.NONE;
			LOGGER.warn("Client config was reset after load failure; backup was written");
		} catch (Exception writeFailure) {
			saveProtection = SaveProtection.INVALID_FILE;
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

	private T deserializeConfig(JsonElement root) {
		T loaded = gson.fromJson(root, configType);
		if (loaded == null) {
			throw new IllegalStateException("config JSON is empty");
		}
		return loaded;
	}

	private String serializeConfig() {
		return serializeConfig(null);
	}

	private String serializeConfig(JsonElement rootToPreserve) {
		JsonElement serialized = JsonParser.parseString(gson.toJson(config));
		if (!serialized.isJsonObject()) {
			throw new IllegalStateException("serialized config root is not an object");
		}

		if (rootToPreserve != null) {
			if (!rootToPreserve.isJsonObject()) {
				throw new IllegalStateException("migration root is not an object");
			}

			var merged = rootToPreserve.getAsJsonObject().deepCopy();
			for (var entry : serialized.getAsJsonObject().entrySet()) {
				merged.add(entry.getKey(), entry.getValue());
			}
			serialized = merged;
		}

		JsonElement marker = new com.google.gson.JsonPrimitive(modVersion.originalVersion());
		serialized.getAsJsonObject().add(ConfigVersionUpdater.VERSION_KEY, marker);
		return gson.toJson(serialized);
	}

	private VersionedConfig readVersionedConfig(String serialized, byte[] sourceBytes) {
		JsonElement parsed = JsonParser.parseString(serialized);
		if (!parsed.isJsonObject()) {
			throw new IllegalStateException("config JSON root is not an object");
		}

		var root = parsed.getAsJsonObject();
		boolean legacyServerDurationMigrated = configType == ServerConfig.class
			&& ServerConfig.migrateLegacyDurationKey(root);
		String rawConfigVersion = ConfigVersionUpdater.requireVersion(root);
		PingForItVersion configVersion = PingForItVersion.parse(rawConfigVersion);
		int comparison = configVersion.compareTo(modVersion);
		if (comparison == 0) {
			List<String> updates = legacyServerDurationMigrated
				? List.of("syncDuration: pingDuration -> syncDuration")
				: List.of();
			return new VersionedConfig(
				root, configVersion, legacyServerDurationMigrated, false, updates, sourceBytes);
		}

		LOGGER.warn(
			"Config version mismatch; type=%s path=%s configVersion=%s modVersion=%s"
				.formatted(configType.getSimpleName(), configPath, configVersion, modVersion));

		if (comparison > 0) {
			return new VersionedConfig(root, configVersion, false, true, List.of(), sourceBytes);
		}

		ConfigVersionUpdater.MigrationResult migration =
			ConfigVersionUpdater.update(root, configVersion, modVersion);
		List<String> updates = new ArrayList<>(migration.updates());
		if (legacyServerDurationMigrated) {
			updates.add("syncDuration: pingDuration -> syncDuration");
		}
		return new VersionedConfig(
			migration.root(),
			configVersion,
			true,
			false,
			List.copyOf(updates),
			sourceBytes);
	}

	private void rejectFutureConfig(PingForItVersion configVersion) {
		config = createDefaultConfig();
		configHash = config.hashCode();
		saveProtection = SaveProtection.FUTURE_VERSION;
		LOGGER.warn(
			"Future config version rejected; type=%s path=%s configVersion=%s modVersion=%s; defaults are in memory and the file will not be rewritten"
				.formatted(configType.getSimpleName(), configPath, configVersion, modVersion));
	}

	private boolean savePendingMigration() {
		PendingMigration migration = pendingMigration;
		if (migration == null) {
			return true;
		}

		if (!ensurePendingMigrationSourceCurrent()) {
			return false;
		}

		migration = pendingMigration;
		if (migration == null) {
			return true;
		}

		try {
			config.onUpdate();
			writeSerializedConfig(serializeConfig(migration.root()));
		} catch (Exception failure) {
			LOGGER.warn(
				"Config migration write failed; type=%s path=%s configVersion=%s modVersion=%s; the valid loaded config was retained and a later save will retry; reason=%s"
					.formatted(
						configType.getSimpleName(),
						configPath,
						migration.configVersion(),
						modVersion,
						safeLogReason(failure)));
			return false;
		}

		pendingMigration = null;
		configHash = config.hashCode();
		LOGGER.info(
			"Config migration applied; type=%s path=%s configVersion=%s modVersion=%s updates=%s"
				.formatted(
					configType.getSimpleName(),
					configPath,
					migration.configVersion(),
					modVersion,
					String.join(", ", migration.updates())));
		return true;
	}

	private boolean ensurePendingMigrationSourceCurrent() {
		PendingMigration migration = pendingMigration;
		if (migration == null) {
			return true;
		}

		byte[] currentBytes;
		try {
			currentBytes = Files.readAllBytes(configPath);
		} catch (Exception failure) {
			return discardStalePendingMigration(
				"the config file could not be read; reason=" + safeLogReason(failure));
		}

		if (!Arrays.equals(migration.sourceBytes(), currentBytes)) {
			return discardStalePendingMigration("the config file bytes changed on disk");
		}
		return true;
	}

	private boolean discardStalePendingMigration(String reason) {
		pendingMigration = null;
		LOGGER.warn(
			"Pending config migration discarded; type=%s path=%s because the disk configuration changed; %s; reloading current disk state"
				.formatted(configType.getSimpleName(), configPath, reason));
		load();
		return false;
	}

	private void logLoadedConfig() {
		if (pendingMigration == null) {
			LOGGER.info("Loaded config type=%s".formatted(configType.getSimpleName()));
			return;
		}

		LOGGER.warn(
			"Loaded config into memory; migration writeback is pending; type=%s path=%s"
				.formatted(configType.getSimpleName(), configPath));
	}

	private void writeSerializedConfig(String serialized) throws Exception {
		atomicConfigWriter.write(configPath, serialized);
	}

	private static void writeAtomically(Path configPath, String serialized) throws IOException {
		Path parent = configPath.toAbsolutePath().normalize().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path temporaryPath = Files.createTempFile(
			parent == null ? Path.of(".").toAbsolutePath().normalize() : parent,
			(configPath.getFileName() == null ? "config" : configPath.getFileName().toString()) + ".",
			".tmp");
		try {
			Files.writeString(
				temporaryPath,
				serialized + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.TRUNCATE_EXISTING,
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
		return uniqueSibling(configPath, suffix);
	}

	private static Path uniqueSibling(Path configPath, String suffix) {
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

	private record VersionedConfig(
		JsonElement root,
		PingForItVersion configVersion,
		boolean migrated,
		boolean future,
		List<String> migrationUpdates,
		byte[] sourceBytes) {
		private VersionedConfig {
			sourceBytes = sourceBytes.clone();
		}
	}

	private record PendingMigration(
		JsonElement root,
		PingForItVersion configVersion,
		List<String> updates,
		byte[] sourceBytes) {
		private PendingMigration {
			sourceBytes = sourceBytes.clone();
		}
	}

	private enum SaveProtection {
		NONE("none"),
		INVALID_FILE("invalid config recovery failed"),
		FUTURE_VERSION("future config version");

		private final String description;

		SaveProtection(String description) {
			this.description = description;
		}
	}

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

@FunctionalInterface
interface AtomicConfigWriter {
	void write(Path configPath, String serialized) throws Exception;
}
