package nx.pingwheel.common.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.domain.ResolvedTarget;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetTypeCatalog;
import nx.pingwheel.common.interaction.cancel.WorldVector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the native selector facade's label contract: every non-blank menu label
 * the headless session publishes is a localization key that exists, is
 * non-blank and carries no format placeholder in every bundled locale. Toggle
 * and target-type keys are checked against their code-defined identities, so a
 * new enum value or catalog entry fails here instead of silently shipping an
 * unresolved key. Exact wording is not asserted.
 */
class SpatialSelectorLocalizationTest {
	private static final List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final SpatialSelectorSession.ListGeometry GEOMETRY =
		new SpatialSelectorSession.ListGeometry(4, 180, 17, 23, 19, 85);
	private static final Pattern PLACEHOLDER = Pattern.compile("%[a-zA-Z%]");
	/** Keys the content bridge publishes for its navigation groups. */
	private static final List<String> CONTENT_GROUP_KEYS = List.of(
		"presentation.pingforit.content.group.create",
		"presentation.pingforit.content.group.stress");

	@Test void facadeMenuLabelsExistAndAreNonBlankInEveryLocale() throws IOException {
		Set<String> keys = facadeLabelKeys();
		assertFalse(keys.isEmpty(), "the facade publishes no menu label");
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			for (String key : keys) {
				assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
				assertFalse(json.get(key).getAsString().isBlank(), () -> "blank translation: " + locale + ":" + key);
			}
		}
	}

	@Test void facadePublishesTheCodeDefinedToggleAndTargetTypeIdentities() {
		Set<String> keys = facadeLabelKeys();
		for (SelectorIntent.CaptureToggle toggle : SelectorIntent.CaptureToggle.values()) {
			String key = "pingforit.spatial.toggle." + toggle.name().toLowerCase(Locale.ROOT);
			assertTrue(keys.contains(key), () -> "facade does not publish " + toggle + " as " + key);
		}
		for (var type : TargetTypeCatalog.builtIn().resolutionOrder()) {
			String key = "pingforit.spatial.target_type." + type.id();
			assertTrue(keys.contains(key), () -> "facade does not publish target type " + type.id() + " as " + key);
		}
	}

	@Test void facadeMenuLabelsCarryNoPlaceholdersAndSpatialKeysStayAlignedAcrossLocales() throws IOException {
		Set<String> keys = facadeLabelKeys();
		Set<String> englishSpatial = spatialKeys(read("en_us"));
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			for (String key : keys) {
				assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
				assertEquals(List.of(), placeholders(json.get(key).getAsString()),
					() -> "menu label must not be formatted: " + locale + ":" + key);
			}
			assertEquals(englishSpatial, spatialKeys(json), () -> "spatial key mismatch in " + locale);
		}
	}

	@Test void contentGroupLabelsExistAndAreNonBlankInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject json = read(locale);
			for (String key : CONTENT_GROUP_KEYS) {
				assertTrue(json.has(key), () -> "missing translation: " + locale + ":" + key);
				assertFalse(json.get(key).getAsString().isBlank(), () -> "blank translation: " + locale + ":" + key);
			}
		}
	}

	/** Labels are read from the real root menu and its precise/settings/intent submenus. */
	private static Set<String> facadeLabelKeys() {
		Set<String> keys = new TreeSet<>();
		collect(keys, null);
		collect(keys, "precise");
		collect(keys, "settings");
		collect(keys, "intent");
		return keys;
	}

	private static void collect(Set<String> keys, String branch) {
		var session = session();
		session.open(0);
		if (branch != null) enter(session, branch, 10);
		for (var menu : session.snapshot().radial().menus()) {
			for (var choice : menu.choices()) {
				if (choice.label() != null && !choice.label().isBlank()) keys.add(choice.label());
			}
		}
	}

	private static SpatialSelectorSession<String> session() {
		var classification = TargetTypeCatalog.builtIn().findById("entity_block").orElseThrow();
		Target target = new Target.BlockTarget("minecraft:overworld", 4, 5, 6, "minecraft:chest");
		var captured = new SpatialSelectorSession.CapturedTarget("ordinary",
			new ResolvedTarget(target, classification), Optional.empty(), Optional.of(new WorldVector(1, 2, 3)));
		var settings = new SpatialSelectorSettings.Snapshot(30, 90, 120, 140,
			new BigDecimal("0.5"), false, 250, true, false);
		var fence = new SpatialSelectorSession.ContentFence(31, 42, 53, "ordinary");
		return new SpatialSelectorSession<String>(captured, Map.of(), settings, fence,
			(value, contentFence) -> null, GEOMETRY);
	}

	private static void enter(SpatialSelectorSession<String> session, String choiceId, long nowMillis) {
		var menu = session.snapshot().radial().menus().getLast();
		var choice = menu.choices().stream().filter(value -> value.id().equals(choiceId)).findFirst().orElseThrow();
		var pointer = session.snapshot().radial().pointer();
		double bearing = Math.toRadians(choice.startDegrees() + choice.spanDegrees() / 2.0);
		double distance = session.snapshot().settings().stroke() * 2.0;
		session.moveGui(menu.origin().x() + Math.sin(bearing) * distance - pointer.x(),
			menu.origin().y() - Math.cos(bearing) * distance - pointer.y(), nowMillis);
		session.tick(nowMillis + session.snapshot().settings().dwellMillis());
		assertEquals(2, session.snapshot().radial().menus().size(), () -> "branch not entered: " + choiceId);
	}

	private static List<String> placeholders(String value) {
		Matcher matcher = PLACEHOLDER.matcher(value);
		List<String> found = new ArrayList<>();
		while (matcher.find()) found.add(matcher.group());
		return found;
	}

	private static Set<String> spatialKeys(JsonObject json) {
		Set<String> keys = new TreeSet<>();
		for (String key : json.keySet())
			if (key.startsWith("pingforit.spatial.")) keys.add(key);
		return keys;
	}

	private static JsonObject read(String locale) throws IOException {
		try (var stream = SpatialSelectorLocalizationTest.class.getClassLoader().getResourceAsStream(
			"assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}
}
