package nx.pingwheel.common.config;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Applies the ordered raw-JSON configuration-version update plan. */
final class ConfigVersionUpdater {
	static final String VERSION_KEY = "pingforit-version";
	private static final String LEGACY_SYNC_DURATION_KEY = "pingDuration";
	private static final String SYNC_DURATION_KEY = "syncDuration";
	private static final PingForItVersion SYNC_DURATION_INTRODUCED_VERSION =
		PingForItVersion.parse("0.3.0-pfi-beta1");
	static final PingForItVersion SPATIAL_SELECTOR_INTRODUCED_VERSION =
		PingForItVersion.parse("0.5.0-pfi-beta1");
	static final PingForItVersion WHEEL_TIMEOUT_REMOVED_VERSION =
		SPATIAL_SELECTOR_INTRODUCED_VERSION;
	static final PingForItVersion MARK_TRANSPARENT_BLOCKS_INTRODUCED_VERSION =
		SPATIAL_SELECTOR_INTRODUCED_VERSION;
	private static final String LEGACY_TRANSPARENT_BLOCKS_KEY = "passThroughTransparentBlocks";
	private static final String MARK_TRANSPARENT_BLOCKS_KEY = "markTransparentBlocks";
	private static final String DOUBLE_CLICK_DANGER_WINDOW_KEY = "doubleClickDangerWindowMs";
	private static final int LEGACY_DOUBLE_CLICK_DANGER_WINDOW_MS = 100;
	private static final int MIGRATED_DOUBLE_CLICK_DANGER_WINDOW_MS = 160;
	private static final PingForItVersion DOUBLE_CLICK_DANGER_WINDOW_REVISED_VERSION =
		PingForItVersion.parse("0.5.1-pfi-beta2");
	private static final PingForItVersion DOUBLE_CLICK_DANGER_WINDOW_LEGACY_MAX_VERSION =
		PingForItVersion.parse("0.5.1-pfi-beta1");

	private static final List<MigrationStep> MIGRATION_STEPS = orderedSteps(List.of(
		new MigrationStep(
			SYNC_DURATION_INTRODUCED_VERSION,
			ServerConfig.class,
			ConfigVersionUpdater::migrateLegacyServerDuration,
			"syncDuration: pingDuration -> syncDuration"),
		new MigrationStep(
			SPATIAL_SELECTOR_INTRODUCED_VERSION,
			ClientConfig.class,
			ConfigVersionUpdater::migrateClientBeta1,
			"client: retired wheel keys removed; transparent block selection renamed and inverted"),
		new MigrationStep(
			DOUBLE_CLICK_DANGER_WINDOW_REVISED_VERSION,
			ClientConfig.class,
			ConfigVersionUpdater::migrateClientBeta2,
			"client: doubleClickDangerWindowMs legacy default 100 -> 160")
	));

	private ConfigVersionUpdater() {}

	static String requireVersion(JsonObject root) {
		if (root == null) {
			throw new IllegalArgumentException("config JSON root is not an object");
		}

		var marker = root.get(VERSION_KEY);
		if (marker == null
			|| marker.isJsonNull()
			|| !marker.isJsonPrimitive()
			|| !marker.getAsJsonPrimitive().isString()
			|| marker.getAsString().isBlank()) {
			throw new IllegalArgumentException("config version marker is missing or not a non-empty string");
		}

		return marker.getAsString();
	}

	static MigrationResult update(
		JsonObject root,
		Class<? extends IConfig> configType,
		PingForItVersion oldVersion,
		PingForItVersion currentVersion) {
		return update(root, configType, oldVersion, currentVersion, MIGRATION_STEPS);
	}

	static MigrationResult update(
		JsonObject root,
		Class<? extends IConfig> configType,
		PingForItVersion oldVersion,
		PingForItVersion currentVersion,
		List<MigrationStep> steps) {
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(configType, "configType");
		Objects.requireNonNull(oldVersion, "oldVersion");
		Objects.requireNonNull(currentVersion, "currentVersion");
		Objects.requireNonNull(steps, "steps");
		validateStepOrdering(steps);

		JsonObject migratedRoot = root.deepCopy();
		List<String> updates = new ArrayList<>();

		for (MigrationStep step : steps) {
			if (step.configType().equals(configType)
				&& oldVersion.compareTo(step.targetVersion()) < 0
				&& step.targetVersion().compareTo(currentVersion) <= 0) {
				step.action().accept(migratedRoot);
				updates.add(step.description());
			}
		}

		// The marker is deliberately stamped after every schema transformation.
		migratedRoot.addProperty(VERSION_KEY, currentVersion.originalVersion());
		updates.add(VERSION_KEY + ": " + oldVersion + " -> " + currentVersion);

		return new MigrationResult(migratedRoot, List.copyOf(updates));
	}

