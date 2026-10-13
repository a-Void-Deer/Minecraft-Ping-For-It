package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Localization boundaries for the content-intent setting and the root
 * marker-removal label: every bundled locale carries the new setting's label
 * and tooltip, the tooltip states next-hold application, and the stable root
 * cancel key keeps its identity while its value states marker removal instead
 * of a generic cancellation. Exact non-English wording is not asserted; only
 * the confirmed Chinese label and English removal word are pinned.
 */
class SpatialSelectorSettingsLocalizationTest {
	private static final List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final String LABEL_KEY = "settings.pingforit.spatial_selector.content_intent_enabled";
	private static final String TOOLTIP_KEY = LABEL_KEY + ".tooltip";
	private static final String CANCEL_KEY = "pingforit.spatial.cancel";

	/** Each locale's established phrase for applying a preference on the next hold. */
	private static final Map<String, String> NEXT_HOLD_MARKERS = Map.of(
		"de_de", "nächsten halten",
		"en_us", "next hold",
		"es_ar", "mantener pulsado de nuevo",
		"fr_fr", "prochain maintien",
		"pl_pl", "następnego przytrzymania",
		"tr_tr", "sonraki basılı tutmada",
		"zh_cn", "下次按住生效",
		"zh_tw", "下次按住生效");

	/** Each locale's marker-removal wording for the root cancel label. */
	private static final Map<String, String> REMOVAL_MARKERS = Map.of(
		"de_de", "entfern",
		"en_us", "unping",
		"es_ar", "quitar",
		"fr_fr", "retirer",
		"pl_pl", "usu",
		"tr_tr", "kaldır",
		"zh_cn", "移除",
		"zh_tw", "移除");

	@Test
	void everyBundledLocaleCarriesTheContentIntentLabelAndTooltip() throws IOException {
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			String label = nonBlank(json, locale, LABEL_KEY);
			String tooltip = nonBlank(json, locale, TOOLTIP_KEY);
			assertFalse(label.contains("%"), () -> "boolean label must not be formatted: " + locale);
			assertFalse(tooltip.contains("%"), () -> "tooltip must not be formatted: " + locale);
			assertFalse(label.equals(tooltip), () -> "label and tooltip must differ: " + locale);
			assertTrue(lowered(tooltip).contains(NEXT_HOLD_MARKERS.get(locale)),
				() -> "tooltip must state next-hold application: " + locale);
		}
		assertEquals("为内容启用意图目标指示",
			nonBlank(read("zh_cn"), "zh_cn", LABEL_KEY),
			"the confirmed Chinese content-intent label is exact");
	}

	@Test
	void contentIntentKeysExistInEveryBundledLocaleWithoutStaleVariants() throws IOException {
		Set<String> expected = Set.of(LABEL_KEY, TOOLTIP_KEY);
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			Set<String> actual = new TreeSet<>();
			for (String key : json.keySet()) {
				if (key.startsWith(LABEL_KEY)) actual.add(key);
			}
			assertEquals(expected, actual, locale);
		}
	}

	@Test
	void rootCancelKeyKeepsItsIdentityAndStatesMarkerRemovalInEveryBundledLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			String value = nonBlank(json, locale, CANCEL_KEY);
			assertFalse(value.contains("%"), () -> "root cancel label must not be formatted: " + locale);
			assertTrue(lowered(value).contains(REMOVAL_MARKERS.get(locale)),
				() -> "root cancel label must state marker removal: " + locale + ":" + value);
		}
		assertEquals("Unping", nonBlank(read("en_us"), "en_us", CANCEL_KEY),
			"the confirmed English removal word is exact");
	}

	private static String lowered(String value) {
		return value.toLowerCase(Locale.ROOT);
	}

	private static String nonBlank(JsonObject json, String locale, String key) {
		assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
		String value = json.get(key).getAsString();
		assertFalse(value.isBlank(), () -> "blank translation: " + locale + ":" + key);
		return value;
	}

	private static JsonObject read(String locale) throws IOException {
		try (InputStream stream = SpatialSelectorSettingsLocalizationTest.class.getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
