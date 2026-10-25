package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class BudgetDelayLocalizationTest {
	private static final @Unmodifiable List<String> LOCALES = List.of("de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final String MESSAGE = "message.pingforit.budget_delay";
	private static final @Unmodifiable Set<String> KEYS = Set.of(
		"settings.pingforit.budget_delay_notifications_enabled",
		"settings.pingforit.budget_delay_notifications_enabled.tooltip",
		"settings.pingforit.group.monitoring",
		MESSAGE,
		MESSAGE + ".module.presentation",
		MESSAGE + ".module.inventory_preview",
		MESSAGE + ".module.inventory_tracking");
	private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([a-zA-Z%])");

	@Test
	void everyLocaleHasTheCompleteMonitoringVocabularyAndOneModuleListArgument() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			Set<String> owned = root.keySet().stream().filter(key -> key.startsWith(MESSAGE)
				|| key.startsWith("settings.pingforit.budget_delay_notifications_enabled")
				|| key.equals("settings.pingforit.group.monitoring")).collect(Collectors.toSet());
			assertEquals(KEYS, owned, locale);
			for (String key : KEYS) {
				String value = requireString(root, locale, key);
				assertEquals(key.equals(MESSAGE) ? List.of("1:s") : List.of(), signature(value), locale + ": " + key);
			}
		}
	}

	@Test
	void monitoringToggleUsesScopeAndBothToggleEffectsWithoutNumericFormatting() throws IOException {
		for (String locale : LOCALES) {
			var root = read(locale);
			String tooltip = requireString(root, locale, "settings.pingforit.budget_delay_notifications_enabled.tooltip");
			assertEquals(3, tooltip.split("\n", -1).length, locale);
			assertEquals(List.of(), signature(tooltip), locale);
		}
	}

	@Test
	void aggregateVariantLabelAndLegacyAliasAreLiteralAndEqualInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			String canonical = requireString(root, locale, "pingforit.spatial.inventory.all_variants");
			String alias = requireString(root, locale, "pingforit.spatial.inventory.component_too_long");
			assertEquals(canonical, alias, locale);
			assertEquals(List.of(), signature(canonical), locale);
			assertEquals(List.of(), signature(alias), locale);
		}
	}

	private static @Unmodifiable List<String> signature(String value) {
		var matcher = PLACEHOLDER.matcher(value);
		var arguments = new ArrayList<String>();
		int implicitIndex = 0;
		while (matcher.find()) {
			if (matcher.group(2).equals("%")) continue;
			String index = matcher.group(1) == null ? Integer.toString(++implicitIndex) : matcher.group(1);
			arguments.add(index + ":" + matcher.group(2));
		}
		return List.copyOf(arguments);
	}

	private static String requireString(JsonObject root, String locale, String key) {
		assertTrue(root.has(key), locale + ": " + key);
		assertTrue(root.get(key).isJsonPrimitive() && root.get(key).getAsJsonPrimitive().isString(), locale + ": " + key);
		String value = root.get(key).getAsString();
		assertFalse(value.isBlank(), locale + ": " + key);
		return value;
	}

	private static JsonObject read(String locale) throws IOException {
		try (var stream = BudgetDelayLocalizationTest.class.getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream, locale);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
