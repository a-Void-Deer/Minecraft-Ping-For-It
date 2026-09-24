package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nx.pingwheel.common.presentation.client.PresentationFieldCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationSettingsLocalizationTest {
	private static final List<String> BUNDLED_LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final List<String> BUILTIN_AND_CREATE_FIELD_IDS = List.of(
		"minecraft:target.name",
		"minecraft:entity.type",
		"minecraft:entity.health",
		"minecraft:entity.max_health",
		"minecraft:item.id",
		"minecraft:item.count",
		"minecraft:item.icon",
		"minecraft:block.state",
		"create:kinetic.speed",
		"create:kinetic.has_network",
		"create:kinetic.overstressed",
		"create:kinetic.stress",
		"create:kinetic.capacity",
		"create:inventory.summary",
		"create:fluid.summary");
	private static final List<String> PRESENTATION_KEYS = List.of(
		"settings.pingforit.category.presentation",
		"settings.pingforit.category.presentation.button",
		"settings.pingforit.category.server_presentation",
		"settings.pingforit.category.server_presentation.button",
		"settings.pingforit.presentation.receive",
		"settings.pingforit.presentation.display",
		"settings.pingforit.presentation.server.title",
		"settings.pingforit.presentation.white",
		"settings.pingforit.presentation.black",
		"settings.pingforit.presentation.whitelist_only",
		"settings.pingforit.presentation.whitelist_only.tooltip",
		"settings.pingforit.presentation.selector",
		"settings.pingforit.presentation.add",
		"settings.pingforit.presentation.remove",
		"settings.pingforit.presentation.refresh",
		"settings.pingforit.presentation.empty",
		"settings.pingforit.presentation.server.unknown",
		"settings.pingforit.presentation.server.loading",
		"settings.pingforit.presentation.server.pending",
		"settings.pingforit.presentation.server.ready",
		"settings.pingforit.presentation.server.unsupported",
		"settings.pingforit.presentation.server.error",
		"settings.pingforit.presentation.server.read_only",
		"settings.pingforit.presentation.server.can_edit",
		"settings.pingforit.presentation.server.cannot_edit",
		"settings.pingforit.presentation.feedback.invalid",
		"settings.pingforit.presentation.feedback.duplicate",
		"settings.pingforit.presentation.feedback.list_full",
		"settings.pingforit.presentation.feedback.empty",
		"settings.pingforit.presentation.feedback.not_found",
		"settings.pingforit.presentation.feedback.denied",
		"settings.pingforit.presentation.feedback.error",
		"settings.pingforit.presentation.feedback.timeout",
		"settings.pingforit.presentation.server.timeout",
		"settings.pingforit.presentation.fields.title",
		"settings.pingforit.presentation.fields.local_preview",
		"settings.pingforit.presentation.fields.empty",
		"settings.pingforit.presentation.fields.unknown",
		"settings.pingforit.presentation.fields.note",
		"settings.pingforit.presentation.advanced.show",
		"settings.pingforit.presentation.advanced.hide",
		"settings.pingforit.presentation.advanced.counts",
		"settings.pingforit.presentation.namespace.minecraft",
		"settings.pingforit.presentation.namespace.create",
		"settings.pingforit.presentation.outcome.allowed_rule",
		"settings.pingforit.presentation.outcome.allowed_default",
		"settings.pingforit.presentation.outcome.client_receive",
		"settings.pingforit.presentation.outcome.client_display",
		"settings.pingforit.presentation.outcome.blocked_rule",
		"settings.pingforit.presentation.outcome.blocked_whitelist",
		"settings.pingforit.presentation.outcome.disabled_default",
		"settings.pingforit.presentation.outcome.waiting",
		"settings.pingforit.presentation.outcome.allow_priority",
		"settings.pingforit.presentation.field.allow",
		"settings.pingforit.presentation.field.block",
		"settings.pingforit.presentation.field.on",
		"settings.pingforit.presentation.field.off",
		"settings.pingforit.presentation.field.id",
		"settings.pingforit.presentation.field.default",
		"settings.pingforit.presentation.field.now",
		"settings.pingforit.presentation.field.matching_white",
		"settings.pingforit.presentation.field.matching_black",
		"settings.pingforit.presentation.rule.allow.tooltip",
		"settings.pingforit.presentation.rule.block.tooltip",
		"settings.pingforit.presentation.field.minecraft_target_name.name",
		"settings.pingforit.presentation.field.minecraft_target_name.description",
		"settings.pingforit.presentation.field.minecraft_entity_type.name",
		"settings.pingforit.presentation.field.minecraft_entity_type.description",
		"settings.pingforit.presentation.field.minecraft_entity_health.name",
		"settings.pingforit.presentation.field.minecraft_entity_health.description",
		"settings.pingforit.presentation.field.minecraft_entity_max_health.name",
		"settings.pingforit.presentation.field.minecraft_entity_max_health.description",
		"settings.pingforit.presentation.field.minecraft_item_id.name",
		"settings.pingforit.presentation.field.minecraft_item_id.description",
		"settings.pingforit.presentation.field.minecraft_item_count.name",
		"settings.pingforit.presentation.field.minecraft_item_count.description",
		"settings.pingforit.presentation.field.minecraft_item_icon.name",
		"settings.pingforit.presentation.field.minecraft_item_icon.description",
		"settings.pingforit.presentation.field.minecraft_block_state.name",
		"settings.pingforit.presentation.field.minecraft_block_state.description",
		"settings.pingforit.presentation.field.create_kinetic_speed.name",
		"settings.pingforit.presentation.field.create_kinetic_speed.description",
		"settings.pingforit.presentation.field.create_kinetic_has_network.name",
		"settings.pingforit.presentation.field.create_kinetic_has_network.description",
		"settings.pingforit.presentation.field.create_kinetic_overstressed.name",
		"settings.pingforit.presentation.field.create_kinetic_overstressed.description",
		"settings.pingforit.presentation.field.create_kinetic_stress.name",
		"settings.pingforit.presentation.field.create_kinetic_stress.description",
		"settings.pingforit.presentation.field.create_kinetic_capacity.name",
		"settings.pingforit.presentation.field.create_kinetic_capacity.description",
		"settings.pingforit.presentation.field.create_inventory_summary.name",
		"settings.pingforit.presentation.field.create_inventory_summary.description",
		"settings.pingforit.presentation.field.create_fluid_summary.name",
		"settings.pingforit.presentation.field.create_fluid_summary.description");

	@Test
	void everyBundledLocaleContainsEveryPresentationKey() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			JsonObject json = readLocaleJson(locale);
			for (String key : PRESENTATION_KEYS) {
				assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
				assertFalse(json.get(key).getAsString().isBlank(), () -> "blank translation: " + locale + ":" + key);
			}
			assertTrue(
				json.get("settings.pingforit.category.presentation.button").getAsString().endsWith("..."),
				() -> "presentation category entrance must advertise navigation: " + locale);
		}
	}

	@Test
	void presentationKeySetIsIdenticalAcrossBundledLocales() throws IOException {
		Set<String> reference = presentationKeySet(readLocaleJson("en_us"));

		assertEquals(PRESENTATION_KEYS.size(), reference.size());
		for (String locale : BUNDLED_LOCALES) {
			assertEquals(reference, presentationKeySet(readLocaleJson(locale)),
				() -> "presentation translations must stay in parity: " + locale);
		}
	}

	@Test
	void englishPresentationLabelsMatchTheApprovedWording() throws IOException {
		JsonObject json = readLocaleJson("en_us");

		assertEquals("Presentation", json.get("settings.pingforit.category.presentation").getAsString());
		assertEquals("Presentation...", json.get("settings.pingforit.category.presentation.button").getAsString());
		assertEquals("Server Presentation", json.get("settings.pingforit.category.server_presentation").getAsString());
		assertEquals("Server Presentation...", json.get("settings.pingforit.category.server_presentation.button").getAsString());
		assertEquals("Client Receive", json.get("settings.pingforit.presentation.receive").getAsString());
		assertEquals("Client Display", json.get("settings.pingforit.presentation.display").getAsString());
		assertEquals("Server Policy", json.get("settings.pingforit.presentation.server.title").getAsString());
		assertEquals("Read only", json.get("settings.pingforit.presentation.server.read_only").getAsString());
		assertEquals("Fields supported by this connection",
			json.get("settings.pingforit.presentation.fields.title").getAsString());
		assertEquals("Local preview",
			json.get("settings.pingforit.presentation.fields.local_preview").getAsString());
		assertTrue(json.get("settings.pingforit.presentation.fields.note").getAsString()
			.contains("permission"), "the server catalogue must say that configuring does not grant data");
		assertEquals("Accept server-authorized",
			json.get("settings.pingforit.presentation.outcome.client_receive").getAsString());
		assertEquals("Show received",
			json.get("settings.pingforit.presentation.outcome.client_display").getAsString());
		assertEquals("Rotation speed (RPM)",
			json.get("settings.pingforit.presentation.field.create_kinetic_speed.name").getAsString());
		assertTrue(json.get("settings.pingforit.presentation.field.create_kinetic_speed.description").getAsString()
			.contains("no allow rule"), "Create RPM must explain that no explicit enable is needed");
		assertTrue(json.get("settings.pingforit.presentation.whitelist_only.tooltip").getAsString()
			.contains("White"));
	}

	@Test
	void everyBuiltinAndCreateFieldHasALocalizedNameAndDescriptionInEveryLocale() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			JsonObject json = readLocaleJson(locale);
			for (String fieldId : BUILTIN_AND_CREATE_FIELD_IDS) {
				String suffix = fieldId.replace(':', '_').replace('.', '_');
				assertTrue(json.has("settings.pingforit.presentation.field." + suffix + ".name"),
					() -> "missing field name: " + locale + ":" + fieldId);
				assertTrue(json.has("settings.pingforit.presentation.field." + suffix + ".description"),
					() -> "missing field description: " + locale + ":" + fieldId);
			}
		}
	}

	@Test
	void presentationCategoryIsRegisteredInTheClientScopeForEveryPlayer() {
		assertEquals(
			nx.pingwheel.common.screen.SettingsNavigationModel.Scope.CLIENT,
			SettingsNavigationModel.Category.PRESENTATION.scope());
		assertEquals(
			"presentation",
			SettingsNavigationModel.Category.PRESENTATION.id());
		assertEquals(
			List.of("presentation_receive", "presentation_display"),
			SettingsCategoryCatalog.settings(SettingsNavigationModel.Category.PRESENTATION)
				.stream()
				.map(SettingsCategoryCatalog.Setting::id)
				.toList());
		assertEquals(SettingsNavigationModel.Scope.SERVER,
			SettingsNavigationModel.Category.SERVER_PRESENTATION.scope());
		assertEquals(List.of("presentation_server_policy"),
			SettingsCategoryCatalog.settings(SettingsNavigationModel.Category.SERVER_PRESENTATION)
				.stream().map(SettingsCategoryCatalog.Setting::id).toList());
	}

	@Test
	void builtinNamespaceHeadingsHaveBundledTranslations() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			JsonObject json = readLocaleJson(locale);
			for (String namespace : List.of("minecraft", "create")) {
				String key = new PresentationFieldCatalog.Namespace(namespace, List.of()).translationKey();
				assertTrue(json.has(key), () -> "missing namespace heading: " + locale + ":" + namespace);
				assertFalse(json.get(key).getAsString().isBlank(),
					() -> "blank namespace heading: " + locale + ":" + namespace);
			}
		}
	}

	@Test
	void readOnlyServerStatusWordingExistsInEveryBundledLocale() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			JsonObject json = readLocaleJson(locale);
			assertTrue(json.has("settings.pingforit.server_status.read_only"),
				() -> "missing read-only server status: " + locale);
			assertFalse(json.get("settings.pingforit.server_status.read_only").getAsString().isBlank(),
				() -> "blank read-only server status: " + locale);
		}
	}

	private static Set<String> presentationKeySet(JsonObject json) {
		Set<String> keys = new TreeSet<>();
		for (String key : json.keySet()) {
			if (key.startsWith("settings.pingforit.presentation.")
				|| key.equals("settings.pingforit.category.presentation")
				|| key.equals("settings.pingforit.category.presentation.button")
				|| key.equals("settings.pingforit.category.server_presentation")
				|| key.equals("settings.pingforit.category.server_presentation.button")) {
				keys.add(key);
			}
		}
		return keys;
	}

	private JsonObject readLocaleJson(String locale) throws IOException {
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
				.getAsJsonObject();
		}
	}
}
