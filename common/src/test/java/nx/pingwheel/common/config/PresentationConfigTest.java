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
		ServerConfig server = gson.fromJson("{\"rateLimit\":5}", ServerConfig.class);
		server.validate();

		assertTrue(server.getPresentation().policyFor("entity").allows("create:kinetic/speed", true));
		assertFalse(server.getPresentation().policyFor("entity").allows("create:inventory", false));
		assertTrue(gson.toJson(server).contains("\"targetTypes\""));
	}

	@Test
	void partialNestedObjectsDoNotSilentlyElevateAllowLists() {
		ServerConfig server = gson.fromJson("{\"presentation\":{\"scanBudget\":4}}", ServerConfig.class);
		server.validate();

		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			assertFalse(server.getPresentation().policyFor(type).allows("create:inventory", true));
		}
	}

	@Test
	void independentTargetTypePoliciesSurviveJsonRoundTrip() {
		ServerConfig server = new ServerConfig();
		server.getPresentation().setRules("dropped_item",
			new PresentationSettings.RuleSet(List.of("minecraft:item.id"), List.of(), true));
		server.getPresentation().setRules("block",
			new PresentationSettings.RuleSet(List.of(), List.of("create:*"), false));

		ServerConfig reloaded = gson.fromJson(gson.toJson(server), ServerConfig.class);
		reloaded.validate();

		assertTrue(reloaded.getPresentation().policyFor("dropped_item").allows("minecraft:item.id", false));
		assertFalse(reloaded.getPresentation().policyFor("dropped_item").allows("minecraft:block.state", true));
		assertFalse(reloaded.getPresentation().policyFor("block").allows("create:gear", true));
		assertTrue(reloaded.getPresentation().policyFor("block").allows("minecraft:basic", true));
		assertTrue(reloaded.getPresentation().policyFor("entity").allows("minecraft:basic", true));
	}

	@Test
	void serverOverridesAreBoundedAndUnknownSelectorsNeverGrantAccess() {
		// The scoped contract needs an explicitly configured sibling type: a missing
		// target type now fails closed by design, so it cannot prove scoping.
		ServerConfig server = gson.fromJson("{\"presentation\":{\"targetTypes\":{"
			+ "\"entity\":{\"white\":[],\"black\":[],\"whitelistOnly\":false},"
			+ "\"block\":{\"white\":[\"create:inventory\"],\"black\":[\"bad selector\"],\"whitelistOnly\":false}},"
			+ "\"scanBudget\":999999,\"minUpdateIntervalTicks\":0,"
			+ "\"permissionLevels\":{\"create:inventory\":99}}}", ServerConfig.class);
		server.validate();

		assertFalse(server.getPresentation().policyFor("block").allows("create:inventory", true));
		assertTrue(server.getPresentation().policyFor("entity").allows("minecraft:basic", true));
		assertEquals(4, server.getPresentation().permission("create:inventory", 0));

		ServerConfig reloaded = gson.fromJson(gson.toJson(server), ServerConfig.class);
		reloaded.validate();
		assertFalse(reloaded.getPresentation().policyFor("block").allows("create:inventory", true));
		assertTrue(reloaded.getPresentation().policyFor("entity").allows("minecraft:basic", true));
	}

	@Test
	void whitelistOnlyAndArbitraryPositionWildcardsAreIndependentOfPermissions() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(
			List.of("cre*te:kin*/speed"), List.of("create:kinetic/*"), true));

		assertTrue(settings.policyFor("block").allows("create:kinetic/speed", false));
		assertFalse(settings.policyFor("block").allows("create:kinetic/stress", true));
		assertFalse(settings.policyFor("block").allows("minecraft:health", true));
	}

	@Test
	void samplingLimitsAndFingerprintsReflectEffectiveChanges() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		String before = settings.fingerprint();
		settings.setPermissionLevels(Map.of("create:inventory", 2));
		settings.setUpdateIntervals(Map.of("create:kinetic", 12));

		assertNotEquals(before, settings.fingerprint());
		assertEquals(settings.fingerprint(),
			gson.fromJson(gson.toJson(settings), PresentationSettings.class).fingerprint());
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
