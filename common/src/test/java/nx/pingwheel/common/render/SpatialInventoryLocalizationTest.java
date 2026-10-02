package nx.pingwheel.common.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the inventory localization contract of the native spatial overlay: the
 * keys {@link SpatialOverlayRenderer} resolves directly, and the inventory
 * status vocabulary the native projection publishes, stay present, literal and
 * aligned across every bundled locale. Exact wording is not asserted.
 */
class SpatialInventoryLocalizationTest {
	private static final List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	/** Keys passed to {@code Component.translatable} by the inventory list panel. */
	private static final List<String> RENDERED_KEYS = List.of(
		"pingforit.spatial.inventory.updating",
		"pingforit.spatial.inventory.back",
		"pingforit.spatial.inventory.forward");
	/** Distinct inventory states the native projection presents. */
	private static final List<String> STATUS_KEYS = List.of(
		"pingforit.spatial.inventory.updating",
		"pingforit.spatial.inventory.unknown",
		"pingforit.spatial.inventory.uncertain",
		"pingforit.spatial.inventory.incomplete",
		"pingforit.spatial.inventory.unavailable",
		"pingforit.spatial.inventory.invalid",
		"pingforit.spatial.inventory.component_too_long",
		"pingforit.spatial.inventory.expired");

	@Test void everyLocaleOwnsTheRendererReferencedInventoryKeys() throws IOException {
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			for (String key : RENDERED_KEYS) {
				requireLiteral(json, locale, key);
			}
		}
	}

	@Test void everyLocaleOwnsEveryInventoryStatusLabel() throws IOException {
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			for (String key : STATUS_KEYS) {
				requireLiteral(json, locale, key);
			}
		}
	}

	@Test void spatialInventoryKeySetsStayAlignedAcrossLocales() throws IOException {
		Set<String> parity = spatialKeys(read("en_us"));
		for (String locale : LOCALES) {
			assertEquals(parity, spatialKeys(read(locale)), () -> "spatial inventory key mismatch in " + locale);
		}
	}

	private static String requireLiteral(JsonObject json, String locale, String key) {
		assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
		String value = json.get(key).getAsString();
		assertFalse(value.isBlank(), () -> "blank translation: " + locale + ":" + key);
		assertFalse(value.contains("%s"), () -> "literal inventory label must not be formatted: " + locale + ":" + key);
		return value;
	}

	private static Set<String> spatialKeys(JsonObject json) {
		Set<String> keys = new TreeSet<>();
		for (String key : json.keySet())
			if (key.startsWith("pingforit.spatial.inventory.")) keys.add(key);
		return keys;
	}

	private static JsonObject read(String locale) throws IOException {
		try (var stream = SpatialInventoryLocalizationTest.class.getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
