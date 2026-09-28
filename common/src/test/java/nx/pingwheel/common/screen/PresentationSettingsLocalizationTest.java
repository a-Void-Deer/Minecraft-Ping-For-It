package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import nx.pingwheel.common.presentation.PresentationSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationSettingsLocalizationTest {
	private static final List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final List<String> PROPERTY_FORMATS = List.of(
		"item", "item_count", "fluid_count", "health_max", "rpm", "text", "number", "flag", "record");
	private static final List<String> TYPES = List.of(
		"attention", "danger", "go_to", "loot", "destroy", "take", "request");
	private static final List<String> FIELDS = List.of(
		"minecraft_target_name", "minecraft_entity_type", "minecraft_entity_health",
		"minecraft_entity_max_health", "minecraft_item_id", "minecraft_item_count",
		"minecraft_item_icon", "minecraft_block_state", "create_kinetic_speed",
		"create_kinetic_has_network", "create_kinetic_overstressed", "create_kinetic_stress",
		"create_kinetic_capacity", "create_inventory_summary", "create_fluid_summary");

	@Test void everyLocaleOwnsCompleteIndependentPresentationVocabulary() throws IOException {
		Set<String> parity = presentationKeys(read("en_us"));
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			assertEquals(parity, presentationKeys(json), () -> "presentation key mismatch in " + locale);
			for (String id : PresentationSettings.TARGET_TYPE_IDS)
				require(json, "settings.pingforit.presentation.target_type." + id);
			for (String type : TYPES) {
				require(json, "presentation.pingforit.type." + type + ".display");
				String phrase = require(json, "presentation.pingforit.type." + type + ".phrase");
				assertTrue(phrase.contains("%s"), () -> locale + ":" + type + " must include the property");
			}
			for (String format : PROPERTY_FORMATS) {
				String value = require(json, "presentation.pingforit.format." + format);
				assertTrue(value.contains("%s"), () -> locale + ":" + format);
			}
			require(json, "presentation.pingforit.yes");
			require(json, "presentation.pingforit.no");
			for (String field : FIELDS) {
				require(json, "settings.pingforit.presentation.field." + field + ".name");
				require(json, "settings.pingforit.presentation.field." + field + ".description");
			}
			assertFalse(json.has("settings.pingforit.category.presentation"));
			assertFalse(json.has("settings.pingforit.presentation.receive"));
			assertFalse(json.has("settings.pingforit.presentation.display"));
		}
	}

	@Test void propertyRequestHasItsOwnPhraseSeparateFromWholeMarkerPing() throws IOException {
		JsonObject chinese = read("zh_cn");
		assertTrue(require(chinese, "presentation.pingforit.type.request.phrase").contains("想要"));
		assertNotEquals(require(chinese, "presentation.pingforit.type.request.phrase"),
			chinese.get("pingforit.ping_type.request.phrase").getAsString());
	}

	private static String require(JsonObject json, String key) {
		assertTrue(json.has(key), "missing " + key);
		String value = json.get(key).getAsString();
		assertFalse(value.isBlank(), "blank " + key);
		return value;
	}
	private static Set<String> presentationKeys(JsonObject json) {
		Set<String> keys = new TreeSet<>();
		for (String key : json.keySet())
			if (key.startsWith("presentation.pingforit.")
				|| key.startsWith("settings.pingforit.presentation.")
				|| key.startsWith("settings.pingforit.category.server_presentation")) keys.add(key);
		return keys;
	}
	private JsonObject read(String locale) throws IOException {
		try (var stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
