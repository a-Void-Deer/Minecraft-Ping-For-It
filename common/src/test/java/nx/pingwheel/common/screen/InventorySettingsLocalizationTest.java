package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventorySettingsLocalizationTest {
	private static final @Unmodifiable List<String> LOCALES = List.of("de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");

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
			assertEquals(2, placeholders(root, "settings.pingforit.inventory.value"), locale);
			for (String key : List.of("settings.pingforit.group.inventory_shared", "settings.pingforit.group.inventory_preview",
				"settings.pingforit.group.inventory_tracking", "settings.pingforit.server_settings.validation")) {
				assertFalse(root.get(key).getAsString().isBlank(), locale + ": " + key);
			}
		}
	}

	@Test
	void sliderResourcesKeepLiteralNamesAndThreeLineDirectionalTooltipsInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (var setting : SettingsCategoryCatalog.settings(SettingsNavigationModel.Category.PERFORMANCE)) {
				if (setting.inventoryField() == null) continue;
				String key = "settings.pingforit." + setting.id();
				assertEquals(setting.inventoryField().isSizeScale() ? 1 : 0, placeholders(root, key), locale + ": " + key);
				String tooltip = root.get(key + ".tooltip").getAsString();
				String[] lines = tooltip.split("\n", -1);
				assertEquals(3, lines.length, locale + ": " + key);
				for (String line : lines) assertFalse(line.isBlank(), locale + ": " + key);
				assertEquals(0, placeholders(root, key + ".tooltip"), locale + ": " + key);
				assertTrue(lines[1].startsWith(decreasePrefix(locale)), locale + ": " + key);
				assertTrue(lines[2].startsWith(increasePrefix(locale)), locale + ": " + key);
			}
			for (String key : List.of("finite", "mode.tooltip", "increase", "decrease", "range")) {
				assertFalse(root.has("settings.pingforit.inventory." + key), locale + ": retired control " + key);
			}
			for (String key : List.of("unlimited", "resync_no_cooldown", "heartbeat_disabled")) {
				assertEquals(0, placeholders(root, "settings.pingforit.inventory." + key), locale + ": " + key);
			}
			assertNotEquals(root.get("settings.pingforit.inventory.resync_no_cooldown").getAsString(),
				root.get("settings.pingforit.inventory.heartbeat_disabled").getAsString(), locale);
		}
	}

	private static String decreasePrefix(String locale) {
		return switch (locale) {
			case "de_de" -> "Verringern:";
			case "es_ar" -> "Reducir:";
			case "fr_fr" -> "Diminuer :";
			case "pl_pl" -> "Zmniejsz:";
			case "tr_tr" -> "Azalt:";
			case "zh_cn" -> "减少值：";
			case "zh_tw" -> "減少值：";
			default -> "Decrease:";
		};
	}

	private static String increasePrefix(String locale) {
		return switch (locale) {
			case "de_de" -> "Erhöhen:";
			case "es_ar" -> "Aumentar:";
			case "fr_fr" -> "Augmenter :";
			case "pl_pl" -> "Zwiększ:";
			case "tr_tr" -> "Artır:";
			case "zh_cn" -> "增加值：";
			case "zh_tw" -> "增加值：";
			default -> "Increase:";
		};
	}

	@Test
	void sizeSlidersExposePhysicalKiBLabelsAndTheAdjustmentNoticeInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (String key : List.of(
				"settings.pingforit.inventory.display_byte_multiplier",
				"settings.pingforit.inventory.display_byte_multiplier.tooltip",
				"settings.pingforit.inventory.frame_byte_multiplier",
				"settings.pingforit.inventory.frame_byte_multiplier.tooltip",
				"settings.pingforit.inventory.size_adjusted",
				"unit.pingforit.kib")) {
				assertTrue(root.has(key), locale + ": " + key);
				assertTrue(root.get(key).isJsonPrimitive(), locale + ": " + key);
				assertFalse(root.get(key).getAsString().isBlank(), locale + ": " + key);
			}
			assertEquals(1, placeholders(root, "settings.pingforit.inventory.display_byte_multiplier"), locale);
			assertEquals(1, placeholders(root, "settings.pingforit.inventory.frame_byte_multiplier"), locale);
			assertEquals(2, placeholders(root, "settings.pingforit.inventory.size_adjusted"), locale);
			assertEquals(1, placeholders(root, "unit.pingforit.kib"), locale);
			assertFalse(root.get("unit.pingforit.kib").getAsString().replace("%s", "").isBlank(), locale);
		}
	}

	private static int placeholders(JsonObject root, String key) {
		return root.get(key).getAsString().split("%s", -1).length - 1;
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