	private static void migrateLegacyServerDuration(JsonObject root) {
		if (!root.has(SYNC_DURATION_KEY) && root.has(LEGACY_SYNC_DURATION_KEY)) {
			root.add(SYNC_DURATION_KEY, root.get(LEGACY_SYNC_DURATION_KEY).deepCopy());
		}

		root.remove(LEGACY_SYNC_DURATION_KEY);
	}

	static void removeLegacyWheelRadii(JsonObject root) {
		root.remove("wheelInnerRadius");
		root.remove("wheelOuterRadius");
		// New preferences are initialized by the model, not copied or injected here.
	}

	static void removeRetiredWheelKeys(JsonObject root) {
		removeLegacyWheelRadii(root);
		removeLegacyWheelTimeout(root);
	}

	private static void migrateClientBeta1(JsonObject root) {
		removeRetiredWheelKeys(root);
		if (!root.has(MARK_TRANSPARENT_BLOCKS_KEY)) {
			var legacy = root.get(LEGACY_TRANSPARENT_BLOCKS_KEY);
			if (legacy == null || legacy.isJsonNull()) {
				// Missing/null used the old false default, which selected OUTLINE.
				root.addProperty(MARK_TRANSPARENT_BLOCKS_KEY, true);
			} else if (legacy.isJsonPrimitive()
				&& (legacy.getAsJsonPrimitive().isBoolean() || legacy.getAsJsonPrimitive().isString())) {
				// Match Gson's boolean adapter: strings use Boolean.parseBoolean.
				root.addProperty(MARK_TRANSPARENT_BLOCKS_KEY, !legacy.getAsBoolean());
			} else {
				// Keep malformed types malformed for the existing typed load/recovery
				// path; getAsBoolean would silently coerce a JSON number to false.
				root.add(MARK_TRANSPARENT_BLOCKS_KEY, legacy.deepCopy());
			}
		}
		removeLegacyTransparentBlockToggle(root);
	}

	/**
	 * The revised default applies only to documents at or before the beta1
	 * boundary. The marker remains the source version until every step has run.
	 */
	private static void migrateClientBeta2(JsonObject root) {
		if (!isLegacyDoubleClickDangerWindowSourceVersion(root)) return;

		var window = root.get(DOUBLE_CLICK_DANGER_WINDOW_KEY);
		if (window == null
			|| !window.isJsonPrimitive()
			|| !window.getAsJsonPrimitive().isNumber()) {
			return;
		}

		try {
			// Compare the exact numeric value rather than truncating through
			// getAsInt, so a value such as 100.5 is preserved.
			if (window.getAsBigDecimal().compareTo(BigDecimal.valueOf(LEGACY_DOUBLE_CLICK_DANGER_WINDOW_MS)) == 0) {
				root.addProperty(DOUBLE_CLICK_DANGER_WINDOW_KEY, MIGRATED_DOUBLE_CLICK_DANGER_WINDOW_MS);
			}
		} catch (RuntimeException ignored) {
			// Leave a malformed numeric representation for typed deserialization
			// and the existing invalid-file recovery path.
		}
	}

	private static boolean isLegacyDoubleClickDangerWindowSourceVersion(JsonObject root) {
		var marker = root.get(VERSION_KEY);
		if (marker == null
			|| !marker.isJsonPrimitive()
			|| !marker.getAsJsonPrimitive().isString()) {
			return false;
		}

		try {
			return PingForItVersion.parse(marker.getAsString())
				.compareTo(DOUBLE_CLICK_DANGER_WINDOW_LEGACY_MAX_VERSION) <= 0;
		} catch (PingForItVersion.InvalidVersionException invalidVersion) {
			return false;
		}
	}

	static void removeLegacyTransparentBlockToggle(JsonObject root) {
		root.remove(LEGACY_TRANSPARENT_BLOCKS_KEY);
	}

	static void removeLegacyWheelTimeout(JsonObject root) {
		root.remove("wheelTimeoutMillis");
	}

	private static List<MigrationStep> orderedSteps(List<MigrationStep> steps) {
		validateStepOrdering(steps);
		return List.copyOf(steps);
	}

	private static void validateStepOrdering(List<MigrationStep> steps) {
		PingForItVersion previousTarget = null;
		for (MigrationStep step : steps) {
			Objects.requireNonNull(step, "migration step");
			if (previousTarget != null && previousTarget.compareTo(step.targetVersion()) >= 0) {
				throw new IllegalStateException("configuration migration steps are not strictly ordered by target version");
			}
			previousTarget = step.targetVersion();
		}
	}

	record MigrationStep(
		PingForItVersion targetVersion,
		Class<? extends IConfig> configType,
		Consumer<JsonObject> action,
		String description) {
		MigrationStep {
			Objects.requireNonNull(targetVersion, "targetVersion");
			Objects.requireNonNull(configType, "configType");
			Objects.requireNonNull(action, "action");
			if (description == null || description.isBlank()) {
				throw new IllegalArgumentException("migration description must be non-empty");
			}
		}
	}

	record MigrationResult(JsonObject root, List<String> updates) {}
}
