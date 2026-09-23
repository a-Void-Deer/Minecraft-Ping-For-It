package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
	private static final List<String> PRESENTATION_KEYS = List.of(
		"settings.pingforit.category.presentation",
		"settings.pingforit.category.presentation.button",
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
		"settings.pingforit.presentation.server.timeout");

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
		assertEquals("Client Receive", json.get("settings.pingforit.presentation.receive").getAsString());
		assertEquals("Client Display", json.get("settings.pingforit.presentation.display").getAsString());
		assertEquals("Server Policy", json.get("settings.pingforit.presentation.server.title").getAsString());
		assertEquals("Read only", json.get("settings.pingforit.presentation.server.read_only").getAsString());
		assertTrue(json.get("settings.pingforit.presentation.whitelist_only.tooltip").getAsString()
			.contains("White"));
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
			List.of("presentation_receive", "presentation_display", "presentation_server_policy"),
			SettingsCategoryCatalog.settings(SettingsNavigationModel.Category.PRESENTATION)
				.stream()
				.map(SettingsCategoryCatalog.Setting::id)
				.toList());
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
				|| key.equals("settings.pingforit.category.presentation.button")) {
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
