package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigVersionUpdaterTest {
	private static final PingForItVersion VERSION_0_1 = version("0.1.0-pfi-beta1");
	private static final PingForItVersion VERSION_0_2 = version("0.2.0-pfi-beta1");
	private static final PingForItVersion VERSION_0_3 = version("0.3.0-pfi-beta1");
	private static final PingForItVersion VERSION_0_4 = version("0.4.0-pfi-beta1");

	@Test
	void directRealMigrationDeepCopiesMovesLegacyDurationPreservesUnknownDataAndStampsCurrentMarker() {
		JsonObject source = json(
			"{\"pingforit-version\":\"0.2.0-pfi-beta1\",\"pingDuration\":23,"
				+ "\"unknown\":{\"value\":true}}");

		ConfigVersionUpdater.MigrationResult result = ConfigVersionUpdater.update(
			source,
			ServerConfig.class,
			VERSION_0_2,
			VERSION_0_4);

		assertEquals(23, result.root().get("syncDuration").getAsInt());
		assertFalse(result.root().has("pingDuration"));
		assertTrue(result.root().get("unknown").getAsJsonObject().get("value").getAsBoolean());
		assertEquals(VERSION_0_4.originalVersion(), result.root().get(ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertTrue(result.updates().contains("syncDuration: pingDuration -> syncDuration"));
		assertTrue(source.has("pingDuration"));
		assertFalse(source.has("syncDuration"));
		assertEquals(VERSION_0_2.originalVersion(), source.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
	}

	@Test
	void bothDurationKeysUseCurrentValueAndAlwaysRemoveTheConsumedLegacyKey() {
		JsonObject source = json(
			"{\"pingforit-version\":\"0.2.0-pfi-beta1\",\"syncDuration\":41,\"pingDuration\":23}");

		JsonObject migrated = ConfigVersionUpdater.update(
			source,
			ServerConfig.class,
			VERSION_0_2,
			VERSION_0_4).root();

		assertEquals(41, migrated.get("syncDuration").getAsInt());
		assertFalse(migrated.has("pingDuration"));
	}

	@Test
	void aDocumentAfterTheIntroductionVersionDoesNotConsumeAReusedKey() {
		JsonObject source = json(
			"{\"pingforit-version\":\"0.3.0-pfi-beta1\",\"pingDuration\":23}");

		JsonObject migrated = ConfigVersionUpdater.update(
			source,
			ServerConfig.class,
			VERSION_0_3,
			VERSION_0_4).root();

		assertEquals(23, migrated.get("pingDuration").getAsInt());
		assertFalse(migrated.has("syncDuration"));
		assertEquals(VERSION_0_4.originalVersion(), migrated.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
	}

	@Test
	void syntheticStepsRunInStrictTargetOrderAcrossASkippedVersionRange() {
		List<ConfigVersionUpdater.MigrationStep> steps = List.of(
			step("0.2.0-pfi-beta1", "02"),
			step("0.3.0-pfi-beta1", "03"),
			step("0.4.0-pfi-beta1", "04"));

		JsonObject directSkip = json("{\"pingforit-version\":\"0.1.0-pfi-beta1\",\"order\":\"start\"}");
		JsonObject directResult = ConfigVersionUpdater.update(
			directSkip,
			ClientConfig.class,
			VERSION_0_1,
			VERSION_0_4,
			steps).root();
		assertEquals("start-02-03-04", directResult.get("order").getAsString());

		JsonObject afterIntermediate = json("{\"pingforit-version\":\"0.3.0-pfi-beta1\",\"order\":\"start\"}");
		JsonObject laterResult = ConfigVersionUpdater.update(
			afterIntermediate,
			ClientConfig.class,
			VERSION_0_3,
			VERSION_0_4,
			steps).root();
		assertEquals("start-04", laterResult.get("order").getAsString());
	}

	@Test
	void theMarkerIsStillOldWhileAStepRunsAndIsStampedOnlyAfterAllSteps() {
		ConfigVersionUpdater.MigrationStep step = new ConfigVersionUpdater.MigrationStep(
			VERSION_0_2,
			ClientConfig.class,
			root -> {
				assertEquals(VERSION_0_1.originalVersion(), root.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
				root.remove("consumed");
			},
			"remove consumed");

		JsonObject migrated = ConfigVersionUpdater.update(
			json("{\"pingforit-version\":\"0.1.0-pfi-beta1\",\"consumed\":true}"),
			ClientConfig.class,
			VERSION_0_1,
			VERSION_0_4,
			List.of(step)).root();

		assertFalse(migrated.has("consumed"));
		assertEquals(VERSION_0_4.originalVersion(), migrated.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
	}

	@Test
	void migrationStepOrderingIsValidatedBeforeExecution() {
		List<ConfigVersionUpdater.MigrationStep> unordered = List.of(
			step("0.3.0-pfi-beta1", "03"),
			step("0.2.0-pfi-beta1", "02"));

		assertThrows(
			IllegalStateException.class,
			() -> ConfigVersionUpdater.update(
				json("{\"pingforit-version\":\"0.1.0-pfi-beta1\"}"),
				ClientConfig.class,
				VERSION_0_1,
				VERSION_0_4,
				unordered));
	}

	private static ConfigVersionUpdater.MigrationStep step(String target, String suffix) {
		return new ConfigVersionUpdater.MigrationStep(
			version(target),
			ClientConfig.class,
			root -> root.addProperty("order", root.get("order").getAsString() + "-" + suffix),
			"append " + suffix);
	}

	private static PingForItVersion version(String value) {
		return PingForItVersion.parse(value);
	}

	private static JsonObject json(String value) {
		return JsonParser.parseString(value).getAsJsonObject();
	}
}
