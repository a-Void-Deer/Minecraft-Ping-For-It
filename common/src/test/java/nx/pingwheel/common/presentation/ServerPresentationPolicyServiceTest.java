package nx.pingwheel.common.presentation;

import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Operation;
import nx.pingwheel.common.presentation.ServerPresentationPolicyService.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPresentationPolicyServiceTest {

	@Test
	void readDisclosesOnlyPresentationSelectors() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic", "create:*"));
		settings.setBlack(List.of("minecraft:entity.health"));
		settings.setWhitelistOnly(true);

		PresentationPolicy policy = ServerPresentationPolicyService.read(settings);

		assertEquals(List.of("minecraft:basic", "create:*"), policy.white());
		assertEquals(List.of("minecraft:entity.health"), policy.black());
		assertTrue(policy.whitelistOnly());
	}

	@Test
	void readOfMissingSettingsFailsClosed() {
		PresentationPolicy policy = ServerPresentationPolicyService.read(null);

		assertTrue(policy.whitelistOnly());
		assertEquals(List.of("*:*"), policy.black());
	}

	@Test
	void mutateWithoutPermissionIsDeniedWithoutChangingSettings() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));

		var result = ServerPresentationPolicyService.mutate(
			false, settings, Operation.ADD_WHITE, "create:*", false);

		assertFalse(result.applied());
		assertEquals(Status.DENIED, result.status());
		assertEquals(List.of("minecraft:basic"), settings.getWhite());
		assertEquals(List.of("minecraft:basic"), result.policy().white());
	}

	@Test
	void mutateWithPermissionAddsAndRemovesAtomically() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));

		var added = ServerPresentationPolicyService.mutate(
			true, settings, Operation.ADD_WHITE, "create:*", false);
		assertTrue(added.applied());
		assertEquals(Status.OK, added.status());
		assertEquals(List.of("minecraft:basic", "create:*"), settings.getWhite());

		var removed = ServerPresentationPolicyService.mutate(
			true, settings, Operation.REMOVE_WHITE, "minecraft:basic", false);
		assertTrue(removed.applied());
		assertEquals(List.of("create:*"), settings.getWhite());
	}

	@Test
	void duplicateAddIsRejectedWithoutMutation() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));

		var result = ServerPresentationPolicyService.mutate(
			true, settings, Operation.ADD_WHITE, "minecraft:basic", false);

		assertFalse(result.applied());
		assertEquals(Status.DUPLICATE, result.status());
		assertEquals(List.of("minecraft:basic"), settings.getWhite());
	}

	@Test
	void invalidSelectorIsRejectedWithoutDurableDenyAll() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));

		var result = ServerPresentationPolicyService.mutate(
			true, settings, Operation.ADD_BLACK, "Not A Selector", false);

		assertFalse(result.applied());
		assertEquals(Status.INVALID, result.status());
		assertEquals(List.of("minecraft:basic"), settings.getWhite());
		assertEquals(List.of(), settings.getBlack());
		assertFalse(settings.isWhitelistOnly());
		assertFalse(settings.policy().whitelistOnly());
	}

	@Test
	void fullListAddIsRejectedWithoutMutation() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		List<String> full = IntStream.range(0, ServerPresentationPolicyService.MAX_SELECTORS)
			.mapToObj(index -> "test:field" + index)
			.toList();
		settings.setWhite(full);

		var result = ServerPresentationPolicyService.mutate(
			true, settings, Operation.ADD_WHITE, "test:extra", false);

		assertFalse(result.applied());
		assertEquals(Status.LIST_FULL, result.status());
		assertEquals(full, settings.getWhite());
	}

	@Test
	void removeMissingSelectorIsRejectedWithoutMutation() {
		PresentationSettings settings = PresentationSettings.serverDefaults();

		var result = ServerPresentationPolicyService.mutate(
			true, settings, Operation.REMOVE_BLACK, "minecraft:*", false);

		assertFalse(result.applied());
		assertEquals(Status.NOT_FOUND, result.status());
		assertEquals(List.of(), settings.getBlack());
	}

	@Test
	void blackListAddAndRemoveApply() {
		PresentationSettings settings = PresentationSettings.serverDefaults();

		var added = ServerPresentationPolicyService.mutate(
			true, settings, Operation.ADD_BLACK, "minecraft:*", false);
		assertTrue(added.applied());
		assertEquals(List.of("minecraft:*"), settings.getBlack());

		var removed = ServerPresentationPolicyService.mutate(
			true, settings, Operation.REMOVE_BLACK, "minecraft:*", false);
		assertTrue(removed.applied());
		assertEquals(List.of(), settings.getBlack());
	}

	@Test
	void whitelistOnlySetAppliesBothValues() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));

		var enabled = ServerPresentationPolicyService.mutate(
			true, settings, Operation.SET_WHITELIST_ONLY, "", true);
		assertTrue(enabled.applied());
		assertTrue(settings.isWhitelistOnly());
		assertTrue(settings.policy().whitelistOnly());

		var disabled = ServerPresentationPolicyService.mutate(
			true, settings, Operation.SET_WHITELIST_ONLY, "", false);
		assertTrue(disabled.applied());
		assertFalse(settings.isWhitelistOnly());
	}

	@Test
	void noOpWhitelistOnlySetIsReportedWithoutMutation() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhitelistOnly(true);

		var result = ServerPresentationPolicyService.mutate(
			true, settings, Operation.SET_WHITELIST_ONLY, "", true);

		assertFalse(result.applied());
		assertEquals(Status.OK, result.status());
		assertTrue(settings.isWhitelistOnly());
	}

	@Test
	void detachedCopyCarriesExactFieldsAndIsolatesMutations() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));
		settings.setBlack(List.of("minecraft:entity"));
		settings.setWhitelistOnly(true);
		settings.setMinUpdateIntervalTicks(40);
		settings.setScanBudget(64);
		settings.setPermissionLevels(Map.of("minecraft:basic", 2));
		settings.setUpdateIntervals(Map.of("minecraft:basic", 30));

		PresentationSettings copy = ServerPresentationPolicyService.detachedCopy(settings);

		assertNotSame(settings, copy);
		assertEquals(settings.getWhite(), copy.getWhite());
		assertEquals(settings.getBlack(), copy.getBlack());
		assertEquals(settings.isWhitelistOnly(), copy.isWhitelistOnly());
		assertEquals(settings.getMinUpdateIntervalTicks(), copy.getMinUpdateIntervalTicks());
		assertEquals(settings.getScanBudget(), copy.getScanBudget());
		assertEquals(settings.getPermissionLevels(), copy.getPermissionLevels());
		assertEquals(settings.getUpdateIntervals(), copy.getUpdateIntervals());

		var result = ServerPresentationPolicyService.mutate(
			true, copy, Operation.ADD_WHITE, "create:field", false);

		assertTrue(result.applied());
		assertEquals(List.of("minecraft:basic", "create:field"), copy.getWhite());
		assertEquals(List.of("minecraft:basic"), settings.getWhite());
		assertEquals(List.of("minecraft:entity"), settings.getBlack());
	}

	@Test
	void invalidMutationOnDetachedCopyLeavesOriginalUntouched() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setWhite(List.of("minecraft:basic"));

		PresentationSettings copy = ServerPresentationPolicyService.detachedCopy(settings);
		var result = ServerPresentationPolicyService.mutate(
			true, copy, Operation.ADD_BLACK, "Not A Selector", false);

		assertFalse(result.applied());
		assertEquals(Status.INVALID, result.status());
		assertEquals(List.of("minecraft:basic"), settings.getWhite());
		assertEquals(List.of(), settings.getBlack());
		assertFalse(settings.isWhitelistOnly());
	}

	@Test
	void detachedCopyOfMissingSettingsIsNull() {
		assertNull(ServerPresentationPolicyService.detachedCopy(null));
	}

	@Test
	void isValidSelectorMatchesPolicyGrammar() {
		assertTrue(ServerPresentationPolicyService.isValidSelector("minecraft:basic"));
		assertTrue(ServerPresentationPolicyService.isValidSelector("create:*"));
		assertFalse(ServerPresentationPolicyService.isValidSelector(null));
		assertFalse(ServerPresentationPolicyService.isValidSelector(""));
		assertFalse(ServerPresentationPolicyService.isValidSelector("Not A Selector"));
		assertFalse(ServerPresentationPolicyService.isValidSelector(
			"a:" + "b".repeat(ServerPresentationPolicyService.MAX_SELECTOR_LENGTH)));
	}
}
