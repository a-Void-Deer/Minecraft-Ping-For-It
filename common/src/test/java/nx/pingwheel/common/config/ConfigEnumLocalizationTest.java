package nx.pingwheel.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nx.pingwheel.common.resource.LanguageUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
@ResourceLock(Resources.LOCALE)
class ConfigEnumLocalizationTest {
	private static final @Unmodifiable List<Locale> DEFAULT_LOCALES = List.of(
		Locale.ROOT, Locale.US, Locale.forLanguageTag("tr-TR"));
	private static final @Unmodifiable List<String> RESOURCE_LOCALES = List.of("en_us", "tr_tr");

	@Test
	void enumLabelsAndDynamicTooltipsResolveRegardlessOfTheDefaultLocale() throws IOException {
		Locale previous = Locale.getDefault();
		Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
		Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
		try {
			for (Locale defaultLocale : DEFAULT_LOCALES) {
				Locale.setDefault(defaultLocale);
				// This key used to contain a dotless i under a Turkish JVM default.
				assertEquals("settings.pingforit.player_info_mode.disabled.tooltip",
					LanguageUtils.settings("player_info_mode")
						.path(PlayerInfoMode.DISABLED.toString(), "tooltip").getKey(),
					defaultLocale.toLanguageTag());

				for (String resourceLocale : RESOURCE_LOCALES) {
					JsonObject translations = readLocale(resourceLocale);
					for (PlayerInfoMode mode : PlayerInfoMode.values()) {
						assertLocalized(translations, resourceLocale, defaultLocale,
							LanguageUtils.of("value", mode.toString()).getKey());
						assertLocalized(translations, resourceLocale, defaultLocale,
							LanguageUtils.settings("player_info_mode").path(mode.toString(), "tooltip").getKey());
					}
					for (TeamColorMode mode : TeamColorMode.values()) {
						assertLocalized(translations, resourceLocale, defaultLocale,
							LanguageUtils.of("value", mode.toString()).getKey());
						assertLocalized(translations, resourceLocale, defaultLocale,
							LanguageUtils.settings("team_color_mode").path(mode.toString(), "tooltip").getKey());
					}
					for (ChannelMode mode : ChannelMode.values()) {
						assertLocalized(translations, resourceLocale, defaultLocale,
							LanguageUtils.of("value", mode.toString()).getKey());
					}
				}
			}
		} finally {
			Locale.setDefault(previous);
			Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
			Locale.setDefault(Locale.Category.FORMAT, previousFormat);
		}
	}

	private static JsonObject readLocale(String resourceLocale) throws IOException {
		String resource = "assets/pingforit/lang/" + resourceLocale + ".json";
		try (@Nullable InputStream stream = ConfigEnumLocalizationTest.class.getClassLoader()
			.getResourceAsStream(resource)) {
			assertNotNull(stream, resource);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static void assertLocalized(JsonObject translations, String resourceLocale, Locale defaultLocale, String key) {
		String context = defaultLocale.toLanguageTag() + " / " + resourceLocale + ": " + key;
		@Nullable JsonElement translation = translations.get(key);
		assertNotNull(translation, "Missing translation: " + context);
		assertTrue(translation.isJsonPrimitive() && translation.getAsJsonPrimitive().isString(), context);
		assertFalse(translation.getAsString().isBlank(), "Blank translation: " + context);
	}
}
