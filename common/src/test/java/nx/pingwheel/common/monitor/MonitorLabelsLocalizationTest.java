package nx.pingwheel.common.monitor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Resource coverage for the finite monitor vocabulary used by selectors and the prepared HUD. */
@NotNullByDefault
@ResourceLock("minecraft.language")
class MonitorLabelsLocalizationTest {
	private static final @Unmodifiable List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:(\\d+)\\$)?([sdf%])");
	private static final String ROOT = "monitor.pingforit.";

	private static final @Unmodifiable List<String> STATISTIC_KEYS = List.of("statistic.mean", "statistic.max");
	private static final @Unmodifiable List<String> UNIT_KEYS = List.of(
		"units.nanoseconds", "units.microseconds", "units.milliseconds", "units.seconds",
		"units.bytes", "units.kibibytes", "units.percent", "units.per_second", "units.count", "units.frames");
	private static final @Unmodifiable List<String> FORMAT_KEYS = List.of(
		"format.scope", "format.label", "format.statistic", "format.tooltip", "format.value",
		"format.row", "format.entry", "format.separator", "format.timing", "format.peak", "format.milliseconds",
		"format.counter", "format.rate", "format.lower_bound", "format.budget", "format.budget_compact", "format.memory", "format.bytes");
	private static final @Unmodifiable List<String> TOOLTIP_KEYS = List.of(
		"tooltip.summary", "tooltip.timing", "tooltip.timing_max", "tooltip.counter",
		"tooltip.gauge", "tooltip.budget", "tooltip.memory",
		"tooltip.scope.local", "tooltip.scope.server", "tooltip.scope.shared", "tooltip.scope.summary",
		"tooltip.sync", "tooltip.buffer_estimate", "tooltip.handoff");

	private static final @Unmodifiable Map<String, @Unmodifiable List<String>> FORMAT_SIGNATURES = Map.ofEntries(
		Map.entry(ROOT + "format.scope", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.label", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.statistic", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.tooltip", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.value", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.row", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.entry", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.timing", List.of("1:s", "2:s", "3:s")),
		Map.entry(ROOT + "format.peak", List.of("1:s")),
		Map.entry(ROOT + "format.milliseconds", List.of("1:s")),
		Map.entry(ROOT + "format.counter", List.of("1:s")),
		Map.entry(ROOT + "format.rate", List.of("1:s")),
		Map.entry(ROOT + "format.lower_bound", List.of("1:s")),
		Map.entry(ROOT + "format.budget", List.of("1:s", "2:s", "3:s", "4:s", "5:s")),
		Map.entry(ROOT + "format.budget_compact", List.of("1:s", "2:s")),
		Map.entry(ROOT + "format.memory", List.of("1:s", "2:s", "3:s", "4:s")),
		Map.entry(ROOT + "format.bytes", List.of("1:s")),
		Map.entry(ROOT + "units.nanoseconds", List.of("1:s")),
		Map.entry(ROOT + "units.microseconds", List.of("1:s")),
		Map.entry(ROOT + "units.milliseconds", List.of("1:s")),
		Map.entry(ROOT + "units.seconds", List.of("1:s")),
		Map.entry(ROOT + "units.bytes", List.of("1:s")),
		Map.entry(ROOT + "units.kibibytes", List.of("1:s")),
		Map.entry(ROOT + "units.percent", List.of("1:s")),
		Map.entry(ROOT + "units.per_second", List.of("1:s")),
		Map.entry(ROOT + "units.count", List.of("1:s")),
		Map.entry(ROOT + "units.frames", List.of("1:s")));

	@Test
	void displayCatalogueStillContainsEverySummaryAndRawSelector() {
		assertEquals(121, Metric.displayMetrics().size());
		assertEquals(5, Metric.defaults().size());
		assertTrue(Metric.defaults().stream().allMatch(metric -> metric.kind() == Metric.Kind.SUMMARY));
	}

	@Test
	void everyFiniteVocabularyKeyExistsInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (String key : requiredKeys()) {
				requireString(root, locale, key);
			}
		}
	}

	@Test
	void everyMetricLabelAndTooltipUsesOnlyLocalizedResourcesInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (Metric metric : Metric.displayMetrics()) {
				assertLocalizedComponent(root, locale, MonitorLabels.metric(metric));
				assertLocalizedComponent(root, locale, MonitorLabels.shortLabel(metric));
				assertLocalizedComponent(root, locale, MonitorLabels.tooltip(metric));
			}
			for (Scope scope : Scope.all()) assertLocalizedComponent(root, locale, MonitorLabels.scope(scope));
		}
	}

	@Test
	void everyUsedFormatHasACompatibleSignatureInEveryLocale() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (var entry : FORMAT_SIGNATURES.entrySet()) {
				String value = requireString(root, locale, entry.getKey());
				assertEquals(entry.getValue(), signature(value), () -> locale + ":" + entry.getKey());
				assertJavaFormatterAccepts(value, entry.getValue(), locale + ":" + entry.getKey());
			}
			for (String key : requiredKeys()) {
				if (!FORMAT_SIGNATURES.containsKey(key)) {
					assertNoArgumentPlaceholders(requireString(root, locale, key), locale + ":" + key);
				}
			}
		}
	}

	@Test
	void memoryAndSyncHelpKeepTheirMeasurementLimits() {
		@Nullable Metric memory = Metric.byId("shared.memory");
		@Nullable Metric sync = Metric.byId("server.preview.sync.handoff_bytes");
		@Nullable Metric buffer = Metric.byId("local.preview.buffer.estimated_bytes");
		@Nullable Metric wireBudget = Metric.byId("server.preview.budget.wire_bytes");
		assertNotNull(memory);
		assertNotNull(sync);
		assertNotNull(buffer);
		assertNotNull(wireBudget);
		assertTrue(collectKeys(MonitorLabels.tooltip(memory)).contains(ROOT + "tooltip.memory"));
		assertTrue(collectKeys(MonitorLabels.tooltip(sync)).contains(ROOT + "tooltip.sync"));
		assertTrue(collectKeys(MonitorLabels.tooltip(sync)).contains(ROOT + "tooltip.handoff"));
		assertTrue(collectKeys(MonitorLabels.tooltip(buffer)).contains(ROOT + "tooltip.buffer_estimate"));
		assertTrue(collectKeys(MonitorLabels.tooltip(wireBudget)).contains(ROOT + "tooltip.sync"));
		assertTrue(collectKeys(MonitorLabels.tooltip(Metric.SUMMARY_MEMORY)).containsAll(
			Set.of(ROOT + "tooltip.memory", ROOT + "tooltip.buffer_estimate")));
		assertTrue(collectKeys(MonitorLabels.tooltip(Metric.SUMMARY_SYNC_RATE)).containsAll(
			Set.of(ROOT + "tooltip.sync", ROOT + "tooltip.handoff")));
	}

	@Test
	void compactLabelsDoNotRepeatTheRegionAndSummaryLabelsRemainSupported() {
		for (Metric metric : Metric.displayMetrics()) {
			@Unmodifiable Set<String> shortKeys = collectKeys(MonitorLabels.shortLabel(metric));
			assertFalse(shortKeys.stream().anyMatch(key -> key.startsWith(ROOT + "source.") || key.startsWith(ROOT + "lane.")));
			if (metric.kind() == Metric.Kind.SUMMARY) {
				assertEquals(MonitorLabels.metric(metric), MonitorLabels.shortLabel(metric));
			} else {
				assertTrue(collectKeys(MonitorLabels.metric(metric)).containsAll(collectKeys(MonitorLabels.scope(
					java.util.Objects.requireNonNull(metric.scope())))));
			}
		}
	}

	@Test
	void actualHudGrammarResolvesAllDetailsAndKeepsOnlyTranslatedOffTokensRed() throws IOException {
		Language previous = Language.getInstance();
		try {
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				for (Metric metric : Metric.displayMetrics()) {
					assertResolved(locale, MonitorLabels.metric(metric));
					assertResolved(locale, MonitorLabels.tooltip(metric));
				}
				var active = new ClientMonitorState.View(true, Metric.all(), ClientMonitorState.Status.ACTIVE,
					ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE, localSample(), remoteSample());
				var details = MonitorDisplayModel.prepare(active, MonitorLabels::metric, MonitorLabels::shortLabel, MonitorLabels::scope);
				for (var row : details.rows()) assertResolved(locale, row.text());
				for (Metric metric : Metric.rawMetrics()) {
					assertResolved(locale, MonitorDisplayModel.format(metric,
						java.util.Objects.requireNonNull(metric.scope()).source() == MonitorSchema.Source.LOCAL
							? localSample() : remoteSample(), false));
				}
				for (ClientMonitorState.Status status : ClientMonitorState.Status.values()) {
					var view = new ClientMonitorState.View(true, Metric.defaults(), status, status, status, localSample(), null);
					var model = MonitorDisplayModel.prepare(view, MonitorLabels::metric, MonitorLabels::shortLabel, MonitorLabels::scope);
					long offTokens = 0;
					for (var row : model.rows()) {
						assertResolved(locale, row.text());
						for (var segment : row.segments()) {
							if (segment.tone() == MonitorDisplayModel.Tone.OFF) {
								offTokens++;
								assertEquals(requireString(root, locale, ROOT + "status.off"), segment.text().getString());
							}
						}
					}
					assertEquals(status == ClientMonitorState.Status.OFF ? 3L : 0L, offTokens, locale);
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	private static void assertResolved(String locale, Component component) {
		String text = component.getString();
		assertFalse(text.isBlank(), locale);
		assertFalse(text.contains(ROOT), () -> locale + ": unresolved monitor key " + text);
		assertFalse(FORMAT_SPECIFIER.matcher(text).find(), () -> locale + ": unresolved argument " + text);
	}

	private static MonitorSchema.MonitorSample localSample() {
		return new MonitorSchema.MonitorSample(1, 1, 1, 0, 1, 1_000_000_000L,
			laneSample(Scope.LOCAL_PREVIEW), laneSample(Scope.LOCAL_TRACKING), null);
	}

	private static MonitorSchema.MonitorSample remoteSample() {
		return new MonitorSchema.MonitorSample(1, 1, 1, 0, 1, 1_000_000_000L,
			laneSample(Scope.SERVER_PREVIEW), laneSample(Scope.SERVER_TRACKING),
			new MonitorSchema.SharedSample(counters(Scope.SHARED), gauges(Scope.SHARED), budgets(Scope.SHARED),
				MonitorSchema.MemoryUsage.accounted(10, 5, 8)));
	}

	private static MonitorSchema.LaneSample laneSample(Scope scope) {
		@Unmodifiable List<MonitorSchema.Timing> timings = java.util.Arrays.stream(Phase.values()).filter(phase -> phase.supports(scope))
			.map(phase -> new MonitorSchema.Timing(phase, new MonitorSchema.TimingStats(2_000_000, 1, 2_000_000))).toList();
		return new MonitorSchema.LaneSample(scope.source(), java.util.Objects.requireNonNull(scope.lane()), timings,
			counters(scope), gauges(scope), budgets(scope));
	}

	private static @Unmodifiable List<MonitorSchema.CounterReading> counters(Scope scope) {
		return java.util.Arrays.stream(Counter.values()).filter(counter -> counter.supports(scope))
			.map(counter -> new MonitorSchema.CounterReading(counter, 2, false)).toList();
	}

	private static @Unmodifiable List<MonitorSchema.GaugeReading> gauges(Scope scope) {
		return java.util.Arrays.stream(Gauge.values()).filter(gauge -> gauge.supports(scope))
			.map(gauge -> new MonitorSchema.GaugeReading(gauge, 2)).toList();
	}

	private static @Unmodifiable List<MonitorSchema.BudgetReading> budgets(Scope scope) {
		return java.util.Arrays.stream(Budget.values()).filter(budget -> budget.supports(scope))
			.map(budget -> new MonitorSchema.BudgetReading(budget, new MonitorSchema.BudgetUsage(true, 1, 10, 3, 1, 6, 6))).toList();
	}

	@NotNullByDefault
	private static final class BundledLanguage extends Language {
		private final @Unmodifiable Map<String, String> translations;

		private BundledLanguage(JsonObject root) {
			var loaded = new HashMap<String, String>();
			for (var entry : root.entrySet()) loaded.put(entry.getKey(), entry.getValue().getAsString());
			this.translations = Map.copyOf(loaded);
		}

		@Override
		public String getOrDefault(String key, @Nullable String fallback) {
			return this.translations.getOrDefault(key, fallback == null ? key : fallback);
		}

		@Override
		public boolean has(String key) { return this.translations.containsKey(key); }

		@Override
		public boolean isDefaultRightToLeft() { return false; }

		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
	}

	private static @Unmodifiable Set<String> collectKeys(Component component) {
		var keys = new LinkedHashSet<String>();
		collectKeys(component, keys);
		return Set.copyOf(keys);
	}

	private static @Unmodifiable List<String> requiredKeys() {
		var keys = new LinkedHashSet<String>();
		keys.add(ROOT + "source.local");
		keys.add(ROOT + "source.server");
		keys.add(ROOT + "source.shared");
		for (var lane : MonitorSchema.Lane.values()) keys.add(ROOT + "lane." + lane.id());
		for (Phase phase : Phase.values()) keys.add(ROOT + "phase." + phase.id());
		for (Counter counter : Counter.values()) keys.add(ROOT + "counter." + counter.id());
		for (Gauge gauge : Gauge.values()) keys.add(ROOT + "gauge." + gauge.id());
		for (Budget budget : Budget.values()) keys.add(ROOT + "budget." + budget.id());
		for (String summary : Metric.defaultIds()) keys.add(ROOT + summary);
		keys.add(ROOT + "memory.shared");
		for (ClientMonitorState.Status status : ClientMonitorState.Status.values()) {
			keys.add(ROOT + "status." + status.name().toLowerCase(Locale.ROOT));
		}
		keys.add(ROOT + "status.unavailable");
		for (String key : STATISTIC_KEYS) keys.add(ROOT + key);
		for (String key : UNIT_KEYS) keys.add(ROOT + key);
		for (String key : FORMAT_KEYS) keys.add(ROOT + key);
		for (String key : TOOLTIP_KEYS) keys.add(ROOT + key);
		return List.copyOf(keys);
	}

	private static void assertLocalizedComponent(JsonObject root, String locale, Component component) {
		var keys = new LinkedHashSet<String>();
		collectKeys(component, keys);
		assertFalse(keys.isEmpty(), () -> locale + ": monitor label produced no localized resource");
		for (String key : keys) requireString(root, locale, key);
	}

	private static void collectKeys(Component component, Set<String> keys) {
		if (component.getContents() instanceof TranslatableContents translatable) {
			keys.add(translatable.getKey());
			for (Object argument : translatable.getArgs()) {
				if (argument instanceof Component nested) collectKeys(nested, keys);
			}
		}
		for (Component sibling : component.getSiblings()) collectKeys(sibling, keys);
	}

	private static JsonObject read(String locale) throws IOException {
		try (@Nullable var stream = MonitorLabelsLocalizationTest.class.getClassLoader()
			.getResourceAsStream("assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream, locale);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String requireString(JsonObject root, String locale, String key) {
		assertTrue(root.has(key), () -> locale + ": missing translation " + key);
		@Nullable var element = root.get(key);
		assertTrue(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
			() -> locale + ": translation is not a string " + key);
		String value = element.getAsString();
		assertFalse(value.isBlank(), () -> locale + ": blank translation " + key);
		return value;
	}

	private static void assertNoArgumentPlaceholders(String value, String description) {
		Matcher matcher = FORMAT_SPECIFIER.matcher(value);
		while (matcher.find()) {
			assertEquals("%", matcher.group(2), () -> description + " has an argument placeholder without arguments: " + value);
		}
	}

	private static @Unmodifiable List<String> signature(String value) {
		var arguments = new java.util.TreeMap<Integer, String>();
		int implicitIndex = 0;
		Matcher matcher = FORMAT_SPECIFIER.matcher(value);
		int cursor = 0;
		while (matcher.find()) {
			if (value.substring(cursor, matcher.start()).indexOf('%') >= 0) {
				fail("unsupported format specifier in translation: " + value);
			}
			@Nullable String explicitIndex = matcher.group(1);
			String type = matcher.group(2);
			if (type.equals("%")) {
				if (explicitIndex != null) fail("indexed percent literal in translation: " + value);
			} else {
				int index = explicitIndex == null ? ++implicitIndex : Integer.parseInt(explicitIndex);
				if (index <= 0) fail("format argument indexes are one-based in translation: " + value);
				@Nullable String previous = arguments.putIfAbsent(index, type);
				if (previous != null && !previous.equals(type)) {
					fail("format argument " + index + " has conflicting types in translation: " + value);
				}
			}
			cursor = matcher.end();
		}
		if (value.substring(cursor).indexOf('%') >= 0) fail("unsupported format specifier in translation: " + value);
		var result = new ArrayList<String>(arguments.size());
		for (var entry : arguments.entrySet()) result.add(entry.getKey() + ":" + entry.getValue());
		return List.copyOf(result);
	}

	private static void assertJavaFormatterAccepts(String value, List<String> expected, String description) {
		int maximumIndex = 0;
		for (String argument : expected) {
			maximumIndex = Math.max(maximumIndex, Integer.parseInt(argument.substring(0, argument.indexOf(':'))));
		}
		List<Object> arguments = new ArrayList<>(Collections.nCopies(maximumIndex, "value"));
		for (String argument : expected) {
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
}
