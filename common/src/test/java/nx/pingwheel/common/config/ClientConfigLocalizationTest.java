package nx.pingwheel.common.config;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigLocalizationTest {

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
		for (String locale : java.util.List.of(
			"en_us", "zh_cn", "de_de", "es_ar", "fr_fr", "pl_pl", "tr_tr", "uk_ua", "zh_tw")) {
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
	void chatLegacyAndGeneralTemplatesAreRenamedWithoutOldAliases() throws IOException {
		assertEquals("%s requests %s %s", readTranslation("en_us", "pingforit.chat.pingmsg"));
		assertEquals(
			"{playerName} requests {pingType} {targetName}",
			readTranslation("en_us", "pingforit.chat.pingmsg.template"));
		assertEquals("%s 请求 %s %s", readTranslation("zh_cn", "pingforit.chat.pingmsg"));
		assertEquals(
			"{playerName} 请求 {pingType} {targetName}",
			readTranslation("zh_cn", "pingforit.chat.pingmsg.template"));
		assertTranslationAbsent("en_us", "pingforit.chat." + "request");
		assertTranslationAbsent("en_us", "pingforit.chat." + "request.template");
		assertTranslationAbsent("zh_cn", "pingforit.chat." + "request");
		assertTranslationAbsent("zh_cn", "pingforit.chat." + "request.template");
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
