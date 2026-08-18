package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigLocalizationTest {

	private static final List<String> BUNDLED_LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final List<String> PING_TYPES = List.of(
		"attention", "danger", "go_to", "loot", "destroy", "take", "request");
	private static final List<String> TEMPLATE_PLACEHOLDERS = List.of(
		"{playerName}", "{pingType}", "{targetName}");

	@Test
	void englishLocaleContainsInteractionSettingsAndUnits() throws IOException {
		String enUs;
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/en_us.json")) {
			assertNotNull(stream);
			enUs = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertContainsKey(enUs, "settings.pingforit.wheel_hold_millis");
		assertContainsKey(enUs, "settings.pingforit.wheel_timeout_millis");
		assertContainsKey(enUs, "settings.pingforit.cancel_half_cone_angle_degrees");
		assertContainsKey(enUs, "settings.pingforit.wheel_inner_radius");
		assertContainsKey(enUs, "settings.pingforit.wheel_outer_radius");
		assertContainsKey(enUs, "settings.pingforit.wheel_opacity");
		assertContainsKey(enUs, "settings.pingforit.wheel_font_size");
		assertContainsKey(enUs, "settings.pingforit.wheel_target_font_size");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_mode");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_mode.tooltip");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_slice_millis");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_slice_millis.tooltip");
		assertContainsKey(enUs, "settings.pingforit.reset_all");
		assertContainsKey(enUs, "settings.pingforit.reset_all.title");
		assertContainsKey(enUs, "settings.pingforit.reset_all.message");
		assertContainsKey(enUs, "unit.pingforit.milliseconds");
		assertContainsKey(enUs, "unit.pingforit.degrees");
		assertContainsKey(enUs, "unit.pingforit.pixels");
	}

	@Test
	void everyBundledLocaleContainsThePhase13SettingsLabels() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			String contents;
			try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
				"assets/pingforit/lang/" + locale + ".json")) {
				assertNotNull(stream);
				contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			}

			var json = JsonParser.parseString(contents).getAsJsonObject();
			for (String key : java.util.List.of(
				"settings.pingforit.wheel_font_size",
				"settings.pingforit.wheel_target_font_size",
				"settings.pingforit.long_press_compatibility_mode",
				"settings.pingforit.long_press_compatibility_mode.tooltip",
				"settings.pingforit.long_press_compatibility_slice_millis",
				"settings.pingforit.long_press_compatibility_slice_millis.tooltip",
				"settings.pingforit.reset_all",
				"settings.pingforit.reset_all.title",
				"settings.pingforit.reset_all.message")) {
				assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
			}
		}
	}

	@Test
	void bundledLocaleFileSetIsIntentional() throws Exception {
		var resource = getClass().getClassLoader().getResource("assets/pingforit/lang");
		assertNotNull(resource);

		Set<String> actualLocales;
		try (var files = Files.list(Path.of(resource.toURI()))) {
			actualLocales = files
				.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.map(name -> name.substring(0, name.length() - ".json".length()))
				.collect(Collectors.toSet());
		}

		assertEquals(Set.copyOf(BUNDLED_LOCALES), actualLocales);
	}

	@Test
	void everyBundledLocaleContainsCompleteChatLocalization() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			JsonObject json = readLocaleJson(locale);
			String legacy = nonBlankTranslation(json, locale, "pingforit.chat.pingmsg");
			assertEquals(3, countOccurrences(legacy, "%s"),
				() -> "legacy chat translation must contain exactly three %s placeholders: " + locale);
			assertFalse(legacy.replace("%s", "").contains("%"),
				() -> "legacy chat translation contains an invalid format specifier: " + locale);
			String.format(java.util.Locale.ROOT, legacy, "player", "ping", "target");

			String general = nonBlankTranslation(json, locale, "pingforit.chat.pingmsg.template");
			for (String placeholder : TEMPLATE_PLACEHOLDERS) {
				assertTrue(general.contains(placeholder),
					() -> "missing chat template placeholder for " + locale + ": " + placeholder);
			}

			for (String pingType : PING_TYPES) {
				nonBlankTranslation(json, locale, "pingforit.ping_type." + pingType);
				nonBlankTranslation(json, locale, "pingforit.ping_type." + pingType + ".phrase");
			}
			nonBlankTranslation(json, locale, "pingforit.target.here");
			nonBlankTranslation(json, locale, "pingforit.target.unknown");

			for (var entry : json.entrySet()) {
				String key = entry.getKey();
				if (key.startsWith("pingforit.chat.") && key.endsWith(".template.override")) {
					String override = nonBlankTranslation(json, locale, key);
					for (String placeholder : TEMPLATE_PLACEHOLDERS) {
						assertTrue(override.contains(placeholder),
							() -> "missing override placeholder for " + locale + ": " + key);
					}
				}
			}
		}
	}

	@Test
	void chatLegacyAndGeneralTemplatesAreRenamedWithoutOldAliases() throws IOException {
		assertEquals("%s requests %s on %s", readTranslation("en_us", "pingforit.chat.pingmsg"));
		assertEquals(
			"{playerName} requests {pingType} {targetName}",
			readTranslation("en_us", "pingforit.chat.pingmsg.template"));
		assertEquals("%s 请求 %s %s", readTranslation("zh_cn", "pingforit.chat.pingmsg"));
		assertEquals(
			"{playerName}: 请求 {pingType} {targetName}",
			readTranslation("zh_cn", "pingforit.chat.pingmsg.template"));
		assertTranslationAbsent("en_us", "pingforit.chat." + "request");
		assertTranslationAbsent("en_us", "pingforit.chat." + "request.template");
		assertTranslationAbsent("zh_cn", "pingforit.chat." + "request");
		assertTranslationAbsent("zh_cn", "pingforit.chat." + "request.template");
	}

	private JsonObject readLocaleJson(String locale) throws IOException {
		return JsonParser.parseString(readLocale(locale)).getAsJsonObject();
	}

	private String readLocale(String locale) throws IOException {
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String nonBlankTranslation(JsonObject json, String locale, String key) {
		assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
		String value = json.get(key).getAsString();
		assertFalse(value.isBlank(), () -> "blank translation: " + locale + ":" + key);
		return value;
	}

	private static int countOccurrences(String value, String token) {
		int count = 0;
		int index = 0;
		while ((index = value.indexOf(token, index)) >= 0) {
			count++;
			index += token.length();
		}
		return count;
	}

	private String readTranslation(String locale, String key) throws IOException {
		String contents;
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		var matcher = Pattern.compile(
			"\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(contents);
		assertTrue(matcher.find(), () -> "missing translation: " + locale + ":" + key);
		return matcher.group(1);
	}

	private static void assertContainsKey(String contents, String key) {
		assertTrue(
			Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:").matcher(contents).find(),
			() -> "missing en_us translation: " + key);
	}

	private void assertTranslationAbsent(String locale, String key) throws IOException {
		String contents;
		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertTrue(!JsonParser.parseString(contents).getAsJsonObject().has(key),
			() -> "unexpected translation alias: " + locale + ":" + key);
	}
}
