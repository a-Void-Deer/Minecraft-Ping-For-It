package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class InventorySettingsLocalizationTest {
	private static final List<String> LOCALES = List.of("de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");

	@Test
	void everyPerformanceSettingHasNonblankLocalizedLabelAndTooltipWithoutClientAuthority() throws IOException {
		Set<String> expected = inventoryKeys(read("en_us"));
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			assertEquals(expected, inventoryKeys(root), locale);
			for (String key : expected) {
				assertTrue(root.get(key).isJsonPrimitive(), locale + ": " + key);
				assertFalse(root.get(key).getAsString().isBlank(), locale + ": " + key);
			}
			for (var setting : SettingsCategoryCatalog.settings(SettingsNavigationModel.Category.PERFORMANCE)) {
				assertNotNull(root.get("settings.pingforit." + setting.id()), locale + ": " + setting);
				assertNotNull(root.get("settings.pingforit." + setting.id() + ".tooltip"), locale + ": " + setting);
			}
			assertTrue(root.get("settings.pingforit.category.performance.button").getAsString().endsWith("..."), locale);
			assertEquals(2, root.get("settings.pingforit.inventory.range").getAsString().split("%s", -1).length - 1, locale);
			for (String key : List.of("settings.pingforit.group.inventory_shared", "settings.pingforit.group.inventory_preview",
				"settings.pingforit.group.inventory_tracking", "settings.pingforit.server_settings.validation")) {
				assertFalse(root.get(key).getAsString().isBlank(), locale + ": " + key);
			}
		}
	}

	private static Set<String> inventoryKeys(JsonObject root) {
		return root.keySet().stream().filter(key -> key.startsWith("settings.pingforit.inventory.")
			|| key.startsWith("settings.pingforit.category.performance")
			|| key.startsWith("settings.pingforit.group.inventory_")).collect(Collectors.toSet());
	}

	private static JsonObject read(String locale) throws IOException {
		try (var stream = InventorySettingsLocalizationTest.class.getClassLoader().getResourceAsStream("assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream, locale);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
