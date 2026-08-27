package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
	private static final List<String> SERVER_SETTINGS_KEYS = List.of(
		"settings.pingforit.client_settings",
		"settings.pingforit.server_settings",
		"settings.pingforit.server_settings.loading",
		"settings.pingforit.server_settings.locked",
		"settings.pingforit.server_settings.validation",
		"settings.pingforit.server_settings.confirm.title",
		"settings.pingforit.server_settings.confirm.message",
		"settings.pingforit.server_settings.confirm.discard",
		"settings.pingforit.server_settings.confirm.cancel",
		"settings.pingforit.default_channel_mode",
		"settings.pingforit.default_channel_mode.tooltip",
		"settings.pingforit.player_tracking_enabled",
		"settings.pingforit.player_tracking_enabled.tooltip",
		"settings.pingforit.ms_to_regenerate",
		"settings.pingforit.ms_to_regenerate.tooltip",
		"settings.pingforit.rate_limit",
		"settings.pingforit.rate_limit.tooltip");
	private static final Map<String, String> EXTERNAL_LIST_RESTART_MARKERS = Map.of(
		"de_de", "Neustart des Clients",
		"en_us", "restarting the client",
		"es_ar", "reiniciar el cliente",
		"fr_fr", "redémarrage du client",
		"pl_pl", "ponownym uruchomieniu klienta",
		"tr_tr", "istemci yeniden başlatıldıktan sonra",
		"zh_cn", "重启客户端后",
		"zh_tw", "重新啟動用戶端後");

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
		assertContainsKey(enUs, "settings.pingforit.marker_display_duration");
		assertContainsKey(enUs, "settings.pingforit.marker_display_duration.tooltip");
		assertContainsKey(enUs, "value.pingforit.follow_server");
		assertTrue(readTranslation("en_us", "settings.pingforit.marker_display_duration").contains("%s"));
		assertEquals("Follow server", readTranslation("en_us", "value.pingforit.follow_server"));
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_mode");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_mode.tooltip");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_slice_millis");
		assertContainsKey(enUs, "settings.pingforit.long_press_compatibility_slice_millis.tooltip");
		assertContainsKey(enUs, "settings.pingforit.pass_through_transparent_blocks");
		assertContainsKey(enUs, "settings.pingforit.mark_blacklisted_targets");
		assertContainsKey(enUs, "settings.pingforit.mark_fluids");
		assertContainsKey(enUs, "settings.pingforit.reset_all");
		assertContainsKey(enUs, "settings.pingforit.reset_all.title");
		assertContainsKey(enUs, "settings.pingforit.reset_all.message");
		assertContainsKey(enUs, "settings.pingforit.open_block_shape_blacklist_config");
		assertContainsKey(enUs, "unit.pingforit.milliseconds");
		assertContainsKey(enUs, "unit.pingforit.seconds");
		assertEquals("%ss", readTranslation("en_us", "unit.pingforit.seconds"));
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
				"key.pingforit.toggle_pass_through_transparent_blocks",
				"key.pingforit.toggle_mark_blacklisted_targets",
				"key.pingforit.toggle_mark_fluids")) {
				nonBlankTranslation(json, locale, key);
			}
			for (String key : java.util.List.of(
				"settings.pingforit.pass_through_transparent_blocks",
				"settings.pingforit.mark_blacklisted_targets",
				"settings.pingforit.mark_fluids",
				"settings.pingforit.wheel_font_size",
				"settings.pingforit.wheel_target_font_size",
				"settings.pingforit.marker_display_duration",
				"settings.pingforit.marker_display_duration.tooltip",
				"settings.pingforit.long_press_compatibility_mode",
				"settings.pingforit.long_press_compatibility_mode.tooltip",
				"settings.pingforit.long_press_compatibility_slice_millis",
				"settings.pingforit.long_press_compatibility_slice_millis.tooltip",
				"settings.pingforit.reset_all",
				"settings.pingforit.reset_all.title",
				"settings.pingforit.reset_all.message")) {
				nonBlankTranslation(json, locale, key);
			}
			nonBlankTranslation(json, locale, "settings.pingforit.open_block_shape_blacklist_config");
			for (String key : SERVER_SETTINGS_KEYS) {
				nonBlankTranslation(json, locale, key);
			}
			nonBlankTranslation(json, locale, "unit.pingforit.milliseconds");
			nonBlankTranslation(json, locale, "unit.pingforit.seconds");
			nonBlankTranslation(json, locale, "value.pingforit.follow_server");
			assertTrue(
				json.get("settings.pingforit.long_press_compatibility_slice_millis").getAsString().contains("%s"),
				() -> "compatibility slice must be formatted: " + locale);
		}
	}

	@Test
	void everyBundledLocaleContainsAllEntityBlockRenderModeLabelsAndTooltips() throws IOException {
		List<String> keys = List.of(
			"settings.pingforit.entity_block_render_mode",
			"settings.pingforit.entity_block_render_mode.tooltip",
			"settings.pingforit.entity_block_render_mode.all.tooltip",
			"settings.pingforit.entity_block_render_mode.compatible.tooltip",
			"settings.pingforit.entity_block_render_mode.voxel_shape_only.tooltip",
			"value.pingforit.all",
			"value.pingforit.compatible",
			"value.pingforit.voxel_shape_only");

		for (String locale : BUNDLED_LOCALES) {
			JsonObject json = readLocaleJson(locale);
			for (String key : keys) {
				nonBlankTranslation(json, locale, key);
			}
		}
	}

	@Test
	void everyExternalListTooltipRequiresRestartingTheClient() throws IOException {
		for (String locale : BUNDLED_LOCALES) {
			String tooltip = readTranslation(locale,
				"settings.pingforit.open_block_shape_blacklist_config.tooltip");
			assertTrue(
				tooltip.contains(EXTERNAL_LIST_RESTART_MARKERS.get(locale)),
				() -> "external list tooltip must require a client restart: " + locale);
		}
	}

	@Test
	void playerAndTeamSettingsAreFullyTranslatedInPreviouslyIncompleteLocales() throws IOException {
		for (String locale : List.of("zh_cn", "zh_tw", "fr_fr", "pl_pl", "tr_tr")) {
			JsonObject json = readLocaleJson(locale);
			for (String key : List.of(
				"settings.pingforit.player_info_mode",
				"settings.pingforit.player_info_mode.hold.tooltip",
				"settings.pingforit.team_color_mode")) {
				nonBlankTranslation(json, locale, key);
			}
			for (String value : List.of("hold", "always", "compact", "full", "ping_only", "labels_only")) {
				nonBlankTranslation(json, locale, "value.pingforit." + value);
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
		var matcher = Pattern.compile(
			"\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(contents);
		assertTrue(matcher.find(), () -> "missing en_us translation: " + key);
		assertFalse(matcher.group(1).isBlank(), () -> "blank en_us translation: " + key);
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
