package nx.pingwheel.common.presentation;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationSettingsPolicyTest {
	private final Gson gson = new Gson();

	@Test
	void eachTargetTypeHasAnIndependentPolicyAndMissingFailsClosed() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(List.of("minecraft:basic"), List.of("create:*"), true));

		assertTrue(settings.policyFor("block").allows("minecraft:basic", true));
		assertFalse(settings.policyFor("block").allows("create:gear", true));
		assertTrue(settings.policyFor("entity").allows("create:gear", true));

		var sparse = new PresentationSettings();
		sparse.validate();
		assertTrue(sparse.policyFor("entity").whitelistOnly());
		assertEquals(List.of("*:*"), sparse.policyFor("entity").black());
		assertTrue(sparse.policyFor("unknown").whitelistOnly());
		assertTrue(sparse.rulesFor("unknown").isWhitelistOnly());
	}

	@Test
	void mutationSelectedRulesNeverLeakIntoAnotherTargetType() {
		var settings = PresentationSettings.serverDefaults();

		var denied = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "entity",
			ServerPresentationPolicyService.Operation.ADD_BLACK, "minecraft:basic", false);

		assertTrue(denied.applied());
		assertFalse(settings.policyFor("entity").allows("minecraft:basic", true));
		assertTrue(settings.policyFor("dropped_item").allows("minecraft:basic", true));
		assertEquals(List.of(), settings.rulesFor("dropped_item").getBlack());

		var copied = settings.rulesFor("entity");
		copied.setWhite(List.of("create:*"));
		assertFalse(settings.policyFor("entity").white().contains("create:*"));
	}

	@Test
	void invalidSelectorsDenyOnlyTheirOwnTargetType() {
		var settings = PresentationSettings.serverDefaults();
		var broken = settings.rulesFor("block");
		broken.setBlack(List.of("bad selector"));
		broken.validate();
		settings.setRules("block", broken);

		assertTrue(settings.policyFor("block").whitelistOnly());
		assertFalse(settings.policyFor("block").allows("minecraft:basic", true));
		assertTrue(settings.policyFor("entity").allows("minecraft:basic", true));
	}

	@Test
	void deepCopyGsonRoundTripAndFingerprintStayIndependent() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("location", new PresentationSettings.RuleSet(
			List.of("minecraft:target.name"), List.of(), true));
		settings.setPermissionLevels(Map.of("minecraft:basic", 2));
		String fingerprint = settings.fingerprint();

		var copy = settings.deepCopy();
		assertEquals(fingerprint, copy.fingerprint());

		var reloaded = gson.fromJson(gson.toJson(settings), PresentationSettings.class);
		reloaded.validate();
		assertEquals(fingerprint, reloaded.fingerprint());

		copy.setRules("location", PresentationSettings.RuleSet.denyAll());
		assertNotEquals(fingerprint, copy.fingerprint());
		assertEquals(fingerprint, settings.fingerprint());
	}

	@Test
	void globalInvalidPolicyStateSurvivesGsonRoundTripAsDurableDenyAll() {
		var settings = PresentationSettings.serverDefaults();
		settings.setPermissionLevels(Map.of("minecraft:basic", 2));
		assertTrue(settings.policyFor("entity").allows("minecraft:basic", true));

		Map<String, Integer> overCapacity = new LinkedHashMap<>();
		for (int i = 0; i <= ServerPresentationPolicyService.MAX_SELECTORS; i++) {
			overCapacity.put("create:p" + i, 0);
		}
		settings.setPermissionLevels(overCapacity);

		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			assertFalse(settings.policyFor(id).allows("minecraft:basic", true));
		}

		var reloaded = new Gson().fromJson(new Gson().toJson(settings), PresentationSettings.class);
		reloaded.validate();
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			assertTrue(reloaded.policyFor(id).whitelistOnly());
			assertFalse(reloaded.policyFor(id).allows("minecraft:basic", true));
		}
	}

	@Test
	void legitimateOverridesAndScopedPolicyProblemsDoNotDenyEveryTargetType() {
		var settings = PresentationSettings.serverDefaults();
		settings.setPermissionLevels(Map.of("minecraft:basic", 2));
		settings.setUpdateIntervals(Map.of("bad id", 5)); // invalid intervals are skipped, never a global denial

		assertEquals(2, settings.permission("minecraft:basic", 0));
		for (String id : PresentationSettings.TARGET_TYPE_IDS) {
			assertFalse(settings.policyFor(id).whitelistOnly());
			assertTrue(settings.policyFor(id).allows("minecraft:basic", true));
		}
	}
}
