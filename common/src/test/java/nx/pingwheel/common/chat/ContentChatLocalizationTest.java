package nx.pingwheel.common.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.domain.PingTypeCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentChatLocalizationTest {

	private static final List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final List<String> TEMPLATE_TOKENS = List.of(
		"{author}", "{type}", "{target}", "{content}");
	private static final List<String> CONTENT_KEYS = List.of(
		ContentChatTemplate.TEMPLATE_KEY,
		ContentChatTemplate.MULTIPLE_TEMPLATE_KEY,
		ContentChatTemplate.ENTRY_TEMPLATE_KEY,
		ContentChatTemplate.SEPARATOR_KEY,
		ContentChatTemplate.QUOTE_KEY,
		ContentChatTemplate.CUSTOM_ITEM_NAME_KEY,
		ContentChatTemplate.FIELD_KEY,
		ContentChatTemplate.ITEM_COUNT_KEY,
		ContentChatTemplate.QUALITY_KEY);
	private static final List<String> CUSTOM_NAME_KEYS = List.of(
		"settings.pingforit.presentation.field.minecraft_target_custom_name.name",
		"settings.pingforit.presentation.field.minecraft_target_custom_name.description");

	@Test void everyLocaleOwnsTheCompleteContentVocabulary() throws IOException {
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			for (String key : CONTENT_KEYS) require(json, key);
			for (String key : CUSTOM_NAME_KEYS) require(json, key);

			String template = require(json, ContentChatTemplate.TEMPLATE_KEY);
			for (String token : TEMPLATE_TOKENS)
				assertTrue(template.contains(token), () -> locale + " template is missing " + token);
			String multiple = require(json, ContentChatTemplate.MULTIPLE_TEMPLATE_KEY);
			for (String token : List.of("{author}", "{target}", "{content}"))
				assertTrue(multiple.contains(token), () -> locale + " multiple template is missing " + token);
			assertFalse(multiple.contains("{type}"), "the outer message must not apply one annotation to the set");
			String entry = require(json, ContentChatTemplate.ENTRY_TEMPLATE_KEY);
			assertEquals(1, occurrences(entry, "{type}"), "each bundled entry contains its own annotation exactly once");
			assertEquals(1, occurrences(entry, "{content}"));
			Component author = Component.literal("Author"), target = Component.literal("Target"), content = Component.literal("Content");
			var annotation = PingTypeCatalog.builtIn().findById("danger").orElseThrow();
			assertTrue(ContentChatTemplate.parse(template, author, annotation, target, content).isPresent(),
				() -> locale + " single template must actually parse, not just contain tokens");
			assertTrue(ContentChatTemplate.parseMultiple(multiple, author, target, content).isPresent(),
				() -> locale + " multiple template must actually parse");
			assertTrue(ContentChatTemplate.parseEntry(entry, annotation, content).isPresent(),
				() -> locale + " entry template must actually parse");

			assertEquals(1, placeholderCount(require(json, ContentChatTemplate.QUOTE_KEY)),
				() -> locale + " quote must wrap exactly one text value");
			assertEquals(2, placeholderCount(require(json, ContentChatTemplate.CUSTOM_ITEM_NAME_KEY)),
				() -> locale + " custom item name must retain quoted custom text and localized base");
			assertEquals(2, placeholderCount(require(json, ContentChatTemplate.FIELD_KEY)),
				() -> locale + " field must carry its label and value");
			assertEquals(2, placeholderCount(require(json, ContentChatTemplate.ITEM_COUNT_KEY)),
				() -> locale + " item count must carry its item and count");
			assertEquals(2, placeholderCount(require(json, ContentChatTemplate.QUALITY_KEY)),
				() -> locale + " quality wrapper must carry its content and status");

			String separator = require(json, ContentChatTemplate.SEPARATOR_KEY);
			assertFalse(separator.isBlank(), () -> locale + " separator must be a written connective");
			assertFalse(separator.contains("%s"), () -> locale + " separator takes no placeholder");
		}
	}

	@Test void contentKeySetAndPlaceholderCountsStayInParityAcrossLocales() throws IOException {
		JsonObject english = read("en_us");
		Set<String> expectedKeys = contentKeys(english);
		assertEquals(CONTENT_KEYS.size(), expectedKeys.size(),
			"every declared content key must exist in the default locale");

		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			assertEquals(expectedKeys, contentKeys(json), () -> "content key mismatch in " + locale);
			for (String key : expectedKeys)
				assertEquals(placeholderCount(english.get(key).getAsString()),
					placeholderCount(json.get(key).getAsString()),
					() -> locale + " placeholder mismatch for " + key);
		}
	}

	private static Set<String> contentKeys(JsonObject json) {
		Set<String> keys = new TreeSet<>();
		for (String key : json.keySet())
			if (key.startsWith("pingforit.chat.content.")) keys.add(key);
		return keys;
	}

	private static int placeholderCount(String value) {
		int count = 0;
		for (int index = value.indexOf("%s"); index >= 0; index = value.indexOf("%s", index + 2)) count++;
		return count;
	}

	private static int occurrences(String value, String token) {
		int count = 0;
		for (int index = value.indexOf(token); index >= 0; index = value.indexOf(token, index + token.length())) count++;
		return count;
	}

	private static String require(JsonObject json, String key) {
		assertTrue(json.has(key), "missing " + key);
		String value = json.get(key).getAsString();
		assertFalse(value.isBlank(), "blank " + key);
		return value;
	}

	private static JsonObject read(String locale) throws IOException {
		try (var stream = ContentChatLocalizationTest.class.getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
