package nx.pingwheel.common.config;

import com.google.gson.JsonObject;

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

	private static final List<MigrationStep> MIGRATION_STEPS = orderedSteps(List.of(
		new MigrationStep(
			SYNC_DURATION_INTRODUCED_VERSION,
			ServerConfig.class,
			ConfigVersionUpdater::migrateLegacyServerDuration,
			"syncDuration: pingDuration -> syncDuration")
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

	static MigrationResult update(JsonObject root, PingForItVersion oldVersion, PingForItVersion currentVersion) {
		return update(root, IConfig.class, oldVersion, currentVersion, MIGRATION_STEPS);
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
