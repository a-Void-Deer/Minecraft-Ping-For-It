package nx.pingwheel.common.monitor.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static nx.pingwheel.common.monitor.client.ClientMonitorState.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class MonitorDisplayModelTest {

	private static Metric metric(String id) { return Objects.requireNonNull(Metric.byId(id)); }
	private static MonitorDisplayModel prepare(ClientMonitorState.View view) {
		return MonitorDisplayModel.prepare(view, m -> Component.literal(m.id()), m -> Component.literal(m.id()),
			s -> Component.literal(s.id()));
	}
	private static MonitorSample local() {
		return new MonitorSample(1, 1, 1, 0, 1, 1_000_000_000,
			new LaneSample(Source.LOCAL, Lane.PREVIEW, List.of(new Timing(Phase.APPLY, new TimingStats(2_000_000, 1, 2_000_000))),
				List.of(new CounterReading(Counter.ACCEPTED, 2, false)), List.of(), List.of()), null, null);
	}
	private static MonitorSample remote() {
		return new MonitorSample(1, 1, 1, 0, 1, 1_000_000_000,
			new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(
				new Timing(Phase.INGRESS, new TimingStats(10_000_000, 1, 10_000_000)),
				new Timing(Phase.CAPTURE, new TimingStats(7_000_000, 1, 7_000_000)),
				new Timing(Phase.READ, new TimingStats(5_000_000, 1, 5_000_000))),
				List.of(new CounterReading(Counter.HANDOFF_BYTES, 20, false),
					new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, 1, false)), List.of(), List.of()),
			new LaneSample(Source.SERVER, Lane.TRACKING, List.of(), List.of(), List.of(), List.of()),
			new SharedSample(List.of(), List.of(), List.of(), MemoryUsage.accounted(10, 5, 8)));
	}

	@Test void defaultFiveSummariesStayCompactAndDeduplicateExplicitRawSelections() {
		withLanguage(() -> {
			List<Metric> selected = new ArrayList<>(Metric.defaults()); selected.add(metric("server.preview.timing.ingress"));
			var model = prepare(new ClientMonitorState.View(true, selected, ACTIVE, ACTIVE, ACTIVE, local(), remote()));
			assertTrue(model.rows().size() <= 15, "default summaries are compact region rows, not 116 raw rows");
			var ids = model.rows().stream().flatMap(row -> row.metricIds().stream()).toList();
			assertEquals(ids.size(), ids.stream().distinct().count());
			assertEquals(1, ids.stream().filter("server.preview.timing.ingress"::equals).count());
			assertFalse(ids.contains("server.preview.timing.capture")); assertFalse(ids.contains("server.preview.timing.read"));
			String processing = model.rows().stream().filter(row -> row.id().equals("summary.processing.0")).findFirst().orElseThrow().text().getString();
			assertTrue(processing.contains("10.00 ms")); assertFalse(processing.contains("22.00"), "inclusive parent and children cannot be summed");
			assertTrue(model.rows().stream().noneMatch(row -> row.text().getString().contains("monitor.pingforit.")));
		});
	}

	@Test void detailedInclusivePhaseIsSeparateFromTheSummaryAndMissingMeasurementsAreNotZero() {
		withLanguage(() -> {
			var view = new ClientMonitorState.View(true, List.of(Metric.SUMMARY_PROCESSING, metric("server.preview.timing.capture")),
				ACTIVE, ACTIVE, ACTIVE, local(), remote());
			var model = prepare(view);
			assertTrue(model.rows().stream().anyMatch(row -> row.id().equals("server.preview.timing.capture")));
			String unknown = MonitorDisplayModel.format(metric("server.tracking.timing.read"), remote(), false).getString();
			assertEquals("Unavailable", unknown);
			assertEquals("Unavailable", MonitorDisplayModel.format(metric("local.preview.buffer.estimated_bytes"), local(), false).getString());
		});
	}

	@Test void negativeSharedMemoryAndUnknownHandoffsKeepTheirAccountingMeaning() {
		withLanguage(() -> {
			assertTrue(MonitorDisplayModel.format(metric("shared.memory"), remote(), false).getString().contains("-7 B"));
			assertEquals("≥20.00/s", MonitorDisplayModel.format(metric("server.preview.sync.handoff_bytes"), remote(), true).getString());
			var saturated = new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(),
				List.of(new CounterReading(Counter.HANDOFF_BYTES, Long.MAX_VALUE, true)), List.of(), List.of());
			var sample = new MonitorSample(1, 1, 1, 0, 1, 1_000_000_000, saturated, null, null);
			assertTrue(MonitorDisplayModel.format(metric("server.preview.sync.handoff_bytes"), sample, false).getString().startsWith("≥"));
			assertTrue(MonitorDisplayModel.format(metric("server.preview.sync.handoff_bytes"), sample, true).getString().contains("Unavailable"));
		});
	}

	@Test void explicitOffStylesOnlyTheOffTokenAndKeepsIndependentLocalRows() {
		withLanguage(() -> {
			var model = prepare(new ClientMonitorState.View(true, Metric.defaults(), OFF, OFF, DEPENDENCY_OFF, local(), null));
			assertTrue(model.rows().stream().anyMatch(row -> row.id().equals("summary.processing.3")));
			for (var row : model.rows()) for (var segment : row.segments()) {
				if (segment.tone() == MonitorDisplayModel.Tone.OFF) assertEquals("OFF", segment.text().getString());
				else assertNull(segment.text().getStyle().getColor());
			}
			assertEquals(2, model.rows().stream().flatMap(row -> row.segments().stream()).filter(s -> s.tone() == MonitorDisplayModel.Tone.OFF).count());
			assertTrue(model.rows().get(2).text().getString().contains("Both lanes off"));
			assertFalse(model.rows().stream().flatMap(row -> row.metricIds().stream()).anyMatch(id -> id.startsWith("shared.")));
		});
	}

	@Test void everyNonOffStatusUsesLocalizedNormalSegmentsAndDisabledViewHasNoRows() {
		withLanguage(() -> {
			for (var status : List.of(UNKNOWN, WAITING, STALE, UNSUPPORTED, UNAUTHORIZED)) {
				var model = prepare(new ClientMonitorState.View(true, Metric.defaults(), status, status, status, null, null));
				assertTrue(model.rows().stream().flatMap(row -> row.segments().stream()).allMatch(s -> s.tone() == MonitorDisplayModel.Tone.NORMAL));
				assertTrue(model.rows().stream().noneMatch(row -> row.text().getString().contains("monitor.pingforit.")));
			}
			assertTrue(prepare(new ClientMonitorState.View(false, Metric.defaults(), OFF, OFF, OFF, null, null)).rows().isEmpty());
		});
	}

	@Test void viewPreparationWrapsToGuiWidthWithoutLosingOffStyleOrMetricIdentity() {
		withLanguage(() -> {
			var original = prepare(new ClientMonitorState.View(true, Metric.defaults(), OFF, OFF, DEPENDENCY_OFF, local(), null));
			var fitted = original.fit(90, new net.minecraft.client.StringSplitter((code, style) -> 1f));
			assertTrue(fitted.rows().size() >= original.rows().size());
			assertTrue(fitted.rows().stream().allMatch(row -> row.text().getString().length() <= 90));
			assertEquals(original.rows().stream().flatMap(row -> row.metricIds().stream()).toList(),
				fitted.rows().stream().flatMap(row -> row.metricIds().stream()).toList());
			assertEquals(2, fitted.rows().stream().flatMap(row -> row.segments().stream()).filter(segment -> segment.tone() == MonitorDisplayModel.Tone.OFF).count());
			assertSame(MonitorDisplayModel.EMPTY, original.fit(0, new net.minecraft.client.StringSplitter((code, style) -> 1f)));
		});
	}

	@Test void publishedComponentsAndListsAreImmutableAndTranslationResolutionIsFinishedBeforePaint() {
		final List<MonitorDisplayModel> prepared = new ArrayList<>();
		withLanguage(() -> prepared.add(prepare(new ClientMonitorState.View(true, Metric.defaults(), OFF, OFF, DEPENDENCY_OFF, local(), null))));
		var model = prepared.getFirst();
		assertTrue(model.rows().getFirst().text().getString().contains("OFF"), "the prepared row is independent of the restored language");
		assertFalse(model.rows().getFirst().text() instanceof net.minecraft.network.chat.MutableComponent);
		assertThrows(UnsupportedOperationException.class, () -> model.rows().clear());
		assertThrows(UnsupportedOperationException.class, () -> model.rows().getFirst().segments().clear());
		assertThrows(UnsupportedOperationException.class, () -> model.rows().getFirst().text().getSiblings().clear());
		assertSame(model.rows().getFirst().text().getVisualOrderText(), model.rows().getFirst().text().getVisualOrderText());
	}

	public static void withLanguage(Runnable test) {
		Language old = Language.getInstance();
		Map<String, String> text = new HashMap<>(Map.ofEntries(
			Map.entry("format.row", "%s: %s"), Map.entry("format.entry", "%s %s"), Map.entry("format.separator", " · "),
			Map.entry("format.scope", "%s / %s"), Map.entry("format.milliseconds", "%s ms"), Map.entry("format.peak", "%s ms max"),
			Map.entry("format.timing", "%s ms / %s (%s ms mean)"), Map.entry("format.counter", "%s"), Map.entry("format.rate", "%s/s"),
			Map.entry("format.lower_bound", "≥%s"), Map.entry("format.bytes", "%s B"), Map.entry("format.budget_compact", "%s/%s"),
			Map.entry("format.budget", "%s / %s; reserved %s; remaining %s; admissible %s"),
			Map.entry("format.memory", "%s retained; %s reserved; cap %s; remaining %s"), Map.entry("status.unavailable", "Unavailable"),
			Map.entry("status.off", "OFF"), Map.entry("status.dependency_off", "Both lanes off"), Map.entry("status.unknown", "Unknown"),
			Map.entry("status.waiting", "Waiting"), Map.entry("status.stale", "Stale"), Map.entry("status.unsupported", "Unsupported"),
			Map.entry("status.unauthorized", "Unauthorized"), Map.entry("source.local", "Local"), Map.entry("lane.preview", "Preview"),
			Map.entry("lane.tracking", "Tracking")));
		Language.inject(new Language() {
			@Override public String getOrDefault(String key, @Nullable String fallback) {
				return key.startsWith(MonitorDisplayModel.PREFIX) ? text.getOrDefault(key.substring(MonitorDisplayModel.PREFIX.length()), key)
					: old.getOrDefault(key, fallback);
			}
			@Override public boolean has(String key) { return key.startsWith(MonitorDisplayModel.PREFIX) || old.has(key); }
			@Override public boolean isDefaultRightToLeft() { return false; }
			@Override public FormattedCharSequence getVisualOrder(FormattedText value) { return FormattedCharSequence.EMPTY; }
		});
		try { test.run(); } finally { Language.inject(old); }
	}
}
