package nx.pingwheel.common.presentation.inventory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationAuthorization;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryPresentationTest {
	private static final UUID VIEWER = new UUID(0, 5);

	@Test
	void manifestDeclaresTheDedicatedInventorySubject() {
		assertEquals("pingforit:inventory", InventoryPresentation.ADAPTER_ID);
		assertEquals(1, InventoryPresentation.SCHEMA);
		assertEquals(PresentationAdapter.DeliveryMode.DEDICATED, InventoryPresentation.INSTANCE.deliveryMode());
		PresentationField items = InventoryPresentation.INSTANCE.fields().stream()
			.filter(field -> field.id().equals(InventoryPresentation.ITEMS)).findFirst().orElseThrow();
		assertEquals(PresentationField.Kind.RECORD, items.kind());
		assertTrue(items.enabledByDefault());
		assertEquals(0, items.permissionLevel());
	}

	@Test
	void defaultPolicyAuthorizesTheSubjectForEveryTargetType() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		for (String type : PresentationSettings.TARGET_TYPE_IDS)
			assertEquals(Set.of(InventoryPresentation.ITEMS),
				InventoryPresentation.allowed(settings, VIEWER, 0, type));
	}

	@Test
	void selectorPolicyCanDenyOrExplicitlyAllowTheSubject() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setRules("block", new PresentationSettings.RuleSet(List.of(), List.of("pingforit:*"), false));
		assertTrue(InventoryPresentation.allowed(settings, VIEWER, 0, "block").isEmpty());
		assertEquals(Set.of(InventoryPresentation.ITEMS),
			InventoryPresentation.allowed(settings, VIEWER, 0, "entity"));

		settings.setRules("entity", new PresentationSettings.RuleSet(List.of(), List.of(), true));
		assertTrue(InventoryPresentation.allowed(settings, VIEWER, 0, "entity").isEmpty());
		settings.setRules("entity", new PresentationSettings.RuleSet(
			List.of(InventoryPresentation.ITEMS), List.of("*:*"), true));
		assertEquals(Set.of(InventoryPresentation.ITEMS),
			InventoryPresentation.allowed(settings, VIEWER, 0, "entity"));
	}

	@Test
	void permissionOverrideAndReplaceableCallbackGovernTheSubject() {
		PresentationSettings settings = PresentationSettings.serverDefaults();
		settings.setPermissionLevels(Map.of(InventoryPresentation.ITEMS, 4));
		assertTrue(InventoryPresentation.allowed(settings, VIEWER, 0, "block").isEmpty());
		assertEquals(Set.of(InventoryPresentation.ITEMS),
			InventoryPresentation.allowed(settings, VIEWER, 4, "block"));
		try {
			PresentationAuthorization.setProvider((recipient, field, actualLevel, requiredLevel) -> false);
			assertTrue(InventoryPresentation.allowed(settings, VIEWER, 4, "block").isEmpty());
		} finally {
			PresentationAuthorization.setProvider(null);
		}
	}

	@Test
	void missingSettingsAndUnknownTargetTypeFailClosed() {
		assertTrue(InventoryPresentation.allowed(null, VIEWER, 4, "block").isEmpty());
		assertTrue(InventoryPresentation.allowed(PresentationSettings.serverDefaults(), VIEWER, 4, "unknown").isEmpty());
	}
}
