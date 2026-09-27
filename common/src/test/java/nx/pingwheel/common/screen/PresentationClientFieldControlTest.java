package nx.pingwheel.common.screen;

import nx.pingwheel.common.presentation.PresentationPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationClientFieldControlTest {
	private static final String FIELD = "minecraft:entity.health";
	private static final String OTHER = "create:kinetic.speed";
	private static final String GROUP = "minecraft:*";

	@Test
	void enablingAddsExactlyOneAllowEntryAndLeavesEveryOtherRuleUntouched() {
		PresentationPolicy policy = new PresentationPolicy(
			List.of(OTHER, GROUP), List.of(FIELD, "create:*"), true);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, true);

		assertEquals(PresentationClientFieldControl.Result.ENABLED, decision.result());
		assertTrue(decision.changed());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(OTHER, GROUP, FIELD), decision.white());
		// The block list and whitelist-only mode are not part of the decision.
		assertEquals(policy.black(), List.of(FIELD, "create:*"));
		assertTrue(policy.whitelistOnly());
	}

	@Test
	void enablingAnAlreadyAllowedFieldIsIdempotent() {
		PresentationPolicy policy = new PresentationPolicy(List.of(FIELD), List.of(), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, true);

		assertEquals(PresentationClientFieldControl.Result.ALREADY_ENABLED, decision.result());
		assertFalse(decision.changed());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(FIELD), decision.white());
	}

	@Test
	void enablingStillAddsAnExactAllowWhenTheFieldIsBlocked() {
		// Whites win over blacks, so the exact allow makes the field allowed
		// while the exact block selector is preserved.
		PresentationPolicy policy = new PresentationPolicy(
			List.of(), List.of(FIELD), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, true);

		assertEquals(PresentationClientFieldControl.Result.ENABLED, decision.result());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(FIELD), decision.white());
		assertEquals(List.of(FIELD), policy.black());
	}

	@Test
	void hidingRemovesOnlyTheExactAllowEntriesAndPreservesOrder() {
		List<String> white = List.of(FIELD, OTHER, FIELD, GROUP, FIELD);
		PresentationPolicy policy = new PresentationPolicy(white, List.of("create:*"), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		// Every exact duplicate is removed; wildcards and other fields remain.
		assertEquals(List.of(OTHER, GROUP), decision.white());
		assertEquals(PresentationClientFieldControl.Result.REMOVED_GROUP_STILL_ALLOWED, decision.result());
		assertTrue(decision.changed());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of("create:*"), policy.black());
		assertFalse(policy.whitelistOnly());
	}

	@Test
	void hidingWithOnlyDefaultFallbackReportsThatTheFieldStaysAllowed() {
		PresentationPolicy policy = new PresentationPolicy(List.of(FIELD, OTHER), List.of(), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		assertEquals(PresentationClientFieldControl.Result.REMOVED_DEFAULT_STILL_ALLOWED, decision.result());
		assertTrue(decision.changed());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(OTHER), decision.white());
	}

	@Test
	void hidingWithoutAnExactAllowEntryReportsTheRemainingGroupRule() {
		PresentationPolicy policy = new PresentationPolicy(List.of(GROUP), List.of(FIELD), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		assertEquals(PresentationClientFieldControl.Result.NO_EXACT_ALLOW_GROUP, decision.result());
		assertFalse(decision.changed());
		// The block rule is not evaluated in whitelist-only terms here: the
		// group allow wins, exactly like the runtime.
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(GROUP), decision.white());
		assertEquals(List.of(FIELD), policy.black());
	}

	@Test
	void hidingWithoutAnExactAllowEntryUnderWhitelistOnlyStillReportsTheGroupRule() {
		PresentationPolicy policy = new PresentationPolicy(List.of(GROUP), List.of(), true);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		// The group allow rule is the actual source, not the default.
		assertEquals(PresentationClientFieldControl.Result.NO_EXACT_ALLOW_GROUP, decision.result());
		assertFalse(decision.changed());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(GROUP), decision.white());
		assertTrue(policy.whitelistOnly());
	}

	@Test
	void hidingWithoutAnExactAllowEntryAndWithoutAGroupRuleReportsTheDefaultFallback() {
		PresentationPolicy policy = new PresentationPolicy(List.of(OTHER), List.of(), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		assertEquals(PresentationClientFieldControl.Result.NO_EXACT_ALLOW, decision.result());
		assertFalse(decision.changed());
		assertTrue(decision.effectiveAllowed());
		assertEquals(List.of(OTHER), decision.white());
	}

	@Test
	void hidingWithoutAnExactAllowEntryWhenDeniedReportsNoEffectiveAllow() {
		PresentationPolicy policy = new PresentationPolicy(List.of(OTHER), List.of(), true);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		// A defensive no-op result: the screen shows no allow notice for it.
		assertEquals(PresentationClientFieldControl.Result.NO_EXACT_ALLOW, decision.result());
		assertFalse(decision.changed());
		assertFalse(decision.effectiveAllowed());
		assertEquals(List.of(OTHER), decision.white());
		assertTrue(policy.whitelistOnly());
	}

	@Test
	void hidingWithWhitelistOnlyActuallyHidesTheField() {
		PresentationPolicy policy = new PresentationPolicy(List.of(FIELD), List.of(), true);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		assertEquals(PresentationClientFieldControl.Result.REMOVED, decision.result());
		assertTrue(decision.changed());
		assertFalse(decision.effectiveAllowed());
		assertEquals(List.of(), decision.white());
		assertTrue(policy.whitelistOnly());
	}

	@Test
	void hidingWithOnlyABlockFallbackActuallyHidesTheField() {
		PresentationPolicy policy = new PresentationPolicy(List.of(FIELD), List.of(FIELD), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);

		assertEquals(PresentationClientFieldControl.Result.REMOVED, decision.result());
		assertTrue(decision.changed());
		assertFalse(decision.effectiveAllowed());
		assertEquals(List.of(), decision.white());
		assertEquals(List.of(FIELD), policy.black());
	}

	@Test
	void enablingStopsAtTheSharedCapacityWithoutCreatingAnInvalidList() {
		List<String> full = new ArrayList<>();
		for (int index = 0; index < PresentationSelectorListModel.MAX_SELECTORS; index++) {
			full.add("mod:field" + index);
		}
		PresentationPolicy policy = new PresentationPolicy(full, List.of(), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, true);

		assertEquals(PresentationClientFieldControl.Result.LIST_FULL, decision.result());
		assertFalse(decision.changed());
		assertEquals(full, decision.white());
		assertEquals(PresentationSelectorListModel.MAX_SELECTORS, decision.white().size());
	}

	@Test
	void enablingFillsTheLastRemainingSlot() {
		List<String> almostFull = new ArrayList<>();
		for (int index = 0; index < PresentationSelectorListModel.MAX_SELECTORS - 1; index++) {
			almostFull.add("mod:field" + index);
		}
		PresentationPolicy policy = new PresentationPolicy(almostFull, List.of(), false);

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, true);

		assertEquals(PresentationClientFieldControl.Result.ENABLED, decision.result());
		assertEquals(PresentationSelectorListModel.MAX_SELECTORS, decision.white().size());
		assertEquals(FIELD, decision.white().get(decision.white().size() - 1));
	}

	@Test
	void wildcardAndMalformedIdsAreRejectedWithoutTouchingTheList() {
		PresentationPolicy policy = new PresentationPolicy(List.of(GROUP), List.of(), false);

		for (String invalid : List.of("minecraft:*", "*:*", "", "no-colon", "Minecraft:health", "minecraft:")) {
			PresentationClientFieldControl.Decision enable =
				PresentationClientFieldControl.apply(policy, invalid, true);
			assertEquals(PresentationClientFieldControl.Result.INVALID_FIELD, enable.result(), invalid);
			assertFalse(enable.changed(), invalid);
			assertEquals(List.of(GROUP), enable.white(), invalid);

			PresentationClientFieldControl.Decision hide =
				PresentationClientFieldControl.apply(policy, invalid, false);
			assertEquals(PresentationClientFieldControl.Result.INVALID_FIELD, hide.result(), invalid);
			assertFalse(hide.changed(), invalid);
			assertEquals(List.of(GROUP), hide.white(), invalid);
		}
		assertTrue(PresentationClientFieldControl.isPlainFieldId(FIELD));
		assertFalse(PresentationClientFieldControl.isPlainFieldId(GROUP));
	}

	@Test
	void aMissingPolicyNeverFabricatesAnAllowList() {
		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(null, FIELD, true);

		assertEquals(PresentationClientFieldControl.Result.MISSING_POLICY, decision.result());
		assertFalse(decision.changed());
		assertFalse(decision.effectiveAllowed());
		assertTrue(decision.white().isEmpty());
	}

	@Test
	void bothClientRolesShareTheSameTruthfulControlOutcome() {
		// The control model has no role dimension: both local panels evaluate
		// with the server-authorized default and therefore see the same state.
		PresentationPolicy policy = new PresentationPolicy(List.of(), List.of(), false);

		assertTrue(PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.CLIENT_RECEIVE, policy, FIELD, false).allowed());
		assertTrue(PresentationFieldOutcome.evaluate(
			PresentationFieldOutcome.Role.CLIENT_DISPLAY, policy, FIELD, false).allowed());

		PresentationClientFieldControl.Decision decision =
			PresentationClientFieldControl.apply(policy, FIELD, false);
		assertEquals(PresentationClientFieldControl.Result.NO_EXACT_ALLOW, decision.result());
		assertTrue(decision.effectiveAllowed());
	}
}
