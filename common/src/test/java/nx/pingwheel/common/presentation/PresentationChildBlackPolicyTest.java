package nx.pingwheel.common.presentation;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted child deny list: exact case-sensitive adapter/field/record-path
 * matching, semantic defaults that deny only the nested Create kinetic RPM
 * entries, an explicit empty list as opt-out, and target-scoped durable denial
 * for a malformed list.
 */
class PresentationChildBlackPolicyTest {
	private static final String CREATE = "create:presentation";
	private static final String SPEED = "create:kinetic.speed";

	private static PresentationPropertyRef ref(String adapter, String field, String... path) {
		return new PresentationPropertyRef(adapter, field, List.of(path));
	}

	private static final PresentationPropertyRef EFFECTIVE_RPM = ref(CREATE, SPEED, "effective_rpm");
	private static final PresentationPropertyRef THEORETICAL_RPM = ref(CREATE, SPEED, "theoretical_rpm");
	private static final PresentationPropertyRef MOVING = ref(CREATE, SPEED, "moving");
	private static final PresentationPropertyRef SPEED_ROOT = PresentationPropertyRef.root(CREATE, SPEED);

	private static List<PresentationPropertyRef> overCapacity() {
		List<PresentationPropertyRef> refs = new ArrayList<>();
		for (int i = 0; i <= PresentationSettings.MAX_CHILD_BLACK_REFS; i++) {
			refs.add(ref(CREATE, SPEED, "k" + i));
		}
		return refs;
	}

	static Stream<Arguments> malformedChildBlackLists() {
		List<PresentationPropertyRef> withNull = new ArrayList<>(List.of(EFFECTIVE_RPM));
		withNull.add(null);
		return Stream.of(
			Arguments.of("duplicate reference", List.of(EFFECTIVE_RPM, EFFECTIVE_RPM)),
			Arguments.of("root reference", List.of(SPEED_ROOT)),
			Arguments.of("over capacity", overCapacity()),
			Arguments.of("null element", withNull));
	}

	@Test
	void everyFreshTargetTypeDeniesOnlyTheTwoNestedRpmEntriesByDefault() {
		var settings = PresentationSettings.serverDefaults();

		for (String type : PresentationSettings.TARGET_TYPE_IDS) {
			var rules = settings.rulesFor(type);
			assertEquals(2, rules.getChildBlack().size(), type);
			assertTrue(rules.getChildBlack().containsAll(List.of(EFFECTIVE_RPM, THEORETICAL_RPM)), type);
			assertTrue(rules.childDenied(EFFECTIVE_RPM), type);
			assertTrue(rules.childDenied(THEORETICAL_RPM), type);
			assertFalse(rules.childDenied(SPEED_ROOT), type);
			assertFalse(rules.childDenied(MOVING), type);

			var policy = settings.policyFor(type);
			assertFalse(policy.propertyAllowed(EFFECTIVE_RPM), type);
			assertFalse(policy.propertyAllowed(EFFECTIVE_RPM, true), type);
			assertTrue(policy.propertyAllowed(SPEED_ROOT, true), type);
			assertTrue(policy.propertyAllowed(MOVING, true), type);
		}
	}

	@Test
	void childDenyMatchesOnlyTheExactCaseSensitiveTupleWithoutDescendants() {
		var policy = new PresentationPolicy(List.of("create:*"), List.of(), false, List.of(EFFECTIVE_RPM));

		assertTrue(policy.childDenied(EFFECTIVE_RPM));
		assertFalse(policy.childDenied(ref(CREATE, SPEED, "Effective_RPM")));
		assertFalse(policy.childDenied(ref(CREATE, SPEED, "effective_rpm", "nested")));
		assertFalse(policy.childDenied(ref(CREATE, SPEED, "theoretical_rpm")));
		assertFalse(policy.childDenied(ref(CREATE, "create:kinetic.stress", "effective_rpm")));
		assertFalse(policy.childDenied(ref("create:other", SPEED, "effective_rpm")));
		assertFalse(policy.childDenied(SPEED_ROOT));

		// An allow selector matches the field but never overrides the child deny.
		assertTrue(policy.allows(SPEED, false));
		assertFalse(policy.propertyAllowed(EFFECTIVE_RPM, false));
		assertTrue(policy.propertyAllowed(SPEED_ROOT, false));
	}

