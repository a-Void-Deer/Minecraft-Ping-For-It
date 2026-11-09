package nx.pingwheel.common.monitor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels.Format;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels.Stat;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetReading;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.GaugeReading;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.LaneSample;
import nx.pingwheel.common.monitor.MonitorSchema.MemoryUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.Timing;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel.Row;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel.Tone;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Finite HUD vocabulary and actual default-model/locale projection, separate from full selectors. */
@NotNullByDefault
@ResourceLock("minecraft.language")
class MonitorHudLabelsLocalizationTest {
	private static final @Unmodifiable List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:(\\d+)\\$)?([sdf%])");
	private static final String ROOT = "monitor.pingforit.hud.";
	private static final String OLD_ROOT = "monitor.pingforit.";
	private static final String ALLOWED_HUD_PUNCTUATION = "%$.,-:;/()>=";
	private static final @Unmodifiable List<Scope> PREFIX_SCOPES = List.of(
		Scope.SERVER_PREVIEW, Scope.SERVER_TRACKING, Scope.SHARED, Scope.LOCAL_PREVIEW, Scope.LOCAL_TRACKING);
	private static final long MB = 1_048_576L;

	private final Labels labels = MonitorHudLabels.DEFAULT;

	@Test
	void finiteHudKeyInventoryIsDerivedFromClosedEnums() throws IOException {
		@Unmodifiable Set<String> expected = expectedKeys();
		assertEquals(81, expected.size());
		assertEquals(6, Format.values().length);
		assertEquals(8, Stat.values().length);
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			@Unmodifiable Set<String> actual = new LinkedHashSet<>();
			for (String key : root.keySet()) {
				if (key.startsWith(ROOT)) actual.add(key);
			}
			assertEquals(expected, Set.copyOf(actual), locale);
		}
	}

	@Test
	void everyLocaleHasNoDuplicateJsonKeys() throws IOException {
		for (String locale : LOCALES) {
			var counts = new LinkedHashMap<String, Integer>();
			try (var reader = new JsonReader(new StringReader(readText(locale)))) {
				reader.setLenient(false);
				collectKeys(reader, counts);
			}
			var duplicates = counts.entrySet().stream()
				.filter(entry -> entry.getValue() > 1)
				.map(Map.Entry::getKey)
				.toList();
			assertTrue(duplicates.isEmpty(), () -> locale + ": duplicate JSON keys " + duplicates);
		}
	}

	@Test
	void everyHudFormatHasTheSameSignatureAndJavaFormatterShapeInEveryLocale() throws IOException {
		assertEquals(6, Format.values().length);
		var expectedSignatures = new LinkedHashMap<String, @Unmodifiable List<String>>();
		for (Format format : Format.values()) {
			expectedSignatures.put(formatKey(format), switch (format) {
				case ROW, ENTRY, STAT -> List.of("1:s", "2:s");
				case MEMORY -> List.of("1:s", "2:s", "3:s");
				case FIELD_SEPARATOR, DETAIL_SEPARATOR -> List.of();
			});
		}
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (var entry : expectedSignatures.entrySet()) {
				String value = requireString(root, locale, entry.getKey());
				assertEquals(entry.getValue(), signature(value), () -> locale + ":" + entry.getKey());
				assertJavaFormatterAccepts(value, entry.getValue(), locale + ":" + entry.getKey());
			}
			for (String key : expectedKeys()) {
				String value = requireString(root, locale, key);
				if (!expectedSignatures.containsKey(key)) {
					assertNoArgumentPlaceholders(value, locale + ":" + key);
				}
			}
		}
	}

	@Test
	void everyRawFieldAndPresentationTokenResolvesFromTheHudNamespaceInEveryLocale() throws IOException {
		Language previous = Language.getInstance();
		try {
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				for (Metric metric : Metric.rawMetrics()) {
					Component field = labels.field(metric);
					assertLocalizedComponent(root, locale, field);
					assertEquals(Set.of(fieldKey(metric)), collectKeys(field), locale + ":" + metric.id());
				}
				for (Stat stat : Stat.values()) {
					assertLocalizedComponent(root, locale, labels.stat(stat));
				}
				for (ClientMonitorState.Status status : ClientMonitorState.Status.values()) {
					assertLocalizedComponent(root, locale, labels.status(status));
				}
				assertLocalizedComponent(root, locale, labels.unavailable());
				assertEquals("N/A", labels.unavailable().getString(), locale);
				for (Format format : Format.values()) {
					if (format == Format.FIELD_SEPARATOR || format == Format.DETAIL_SEPARATOR) {
						assertFalse(labels.format(format).getString().isEmpty(), locale + ":" + format);
					} else {
						assertResolved(locale, labels.format(format, sampleArguments(format)));
					}
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void everyScopePrefixResolvesAndMatchesTheFiveFixedGroups() throws IOException {
		Language previous = Language.getInstance();
		try {
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				for (Scope scope : Scope.all()) {
					Component prefix = labels.prefix(scope);
					assertLocalizedComponent(root, locale, prefix);
					assertEquals(Set.of(prefixKey(scope)), collectKeys(prefix), locale + ":" + scope.id());
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void timingMaxUsesThePhaseWordAndSummaryIsNeverAField() {
		for (Phase phase : Phase.values()) {
			@Nullable Metric timing = Metric.byId("server.preview.timing." + phase.id());
			@Nullable Metric max = Metric.byId("server.preview.timing." + phase.id() + ".max");
			if (timing == null || max == null) continue;
			assertEquals(Set.of(ROOT + "phase." + phase.id()), collectKeys(labels.field(timing)));
			assertEquals(Set.of(ROOT + "phase." + phase.id()), collectKeys(labels.field(max)));
		}
		for (Metric summary : Metric.defaults()) {
			assertThrows(IllegalArgumentException.class, () -> labels.field(summary), summary.id());
		}
	}

	@Test
	void memoryUsesTheClosedMemoryField() {
		@Nullable Metric memory = Metric.byId("shared.memory");
		assertNotNull(memory);
		assertEquals(Set.of(ROOT + "field.memory"), collectKeys(labels.field(memory)));
	}

	@Test
	void fullSelectorVocabularyRemainsOnTheOldNamespaceAndIsNotReplacedByHudLabels() throws IOException {
		Language previous = Language.getInstance();
		try {
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				for (Metric metric : Metric.displayMetrics()) {
					Component full = MonitorLabels.metric(metric);
					Component shortLabel = MonitorLabels.shortLabel(metric);
					assertFalse(collectKeys(full).stream().anyMatch(key -> key.startsWith(ROOT)), metric.id());
					assertFalse(collectKeys(shortLabel).stream().anyMatch(key -> key.startsWith(ROOT)), metric.id());
					assertTrue(collectKeys(full).stream().allMatch(key -> key.startsWith(OLD_ROOT)), metric.id());
					assertTrue(collectKeys(full).contains(oldSelectorKey(metric)), metric.id());
					assertLocalizedComponent(root, locale, full);
					assertLocalizedComponent(root, locale, shortLabel);
					assertResolved(locale, full);
					assertResolved(locale, shortLabel);
				}
				for (Scope scope : Scope.all()) {
					assertFalse(collectKeys(MonitorLabels.scope(scope)).stream().anyMatch(key -> key.startsWith(ROOT)));
					assertLocalizedComponent(root, locale, MonitorLabels.scope(scope));
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void hudPunctuationIsAsciiAndForbiddenFormsAreAbsent() throws IOException {
		for (String locale : LOCALES) {
			JsonObject root = read(locale);
			for (String key : expectedKeys()) {
				String value = requireString(root, locale, key);
				for (int index = 0; index < value.length(); index++) {
					char character = value.charAt(index);
					if (character >= 0x80) {
						assertTrue(Character.isLetter(character) || isCombiningMark(character),
							() -> locale + ":" + key + " has non-ASCII punctuation " + character);
					} else if (!Character.isLetterOrDigit(character) && character != ' ') {
						assertTrue(ALLOWED_HUD_PUNCTUATION.indexOf(character) >= 0,
							() -> locale + ":" + key + " has unexpected ASCII punctuation " + character);
					}
				}
				for (String forbidden : List.of("·", "≥", "：", "；", "，", "／", "（", "）", "％", "–", "—", "’", "“", "”")) {
					assertFalse(value.contains(forbidden), () -> locale + ":" + key + " contains " + forbidden);
				}
			}
			assertEquals("; ", requireString(root, locale, ROOT + "format.field_separator"), locale);
			assertEquals(" ", requireString(root, locale, ROOT + "format.detail_separator"), locale);
			assertEquals("%1$s: %2$s", requireString(root, locale, ROOT + "format.row"), locale);
		}
	}

	@Test
	void memoryAndStatFormattingKeepsPercentBeforeTheTripletAndUnitSpacingFixed() throws IOException {
		Language previous = Language.getInstance();
		try {
			JsonObject english = read("en_us");
			Language.inject(new BundledLanguage(english));
			assertEquals("Mem 25% 96/128/512MB",
				labels.format(Format.MEMORY, labels.field(memoryMetric()), "25%", "96/128/512MB").getString());
			assertEquals("Avg 0.13ms",
				labels.format(Format.STAT, labels.stat(Stat.AVG), "0.13ms").getString());
			assertEquals("Open 2",
				labels.format(Format.ENTRY, labels.field(openMetric()), "2").getString());

			JsonObject simplified = read("zh_cn");
			Language.inject(new BundledLanguage(simplified));
			assertEquals("内存25% 96/128/512MB",
				labels.format(Format.MEMORY, labels.field(memoryMetric()), "25%", "96/128/512MB").getString());
			assertEquals("均值0.13ms",
				labels.format(Format.STAT, labels.stat(Stat.AVG), "0.13ms").getString());
			assertEquals("打开2",
				labels.format(Format.ENTRY, labels.field(openMetric()), "2").getString());

			JsonObject traditional = read("zh_tw");
			Language.inject(new BundledLanguage(traditional));
			assertEquals("記憶體25% 96/128/512MB",
				labels.format(Format.MEMORY, labels.field(memoryMetric()), "25%", "96/128/512MB").getString());

			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				String memory = labels.format(Format.MEMORY, "Mem", "25%", "96/128/512MB").getString();
				assertTrue(memory.contains("25% 96/128/512MB"), locale);
				assertFalse(memory.contains("25 %"), locale);
				assertFalse(memory.contains("512 MB"), locale);
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void englishAndChinesePrefixesAndStatusesAreTheFixedCompactVocabulary() throws IOException {
		Language previous = Language.getInstance();
		try {
			JsonObject english = read("en_us");
			Language.inject(new BundledLanguage(english));
			assertEquals(List.of("Server - Inv Prev", "Server - Inv Track", "Server - Inv Shared",
					"Client - Inv Prev", "Client - Inv Track"),
				PREFIX_SCOPES.stream().map(scope -> labels.prefix(scope).getString()).toList());
			assertEquals("OFF", labels.status(ClientMonitorState.Status.OFF).getString());
			assertEquals("Shared dependency off", labels.status(ClientMonitorState.Status.DEPENDENCY_OFF).getString());

			JsonObject simplified = read("zh_cn");
			Language.inject(new BundledLanguage(simplified));
			assertEquals(List.of("服务器 - Inv预览", "服务器 - Inv追踪", "服务器 - Inv共享",
					"客户端 - Inv预览", "客户端 - Inv追踪"),
				PREFIX_SCOPES.stream().map(scope -> labels.prefix(scope).getString()).toList());
			assertEquals("已应用", labels.field(Objects.requireNonNull(Metric.byId("local.preview.apply.accepted"))).getString());
			assertEquals("拒绝", labels.field(Objects.requireNonNull(Metric.byId("local.preview.apply.rejected"))).getString());

			JsonObject traditional = read("zh_tw");
			Language.inject(new BundledLanguage(traditional));
			assertEquals(List.of("伺服器 - Inv預覽", "伺服器 - Inv追蹤", "伺服器 - Inv共用",
					"用戶端 - Inv預覽", "用戶端 - Inv追蹤"),
				PREFIX_SCOPES.stream().map(scope -> labels.prefix(scope).getString()).toList());
			assertEquals("已套用", labels.field(metric("local.preview.apply.accepted")).getString());
		} finally {
			Language.inject(previous);
		}
	}

	/** Oracle: inventory HUD contract, not a snapshot of prepared prose or a fake label provider. */
	@Test
	void actualDefaultProjectionUsesFiveLocalizedRowsAndIndependentEndpointValues() throws IOException {
		Language previous = Language.getInstance();
		try {
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				var selected = new ArrayList<>(Metric.defaults());
				for (String id : List.of("server.preview.timing.ingress.max", "server.tracking.timing.ingress.max",
					"local.preview.timing.apply.max", "local.tracking.timing.apply.max")) selected.add(metric(id));
				var model = MonitorDisplayModel.prepare(activeView(selected), MonitorHudLabels.DEFAULT);
				assertEquals(PREFIX_SCOPES.stream().map(scope -> "scope." + scope.id()).toList(),
					model.rows().stream().map(Row::id).toList(), locale);
				assertEquals(5, PREFIX_SCOPES.stream().map(scope -> labels.prefix(scope).getString()).distinct().count(), locale);
				for (int index = 0; index < PREFIX_SCOPES.size(); index++) {
					Scope scope = PREFIX_SCOPES.get(index);
					Row row = model.rows().get(index);
					String text = row.text().getString();
					String prefix = requireString(root, locale, prefixKey(scope));
					assertTrue(text.startsWith(prefix + ": "), locale + ":" + text);
					assertEquals(1, occurrences(text, prefix), locale + ": prefix repeated per category");
					assertEquals(1, occurrences(text, ": "), locale + ": extra category heading");
					assertEquals(0, occurrences(text, "N/A"), locale + ": fixture has all displayed summary values");
					assertEquals(row.metricIds().size(), Set.copyOf(row.metricIds()).size(), locale + ": duplicate field identity");
					assertTrue(row.metricIds().stream().allMatch(id -> scope.equals(metric(id).scope())), locale);
					for (Metric summary : Metric.defaults()) {
						assertFalse(text.contains(MonitorLabels.metric(summary).getString()), locale + ": summary heading");
					}
					for (String id : row.metricIds()) {
						assertFalse(text.contains(MonitorLabels.metric(metric(id)).getString()), locale + ": full selector in HUD " + id);
					}
					assertCompactGrammar(locale, text);
					assertRedText(row, "");
				}
				Row preview = model.rows().get(0);
				Row tracking = model.rows().get(1);
				Row shared = model.rows().get(2);
				Row localPreview = model.rows().get(3);
				Row localTracking = model.rows().get(4);
				assertCell(root, locale, preview, "server.preview.timing.ingress", stat(root, locale, "avg", "4ms") + " " + stat(root, locale, "peak", "8ms"));
				assertCell(root, locale, tracking, "server.tracking.timing.ingress", stat(root, locale, "avg", "6ms") + " " + stat(root, locale, "peak", "10ms"));
				assertCell(root, locale, preview, "server.preview.requests.open", "2");
				assertCell(root, locale, preview, "server.preview.requests.received", "12");
				assertCell(root, locale, preview, "server.preview.requests.admitted", "9");
				assertCell(root, locale, tracking, "server.tracking.requests.open", "0");
				assertCell(root, locale, tracking, "server.tracking.requests.received", "7");
				assertCell(root, locale, tracking, "server.tracking.requests.admitted", "5");
				assertCell(root, locale, localPreview, "local.preview.timing.apply", stat(root, locale, "avg", "1ms") + " " + stat(root, locale, "peak", "1.5ms"));
				assertCell(root, locale, localTracking, "local.tracking.timing.apply", stat(root, locale, "avg", "3ms") + " " + stat(root, locale, "peak", "4ms"));
				assertCell(root, locale, localPreview, "local.preview.apply.accepted", "8");
				assertCell(root, locale, localPreview, "local.preview.apply.rejected", "1");
				assertCell(root, locale, localTracking, "local.tracking.apply.accepted", "6");
				assertCell(root, locale, localTracking, "local.tracking.apply.rejected", "2");
				assertCell(root, locale, shared, "shared.budget.physical_slots", "50% 256/512");
				assertTrue(cells(shared).contains(resourceFormat(root, locale, "memory",
					requireString(root, locale, ROOT + "field.memory"), "25%", "96/128/512MB")), locale);
				// 20480/5 and 40960/5 B/s. An unobserved handoff bounds only that lane's known bytes.
				assertCell(root, locale, preview, "server.preview.sync.handoff_bytes", "4096B/s");
				assertCell(root, locale, tracking, "server.tracking.sync.handoff_bytes", ">=8192B/s");
				// Local elapsed is two seconds, not the remote sample's five seconds.
				assertCell(root, locale, localPreview, "local.preview.sync.frames_preview", "2/s");
				assertCell(root, locale, localTracking, "local.tracking.sync.frames_snapshot", "3/s");
				assertBefore(preview, "server.preview.timing.ingress", "server.preview.requests.open", "server.preview.requests.received",
					"server.preview.requests.admitted", "server.preview.budget.logical_slots", "server.preview.sync.handoff_bytes");
				assertBefore(shared, "shared.budget.physical_slots", "shared.memory");
				assertBefore(localPreview, "local.preview.timing.apply", "local.preview.apply.accepted", "local.preview.apply.rejected",
					"local.preview.buffer.estimated_bytes", "local.preview.sync.frames_preview");
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void defaultLocalRowsDistinguishApplyDurationFromSuccessfulAndRejectedCountsInEveryLocale() {
		Language previous = Language.getInstance();
		try {
			assertAll(LOCALES.stream().map(locale -> () -> {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				var model = MonitorDisplayModel.prepare(activeView(Metric.defaults()), MonitorHudLabels.DEFAULT);
				String phase = requireString(root, locale, ROOT + "phase.apply");
				String accepted = requireString(root, locale, ROOT + "field.counter.apply.accepted");
				String rejected = requireString(root, locale, ROOT + "field.counter.apply.rejected");
				assertNotEquals(phase, accepted, locale + ": Apply duration and successful count need distinguishable labels");
				assertNotEquals(phase, rejected, locale);
				assertNotEquals(accepted, rejected, locale);
				for (int index : List.of(3, 4)) {
					Row row = model.rows().get(index);
					String lane = index == 3 ? "preview" : "tracking";
					assertCell(root, locale, row, "local." + lane + ".timing.apply", stat(root, locale, "avg", index == 3 ? "1ms" : "3ms"));
					assertCell(root, locale, row, "local." + lane + ".apply.accepted", index == 3 ? "8" : "6");
					assertCell(root, locale, row, "local." + lane + ".apply.rejected", index == 3 ? "1" : "2");
				}
			}));
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void actualLocalizedMissingValuesMergeOnceAndDoNotReplaceMeasuredZeros() throws IOException {
		Language previous = Language.getInstance();
		try {
			var selected = List.of(metric("server.preview.requests.received"), metric("server.preview.requests.admitted"),
				metric("server.preview.requests.open"), metric("server.preview.timing.ingress"));
			var remote = sample(5_000_000_000L, new LaneSample(Source.SERVER, Lane.PREVIEW,
				List.of(new Timing(Phase.INGRESS, new TimingStats(0, 1, 0))),
				List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 0, false)), List.of(), List.of()), null, null);
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				var mixed = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, selected, ClientMonitorState.Status.ACTIVE,
					ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE, null, remote), MonitorHudLabels.DEFAULT);
				Row row = mixed.rows().getFirst();
				assertCell(root, locale, row, "server.preview.requests.received", "0");
				assertTrue(row.text().getString().contains(stat(root, locale, "avg", "0ms")), locale);
				assertEquals(1, occurrences(row.text().getString(), "N/A"), locale);
				assertEquals("N/A", cells(row).getLast(), locale);
				var absent = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, selected, ClientMonitorState.Status.ACTIVE,
					ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE, null, null), MonitorHudLabels.DEFAULT);
				assertEquals(List.of("N/A"), cells(absent.rows().getFirst()), locale);
				assertCompactGrammar(locale, row.text().getString());
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void coDisplayedDefaultFieldsKeepDistinctCompactLabelsWithinEachLocaleAndScope() {
		Language previous = Language.getInstance();
		try {
			assertAll(LOCALES.stream().map(locale -> () -> {
				Language.inject(new BundledLanguage(read(locale)));
				var model = MonitorDisplayModel.prepare(activeView(Metric.defaults()), MonitorHudLabels.DEFAULT);
				for (Row row : model.rows()) {
					var byLabel = new LinkedHashMap<String, String>();
					for (String id : row.metricIds()) {
						String compact = Normalizer.normalize(labels.field(metric(id)).getString().strip(), Normalizer.Form.NFC)
							.toLowerCase(Locale.ROOT);
						@Nullable String previousId = byLabel.putIfAbsent(compact, id);
						assertTrue(previousId == null, () -> locale + ": indistinguishable co-displayed fields " + previousId + " / " + id);
					}
				}
			}));
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void actualLocalizedRemoteStatesDoNotConsumeLocalRowsAndOnlyOffIsRed() throws IOException {
		Language previous = Language.getInstance();
		try {
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				var active = MonitorDisplayModel.prepare(activeView(Metric.defaults()), MonitorHudLabels.DEFAULT);
				for (var status : List.of(ClientMonitorState.Status.UNKNOWN, ClientMonitorState.Status.WAITING,
					ClientMonitorState.Status.STALE, ClientMonitorState.Status.UNSUPPORTED,
					ClientMonitorState.Status.UNAUTHORIZED, ClientMonitorState.Status.OFF)) {
					var sharedStatus = status == ClientMonitorState.Status.OFF ? ClientMonitorState.Status.DEPENDENCY_OFF : status;
					var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(), status, status,
						sharedStatus, localSample(), remoteSample()), MonitorHudLabels.DEFAULT);
					assertEquals(5, model.rows().size(), locale);
					for (int index = 0; index < 3; index++) {
						var rowStatus = index == 2 ? sharedStatus : status;
						Row row = model.rows().get(index);
						String caption = requireString(root, locale, ROOT + "status." + rowStatus.name().toLowerCase(Locale.ROOT));
						assertEquals("status." + PREFIX_SCOPES.get(index).id(), row.id(), locale);
						assertEquals(List.of(caption), cells(row), locale + ": inactive source leaked numbers or N/A");
						assertTrue(row.metricIds().isEmpty(), locale);
						assertEquals(rowStatus == ClientMonitorState.Status.OFF ? caption : "", offText(row), locale);
						assertRedText(row, rowStatus == ClientMonitorState.Status.OFF ? caption : "");
					}
					for (int index : List.of(3, 4)) {
						assertEquals(active.rows().get(index).text().getString(), model.rows().get(index).text().getString(), locale);
						assertEquals(active.rows().get(index).metricIds(), model.rows().get(index).metricIds(), locale);
						assertRedText(model.rows().get(index), "");
					}
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void nativeVariableGlyphWrappingOfActualLocaleRowsKeepsTextIdentityAndOffTone() throws IOException {
		Language previous = Language.getInstance();
		try {
			// Native StringSplitter, deterministic unequal advances; not an installed-font/GPU claim.
			var splitter = new StringSplitter((code, style) -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN
				? 9f : code == 'i' || code == 'l' || code == ' ' ? 3f : code == 'W' || code == 'M' ? 8f : 6f);
			for (String locale : LOCALES) {
				Language.inject(new BundledLanguage(read(locale)));
				var original = MonitorDisplayModel.prepare(activeView(Metric.defaults()), MonitorHudLabels.DEFAULT);
				var off = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(), ClientMonitorState.Status.OFF,
					ClientMonitorState.Status.OFF, ClientMonitorState.Status.DEPENDENCY_OFF, localSample(), remoteSample()), MonitorHudLabels.DEFAULT);
				for (var model : List.of(original, off)) {
					for (int width : List.of(18, 43, 97)) {
						var fitted = model.fit(width, splitter);
						assertTrue(fitted.rows().size() > model.rows().size(), locale);
						assertEquals(model.rows().stream().flatMap(row -> row.metricIds().stream()).toList(),
							fitted.rows().stream().flatMap(row -> row.metricIds().stream()).toList(), locale);
						for (Row logical : model.rows()) {
							@Unmodifiable List<Row> lines = fitted.rows().stream().filter(row -> row.id().startsWith(logical.id() + ".line.")).toList();
							assertFalse(lines.isEmpty(), locale);
							assertEquals(logical.metricIds(), lines.getFirst().metricIds(), locale);
							assertTrue(lines.stream().skip(1).allMatch(row -> row.metricIds().isEmpty()), locale);
							// Fixed group breaks may consume one separator per boundary; native
							// wrapping may consume a break-space, never a label, number or other punctuation.
							assertGroupedWrapPreservesText(logical.text().getString(), String.join("",
								lines.stream().map(row -> row.text().getString()).toList()), locale);
							assertEquals(offText(logical), String.join("", lines.stream().map(MonitorHudLabelsLocalizationTest::offText).toList()), locale);
							for (Row line : lines) {
								assertTrue(splitter.stringWidth(line.text()) <= width, locale + ": right overflow " + line.text().getString());
								assertRedText(line, offText(line));
							}
						}
					}
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	@Test
	void fixedGroupBoundariesDoNotDependOnLocalizedLabels() throws IOException {
		Language previous = Language.getInstance();
		try {
			int expectedGroups = -1;
			for (String locale : LOCALES) {
				JsonObject root = read(locale);
				Language.inject(new BundledLanguage(root));
				var model = MonitorDisplayModel.prepare(activeView(Metric.defaults()), MonitorHudLabels.DEFAULT);
				assertEquals(5, model.rows().size(), locale + ": prepare keeps five logical rows");
				var fitted = model.fit(100_000, new StringSplitter((code, style) -> 1f));
				assertTrue(fitted.rows().size() > model.rows().size(), locale);
				if (expectedGroups < 0) expectedGroups = fitted.rows().size();
				else assertEquals(expectedGroups, fitted.rows().size(), locale + ": localized text changed fixed groups");
				for (Row logical : model.rows()) {
					@Unmodifiable List<Row> lines = fitted.rows().stream()
						.filter(row -> row.id().startsWith(logical.id() + ".line.")).toList();
					assertFalse(lines.isEmpty(), locale);
					String text = logical.text().getString();
					String prefix = text.substring(0, text.indexOf(": ")) + ": ";
					assertTrue(lines.getFirst().text().getString().startsWith(prefix), locale + ": missing scope prefix");
					assertTrue(lines.stream().skip(1).noneMatch(row -> row.text().getString().startsWith(prefix)),
						locale + ": repeated scope prefix");
					assertGroupedWrapPreservesText(text, String.join("",
						lines.stream().map(row -> row.text().getString()).toList()), locale);
				}
			}
		} finally {
			Language.inject(previous);
		}
	}

	private static ClientMonitorState.View activeView(List<Metric> selected) {
		return new ClientMonitorState.View(true, selected, ClientMonitorState.Status.ACTIVE, ClientMonitorState.Status.ACTIVE,
			ClientMonitorState.Status.ACTIVE, localSample(), remoteSample());
	}

	private static MonitorSample sample(long elapsed, @Nullable LaneSample preview, @Nullable LaneSample tracking, @Nullable SharedSample shared) {
		return new MonitorSample(1, 1, 1, 10, 14, elapsed, preview, tracking, shared);
	}

	private static Timing timing(Phase phase, long total, long count, long peak) {
		return new Timing(phase, new TimingStats(total, count, peak));
	}

	private static MonitorSample remoteSample() {
		return sample(5_000_000_000L, serverLane(Lane.PREVIEW), serverLane(Lane.TRACKING), new SharedSample(List.of(),
			List.of(new GaugeReading(Gauge.CONSUMERS, 4)), List.of(
				new BudgetReading(Budget.PHYSICAL_SLOTS, new BudgetUsage(true, 7, 512, 256, 0, 256, 256)),
				new BudgetReading(Budget.PROCESSING_CHARGE, new BudgetUsage(true, 9, 12, 3, 0, 9, 9))),
			MemoryUsage.accounted(96 * MB, 32 * MB, 512 * MB)));
	}

	private static LaneSample serverLane(Lane lane) {
		boolean preview = lane == Lane.PREVIEW;
		var counters = new ArrayList<>(List.of(new CounterReading(Counter.REQUESTS_RECEIVED, preview ? 12 : 7, false),
			new CounterReading(Counter.REQUESTS_ADMITTED, preview ? 9 : 5, false),
			new CounterReading(Counter.HANDOFF_BYTES, preview ? 20_480 : 40_960, false),
			new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, preview ? 0 : 1, false)));
		if (preview) counters.add(new CounterReading(Counter.FRAMES_PREVIEW, 20, false));
		else counters.addAll(List.of(new CounterReading(Counter.FRAMES_SNAPSHOT, 5, false), new CounterReading(Counter.FRAMES_STREAM, 10, false)));
		return new LaneSample(Source.SERVER, lane, List.of(
			timing(Phase.INGRESS, preview ? 12_000_000 : 18_000_000, 3, preview ? 8_000_000 : 10_000_000),
			timing(Phase.TICK_WORK, preview ? 10_000_000 : 35_000_000, preview ? 2 : 5, preview ? 7_000_000 : 9_000_000)),
			counters, List.of(new GaugeReading(Gauge.REQUESTS_OPEN, preview ? 2 : 0), new GaugeReading(Gauge.QUEUE_LOGICAL_COST, preview ? 34 : 11)),
			List.of(new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 91, 64, 7, 3, 57, 49)),
				new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 27, 20_480, 10_240, 0, 10_240, 10_240))));
	}

	private static MonitorSample localSample() {
		return sample(2_000_000_000L, localLane(Lane.PREVIEW), localLane(Lane.TRACKING), null);
	}

	private static LaneSample localLane(Lane lane) {
		boolean preview = lane == Lane.PREVIEW;
		var counters = new ArrayList<>(List.of(new CounterReading(Counter.ACCEPTED, preview ? 8 : 6, false),
			new CounterReading(Counter.REJECTED, preview ? 1 : 2, false)));
		if (preview) counters.add(new CounterReading(Counter.FRAMES_PREVIEW, 4, false));
		else counters.addAll(List.of(new CounterReading(Counter.FRAMES_SNAPSHOT, 6, false), new CounterReading(Counter.FRAMES_STREAM, 8, false)));
		return new LaneSample(Source.LOCAL, lane, List.of(
			timing(Phase.APPLY, preview ? 2_000_000 : 9_000_000, preview ? 2 : 3, preview ? 1_500_000 : 4_000_000),
			timing(Phase.PREPARE, preview ? 6_000_000 : 8_000_000, 2, preview ? 4_000_000 : 5_000_000),
			timing(Phase.DRAW, preview ? 0 : 4_000_000, preview ? 1 : 2, preview ? 0 : 3_000_000)), counters,
			List.of(new GaugeReading(Gauge.CHANNELS, preview ? 3 : 5), new GaugeReading(Gauge.BUFFER_ESTIMATED_BYTES, preview ? 2560 : 10_240)), List.of());
	}

	private static Metric metric(String id) { return Objects.requireNonNull(Metric.byId(id), id); }
	private static long occurrences(String text, String token) { return (text.length() - text.replace(token, "").length()) / token.length(); }
	private static String withoutWhitespace(String text) { return text.replaceAll("\\s+", ""); }
	/** Visual text may omit a field separator at a fixed group boundary, never other content. */
	private static void assertGroupedWrapPreservesText(String logical, String visual, String locale) {
		String expected = withoutWhitespace(logical);
		String actual = withoutWhitespace(visual);
		int source = 0;
		for (int index = 0; index < actual.length(); index++) {
			char value = actual.charAt(index);
			while (source < expected.length() && expected.charAt(source) != value) {
				assertEquals(';', expected.charAt(source), () -> locale + ": wrapped text lost non-separator content");
				source++;
			}
			assertTrue(source < expected.length(), () -> locale + ": wrapped text gained or reordered content");
			source++;
		}
		while (source < expected.length()) {
			assertEquals(';', expected.charAt(source), () -> locale + ": wrapped text lost non-separator content");
			source++;
		}
	}
	private static @Unmodifiable List<String> cells(Row row) {
		String text = row.text().getString();
		assertTrue(text.contains(": "), text);
		return List.of(text.substring(text.indexOf(": ") + 2).split("; ", -1));
	}
	private static String resourceFormat(JsonObject root, String locale, String format, Object... args) {
		return String.format(Locale.ROOT, requireString(root, locale, ROOT + "format." + format), args);
	}
	private static String stat(JsonObject root, String locale, String stat, String value) {
		return resourceFormat(root, locale, "stat", requireString(root, locale, ROOT + "stat." + stat), value);
	}
	private static void assertCell(JsonObject root, String locale, Row row, String id, String value) {
		String expected = resourceFormat(root, locale, "entry", requireString(root, locale, fieldKey(metric(id))), value);
		assertTrue(cells(row).contains(expected), () -> locale + ": missing field " + id + " -> " + expected + " in " + row.text().getString());
		assertTrue(row.metricIds().contains(id), locale + ": missing metric identity " + id);
	}
	private static void assertBefore(Row row, String... ids) {
		int previous = -1;
		for (String id : ids) {
			int index = row.metricIds().indexOf(id);
			assertTrue(index > previous, () -> "wrong category/field order for " + id + ": " + row.metricIds());
			previous = index;
		}
	}
	private static String offText(Row row) {
		return String.join("", row.segments().stream().filter(segment -> segment.tone() == Tone.OFF).map(segment -> segment.text().getString()).toList());
	}
	private static void assertRedText(Row row, String expected) {
		var red = new StringBuilder();
		row.text().visit((style, text) -> {
			if (style.getColor() != null && ChatFormatting.RED.getColor().equals(style.getColor().getValue())) red.append(text);
			return Optional.empty();
		}, Style.EMPTY);
		assertEquals(expected, red.toString(), row.id());
	}
	private static void assertCompactGrammar(String locale, String text) {
		assertFalse(text.contains("monitor.pingforit."), locale + ": unresolved key");
		assertFalse(text.contains("\uFFFD"), locale + ": replacement character");
		assertFalse(FORMAT_SPECIFIER.matcher(text).find(), locale + ": unresolved format argument");
		assertFalse(Pattern.compile("\\d\\s+(?:ms|s|B|KB|MB|GB|TB|PB|EB|%)\\b").matcher(text).find(), locale + ": numeric-unit space");
		assertFalse(Pattern.compile("\\d\\s+/|/\\s+\\d|\\d\\s+%").matcher(text).find(), locale + ": ratio space in " + text);
		assertFalse(text.contains("/t"), locale + ": invented tick denominator");
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			assertTrue(character < 128 || Character.isLetter(character) || isCombiningMark(character),
				() -> locale + ": non-ASCII punctuation " + character);
			if (character == ';') assertTrue(index + 1 < text.length() && text.charAt(index + 1) == ' ', locale);
		}
	}

	private static @Unmodifiable Set<String> expectedKeys() {
		var keys = new LinkedHashSet<String>();
		for (Scope scope : Scope.all()) keys.add(prefixKey(scope));
		for (Phase phase : Phase.values()) keys.add(ROOT + "phase." + phase.id());
		for (Counter counter : Counter.values()) keys.add(ROOT + "field.counter." + counter.id());
		for (Gauge gauge : Gauge.values()) keys.add(ROOT + "field.gauge." + gauge.id());
		for (Budget budget : Budget.values()) keys.add(ROOT + "field.budget." + budget.id());
		keys.add(ROOT + "field.memory");
		for (Stat stat : Stat.values()) keys.add(ROOT + "stat." + stat.name().toLowerCase(Locale.ROOT));
		for (ClientMonitorState.Status status : ClientMonitorState.Status.values()) {
			keys.add(ROOT + "status." + status.name().toLowerCase(Locale.ROOT));
		}
		keys.add(ROOT + "status.unavailable");
		for (Format format : Format.values()) keys.add(formatKey(format));
		return Set.copyOf(keys);
	}

	private static String prefixKey(Scope scope) {
		return switch (scope.source()) {
			case SERVER -> ROOT + "prefix.server." + Objects.requireNonNull(scope.lane(), "server lane").id();
			case LOCAL -> ROOT + "prefix.local." + Objects.requireNonNull(scope.lane(), "local lane").id();
			case SHARED -> ROOT + "prefix.shared";
		};
	}

	private static String fieldKey(Metric metric) {
		return switch (metric.kind()) {
			case TIMING, TIMING_MAX -> ROOT + "phase." + Objects.requireNonNull(metric.phase()).id();
			case COUNTER -> ROOT + "field.counter." + Objects.requireNonNull(metric.counter()).id();
			case GAUGE -> ROOT + "field.gauge." + Objects.requireNonNull(metric.gauge()).id();
			case BUDGET -> ROOT + "field.budget." + Objects.requireNonNull(metric.budget()).id();
			case MEMORY -> ROOT + "field.memory";
			case SUMMARY -> throw new IllegalArgumentException("summary is not a field");
		};
	}

	private static String formatKey(Format format) {
		return ROOT + "format." + format.name().toLowerCase(Locale.ROOT);
	}

	private static String oldSelectorKey(Metric metric) {
		return switch (metric.kind()) {
			case SUMMARY -> OLD_ROOT + metric.id();
			case TIMING, TIMING_MAX -> OLD_ROOT + "phase." + Objects.requireNonNull(metric.phase()).id();
			case COUNTER -> OLD_ROOT + "counter." + Objects.requireNonNull(metric.counter()).id();
			case GAUGE -> OLD_ROOT + "gauge." + Objects.requireNonNull(metric.gauge()).id();
			case BUDGET -> OLD_ROOT + "budget." + Objects.requireNonNull(metric.budget()).id();
			case MEMORY -> OLD_ROOT + "memory.shared";
		};
	}

	private static Metric memoryMetric() {
		return Objects.requireNonNull(Metric.byId("shared.memory"));
	}

	private static Metric openMetric() {
		return Objects.requireNonNull(Metric.byId("server.preview.requests.open"));
	}

	private static Object[] sampleArguments(Format format) {
		return switch (format) {
			case ROW, ENTRY, STAT -> new Object[]{"label", "value"};
			case MEMORY -> new Object[]{"label", "25%", "96/128/512MB"};
			case FIELD_SEPARATOR, DETAIL_SEPARATOR -> new Object[0];
		};
	}

	private static void assertResolved(String locale, Component component) {
		String text = component.getString();
		assertFalse(text.isBlank(), locale);
		assertFalse(text.contains("\uFFFD"), locale + ": replacement character");
		assertFalse(text.contains(OLD_ROOT), () -> locale + ": unresolved monitor key " + text);
		assertFalse(FORMAT_SPECIFIER.matcher(text).find(), () -> locale + ": unresolved argument " + text);
	}

	private static void assertLocalizedComponent(JsonObject root, String locale, Component component) {
		var keys = collectKeys(component);
		assertFalse(keys.isEmpty(), () -> locale + ": monitor HUD label produced no localized resource");
		for (String key : keys) requireString(root, locale, key);
	}

	private static @Unmodifiable Set<String> collectKeys(Component component) {
		var keys = new LinkedHashSet<String>();
		collectKeys(component, keys);
		return Set.copyOf(keys);
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

	private static void collectKeys(JsonReader reader, Map<String, Integer> counts) throws IOException {
		JsonToken token = reader.peek();
		if (token == JsonToken.BEGIN_OBJECT) {
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				counts.merge(name, 1, Integer::sum);
				collectKeys(reader, counts);
			}
			reader.endObject();
		} else if (token == JsonToken.BEGIN_ARRAY) {
			reader.beginArray();
			while (reader.hasNext()) collectKeys(reader, counts);
			reader.endArray();
		} else {
			reader.skipValue();
		}
	}

	private static JsonObject read(String locale) throws IOException {
		return JsonParser.parseString(readText(locale)).getAsJsonObject();
	}

	private static String readText(String locale) throws IOException {
		try (@Nullable var stream = MonitorHudLabelsLocalizationTest.class.getClassLoader()
			.getResourceAsStream("assets/pingforit/lang/" + locale + ".json")) {
			assertNotNull(stream, locale);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String requireString(JsonObject root, String locale, String key) {
		assertTrue(root.has(key), () -> locale + ": missing translation " + key);
		@Nullable var element = root.get(key);
		assertTrue(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
			() -> locale + ": translation is not a string " + key);
		String value = element.getAsString();
		assertFalse(value.isBlank() && !key.equals(ROOT + "format.detail_separator"),
			() -> locale + ": blank translation " + key);
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
			assertFalse(formatted.isEmpty(), () -> description + " formats to an empty value");
		} catch (IllegalFormatException error) {
			fail(description + " is not a valid Java format: " + error.getMessage(), error);
		}
	}

	private static boolean isCombiningMark(char character) {
		int type = Character.getType(character);
		return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
			|| type == Character.ENCLOSING_MARK;
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
		public FormattedCharSequence getVisualOrder(FormattedText text) {
			return sink -> text.visit((style, value) -> StringDecomposer.iterateFormatted(value, style, sink)
				? Optional.empty() : Optional.of(false), Style.EMPTY).isEmpty();
		}
	}
}
