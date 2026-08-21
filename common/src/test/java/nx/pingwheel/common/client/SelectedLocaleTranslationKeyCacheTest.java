package nx.pingwheel.common.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.locale.Language;
import org.junit.jupiter.api.Test;

import nx.pingwheel.common.chat.PingChatBuilder;
import nx.pingwheel.common.domain.PingType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedLocaleTranslationKeyCacheTest {

	private static final String SELECTED_LOCALE = "zh_cn";
	private static final String ENGLISH_LOCALE = "en_us";

	@Test
	void mergedEnglishOverrideDoesNotApplyToSelectedLocaleWithoutOwnKey() {
		PingType pingType = pingType("danger");
		String overrideKey = PingChatBuilder.templateOverrideKey(pingType);
		Set<String> mergedLanguageKeys = Set.of(PingChatBuilder.TEMPLATE_KEY, overrideKey);
		SelectedLocaleTranslationKeyCache cache = new SelectedLocaleTranslationKeyCache(
			(code, ignoredResourceManager) ->
				code.equals(SELECTED_LOCALE) ? Set.of(PingChatBuilder.TEMPLATE_KEY) : mergedLanguageKeys);

		assertTrue(mergedLanguageKeys.contains(overrideKey));
		assertEquals(
			PingChatBuilder.TEMPLATE_KEY,
			PingChatBuilder.selectTemplateKey(
				pingType,
				key -> cache.contains(SELECTED_LOCALE, new Object(), null, key)));
	}

	@Test
	void selectedLocaleResourcePackOverrideIsSelected() {
		PingType pingType = pingType("danger");
		String overrideKey = PingChatBuilder.templateOverrideKey(pingType);
		Object languageIdentity = new Object();
		SelectedLocaleTranslationKeyCache cache = new SelectedLocaleTranslationKeyCache(
			(code, ignoredResourceManager) -> Set.of(PingChatBuilder.TEMPLATE_KEY, overrideKey));

		assertEquals(
			overrideKey,
			PingChatBuilder.selectTemplateKey(
				pingType,
				key -> cache.contains(SELECTED_LOCALE, languageIdentity, null, key)));
	}

	@Test
	void selectedEnglishLocaleOverrideIsSelected() {
		PingType pingType = pingType("danger");
		String overrideKey = PingChatBuilder.templateOverrideKey(pingType);
		SelectedLocaleTranslationKeyCache cache = new SelectedLocaleTranslationKeyCache(
			(code, ignoredResourceManager) -> code.equals(ENGLISH_LOCALE)
				? Set.of(overrideKey)
				: Set.of());

		assertEquals(overrideKey, selectedKey(cache, ENGLISH_LOCALE, new Object(), pingType));
	}

	@Test
	void cacheLoadsOnceUntilLanguageIdentityOrSelectedCodeChanges() {
		AtomicInteger loads = new AtomicInteger();
		SelectedLocaleTranslationKeyCache cache = new SelectedLocaleTranslationKeyCache(
			(code, ignoredResourceManager) -> {
				loads.incrementAndGet();
				return Set.of("key." + code);
			});
		Object firstLanguage = new Object();
		Object secondLanguage = new Object();

		assertTrue(cache.contains(SELECTED_LOCALE, firstLanguage, null, "key." + SELECTED_LOCALE));
		assertTrue(cache.contains(SELECTED_LOCALE, firstLanguage, null, "key." + SELECTED_LOCALE));
		assertEquals(1, loads.get());

		assertTrue(cache.contains(SELECTED_LOCALE, secondLanguage, null, "key." + SELECTED_LOCALE));
		assertEquals(2, loads.get());

		assertTrue(cache.contains(ENGLISH_LOCALE, secondLanguage, null, "key." + ENGLISH_LOCALE));
		assertEquals(3, loads.get());
	}

	@Test
	void emptyOrMissingSelectedLocaleStackFallsBackToGeneralTemplate() {
		PingType pingType = pingType("danger");
		SelectedLocaleTranslationKeyCache cache = new SelectedLocaleTranslationKeyCache(
			(code, ignoredResourceManager) -> Set.of());

		assertEquals(
			PingChatBuilder.TEMPLATE_KEY,
			selectedKey(cache, SELECTED_LOCALE, new Object(), pingType));
	}

	@Test
	void malformedResourceIsSkippedWhileValidStackResourceContributesKeys() {
		TrackingInputStream malformed = resource("{ malformed");
		TrackingInputStream valid = resource("{\"pingforit.valid\":\"value\"}");

		Set<String> keys = SelectedLocaleTranslationKeyCache.collectResourceKeys(
			List.of(malformed, valid),
			inputStream -> inputStream,
			SelectedLocaleTranslationKeyCacheTest::parseKeys);

		assertFalse(keys.contains("pingforit.malformed"));
		assertTrue(keys.contains("pingforit.valid"));
		assertTrue(malformed.closed);
		assertTrue(valid.closed);
	}

	@Test
	void resourceOpeningFailureIsSkippedWhileLaterResourcesStillContribute() {
		TrackingInputStream valid = resource("{\"pingforit.valid\":\"value\"}");
		AtomicInteger opened = new AtomicInteger();

		Set<String> keys = SelectedLocaleTranslationKeyCache.collectResourceKeys(
			List.of("broken", "valid"),
			resource -> {
				opened.incrementAndGet();
				if (resource.equals("broken")) {
					throw new IOException("broken resource");
				}
				return valid;
			},
			SelectedLocaleTranslationKeyCacheTest::parseKeys);

		assertTrue(keys.contains("pingforit.valid"));
		assertEquals(2, opened.get());
		assertTrue(valid.closed);
	}

	@Test
	void selectedKeyIsUsedToReadTheCurrentMergedTemplateValue() {
		PingType pingType = pingType("danger");
		String overrideKey = PingChatBuilder.templateOverrideKey(pingType);
		Map<String, String> mergedLanguage = new HashMap<>();
		mergedLanguage.put(PingChatBuilder.TEMPLATE_KEY, "general value");
		mergedLanguage.put(overrideKey, "selected pack value");
		SelectedLocaleTranslationKeyCache cache = new SelectedLocaleTranslationKeyCache(
			(code, ignoredResourceManager) -> Set.of(overrideKey));

		String selectedKey = selectedKey(cache, SELECTED_LOCALE, new Object(), pingType);

		assertEquals("selected pack value", mergedLanguage.get(selectedKey));
	}

	private static String selectedKey(
		SelectedLocaleTranslationKeyCache cache,
		String languageCode,
		Object languageIdentity,
		PingType pingType
	) {
		return PingChatBuilder.selectTemplateKey(
			pingType,
			key -> cache.contains(languageCode, languageIdentity, null, key));
	}

	private static Set<String> parseKeys(InputStream inputStream) throws IOException {
		Map<String, String> translations = new HashMap<>();
		Language.loadFromJson(inputStream, translations::put);
		return translations.keySet();
	}

	private static TrackingInputStream resource(String json) {
		return new TrackingInputStream(json.getBytes(StandardCharsets.UTF_8));
	}

	private static PingType pingType(String id) {
		return nx.pingwheel.common.domain.PingTypeCatalog.builtIn()
			.findById(id)
			.orElseThrow();
	}

	private static final class TrackingInputStream extends ByteArrayInputStream {
		private boolean closed;

		private TrackingInputStream(byte[] bytes) {
			super(bytes);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}
}