	@Test
	void explicitEmptyChildBlackOptsOutAndThreeArgRulesKeepTheSemanticDefault() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of(), List.of(), false, List.of()));

		var optedOut = settings.policyFor("entity");
		assertFalse(optedOut.childDenied(EFFECTIVE_RPM));
		assertTrue(optedOut.propertyAllowed(EFFECTIVE_RPM, true));
		assertTrue(settings.rulesFor("entity").getChildBlack().isEmpty());
		assertTrue(settings.policyFor("block").childDenied(EFFECTIVE_RPM));

		var semanticDefault = new PresentationSettings.RuleSet(List.of("minecraft:basic"), List.of(), false);
		assertTrue(semanticDefault.childDenied(EFFECTIVE_RPM));
		assertTrue(semanticDefault.childDenied(THEORETICAL_RPM));
		assertTrue(PresentationSettings.RuleSet.denyAll().childDenied(EFFECTIVE_RPM));
	}

	@Test
	void childReferencesSurviveCopyFingerprintAndJsonRoundTrip() {
		var custom = ref(CREATE, "create:inventory.summary", "minecraft:cobblestone");
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(List.of(), List.of(), false, List.of(custom)));
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of(), List.of(), false, List.of()));
		var fingerprint = settings.fingerprint();

		var copy = settings.deepCopy();
		assertEquals(fingerprint, copy.fingerprint());
		assertTrue(copy.rulesFor("block").childDenied(custom));
		assertTrue(copy.rulesFor("entity").getChildBlack().isEmpty());

		var reloaded = new Gson().fromJson(new Gson().toJson(settings), PresentationSettings.class);
		reloaded.validate();
		assertEquals(fingerprint, reloaded.fingerprint());
		assertTrue(reloaded.rulesFor("block").childDenied(custom));
		assertTrue(reloaded.rulesFor("entity").getChildBlack().isEmpty());

		copy.setRules("block", new PresentationSettings.RuleSet(List.of(), List.of(), false, List.of()));
		assertNotEquals(fingerprint, copy.fingerprint());

		var absent = new Gson().fromJson(
			"{\"targetTypes\":{\"entity\":{\"white\":[],\"black\":[],\"whitelistOnly\":false}}}",
			PresentationSettings.class);
		absent.validate();
		assertTrue(absent.policyFor("entity").childDenied(EFFECTIVE_RPM));

		var explicit = new Gson().fromJson(
			"{\"targetTypes\":{\"entity\":{\"white\":[],\"black\":[],\"whitelistOnly\":false,\"childBlack\":[]}}}",
			PresentationSettings.class);
		explicit.validate();
		assertFalse(explicit.policyFor("entity").childDenied(EFFECTIVE_RPM));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("malformedChildBlackLists")
	void malformedProgrammaticChildBlackDeniesItsTargetTypeDurably(String description, List<PresentationPropertyRef> invalid) {
		var settings = PresentationSettings.serverDefaults();
		var rules = settings.rulesFor("block");
		rules.setChildBlack(invalid);
		settings.setRules("block", rules);

		assertTrue(settings.policyFor("block").whitelistOnly(), description);
		assertEquals(List.of("*:*"), settings.policyFor("block").black(), description);
		assertFalse(settings.policyFor("block").allows("minecraft:basic", true), description);
		assertFalse(settings.policyFor("block").propertyAllowed(EFFECTIVE_RPM, true), description);
		assertFalse(settings.rulesFor("block").propertyAllowed(EFFECTIVE_RPM, true), description);
		assertTrue(settings.policyFor("entity").allows("minecraft:basic", true), description);

		var reloaded = new Gson().fromJson(new Gson().toJson(settings), PresentationSettings.class);
		reloaded.validate();
		assertFalse(reloaded.policyFor("block").allows("minecraft:basic", true), description);
		assertTrue(reloaded.policyFor("entity").allows("minecraft:basic", true), description);
	}
}
