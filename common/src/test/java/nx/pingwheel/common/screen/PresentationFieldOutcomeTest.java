package nx.pingwheel.common.screen;

import nx.pingwheel.common.presentation.PresentationPolicy;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import nx.pingwheel.common.presentation.client.ServerPresentationPolicyState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationFieldOutcomeTest {
	private static final String FIELD = "minecraft:entity.health";
	private static final String OTHER = "create:inventory.summary";

	@Test
	void clientRolesAlwaysUseTheServerAuthorizedDefault() {
		PresentationPolicy empty = PresentationPolicy.acceptAll();

		assertEquals(PresentationFieldOutcome.Effective.ALLOWED_BY_DEFAULT,
			PresentationFieldOutcome.evaluate(
				PresentationFieldOutcome.Role.CLIENT_RECEIVE, empty, FIELD, false).effective());
		assertEquals(PresentationFieldOutcome.Effective.ALLOWED_BY_DEFAULT,
			PresentationFieldOutcome.evaluate(
				PresentationFieldOutcome.Role.CLIENT_DISPLAY, empty, FIELD, false).effective());
	}

	@Test
	void serverRoleUsesTheAdvertisedManifestDefault() {
		PresentationPolicy empty = PresentationPolicy.acceptAll();

		assertEquals(PresentationFieldOutcome.Effective.ALLOWED_BY_DEFAULT,
			PresentationFieldOutcome.evaluate(
				PresentationFieldOutcome.Role.SERVER_POLICY, empty, FIELD, true).effective());
		assertEquals(PresentationFieldOutcome.Effective.DISABLED_BY_DEFAULT,
			PresentationFieldOutcome.evaluate(
				PresentationFieldOutcome.Role.SERVER_POLICY, empty, OTHER, false).effective());
	}

	@Test
	void exactAndWildcardAllowRulesWinOverBlockRules() {
		PresentationPolicy policy = new PresentationPolicy(List.of("minecraft:*"), List.of(FIELD), false);

		PresentationFieldOutcome.Outcome outcome = PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.SERVER_POLICY, policy, FIELD, false);

		assertEquals(PresentationFieldOutcome.Effective.ALLOW_RULE_PRIORITY, outcome.effective());
		assertTrue(outcome.allowed());
		// The exact field id is not in the allow list; only the broad wildcard is.
		assertFalse(outcome.allowOn());
		assertTrue(outcome.blockOn());
		assertEquals(List.of("minecraft:*"), outcome.matchingWhite());
		assertEquals(List.of(FIELD), outcome.matchingBlack());
	}

	@Test
	void exactMembershipTogglesMapToSingleListOperations() {
		assertEquals(Operation.ADD_WHITE, PresentationFieldOutcome.toggleAllow(false));
		assertEquals(Operation.REMOVE_WHITE, PresentationFieldOutcome.toggleAllow(true));
		assertEquals(Operation.ADD_BLACK, PresentationFieldOutcome.toggleBlock(false));
		assertEquals(Operation.REMOVE_BLACK, PresentationFieldOutcome.toggleBlock(true));
	}

	@Test
	void whitelistOnlyBlocksUnmatchedFieldsButStillHonorsAllowRules() {
		PresentationPolicy blocked = new PresentationPolicy(List.of(), List.of(), true);
		PresentationFieldOutcome.Outcome blockedOutcome = PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.CLIENT_RECEIVE, blocked, FIELD, true);
		assertEquals(PresentationFieldOutcome.Effective.BLOCKED_BY_WHITELIST_ONLY, blockedOutcome.effective());
		assertFalse(blockedOutcome.allowed());

		PresentationPolicy allowed = new PresentationPolicy(List.of(FIELD), List.of(), true);
		PresentationFieldOutcome.Outcome allowedOutcome = PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.CLIENT_RECEIVE, allowed, FIELD, true);
		assertEquals(PresentationFieldOutcome.Effective.ALLOWED_BY_RULE, allowedOutcome.effective());
		assertTrue(allowedOutcome.allowed());
	}

	@Test
	void blockRuleBeatsTheDefaultAndKeepsUnknownSelectorsVisible() {
		PresentationPolicy policy = new PresentationPolicy(List.of("minecraft:*"), List.of("create:*"), false);

		PresentationFieldOutcome.Outcome outcome = PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.SERVER_POLICY, policy, OTHER, true);

		assertEquals(PresentationFieldOutcome.Effective.BLOCKED_BY_RULE, outcome.effective());
		assertFalse(outcome.allowed());
		assertFalse(outcome.allowOn());
		assertFalse(outcome.blockOn());
		assertEquals(List.of("create:*"), outcome.matchingBlack());
	}

	@Test
	void unknownPolicyNeverFabricatesMembershipOrAnEmptyAuthoritativeView() {
		PresentationFieldOutcome.Outcome outcome = PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.SERVER_POLICY, null, FIELD, true);

		assertEquals(PresentationFieldOutcome.Effective.UNKNOWN, outcome.effective());
		assertFalse(outcome.allowOn());
		assertFalse(outcome.blockOn());
		assertFalse(outcome.allowed());
		assertTrue(outcome.matchingWhite().isEmpty());
		assertTrue(outcome.matchingBlack().isEmpty());
	}

	@Test
	void serverRuleControlsFollowThePendingAndPermissionGate() {
		long[] now = {0L};
		ServerPresentationPolicyState state = new ServerPresentationPolicyState(() -> now[0], 100L);
		assertFalse(PresentationFieldOutcome.canEditServerPolicy(state));

		long requestId = state.beginConnection();
		assertFalse(PresentationFieldOutcome.canEditServerPolicy(state), "a pending read is not editable");

		assertTrue(state.applySnapshot(requestId, 1, Status.OK, true, List.of(), List.of(), false));
		assertTrue(PresentationFieldOutcome.canEditServerPolicy(state));

		state.beginMutation(Operation.ADD_WHITE, FIELD, false);
		assertFalse(PresentationFieldOutcome.canEditServerPolicy(state), "an in-flight mutation disables the controls");

		now[0] = 200L;
		assertTrue(state.tick());
		assertTrue(state.mutationOutcomeUncertain());
		assertFalse(PresentationFieldOutcome.canEditServerPolicy(state), "an uncertain mutation requires a fresh read");

		long deniedRequest = state.beginReadRequest();
		assertTrue(deniedRequest > 0L);
		state.applySnapshot(deniedRequest, 2, Status.DENIED, false, List.of(), List.of(), false);
		assertFalse(PresentationFieldOutcome.canEditServerPolicy(state), "a denied read revokes the edit hint");
	}
}
