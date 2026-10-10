package nx.pingwheel.common.presentation;

import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPresentationPolicyServiceTest {

	@Test
	void readAllDisclosesEveryTargetTypeInCatalogOrder() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(List.of("minecraft:basic"),
			List.of("minecraft:entity.health"), true));

		var views = ServerPresentationPolicyService.readAll(settings);

		// Independent of the settings constant: the confirmed protocol catalog and its order.
		assertEquals(List.of("dropped_item", "entity", "entity_block", "block", "location"),
			List.copyOf(views.keySet()));
		assertEquals(List.of("minecraft:basic"), views.get("block").white());
		assertEquals(List.of("minecraft:entity.health"), views.get("block").black());
		assertTrue(views.get("block").whitelistOnly());
		assertEquals(List.of(), views.get("entity").white());
	}

	@Test
	void readOfMissingSettingsOrUnknownTypeFailsClosed() {
		var view = ServerPresentationPolicyService.read(null, "entity");
		assertTrue(view.whitelistOnly());
		assertEquals(List.of("*:*"), view.black());
		assertTrue(ServerPresentationPolicyService.read(PresentationSettings.serverDefaults(), "unknown").whitelistOnly());
	}

	@Test
	void mutationWithoutPermissionIsDeniedWithoutChangingAnyType() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of("minecraft:basic"), List.of(), false));

		var result = ServerPresentationPolicyService.mutateSelectedRules(false, settings, "entity",
			Operation.ADD_WHITE, "create:*", false);

		assertFalse(result.applied());
		assertEquals(Status.DENIED, result.status());
		assertEquals(List.of("minecraft:basic"), settings.rulesFor("entity").getWhite());
	}

	@Test
	void mutationAddsAndRemovesOnlyTheSelectedTargetType() {
		var settings = PresentationSettings.serverDefaults();

		var added = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "block",
			Operation.ADD_WHITE, "create:*", false);
		assertTrue(added.applied());
		assertEquals(Status.OK, added.status());
		assertEquals(List.of("create:*"), settings.rulesFor("block").getWhite());
		assertEquals(List.of(), settings.rulesFor("entity").getWhite());

		var removed = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "block",
			Operation.REMOVE_WHITE, "create:*", false);
		assertTrue(removed.applied());
		assertEquals(List.of(), settings.rulesFor("block").getWhite());
	}

	@Test
	void duplicateMissingInvalidAndUnknownTypeRequestsNeverChangeState() {
		var settings = PresentationSettings.serverDefaults();
		ServerPresentationPolicyService.mutateSelectedRules(true, settings, "entity",
			Operation.ADD_BLACK, "create:*", false);

		assertEquals(Status.DUPLICATE, ServerPresentationPolicyService.mutateSelectedRules(true, settings,
			"entity", Operation.ADD_BLACK, "create:*", false).status());
		assertEquals(Status.NOT_FOUND, ServerPresentationPolicyService.mutateSelectedRules(true, settings,
			"entity", Operation.REMOVE_BLACK, "minecraft:*", false).status());
		assertEquals(Status.INVALID, ServerPresentationPolicyService.mutateSelectedRules(true, settings,
			"entity", Operation.ADD_WHITE, "bad selector", false).status());
		assertEquals(Status.INVALID, ServerPresentationPolicyService.mutateSelectedRules(true, settings,
			"unknown", Operation.ADD_WHITE, "create:*", false).status());
		assertEquals(List.of("create:*"), settings.rulesFor("entity").getBlack());
	}

	@Test
	void overCapacityAndNoOpRequestsLeaveTheRuleViewUnchanged() {
		var settings = PresentationSettings.serverDefaults();
		var full = IntStream.range(0, ServerPresentationPolicyService.MAX_SELECTORS)
			.mapToObj(i -> "create:" + i)
			.toList();
		for (String selector : full) {
			assertTrue(ServerPresentationPolicyService.mutateSelectedRules(true, settings, "entity",
				Operation.ADD_WHITE, selector, false).applied());
		}

		var overflow = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "entity",
			Operation.ADD_WHITE, "forge:extra", false);
		assertFalse(overflow.applied());
		assertEquals(Status.LIST_FULL, overflow.status());

		var noOp = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "entity",
			Operation.SET_WHITELIST_ONLY, "", false);
		assertFalse(noOp.applied());
		assertEquals(Status.OK, noOp.status());
		assertEquals(ServerPresentationPolicyService.MAX_SELECTORS, settings.rulesFor("entity").getWhite().size());
		assertEquals(List.of(), settings.rulesFor("block").getWhite());
	}

	@Test
	void whitelistOnlyAssignmentIsSelectedTypeOnly() {
		var settings = PresentationSettings.serverDefaults();

		var applied = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "location",
			Operation.SET_WHITELIST_ONLY, "", true);

		assertTrue(applied.applied());
		assertTrue(settings.policyFor("location").whitelistOnly());
		assertFalse(settings.policyFor("block").whitelistOnly());
	}

	@Test
	void detachedCopyIsIndependentAndMissingFailsClosed() {
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of("minecraft:basic"),
			List.of("minecraft:entity"), true));

		var copy = ServerPresentationPolicyService.detachedCopy(settings);
		copy.setRules("entity", PresentationSettings.RuleSet.allowByDefault());

		assertTrue(settings.rulesFor("entity").isWhitelistOnly());
		assertFalse(copy.rulesFor("entity").isWhitelistOnly());
		assertNull(ServerPresentationPolicyService.detachedCopy(null));
	}

	@Test
	void fieldMutationsPreserveThePersistedChildDenyList() {
		var effectiveRpm = new PresentationPropertyRef("create:presentation", "create:kinetic.speed",
			List.of("effective_rpm"));
		var custom = new PresentationPropertyRef("create:presentation", "create:inventory.summary",
			List.of("minecraft:cobblestone"));
		var settings = PresentationSettings.serverDefaults();
		settings.setRules("entity", new PresentationSettings.RuleSet(List.of(), List.of(), false, List.of()));
		settings.setRules("block", new PresentationSettings.RuleSet(List.of(), List.of(), false, List.of(custom)));

		var added = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "entity",
			Operation.ADD_WHITE, "create:*", false);
		assertTrue(added.applied());
		assertFalse(settings.policyFor("entity").childDenied(effectiveRpm));
		assertTrue(settings.rulesFor("entity").getChildBlack().isEmpty());

		var toggled = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "block",
			Operation.SET_WHITELIST_ONLY, "", true);
		assertTrue(toggled.applied());
		assertTrue(settings.rulesFor("block").childDenied(custom));

		var denied = ServerPresentationPolicyService.mutateSelectedRules(true, settings, "location",
			Operation.ADD_BLACK, "create:*", false);
		assertTrue(denied.applied());
		assertTrue(settings.rulesFor("location").childDenied(effectiveRpm));
	}
}
