package nx.pingwheel.common.presentation;

import com.google.gson.Gson;
import nx.pingwheel.common.config.ServerConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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

	@Test
	void repeatedPolicyLookupReusesTheCompiledRuleSetPolicy() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(
			List.of("minecraft:*"), List.of("create:*"), false));

		var compiled = settings.policyFor("block");
		assertSame(compiled, settings.policyFor("block"), "an unchanged rule set keeps its compiled policy");
		assertTrue(compiled.allows("minecraft:basic", true));
		assertFalse(compiled.allows("create:gear", true));
		assertSame(settings.policyFor("unknown"), settings.policyFor("also-unknown"),
			"missing target types share the immutable deny-all policy");
	}

	@Test
	void ruleMutationsInvalidateTheCompiledPolicy() {
		var rules = new PresentationSettings.RuleSet(List.of(), List.of(), false);
		var open = rules.policy();
		assertTrue(open.allows("minecraft:basic", true));

		rules.setWhitelistOnly(true);
		var closed = rules.policy();
		assertNotSame(open, closed);
		assertFalse(closed.allows("minecraft:basic", true));

		rules.setWhite(List.of("minecraft:basic"));
		var narrowed = rules.policy();
		assertNotSame(closed, narrowed);
		assertTrue(narrowed.allows("minecraft:basic", true));
		assertFalse(narrowed.allows("minecraft:other", true));

		var ref = new PresentationPropertyRef(PresentationBasic.ID, PresentationBasic.NAME, List.of("child"));
		rules.setWhite(List.of());
		rules.setWhitelistOnly(false);
		assertTrue(rules.policy().propertyAllowed(ref, true));
		rules.setChildBlack(List.of(ref));
		assertFalse(rules.policy().propertyAllowed(ref, true));
	}

	@Test
	void failedCompilationDeniesAndRepairsOnANewRuleSet() {
		var broken = new PresentationSettings.RuleSet(List.of("bad selector"), List.of(), false);
		var denied = broken.policy();
		assertTrue(denied.whitelistOnly());
		assertFalse(denied.allows("minecraft:basic", true));

		broken.setWhite(List.of("minecraft:basic"));
		var repaired = broken.policy();
		assertFalse(repaired.whitelistOnly());
		assertTrue(repaired.allows("minecraft:basic", true));
	}

	@Test
	void compiledPolicyDoesNotChangeFingerprintOrSerializedConfiguration() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(
			List.of("minecraft:*"), List.of(), false));
		String fingerprint = settings.fingerprint();
		String serialized = gson.toJson(settings);

		assertSame(settings.policyFor("block"), settings.policyFor("block"));
		assertSame(settings.policyFor("unknown"), settings.policyFor("missing"));

		assertEquals(fingerprint, settings.fingerprint());
		assertEquals(serialized, gson.toJson(settings), "the compiled cache is never serialized");

		var reloaded = gson.fromJson(serialized, PresentationSettings.class);
		reloaded.validate();
		assertEquals(fingerprint, reloaded.fingerprint());
		assertEquals(settings.policyFor("block"), reloaded.policyFor("block"));
	}

	@Test
	void copyFromAndConfigReplacementKeepEachSettingsOwnCompiledPolicy() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(
			List.of("minecraft:basic"), List.of(), true));
		var original = settings.policyFor("entity");

		var replacement = PresentationSettings.serverDefaults();
		replacement.setRules("entity", PresentationSettings.RuleSet.denyAll());
		settings.copyFrom(replacement);
		var copied = settings.policyFor("entity");
		assertNotSame(original, copied);
		assertFalse(copied.allows("minecraft:basic", true));
		assertTrue(replacement.policyFor("entity").whitelistOnly());

		var config = new ServerConfig();
		var first = config.getPresentation();
		first.setRules("block", new PresentationSettings.RuleSet(
			List.of("minecraft:basic"), List.of(), true));
		var firstPolicy = first.policyFor("block");

		var candidate = first.deepCopy();
		candidate.setRules("block", PresentationSettings.RuleSet.denyAll());
		config.setPresentation(candidate);
		assertFalse(config.getPresentation().policyFor("block").allows("minecraft:basic", true));

		config.setPresentation(first);
		assertSame(firstPolicy, config.getPresentation().policyFor("block"),
			"rollback to the original settings keeps its own compiled policy");
		assertTrue(config.getPresentation().policyFor("block").allows("minecraft:basic", true));
	}
}
