package nx.pingwheel.common.presentation.inventory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryFailureLocalizationTest {
	private static final @Unmodifiable List<String> LOCALES = List.of("de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	@Test void everyLocaleOwnsAllReasonsAndSeparateBoundedFormatKeys() throws IOException {
		Set<String> expected = new TreeSet<>();
		for (InventoryFailureReason reason : InventoryFailureReason.values()) expected.add(reason.translationKey());
		for (String locale : LOCALES) {
			JsonObject json = read(locale); Set<String> actual = new TreeSet<>();
			for (String key : json.keySet()) if (key.startsWith("pingforit.inventory.reason.")) actual.add(key);
			assertEquals(expected, actual, locale);
			for (String key : expected) { assertFalse(json.get(key).getAsString().isBlank(), locale + ":" + key); assertFalse(json.get(key).getAsString().contains("%")); }
			String retry = json.get("pingforit.inventory.retry").getAsString();
			assertEquals(2, placeholders(retry), locale); assertTrue(retry.contains("%s/3"), locale);
			String tracking = json.get("pingforit.inventory.tracking_failed").getAsString();
			assertEquals(1, placeholders(tracking), locale); assertTrue(tracking.startsWith("[pingforit] "), locale); assertTrue(tracking.endsWith("%s"), locale);
			assertEquals("[ping for it] ", json.get("pingforit.chat.local_error.prefix").getAsString(), locale);
		}
	}
	private static int placeholders(String value) {
		int count = 0;
		for (int i = value.indexOf("%s"); i >= 0; i = value.indexOf("%s", i + 2)) count++;
		return count;
	}
	private static JsonObject read(String locale) throws IOException {
		try (@Nullable InputStream stream = InventoryFailureLocalizationTest.class.getClassLoader().getResourceAsStream("assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
