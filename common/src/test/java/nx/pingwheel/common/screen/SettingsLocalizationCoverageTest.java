package nx.pingwheel.common.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.network.chat.Component;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.screen.SettingsCategoryCatalog.Setting;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Resource-backed coverage for every GUI value setting. Expected keys are
 * derived from the stable {@link Setting#id()} and the production
 * {@link LanguageUtils} key builder rather than from a manually copied key
 * list, so a newly catalogued value control is covered automatically.
 */
@NotNullByDefault
class SettingsLocalizationCoverageTest {
	private static final @Unmodifiable List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:(\\d+)\\$)?([sdf%])");

	/**
	 * These two catalogue entries are not value controls. The configuration-file
	 * entry is an action button and the server-policy entry opens the rule editor;
	 * their labels and descriptions are owned by {@code ClientConfigLocalizationTest}
	 * and {@code PresentationSettingsLocalizationTest}. Every remaining catalogue
	 * entry renders a setting value and is covered by the value-control oracle.
	 */
	private static final @Unmodifiable Set<Setting> NON_VALUE_ENTRIES = Set.of(
		Setting.OPEN_CLIENT_CONFIG,
		Setting.PRESENTATION_SERVER_POLICY);

	/**
	 * Value labels whose widget embeds the rendered value in the label itself and
	 * therefore require exactly one string argument. The other value controls
	 * render their value in a separate widget or button suffix and keep literal
	 * labels.
	 */
	private static final @Unmodifiable Set<Setting> FORMATTED_LABEL_SETTINGS = Set.of(
		Setting.PING_DISTANCE,
		Setting.MARKER_DISPLAY_DURATION,
		Setting.PING_SIZE,
		Setting.SPATIAL_ROOT_DISTANCE,
		Setting.SPATIAL_SUBMENU_RADIUS_SCALE,
		Setting.SPATIAL_INVENTORY_MAX_WIDTH_FRACTION,
		Setting.SPATIAL_INVENTORY_MAX_HEIGHT_FRACTION,
		Setting.WHEEL_OPACITY,
		Setting.WHEEL_TARGET_OPACITY,
		Setting.WHEEL_TARGET_FONT_SIZE,
		Setting.WHEEL_OPTION_FONT_SIZE,
		Setting.SPATIAL_TRAIL_LENGTH,
		Setting.SPATIAL_TRAIL_OPACITY,
		Setting.WHEEL_HOLD_MILLIS,
		Setting.SPATIAL_GESTURE_SKIP_HOLD_MILLIS,
		Setting.LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
		Setting.CANCEL_HALF_CONE_ANGLE_DEGREES,
		Setting.DOUBLE_CLICK_DANGER_WINDOW,
		Setting.DOUBLE_CLICK_DANGER_ANGLE,
		Setting.SPATIAL_DEADZONE,
		Setting.SPATIAL_STROKE,
		Setting.SPATIAL_DWELL_MILLIS,
		Setting.SPATIAL_HOVER_MILLIS,
		Setting.PRECISE_CAPTURE_PERIOD_TICKS,
		Setting.PING_VOLUME,
		Setting.CONFIGURATION_NOTICE_SIZE,
		Setting.INVENTORY_DISPLAY_BYTE_MULTIPLIER,
		Setting.INVENTORY_FRAME_BYTE_MULTIPLIER);

	private static final @Unmodifiable List<String> USED_UNIT_KEYS = List.of(
		LanguageUtils.UNIT_METERS.getKey(),
		LanguageUtils.UNIT_PERCENT.getKey(),
		LanguageUtils.UNIT_MILLISECONDS.getKey(),
		LanguageUtils.UNIT_SECONDS.getKey(),
		LanguageUtils.UNIT_DEGREES.getKey(),
		LanguageUtils.UNIT_PIXELS.getKey(),
		LanguageUtils.UNIT_TICKS.getKey(),
		LanguageUtils.UNIT_POINTS.getKey(),
		LanguageUtils.UNIT_MULTIPLIER.getKey(),
		LanguageUtils.of("unit", "kib").getKey());

	private static final @Unmodifiable Set<String> USED_VALUE_IDS = usedValueIds();

	private static final @Unmodifiable List<String> STRUCTURAL_HELP_IDS = List.of(
		"scope.client.tooltip",
		"scope.server.tooltip",
		"navigation.back.tooltip",
		"navigation.done.tooltip",
		"reset_all.tooltip",
		"open_client_config.tooltip",
		"presentation.server.tooltip",
		"presentation.target_type.tooltip",
		"presentation.selector.tooltip",
		"presentation.add.tooltip",
		"presentation.remove.tooltip",
		"presentation.refresh.tooltip",
		"presentation.advanced.tooltip",
		"presentation.whitelist_only.tooltip",
		"presentation.rule.allow.tooltip",
		"presentation.rule.block.tooltip");

	/**
	 * Settings-screen format resources that are rendered with more than one value
	 * or with a separate value formatter. The signature records the type and
	 * cardinality independently of translation order.
	 */
	private static final @Unmodifiable Map<String, @Unmodifiable List<String>> SETTINGS_FORMAT_SIGNATURES = Map.of(
		LanguageUtils.settings("inventory").path("value").getKey(), List.of("1:s", "2:s"),
		LanguageUtils.settings("inventory").path("size_adjusted").getKey(), List.of("1:s", "2:s"),
		LanguageUtils.settings("channel").path("placeholder").getKey(), List.of("1:s"),
		LanguageUtils.settings("presentation").path("advanced", "counts").getKey(), List.of("1:s", "2:s"),
		LanguageUtils.settings("format").path("name_value").getKey(), List.of("1:s", "2:s"),
		LanguageUtils.settings("presentation").path("advanced", "with_counts").getKey(), List.of("1:s", "2:s"),
		LanguageUtils.settings("format").path("truncated").getKey(), List.of("1:s"),
		LanguageUtils.settings("title").path("breadcrumb").getKey(), List.of("1:s", "2:s", "3:s"),
		LanguageUtils.settings("title").path("scope").getKey(), List.of("1:s", "2:s"),
		LanguageUtils.settings("presentation").path("selector_separator").getKey(), List.of());

	@Test
	void everyCatalogValueControlHasLocalizedLabelAndTooltipInEveryLocale() throws IOException {
		Set<Setting> valueSettings = catalogValueSettings();
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (Setting setting : valueSettings) {
				requireString(root, locale, labelKey(setting));
				requireString(root, locale, tooltipKey(setting));
			}
		}
	}

	@Test
	void everyUsedValueLabelIsNonblankAndLiteralInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (String id : USED_VALUE_IDS) {
				String key = LanguageUtils.of("value", id).getKey();
				assertLocalizedFormat(root, locale, key, List.of());
			}
		}
	}

	@Test
	void everyCatalogCategoryTooltipAndSectionHeadingIsLocalizedAndLiteral() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (Category category : Category.values()) {
				assertLocalizedFormat(root, locale, SettingsCategoryCatalog.categoryTooltipKey(category), List.of());
				for (var section : SettingsCategoryCatalog.sections(category)) {
					assertLocalizedFormat(root, locale, section.headingKey(), List.of());
				}
			}
		}
	}

	@Test
	void everyStructuralHelpTooltipIsLocalizedAndLiteral() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (String id : STRUCTURAL_HELP_IDS) {
				assertLocalizedFormat(root, locale, LanguageUtils.settings(id).getKey(), List.of());
			}
		}
	}

	@Test
	void everyEnumDefaultAndDynamicModeTooltipHasTheRequiredLocalizedFormat() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (Setting setting : List.of(Setting.PLAYER_INFO_MODE, Setting.TEAM_COLOR_MODE,
				Setting.ENTITY_BLOCK_RENDER_MODE, Setting.DEFAULT_CHANNEL_MODE)) {
				assertLocalizedFormat(root, locale, tooltipKey(setting), List.of());
			}
			for (PlayerInfoMode mode : PlayerInfoMode.values()) {
				String key = LanguageUtils.settings("player_info_mode").path(mode.toString(), "tooltip").getKey();
				@Unmodifiable List<String> expectedSignature = mode == PlayerInfoMode.HOLD
					? List.of("1:s", "2:s") : List.of();
				assertLocalizedFormat(root, locale, key, expectedSignature);
			}
			for (TeamColorMode mode : TeamColorMode.values()) {
				String key = LanguageUtils.settings("team_color_mode").path(mode.toString(), "tooltip").getKey();
				assertLocalizedFormat(root, locale, key, List.of());
			}
			for (EntityBlockRenderMode mode : EntityBlockRenderMode.values()) {
				String key = LanguageUtils.settings("entity_block_render_mode").path(mode.toString(), "tooltip").getKey();
				assertLocalizedFormat(root, locale, key, List.of());
			}
		}
	}

	@Test
	void everyUsedUnitHasOneLocalizedValueAndAValidFormatInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (String key : USED_UNIT_KEYS) {
				String value = requireString(root, locale, key);
				assertEquals(List.of("1:s"), signature(value), () -> locale + ":" + key);
				assertJavaFormatterAccepts(value, List.of("1:s"), locale + ":" + key);
			}
		}
	}

	@Test
	void settingsLabelAndTooltipFormatsAreCompatibleAcrossBundledLocales() throws IOException {
		Set<Setting> valueSettings = catalogValueSettings();
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (Setting setting : valueSettings) {
				@Unmodifiable List<String> expectedLabelSignature = FORMATTED_LABEL_SETTINGS.contains(setting)
					? List.of("1:s")
					: List.of();
				assertLocalizedFormat(root, locale, labelKey(setting), expectedLabelSignature);
				assertLocalizedFormat(root, locale, tooltipKey(setting), List.of());
			}
			for (Map.Entry<String, List<String>> entry : SETTINGS_FORMAT_SIGNATURES.entrySet()) {
				assertLocalizedFormat(root, locale, entry.getKey(), entry.getValue());
			}
		}
	}

	@Test
	void zeroArgumentResourceProseAllowsBareAndEscapedPercents() {
		String key = "test.pingforit.zero_argument_percent";
		for (String text : List.of("Use 100% of configured budget.", "Use 100%% of configured budget.")) {
			JsonObject root = new JsonObject();
			root.addProperty(key, text);
			assertLocalizedFormat(root, "test", key, List.of());
			assertEquals("Use 100% of configured budget.", Component.translatableWithFallback(key, text).getString());
		}
	}

	@Test
	void zeroArgumentResourcesRejectConsumingPlaceholdersButNotEscapedTokens() {
		String key = "test.pingforit.zero_argument_placeholder";
		JsonObject root = new JsonObject();
		root.addProperty(key, "Show %%s literally.");
		assertLocalizedFormat(root, "test", key, List.of());
		assertEquals("Show %s literally.", Component.translatableWithFallback(key, "Show %%s literally.").getString());
		for (String placeholder : List.of("%s", "%1$s", "%d", "%1$d", "%f", "%1$f", "%%%s")) {
			root.addProperty(key, "Use " + placeholder + " of configured budget.");
			assertThrows(AssertionError.class, () -> assertLocalizedFormat(root, "test", key, List.of()), placeholder);
		}
		assertEquals("Use value of configured budget.", Component.translatableWithFallback(key,
			"Use %s of configured budget.", "value").getString());
	}

	@Test
	void argumentTemplatesStillRequireStrictPercentFormatting() {
		String key = "test.pingforit.argument_percent";
		JsonObject root = new JsonObject();
		root.addProperty(key, "Use %s%% of configured budget.");
		assertLocalizedFormat(root, "test", key, List.of("1:s"));
		assertEquals("Use 100% of configured budget.", Component.translatableWithFallback(key,
			"Use %s%% of configured budget.", "100").getString());
		root.addProperty(key, "Use %s% of configured budget.");
		assertThrows(AssertionError.class, () -> assertLocalizedFormat(root, "test", key, List.of("1:s")));
	}

	@Test
	void everyBundledLocaleRejectsDuplicateTranslationKeys() throws IOException {
		for (String locale : LOCALES) {
			assertNoDuplicateKeys(locale, readLocale(locale));
		}
	}

	private static Set<Setting> catalogValueSettings() {
		Set<Setting> settings = EnumSet.noneOf(Setting.class);
		for (Category category : Category.values()) {
			settings.addAll(SettingsCategoryCatalog.settings(category));
		}
		settings.removeAll(NON_VALUE_ENTRIES);
		return settings;
	}

	private static Set<String> usedValueIds() {
		Set<String> ids = new LinkedHashSet<>();
		for (PlayerInfoMode mode : PlayerInfoMode.values()) ids.add(mode.toString());
		for (TeamColorMode mode : TeamColorMode.values()) ids.add(mode.toString());
		for (ChannelMode mode : ChannelMode.values()) ids.add(mode.toString());
		for (EntityBlockRenderMode mode : EntityBlockRenderMode.values()) ids.add(mode.toString());
		ids.add(TeamContext.VANILLA_TEAM.toString());
		ids.add("global");
		ids.add("follow_server");
		ids.add("enabled");
		ids.add("off");
		ids.add("hidden");
		ids.add("infinite");
		return Set.copyOf(ids);
	}

	private static String labelKey(Setting setting) {
		return LanguageUtils.settings(setting.id()).getKey();
	}

	private static String tooltipKey(Setting setting) {
		return LanguageUtils.settings(setting.id()).path("tooltip").getKey();
	}

	private static void assertLocalizedFormat(JsonObject root, String locale, String key,
		@Unmodifiable List<String> expectedSignature) {
		String value = requireString(root, locale, key);
		if (expectedSignature.isEmpty()) {
			assertNoArgumentPlaceholders(value, locale + ":" + key);
			return;
		}
		assertEquals(expectedSignature, signature(value), () -> locale + ":" + key);
		assertJavaFormatterAccepts(value, expectedSignature, locale + ":" + key);
	}

	/** Zero-argument prose may use Minecraft's literal fallback, but must not consume an absent argument. */
	private static void assertNoArgumentPlaceholders(String value, String description) {
		Matcher matcher = FORMAT_SPECIFIER.matcher(value);
		while (matcher.find()) {
			assertEquals("%", matcher.group(2), () -> description + " has an argument placeholder without arguments: " + value);
		}
	}

	private static JsonObject read(String locale) throws IOException {
		return JsonParser.parseString(readLocale(locale)).getAsJsonObject();
	}

	private static String readLocale(String locale) throws IOException {
		try (var stream = SettingsLocalizationCoverageTest.class.getClassLoader()
			.getResourceAsStream("assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream, locale);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String requireString(JsonObject root, String locale, String key) {
		assertTrue(root.has(key), () -> locale + ": missing translation " + key);
		var element = root.get(key);
		assertTrue(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
			() -> locale + ": translation is not a string " + key);
		String value = element.getAsString();
		assertFalse(value.isBlank(), () -> locale + ": blank translation " + key);
		return value;
	}

	/**
	 * Parses Minecraft/Java-style placeholders. {@code %%} is a literal percent;
	 * unindexed {@code %s}/{@code %d}/{@code %f} receive implicit indexes and
	 * indexed forms keep their explicit argument slot. Reordering indexed
	 * arguments is allowed, while a changed type or missing slot is not.
	 */
	private static @Unmodifiable List<String> signature(String value) {
		SortedMap<Integer, String> arguments = new TreeMap<>();
		int implicitIndex = 0;
		int cursor = 0;
		Matcher matcher = FORMAT_SPECIFIER.matcher(value);
		while (matcher.find()) {
			if (value.substring(cursor, matcher.start()).indexOf('%') >= 0) {
				fail("unsupported format specifier in translation: " + value);
			}
			String explicitIndex = matcher.group(1);
			String type = matcher.group(2);
			if (type.equals("%")) {
				if (explicitIndex != null) {
					fail("indexed percent literal in translation: " + value);
				}
			} else {
				int index = explicitIndex == null ? ++implicitIndex : parseFormatIndex(value, explicitIndex);
				String previous = arguments.putIfAbsent(index, type);
				if (previous != null && !previous.equals(type)) {
					fail("format argument " + index + " has conflicting types in translation: " + value);
				}
			}
			cursor = matcher.end();
		}
		if (value.substring(cursor).indexOf('%') >= 0) {
			fail("unsupported format specifier in translation: " + value);
		}

		List<String> result = new ArrayList<>(arguments.size());
		for (Map.Entry<Integer, String> entry : arguments.entrySet()) {
			result.add(entry.getKey() + ":" + entry.getValue());
		}
		return List.copyOf(result);
	}

	private static int parseFormatIndex(String value, String index) {
		try {
			int parsed = Integer.parseInt(index);
			if (parsed <= 0) {
				return fail("format argument indexes are one-based in translation: " + value);
			}
			return parsed;
		} catch (NumberFormatException error) {
			return fail("invalid format argument index in translation: " + value, error);
		}
	}

	private static void assertJavaFormatterAccepts(String value, List<String> signature, String description) {
		int maximumIndex = 0;
		for (String argument : signature) {
			maximumIndex = Math.max(maximumIndex, Integer.parseInt(argument.substring(0, argument.indexOf(':'))));
		}
		List<Object> arguments = new ArrayList<>(Collections.nCopies(maximumIndex, "value"));
		for (String argument : signature) {
			int separator = argument.indexOf(':');
			int index = Integer.parseInt(argument.substring(0, separator)) - 1;
			arguments.set(index, switch (argument.substring(separator + 1)) {
				case "s" -> "value";
				case "d" -> 1;
				case "f" -> 1.0d;
				default -> throw new AssertionError("unsupported placeholder type: " + argument);
			});
		}

		try {
			String formatted = String.format(Locale.ROOT, value, arguments.toArray());
			assertFalse(formatted.isBlank(), () -> description + " formats to a blank value");
		} catch (IllegalFormatException error) {
			fail(description + " is not a valid Java format: " + error.getMessage(), error);
		}
	}

	private static void assertNoDuplicateKeys(String locale, String contents) throws IOException {
		try (JsonReader reader = new JsonReader(new StringReader(contents))) {
			reader.setLenient(false);
			assertEquals(JsonToken.BEGIN_OBJECT, reader.peek(), () -> locale + ": translation root must be an object");
			readJsonValue(reader, locale, "");
			assertEquals(JsonToken.END_DOCUMENT, reader.peek(), () -> locale + ": trailing JSON content");
		}
	}

	private static void readJsonValue(JsonReader reader, String locale, String path) throws IOException {
		JsonToken token = reader.peek();
		switch (token) {
			case BEGIN_OBJECT -> {
				Set<String> names = new HashSet<>();
				reader.beginObject();
				while (reader.hasNext()) {
					String name = reader.nextName();
					if (!names.add(name)) {
						fail("duplicate translation key in " + locale + ": " + path + name);
					}
					readJsonValue(reader, locale, path + name + ".");
				}
				reader.endObject();
			}
			case BEGIN_ARRAY -> {
				reader.beginArray();
				int index = 0;
				while (reader.hasNext()) {
					readJsonValue(reader, locale, path + "[" + index++ + "].");
				}
				reader.endArray();
			}
			default -> reader.skipValue();
		}
	}
}
