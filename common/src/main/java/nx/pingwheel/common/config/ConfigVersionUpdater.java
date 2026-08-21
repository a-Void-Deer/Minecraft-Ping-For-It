package nx.pingwheel.common.config;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the ordered, common configuration-version update plan.
 *
 * <p>The plan intentionally has no field migrations yet. Keeping the marker
 * update here gives future migrations one explicit order and one failure
 * boundary without putting version fields in the configuration models.</p>
 */
final class ConfigVersionUpdater {
	static final String VERSION_KEY = "pingforit-version";

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

	static MigrationResult update(JsonObject root, PingForItVersion oldVersion, PingForItVersion currentVersion) {
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(oldVersion, "oldVersion");
		Objects.requireNonNull(currentVersion, "currentVersion");

		List<String> updates = new ArrayList<>();

		// Keep future field migrations here in their explicit execution order.
		// There are currently no field migrations: the unmarked 0.1.0 format is
		// intentionally handled as damaged rather than given a special migration.
		root.addProperty(VERSION_KEY, currentVersion.originalVersion());
		updates.add(VERSION_KEY + ": " + oldVersion + " -> " + currentVersion);

		return new MigrationResult(root, List.copyOf(updates));
	}

	record MigrationResult(JsonObject root, List<String> updates) {}
}
