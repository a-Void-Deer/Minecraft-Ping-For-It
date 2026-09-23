package nx.pingwheel.common.config;

import com.google.gson.Gson;
import nx.pingwheel.common.presentation.PresentationAuthorization;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationConfigTest {
	private final Gson gson = new Gson();

	@AfterEach
	void restoreAuthorization() {
		PresentationAuthorization.setProvider(null);
	}

	@Test
	void missingKeysDoNotRequireASchemaMigration() {
		ClientConfig client = gson.fromJson("{\"pingVolume\":42}", ClientConfig.class);
		ServerConfig server = gson.fromJson("{\"rateLimit\":5}", ServerConfig.class);
		client.validate((key, supplied, effective) -> {});
		server.validate();

		assertTrue(client.getPresentationReceive().policy().allows("create:inventory", true));
		assertTrue(client.getPresentationDisplay().policy().allows("create:inventory", true));
		assertTrue(server.getPresentation().policy().allows("create:kinetic/speed", true));
		assertFalse(server.getPresentation().policy().allows("create:inventory", false));
		assertTrue(gson.toJson(client).contains("\"presentationReceive\""));
		assertTrue(gson.toJson(server).contains("\"presentation\""));
	}

	@Test
	void partialNestedObjectsDoNotSilentlyElevateAllowLists() {
		ClientConfig client = gson.fromJson("{\"presentationReceive\":{\"scanBudget\":4}}", ClientConfig.class);
		ServerConfig server = gson.fromJson("{\"presentation\":{\"scanBudget\":4}}", ServerConfig.class);
		client.validate((key, supplied, effective) -> {});
		server.validate();
		assertFalse(client.getPresentationReceive().policy().allows("create:inventory", false));
		assertFalse(server.getPresentation().policy().allows("create:inventory", false));
	}

	@Test
	void independentLocalListsAndWhiteOverBlackSurviveJsonRoundTrip() {
		ClientConfig client = new ClientConfig();
		client.getPresentationReceive().setWhite(List.of("create:kinetic/*"));
		client.getPresentationReceive().setBlack(List.of("create:kinetic/speed", "create:inventory"));
		client.getPresentationDisplay().setWhite(List.of());
		client.getPresentationDisplay().setWhitelistOnly(true);

		ClientConfig reloaded = gson.fromJson(gson.toJson(client), ClientConfig.class);
		reloaded.validate((key, supplied, effective) -> {});

		assertTrue(reloaded.getPresentationReceive().policy().allows("create:kinetic/speed", false));
		assertFalse(reloaded.getPresentationReceive().policy().allows("create:inventory", true));
		assertFalse(reloaded.getPresentationDisplay().policy().allows("create:kinetic/speed", true));
	}

	@Test
	void serverOverridesAreBoundedAndUnknownSelectorsNeverGrantAccess() {
		ServerConfig server = gson.fromJson("{\"presentation\":{\"white\":[\"create:inventory\"],"
			+ "\"black\":[\"bad selector\"],\"scanBudget\":999999,"
			+ "\"minUpdateIntervalTicks\":0,\"permissionLevels\":{\"create:inventory\":99}}}",
			ServerConfig.class);
		server.validate();

		assertFalse(server.getPresentation().policy().allows("create:inventory", true));
		assertEquals(0, server.getPresentation().scanBudget());
		assertEquals(5, server.getPresentation().permission("create:inventory", 0));
		ServerConfig reloaded = gson.fromJson(gson.toJson(server), ServerConfig.class);
		reloaded.validate();
		assertFalse(reloaded.getPresentation().policy().allows("create:inventory", true));
	}

	@Test
	void whitelistOnlyAndArbitraryPositionWildcardsAreIndependentOfPermissions() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhitelistOnly(true);
		settings.setBlack(List.of("create:kinetic/*"));
		settings.setWhite(List.of("cre*te:kin*/speed"));
		assertTrue(settings.policy().allows("create:kinetic/speed", false));
		assertFalse(settings.policy().allows("create:kinetic/stress", true));
		assertFalse(settings.policy().allows("minecraft:health", true));
	}

	@Test
	void samplingLimitsAndFingerprintsReflectEffectiveChanges() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		String before = settings.fingerprint();
		settings.setPermissionLevels(Map.of("create:inventory", 2));
		settings.setUpdateIntervals(Map.of("create:kinetic", 12));
		assertNotEquals(before, settings.fingerprint());
		assertEquals(settings.fingerprint(), gson.fromJson(gson.toJson(settings), PresentationSettings.class).fingerprint());
		assertEquals(2, settings.permission("create:inventory", 0));
		assertEquals(3, settings.permission("create:kinetic/speed", 3));
		assertEquals(15, settings.interval("create:kinetic", 15));
		assertEquals(12, settings.interval("create:kinetic", 1));
		settings.setScanBudget(-10);
		assertEquals(0, settings.scanBudget());
	}

	@Test
	void replaceablePermissionProviderDefaultsToVanillaAndFailsClosedOnException() {
		UUID recipient = UUID.randomUUID();
		PresentationField field = new PresentationField("create:inventory", PresentationField.Kind.NUMBER, false, 2, "Inventory");
		assertFalse(PresentationAuthorization.canSee(recipient, field, 1, 2));
		assertTrue(PresentationAuthorization.canSee(recipient, field, 2, 2));
		PresentationAuthorization.setProvider((id, candidate, actual, required) -> true);
		assertTrue(PresentationAuthorization.canSee(recipient, field, 0, 2));
		PresentationAuthorization.setProvider((id, candidate, actual, required) -> { throw new IllegalStateException(); });
		assertFalse(PresentationAuthorization.canSee(recipient, field, 4, 2));
	}
}
