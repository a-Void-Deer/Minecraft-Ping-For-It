package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonIOException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import lombok.Getter;
import lombok.SneakyThrows;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationSettings;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.platform.IPlatformContextService;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_ID;

public class ConfigHandler <T extends IConfig> {

	private final Gson gson;
	private final Gson documentGson;
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
		this.gson = new GsonBuilder()
			.setPrettyPrinting()
			// IntLimit carries its model default in the owning field, so a
			// persisted object that omits or nulls the finite value needs
			// presence-aware parsing before InventorySettings validation.
			.registerTypeAdapter(IntLimit.class, new IntLimitDeserializer())
			.create();
		// Model nulls remain omitted above; explicit nulls already present in the
		// merged raw document must survive its final tree-only serialization.
		this.documentGson = gson.newBuilder().serializeNulls().create();
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
			Diagnostics.event(Level.DEBUG, "config/save", configType.getName(), "protected",
				() -> "path=" + configPath + " reason=" + saveProtection.description + "; original file preserved");
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
			logFailure("save", "config save failed", e);
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
			Diagnostics.error("config/load", configType.getName(),
				() -> "path=" + configPath + " defaults will be restored", e);
		}

		if (config == null) {
			config = createDefaultConfig();
			saveProtection = SaveProtection.NONE;

			save();
			return;
		}

		config.validate();
		configHash = config.hashCode();
		logLoadedConfig();
	}

	private void loadWithClientRecovery() {
		byte @Nullable [] originalBytes = null;

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

	private void recoverClientConfig(byte @Nullable [] originalBytes, Exception failure) {
		pendingMigration = null;
		String reason = safeReason(failure);
		logFailure("load", "client defaults will be restored", failure);

		BackupResult backup = backupOriginal(originalBytes);
		config = createDefaultConfig();

		if (backup.failure() != null) {
			saveProtection = SaveProtection.INVALID_FILE;
			configHash = config.hashCode();
			logFailure("backup", "original file preserved; defaults remain in memory", backup.failure());
			return;
		}

		String header = recoveryHeader(reason, backup.path());
		try {
			config.onUpdate();
			writeSerializedConfig(header + serializeConfig());
			configHash = config.hashCode();
			saveProtection = SaveProtection.NONE;
			Diagnostics.event(Level.DEBUG, "config/reset", configType.getName(), "recovered",
				() -> "path=" + configPath + " backup=" + backup.path());
		} catch (Exception writeFailure) {
			saveProtection = SaveProtection.INVALID_FILE;
			configHash = config.hashCode();
			logFailure("reset", "reset file could not be written; original backup is available", writeFailure);
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
		if (usesLegacyTransparentBlockToggle()) {
			// A handler running below the boundary still reads the old schema. Keep
			// a future-named raw member in the preservation base, not in the model.
			JsonObject modelRoot = root.getAsJsonObject().deepCopy();
			modelRoot.addProperty("markTransparentBlocks", !legacyTransparentBlockToggle(modelRoot));
			root = modelRoot;
		}
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

		if (configType == ClientConfig.class) {
			removeKeys(serialized.getAsJsonObject(), REMOVED_CLIENT_PRESENTATION_KEYS);
		}
		if (usesLegacyTransparentBlockToggle()) {
			projectLegacyTransparentBlockToggle(serialized.getAsJsonObject(), rootToPreserve);
		}

		if (rootToPreserve != null) {
			if (!rootToPreserve.isJsonObject()) {
				throw new IllegalStateException("migration root is not an object");
			}

			var merged = rootToPreserve.getAsJsonObject().deepCopy();
			for (var entry : serialized.getAsJsonObject().entrySet()) {
				if (configType == ServerConfig.class && entry.getKey().equals("inventory")
					&& merged.has("inventory") && merged.get("inventory").isJsonObject()
					&& entry.getValue().isJsonObject()) {
					mergeInventoryMembers(merged.getAsJsonObject("inventory"), entry.getValue().getAsJsonObject());
					continue;
				}
				if (configType == ClientConfig.class && entry.getKey().equals("spatialSelector")
					&& merged.has(entry.getKey()) && merged.get(entry.getKey()).isJsonObject()
					&& entry.getValue().isJsonObject()) {
					// Additive selector fields replace only their own entries. Keep unknown
					// nested preferences in the guarded migration preservation base.
					JsonObject selector = merged.getAsJsonObject(entry.getKey());
					for (var preference : entry.getValue().getAsJsonObject().entrySet()) {
						selector.add(preference.getKey(), preference.getValue());
					}
					continue;
				}
				merged.add(entry.getKey(), entry.getValue());
			}
			serialized = merged;
		}

		if (configType == ClientConfig.class
			&& modVersion.compareTo(ConfigVersionUpdater.SPATIAL_SELECTOR_INTRODUCED_VERSION) >= 0) {
			// Even a preservation-base merge must not resurrect retired radius keys.
			ConfigVersionUpdater.removeLegacyWheelRadii(serialized.getAsJsonObject());
		}
		if (configType == ClientConfig.class
			&& modVersion.compareTo(ConfigVersionUpdater.WHEEL_TIMEOUT_REMOVED_VERSION) >= 0) {
			ConfigVersionUpdater.removeLegacyWheelTimeout(serialized.getAsJsonObject());
		}
		if (configType == ClientConfig.class
			&& modVersion.compareTo(ConfigVersionUpdater.MARK_TRANSPARENT_BLOCKS_INTRODUCED_VERSION) >= 0) {
			ConfigVersionUpdater.removeLegacyTransparentBlockToggle(serialized.getAsJsonObject());
		}

		JsonElement marker = new com.google.gson.JsonPrimitive(modVersion.originalVersion());
		serialized.getAsJsonObject().add(ConfigVersionUpdater.VERSION_KEY, marker);
		return documentGson.toJson(serialized);
	}

	/** Inventory-local preservation for guarded shape writeback, including unknown nested members. */
	private static void mergeInventoryMembers(JsonObject preserved, JsonObject known) {
		for (var entry : known.entrySet()) {
			@Nullable JsonElement previous = preserved.get(entry.getKey());
			if (previous != null && previous.isJsonObject() && entry.getValue().isJsonObject()) {
				mergeInventoryMembers(previous.getAsJsonObject(), entry.getValue().getAsJsonObject());
			} else {
				preserved.add(entry.getKey(), entry.getValue());
			}
		}
	}

	private boolean usesLegacyTransparentBlockToggle() {
		return configType == ClientConfig.class
			&& modVersion.compareTo(ConfigVersionUpdater.MARK_TRANSPARENT_BLOCKS_INTRODUCED_VERSION) < 0;
	}

	private boolean legacyTransparentBlockToggle(JsonObject root) {
		// Use the existing Gson boolean adapter, including its invalid-type errors.
		return Boolean.TRUE.equals(gson.fromJson(root.get("passThroughTransparentBlocks"), Boolean.class));
	}

	private void projectLegacyTransparentBlockToggle(JsonObject serialized, JsonElement rootToPreserve) {
		boolean passThrough = !((ClientConfig) config).isMarkTransparentBlocks();
		serialized.remove("markTransparentBlocks");
		if (rootToPreserve != null && rootToPreserve.isJsonObject()) {
			JsonObject preserved = rootToPreserve.getAsJsonObject();
			if (preserved.has("passThroughTransparentBlocks")
				&& legacyTransparentBlockToggle(preserved) == passThrough) {
				// Let the guarded merge retain an unchanged legacy raw value. Omitting
				// the future key above likewise leaves any pre-existing unknown one alone.
				return;
			}
		}
		// Fresh files, ordinary saves and pending edits must persist the model's
		// actual selection effect without prematurely introducing the future key.
		serialized.addProperty("passThroughTransparentBlocks", passThrough);
	}

	private VersionedConfig readVersionedConfig(String serialized, byte[] sourceBytes) {
		JsonElement parsed = JsonParser.parseString(serialized);
		if (!parsed.isJsonObject()) {
			throw new IllegalStateException("config JSON root is not an object");
		}

		var root = parsed.getAsJsonObject();
		String rawConfigVersion = ConfigVersionUpdater.requireVersion(root);
		PingForItVersion configVersion = PingForItVersion.parse(rawConfigVersion);
		int comparison = configVersion.compareTo(modVersion);
		if (comparison == 0) {
			@Nullable String shapeUpdate = normalizeRawShape(root);
			return new VersionedConfig(
				root,
				configVersion,
				shapeUpdate != null,
				false,
				shapeUpdate == null ? List.of() : List.of(shapeUpdate),
				sourceBytes);
		}

		Diagnostics.event(Level.DEBUG, "config/version", configType.getName(), "mismatch",
			() -> "path=" + configPath + " configVersion=" + configVersion + " modVersion=" + modVersion);

		if (comparison > 0) {
			return new VersionedConfig(root, configVersion, false, true, List.of(), sourceBytes);
		}

		ConfigVersionUpdater.MigrationResult migration =
			ConfigVersionUpdater.update(root, configType, configVersion, modVersion);
		@Nullable String shapeUpdate = normalizeRawShape(migration.root());
		List<String> updates = shapeUpdate == null
			? migration.updates()
			: appendedUpdate(migration.updates(), shapeUpdate);
		return new VersionedConfig(
			migration.root(),
			configVersion,
			true,
			false,
			updates,
			sourceBytes);
	}

	private static final List<String> REMOVED_CLIENT_PRESENTATION_KEYS =
		List.of("presentationReceive", "presentationDisplay");
	private static final List<String> LEGACY_FLAT_PRESENTATION_KEYS =
		List.of("white", "black", "whitelistOnly");

	/**
	 * Same-version raw-shape and preference reconciliation. Only exact obsolete
	 * shapes or explicitly conflicting client preferences are rewritten:
	 * the legacy flat server policy is copied to every target type
	 * when the new map is absent, the new map wins and drops the flat keys when
	 * present, the old memory scalar becomes an explicit finite limit, and the
	 * removed client presentation keys are stripped. A future or
	 * unrecognized shape is preserved for the version guard, and no unrelated
	 * user data is touched. Returns a non-null description only when the raw root
	 * changed, so an untouched file is never rewritten. Client gesture
	 * reconciliation also uses this preservation base and changed-source guard.
	 */
	private @Nullable String normalizeRawShape(JsonObject root) {
		if (configType == ServerConfig.class) {
			@Nullable String presentationUpdate = normalizeServerPresentationShape(root);
			boolean memoryChanged = normalizePendingMemoryShape(root);
			if (!memoryChanged) return presentationUpdate;
			String memoryUpdate = "inventory: scalar pending memory normalized to explicit finite limit";
			return presentationUpdate == null ? memoryUpdate : presentationUpdate + "; " + memoryUpdate;
		}

		if (configType == ClientConfig.class) {
			boolean changed = removeKeys(root, REMOVED_CLIENT_PRESENTATION_KEYS);
			// Reconcile only an explicit conflicting pair, through the same guarded
			// writeback as other normalization. Future/protected files never reach it.
			if (isTrue(root.get("longPressCompatibilityMode")) && root.has("spatialSelector")
				&& root.get("spatialSelector").isJsonObject()) {
				JsonObject selector = root.getAsJsonObject("spatialSelector");
				if (isTrue(selector.get("gestureSkipHoldEnabled"))) {
					selector.addProperty("gestureSkipHoldEnabled", false);
					changed = true;
				}
			}
			// Compatibility mode owns a real zero angle, not a display-only mask.
			// Normalize absent, null and non-zero raw values so a same-version load
			// persists the same invariant the setters and validation enforce. A
			// string that the typed double reader accepts (including "NaN") is
			// normalized too; an undecodable value stays for typed recovery.
			if (isTrue(root.get("longPressCompatibilityMode"))) {
				JsonElement angle = root.get("doubleClickDangerAngleDegrees");
				if (angle == null || angle.isJsonNull()) {
					root.addProperty("doubleClickDangerAngleDegrees", 0.0);
					changed = true;
				} else {
					Double decoded = decodedDouble(angle);
					if (decoded != null && (Double.isNaN(decoded) || decoded != 0.0)) {
						root.addProperty("doubleClickDangerAngleDegrees", 0.0);
						changed = true;
					}
				}
			}
			return changed ? "client: obsolete policy or conflicting press preference normalized" : null;
		}

		return null;
	}

	/** Only the former memory scalar is accepted; other IntLimit fields still require objects. */
	private static boolean normalizePendingMemoryShape(JsonObject root) {
		@Nullable JsonElement inventory = root.get("inventory");
		if (inventory == null || !inventory.isJsonObject()) return false;
		JsonObject settings = inventory.getAsJsonObject();
		@Nullable JsonElement memory = settings.get("pendingMemoryMiB");
		if (!isIntegralNumber(memory)) return false;
		JsonObject limit = new JsonObject();
		limit.addProperty("unlimited", false);
		limit.add("value", memory);
		settings.add("pendingMemoryMiB", limit);
		return true;
	}

	private static boolean isTrue(JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
	}

	/**
	 * Mirrors the typed Gson double reader for a present primitive: a JSON number
	 * uses its numeric value, while a string uses {@link Double#parseDouble}.
	 * Returns null when the value cannot be decoded as a double.
	 */
	private static Double decodedDouble(JsonElement value) {
		if (value == null || !value.isJsonPrimitive()) return null;
		var primitive = value.getAsJsonPrimitive();
		if (primitive.isNumber()) {
			try {
				return primitive.getAsDouble();
			} catch (RuntimeException failure) {
				return null;
			}
		}
		if (primitive.isString()) {
			try {
				return Double.parseDouble(primitive.getAsString());
			} catch (NumberFormatException failure) {
				return null;
			}
		}
		return null;
	}

	private static @Nullable String normalizeServerPresentationShape(JsonObject root) {
		JsonElement presentationElement = root.get("presentation");
		if (presentationElement == null) {
			return null;
		}

		if (!presentationElement.isJsonObject()) {
			// A malformed security-sensitive policy object must fail closed instead of
			// failing the whole config into a reset that allows every field. The new
			// deny state wins over any legacy shape because the whole object is replaced.
			root.add("presentation", failClosedPresentation());
			return "presentation: malformed policy object replaced with deny-all target types";
		}

		JsonObject presentation = presentationElement.getAsJsonObject();
		boolean changed = false;
		boolean globalDeny = false;

		JsonElement targetTypesElement = presentation.get("targetTypes");
		if (targetTypesElement != null) {
			if (targetTypesElement.isJsonObject()) {
				JsonObject normalized = normalizeTargetTypeRules(targetTypesElement.getAsJsonObject());
				if (!normalized.equals(targetTypesElement)) {
					presentation.add("targetTypes", normalized);
					changed = true;
				}
			} else {
				// The malformed new map is authoritative over the legacy flat keys: it is
				// replaced by an explicit deny-all map for every existing target type so a
				// same-version and an older-version load both stay fail closed.
				presentation.add("targetTypes", failClosedTargetTypes());
				changed = true;
			}
			changed |= removeKeys(presentation, LEGACY_FLAT_PRESENTATION_KEYS);
		} else if (presentation.has("white") || presentation.has("black") || presentation.has("whitelistOnly")) {
			presentation.add("targetTypes", validFlatRuleShape(presentation)
				? flatRuleCopies(presentation)
				: failClosedTargetTypes());
			removeKeys(presentation, LEGACY_FLAT_PRESENTATION_KEYS);
			changed = true;
		}

		JsonElement permissionElement = presentation.get("permissionLevels");
		if (permissionElement != null) {
			if (permissionElement.isJsonObject()) {
				JsonObject sanitized = sanitizeIntegralMap(permissionElement.getAsJsonObject());
				if (!sanitized.equals(permissionElement)) {
					presentation.add("permissionLevels", sanitized);
					changed = true;
					globalDeny = true;
				}
			} else {
				// A malformed global permission structure denies every target type
				// durably instead of reaching the reset-to-allow recovery path.
				presentation.remove("permissionLevels");
				changed = true;
				globalDeny = true;
			}
		}

		JsonElement intervalsElement = presentation.get("updateIntervals");
		if (intervalsElement != null) {
			if (intervalsElement.isJsonObject()) {
				JsonObject sanitized = sanitizeIntegralMap(intervalsElement.getAsJsonObject());
				if (!sanitized.equals(intervalsElement)) {
					presentation.add("updateIntervals", sanitized);
					changed = true;
				}
			} else {
				// Interval overrides are non-security: a malformed shape degrades to
				// "no overrides" through the existing skip/default validation.
				presentation.remove("updateIntervals");
				changed = true;
			}
		}

		changed |= removeNonIntegral(presentation, "minUpdateIntervalTicks");
		changed |= removeNonIntegral(presentation, "scanBudget");

		if (globalDeny) {
			presentation.add("targetTypes", failClosedTargetTypes());
			changed = true;
		}

		return changed ? "presentation: security-sensitive shapes normalized fail closed" : null;
	}

	private static JsonObject normalizeTargetTypeRules(JsonObject raw) {
		JsonObject normalized = new JsonObject();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			JsonElement entry = raw.get(id);
			if (entry == null || !entry.isJsonObject() || !validRuleShape(entry.getAsJsonObject())) {
				// A missing, non-object, or partially mistyped type rule fails closed
				// instead of silently becoming the allow-by-default rule shape.
				normalized.add(id, denyAllRule());
				continue;
			}
			normalized.add(id, entry.deepCopy());
		}
		return normalized;
	}

	private static JsonObject flatRuleCopies(JsonObject presentation) {
		JsonObject targetTypes = new JsonObject();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			JsonObject rules = new JsonObject();
			copyIfPresent(presentation, "white", rules);
			copyIfPresent(presentation, "black", rules);
			copyIfPresent(presentation, "whitelistOnly", rules);
			targetTypes.add(id, rules);
		}
		return targetTypes;
	}

	private static boolean validFlatRuleShape(JsonObject presentation) {
		return validStringArrayMember(presentation, "white")
			&& validStringArrayMember(presentation, "black")
			&& validBooleanMember(presentation, "whitelistOnly");
	}

	private static boolean validRuleShape(JsonObject rule) {
		return validStringArrayMember(rule, "white")
			&& validStringArrayMember(rule, "black")
			&& validBooleanMember(rule, "whitelistOnly")
			&& validChildBlackMember(rule);
	}

	/**
	 * A present {@code childBlack} member must be an array of addressable nested
	 * adapter/field/record-path references; a missing member keeps the model's
	 * semantic default. Any malformed, duplicate, empty-path, or over-capacity
	 * entry fails the whole rule shape closed, so the target type is replaced by
	 * the durable deny-all rule instead of silently discarding the security
	 * member and leaving the type allowed.
	 */
	private static boolean validChildBlackMember(JsonObject rule) {
		JsonElement value = rule.get("childBlack");
		if (value == null) return true;
		if (!value.isJsonArray()) return false;
		JsonArray array = value.getAsJsonArray();
		if (array.size() > PresentationSettings.MAX_CHILD_BLACK_REFS) return false;
		HashSet<PresentationPropertyRef> unique = new HashSet<>();
		for (JsonElement element : array) {
			PresentationPropertyRef ref = parseChildDenyRef(element);
			if (ref == null || ref.isRoot() || !unique.add(ref)) return false;
		}
		return true;
	}

	/** A well-formed reference object, or null when any part is mistyped or invalid. */
	private static PresentationPropertyRef parseChildDenyRef(JsonElement element) {
		if (element == null || !element.isJsonObject()) return null;
		JsonObject object = element.getAsJsonObject();
		JsonElement adapterId = object.get("adapterId");
		JsonElement fieldId = object.get("fieldId");
		JsonElement recordPath = object.get("recordPath");
		if (!isString(adapterId) || !isString(fieldId) || recordPath == null || !recordPath.isJsonArray()) return null;
		List<String> keys = new ArrayList<>();
		for (JsonElement key : recordPath.getAsJsonArray()) {
			if (!isString(key)) return null;
			keys.add(key.getAsString());
		}
		try {
			return new PresentationPropertyRef(adapterId.getAsString(), fieldId.getAsString(), keys);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}

	/** A present member must have the exact JSON type; a missing member keeps the documented default. */
	private static boolean validStringArrayMember(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null) return true;
		if (!value.isJsonArray()) return false;
		for (JsonElement element : value.getAsJsonArray()) {
			if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return false;
		}
		return true;
	}

	private static boolean validBooleanMember(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null) return true;
		return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
	}

	/** Keeps only entries whose value deserializes to an int without coercion or overflow. */
	private static JsonObject sanitizeIntegralMap(JsonObject raw) {
		JsonObject sanitized = new JsonObject();
		for (var entry : raw.entrySet()) {
			if (isIntegralNumber(entry.getValue())) sanitized.add(entry.getKey(), entry.getValue());
		}
		return sanitized;
	}

	private static boolean removeNonIntegral(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null) return false;
		if (isIntegralNumber(value)) return false;
		object.remove(key);
		return true;
	}

	private static boolean isIntegralNumber(@Nullable JsonElement element) {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return false;
		BigDecimal value;
		try {
			value = element.getAsBigDecimal();
		} catch (RuntimeException ex) {
			return false;
		}
		return value.stripTrailingZeros().scale() <= 0
			&& value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0
			&& value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
	}

	private static JsonObject failClosedPresentation() {
		JsonObject presentation = new JsonObject();
		presentation.add("targetTypes", failClosedTargetTypes());
		return presentation;
	}

	private static JsonObject failClosedTargetTypes() {
		JsonObject targetTypes = new JsonObject();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			targetTypes.add(id, denyAllRule());
		}
		return targetTypes;
	}

	private static JsonObject denyAllRule() {
		JsonObject rules = new JsonObject();
		rules.add("white", new JsonArray());
		JsonArray black = new JsonArray();
		black.add("*:*");
		rules.add("black", black);
		rules.addProperty("whitelistOnly", true);
		return rules;
	}

	private static void copyIfPresent(JsonObject source, String key, JsonObject target) {
		JsonElement value = source.get(key);
		if (value != null) {
			target.add(key, value.deepCopy());
		}
	}

	private static boolean removeKeys(JsonObject root, List<String> keys) {
		boolean removed = false;
		for (String key : keys) {
			removed |= root.remove(key) != null;
		}
		return removed;
	}

	private static List<String> appendedUpdate(List<String> updates, String update) {
		List<String> merged = new java.util.ArrayList<>(updates);
		merged.add(update);
		return List.copyOf(merged);
	}

	private void rejectFutureConfig(PingForItVersion configVersion) {
		config = createDefaultConfig();
		configHash = config.hashCode();
		saveProtection = SaveProtection.FUTURE_VERSION;
		Diagnostics.event(Level.WARN, "config/load", configType.getName(), "future-version",
			() -> "path=" + configPath + " configVersion=" + configVersion + " modVersion=" + modVersion
				+ "; defaults in memory; original file preserved");
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
			logFailure("migration-write", "configVersion=" + migration.configVersion() + " modVersion=" + modVersion
				+ "; valid loaded config retained; later save will retry", failure);
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
			logFailure("migration-source-read", "pending migration discarded; reloading disk state", failure);
			return discardStalePendingMigration("the config file could not be read");
		}

		if (!Arrays.equals(migration.sourceBytes(), currentBytes)) {
			return discardStalePendingMigration("the config file bytes changed on disk");
		}
		return true;
	}

	private boolean discardStalePendingMigration(String reason) {
		pendingMigration = null;
		Diagnostics.event(Level.DEBUG, "config/migration", configType.getName(), "source-changed",
			() -> "path=" + configPath + " reason=" + reason + "; reloading current disk state");
		load();
		return false;
	}

	private void logLoadedConfig() {
		if (pendingMigration == null) {
			LOGGER.info("Loaded config type=%s".formatted(configType.getSimpleName()));
			return;
		}

		Diagnostics.event(Level.DEBUG, "config/migration", configType.getName(), "write-pending",
			() -> "path=" + configPath + "; loaded config in memory; migration writeback pending");
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

	private BackupResult backupOriginal(byte @Nullable [] originalBytes) {
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
			} catch (IOException cleanupFailure) {
				Diagnostics.debug("config/backup-cleanup", configType.getName(), () -> "path=" + backupPath, cleanupFailure);
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

	private void logFailure(String operation, String context, @Nullable Throwable failure) {
		Diagnostics.warn("config/" + operation, configType.getName(), () -> "path=" + configPath + " " + context, failure);
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

	private record BackupResult(@Nullable Path path, @Nullable Exception failure) {}

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
