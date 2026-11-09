package nx.pingwheel.common.monitor.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels.Format;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels.Stat;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import static nx.pingwheel.common.monitor.client.ClientMonitorState.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent literal vocabulary keeps model tests separate from the locale resource owner. */
@NotNullByDefault
class MonitorDisplayModelTest {

	private static final Labels LABELS = new FixtureLabels();
	private static final long MB = 1_048_576;

	private static Metric metric(String id) { return Objects.requireNonNull(Metric.byId(id)); }
	private static @Unmodifiable List<Metric> metrics(String... ids) { return java.util.Arrays.stream(ids).map(MonitorDisplayModelTest::metric).toList(); }
	private static MonitorDisplayModel prepare(List<Metric> selected, @Nullable MonitorSample local, @Nullable MonitorSample remote) {
		return MonitorDisplayModel.prepare(new ClientMonitorState.View(true, selected, ACTIVE, ACTIVE, ACTIVE, local, remote), LABELS);
	}
	private static MonitorSample sample(@Nullable LaneSample preview, @Nullable LaneSample tracking, @Nullable SharedSample shared) {
		return new MonitorSample(1, 1, 1, 10, 14, 5_000_000_000L, preview, tracking, shared);
	}
	private static LaneSample lane(Source source, Lane lane, List<Timing> timings, List<CounterReading> counters) {
		return lane(source, lane, timings, counters, List.of(), List.of());
	}
	private static LaneSample lane(Source source, Lane lane, List<Timing> timings, List<CounterReading> counters,
		List<GaugeReading> gauges, List<BudgetReading> budgets) {
		return new LaneSample(source, lane, timings, counters, gauges, budgets);
	}
	private static LaneSample timed(Source source, Lane lane, Phase phase, long totalNs, long count, long maxNs) {
		return lane(source, lane, List.of(new Timing(phase, new TimingStats(totalNs, count, maxNs))), List.of());
	}
	private static SharedSample shared(MemoryUsage memory) { return shared(List.of(), List.of(), List.of(), memory); }
	private static SharedSample shared(List<CounterReading> counters, List<GaugeReading> gauges,
		List<BudgetReading> budgets, MemoryUsage memory) {
		return new SharedSample(counters, gauges, budgets, memory);
	}
	private static String onlyText(MonitorDisplayModel model) { assertEquals(1, model.rows().size()); return model.rows().getFirst().text().getString(); }
	private static long occurrences(String text, String token) { return (text.length() - text.replace(token, "").length()) / token.length(); }
	private static String joinedText(MonitorDisplayModel model) {
		return String.join("", model.rows().stream().map(row -> row.text().getString()).toList());
	}
	private static float widest(MonitorDisplayModel model, StringSplitter splitter) {
		return model.rows().stream().map(row -> splitter.stringWidth(row.text())).max(Float::compare).orElse(0f);
	}
	/** First field token, used as a structural group marker rather than a full-text golden. */
	private static String groupStart(String line) {
		int separator = line.indexOf(": ");
		String value = separator < 0 ? line : line.substring(separator + 2);
		int space = value.indexOf(' ');
		return space < 0 ? value : value.substring(0, space);
	}
	private static List<String> groupStarts(List<MonitorDisplayModel.Row> rows) {
		return rows.stream().map(row -> groupStart(row.text().getString())).toList();
	}
	private static List<String> groupStarts(MonitorDisplayModel model) { return groupStarts(model.rows()); }
	private static List<MonitorDisplayModel.Row> fittedRows(MonitorDisplayModel model, String logicalId) {
		return model.rows().stream().filter(row -> row.id().startsWith(logicalId + ".line.")).toList();
	}
	private static String textOf(List<MonitorDisplayModel.Row> rows) {
		return String.join("", rows.stream().map(row -> row.text().getString()).toList());
	}
	private static String withoutWhitespace(String text) { return text.replaceAll("\\s+", ""); }

	@Test void scopeFirstRowsKeepFiveGroupsAndEndpointLanesIndependent() {
		var local = sample(timed(Source.LOCAL, Lane.PREVIEW, Phase.APPLY, 2_000_000, 1, 2_000_000),
			timed(Source.LOCAL, Lane.TRACKING, Phase.APPLY, 6_000_000, 2, 4_000_000), null);
		var remote = sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, 12_000_000, 3, 8_000_000),
			timed(Source.SERVER, Lane.TRACKING, Phase.TICK_WORK, 35_000_000, 5, 9_000_000), shared(MemoryUsage.accounted(96 * MB, 32 * MB, 512 * MB)));
		var model = prepare(List.of(Metric.SUMMARY_PROCESSING, Metric.SUMMARY_MEMORY), local, remote);
		assertEquals(List.of("scope.server.preview", "scope.server.tracking", "scope.shared", "scope.local.preview", "scope.local.tracking"),
			model.rows().stream().map(MonitorDisplayModel.Row::id).toList());
		assertEquals(List.of("ServerPrev: Ingress Avg 4ms; N/A", "ServerTrack: Tick Avg 7ms; N/A",
			"ServerShared: Mem 25% 96/128/512MB", "ClientPrev: Apply Avg 2ms; N/A", "ClientTrack: Apply Avg 3ms; N/A"),
			model.rows().stream().map(row -> row.text().getString()).toList());
		for (var row : model.rows()) {
			assertEquals(1, occurrences(row.text().getString(), ": "));
			assertFalse(row.text().getString().contains("summary."));
		}
	}

	@Test void allSelectedCategoriesShareOneRowAndRequestsKeepTheirActualMeaning() {
		var preview = new LaneSample(Source.SERVER, Lane.PREVIEW,
			List.of(new Timing(Phase.INGRESS, new TimingStats(12_000_000, 3, 8_000_000))),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 12, false), new CounterReading(Counter.REQUESTS_ADMITTED, 9, false),
				new CounterReading(Counter.FRAMES_PREVIEW, 20, false)),
			List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 2), new GaugeReading(Gauge.QUEUE_LOGICAL_COST, 34)),
			List.of(new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 91, 64, 7, 3, 57, 49))));
		var model = prepare(List.of(Metric.SUMMARY_PROCESSING, Metric.SUMMARY_REQUESTS_QUEUE, Metric.SUMMARY_SYNC_RATE,
			metric("server.preview.budget.logical_slots")), null, sample(preview, null, null));
		String text = model.rows().getFirst().text().getString();
		assertEquals("scope.server.preview", model.rows().getFirst().id());
		assertEquals("ServerPrev: Ingress Avg 4ms; Open 2; Recv 12; Admitted 9; QCost 34; Slots 10.94% 7/64 Reserved 3 Rem 57 Admissible 49; PreviewFrames 4/s; N/A", text);
		assertFalse(text.contains("Pending"));
		assertEquals(1, occurrences(text, "ServerPrev"));
		assertTrue(model.rows().getFirst().metricIds().contains("server.preview.requests.admitted"));
		assertFalse(model.rows().getFirst().metricIds().contains("server.preview.queue.pending_entries"));
	}

	@Test void summaryMainRawAndPeakDeduplicateOnePhaseWithActualIntervalMean() {
		var remote = sample(new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(
			new Timing(Phase.INGRESS, new TimingStats(12_000_000, 3, 8_000_000)),
			new Timing(Phase.TICK_WORK, new TimingStats(3_000_000, 1, 3_000_000)),
			new Timing(Phase.CAPTURE, new TimingStats(10_000_000, 2, 6_000_000))), List.of(), List.of(), List.of()), null, null);
		var selected = List.of(Metric.SUMMARY_PROCESSING, metric("server.preview.timing.ingress"),
			metric("server.preview.timing.ingress.max"), metric("server.preview.timing.capture"));
		var model = prepare(selected, null, remote);
		assertEquals("ServerPrev: Ingress Avg 4ms Peak 8ms Total 12ms N 3; Tick Avg 3ms; Capture Avg 5ms Total 10ms N 2",
			model.rows().getFirst().text().getString());
		assertEquals(List.of("server.preview.timing.ingress", "server.preview.timing.ingress.max", "server.preview.timing.tick_work",
			"server.preview.timing.capture"), model.rows().getFirst().metricIds());
		assertFalse(model.rows().getFirst().text().getString().contains("2.4ms"), "five ticks in the window are not three measured intervals");
		assertFalse(model.rows().getFirst().text().getString().contains("/t"));
	}

	@Test void peakOnlyDoesNotEnableMeanTotalsOrDefaultPeaks() {
		var remote = sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, 12_000_000, 3, 8_000_000), null, null);
		var model = prepare(metrics("server.preview.timing.ingress.max"), null, remote);
		assertEquals("ServerPrev: Ingress Peak 8ms", onlyText(model));
		assertEquals(List.of("server.preview.timing.ingress.max"), model.rows().getFirst().metricIds());
		String summary = prepare(List.of(Metric.SUMMARY_PROCESSING), null, remote).rows().getFirst().text().getString();
		assertFalse(summary.contains("Peak"));
		assertFalse(summary.contains("Total"));
	}

	@Test void tickWorkUsesMeasuredIntervalsRatherThanWindowTicksAndMayUseSeconds() {
		assertEquals("ServerPrev: Tick Avg 4ms Total 12ms N 3", onlyText(prepare(metrics("server.preview.timing.tick_work"), null,
			sample(timed(Source.SERVER, Lane.PREVIEW, Phase.TICK_WORK, 12_000_000, 3, 8_000_000), null, null))));
		assertEquals("ServerPrev: Tick Avg 1.5s Peak 2s Total 3s N 2", onlyText(prepare(metrics("server.preview.timing.tick_work",
			"server.preview.timing.tick_work.max"), null,
			sample(timed(Source.SERVER, Lane.PREVIEW, Phase.TICK_WORK, 3_000_000_000L, 2, 2_000_000_000L), null, null))));
	}

	@Test void missingSelectedFieldsMergeOnceWithoutSuppressingKnownZeros() {
		var remote = sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 0, false))), null, null);
		assertEquals("ServerPrev: Recv 0; N/A", onlyText(prepare(metrics("server.preview.requests.received", "server.preview.requests.admitted",
			"server.preview.requests.open"), null, remote)));
		assertEquals("ServerPrev: N/A", onlyText(prepare(metrics("server.preview.requests.admitted", "server.preview.requests.open"), null, remote)));
		assertEquals("ServerPrev: Recv 0", onlyText(prepare(metrics("server.preview.requests.received"), null, remote)));
		assertEquals("ServerPrev: N/A", onlyText(prepare(metrics("server.preview.requests.received"), null, null)));
	}

	@Test void nonDisplayedSummaryConstituentsDoNotCreateUnavailableFields() {
		var remote = sample(new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 12, false), new CounterReading(Counter.REQUESTS_ADMITTED, 9, false)),
			List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 2), new GaugeReading(Gauge.QUEUE_LOGICAL_COST, 34)), List.of()),
			new LaneSample(Source.SERVER, Lane.TRACKING, List.of(),
				List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 3, false), new CounterReading(Counter.REQUESTS_ADMITTED, 1, false)),
				List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 0), new GaugeReading(Gauge.QUEUE_LOGICAL_COST, 0)), List.of()),
			new SharedSample(List.of(), List.of(new GaugeReading(Gauge.CONSUMERS, 4)), List.of(), null));
		var local = sample(new LaneSample(Source.LOCAL, Lane.PREVIEW, List.of(),
			List.of(new CounterReading(Counter.ACCEPTED, 8, false), new CounterReading(Counter.REJECTED, 1, false)),
			List.of(new GaugeReading(Gauge.CHANNELS, 3)), List.of()),
			new LaneSample(Source.LOCAL, Lane.TRACKING, List.of(),
				List.of(new CounterReading(Counter.ACCEPTED, 6, false), new CounterReading(Counter.REJECTED, 2, false)),
				List.of(new GaugeReading(Gauge.CHANNELS, 5)), List.of()), null);
		var model = prepare(List.of(Metric.SUMMARY_REQUESTS_QUEUE), local, remote);
		assertEquals(List.of("ServerPrev: Open 2; Recv 12; Admitted 9; QCost 34", "ServerTrack: Open 0; Recv 3; Admitted 1; QCost 0",
			"ServerShared: Consumers 4", "ClientPrev: Applied 8; Rejected 1; Channels 3", "ClientTrack: Applied 6; Rejected 2; Channels 5"),
			model.rows().stream().map(row -> row.text().getString()).toList());
	}

	@Test void allRemoteNonActiveStatesReplaceNumbersWithoutUnavailableStorms() {
		var local = sample(lane(Source.LOCAL, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.ACCEPTED, 8, false))), null, null);
		for (var status : List.of(UNKNOWN, WAITING, STALE, UNSUPPORTED, UNAUTHORIZED, OFF, DEPENDENCY_OFF)) {
			var view = new ClientMonitorState.View(true, metrics("server.preview.requests.received", "shared.memory", "local.preview.apply.accepted"),
				status, status, status, local, null);
			var model = MonitorDisplayModel.prepare(view, LABELS);
			assertEquals(List.of("status.server.preview", "status.shared", "scope.local.preview"), model.rows().stream().map(MonitorDisplayModel.Row::id).toList());
			assertEquals("ClientPrev: Applied 8", model.rows().getLast().text().getString());
			assertTrue(model.rows().stream().noneMatch(row -> row.text().getString().contains("N/A")));
			assertEquals(status == OFF ? 2 : 0, model.rows().stream().flatMap(row -> row.segments().stream()).filter(segment -> segment.tone() == MonitorDisplayModel.Tone.OFF).count());
		}
	}

	@Test void offStylesOnlyItsTokenAndSharedDependencyIsNeutral() {
		var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, Metric.defaults(), OFF, OFF, DEPENDENCY_OFF, null, null), LABELS);
		assertEquals(List.of("ServerPrev: OFF", "ServerTrack: OFF", "ServerShared: Shared dependency off"),
			model.rows().subList(0, 3).stream().map(row -> row.text().getString()).toList());
		for (var row : model.rows()) for (var segment : row.segments()) {
			if (segment.tone() == MonitorDisplayModel.Tone.OFF) assertEquals("OFF", segment.text().getString());
			assertNull(segment.text().getStyle().getColor());
		}
		List<String> redText = new ArrayList<>();
		model.rows().getFirst().text().visit((style, value) -> {
			if (style.getColor() != null && ChatFormatting.RED.getColor().equals(style.getColor().getValue())) redText.add(value);
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		assertEquals(List.of("OFF"), redText);
	}

	@Test void unselectedScopesAndDisabledViewProduceNoExtraStatusRows() {
		var view = new ClientMonitorState.View(true, metrics("local.preview.apply.accepted"), OFF, OFF, DEPENDENCY_OFF, null, null);
		assertEquals("ClientPrev: N/A", onlyText(MonitorDisplayModel.prepare(view, LABELS)));
		assertTrue(prepare(List.of(), null, null).rows().isEmpty());
		assertSame(MonitorDisplayModel.EMPTY, MonitorDisplayModel.prepare(new ClientMonitorState.View(false, Metric.defaults(), OFF, OFF, OFF, null, null), LABELS));
	}

	@Test void memoryUsesOccupiedPercentBeforeCommonUnitTripletAndKeepsRawRemaining() {
		var remote = sample(null, null, shared(MemoryUsage.accounted(96 * MB, 32 * MB, 512 * MB)));
		assertEquals("ServerShared: Mem 25% 96/128/512MB", prepare(List.of(Metric.SUMMARY_MEMORY), null, remote).rows().getFirst().text().getString());
		assertEquals("ServerShared: Mem 25% 96/128/512MB Rem 384MB", onlyText(prepare(metrics("shared.memory"), null, remote)));
	}

	@Test void memoryCapZeroOverCapacityNegativeRemainingAndBigIntegerOccupiedStayHonest() {
		assertEquals("ServerShared: Mem 10/15/0B Rem -15B; N/A", onlyText(prepare(metrics("shared.memory"), null,
			sample(null, null, shared(MemoryUsage.accounted(10, 5, 0))))));
		assertEquals("ServerShared: Mem 187.5% 10/15/8B Rem -7B", onlyText(prepare(metrics("shared.memory"), null,
			sample(null, null, shared(MemoryUsage.accounted(10, 5, 8))))));
		assertEquals("ServerShared: Mem 200% 8/16/8EB Rem -8192PB", onlyText(prepare(metrics("shared.memory"), null,
			sample(null, null, shared(MemoryUsage.accounted(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))))));
	}

	@Test void independentBudgetPeriodsPreserveRecordedDetailsAndDoNotInventPerTickUnits() {
		var preview = new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(), List.of(), List.of(), List.of(
			new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 91, Long.MAX_VALUE, 9_007_199_254_740_993L, 3, -7, 0)),
			new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 27, 512 * MB, 128 * MB, 32 * MB, 384 * MB, 200 * MB))));
		String text = onlyText(prepare(metrics("server.preview.budget.logical_slots", "server.preview.budget.wire_bytes"), null, sample(preview, null, null)));
		assertEquals("ServerPrev: Slots 0.1% 9007199254740993/9223372036854775807 Reserved 3 Rem -7 Admissible 0; Wire 25% 128/512MB Reserved 32MB Rem 384MB Admissible 200MB", text);
		assertFalse(text.contains("/t"));
		assertFalse(text.contains("9007K"));
	}

	@Test void unavailableBudgetIsNotItsCanonicalZerosAndZeroCapKeepsRecordedPair() {
		var preview = new LaneSample(Source.SERVER, Lane.PREVIEW, List.of(), List.of(), List.of(), List.of(
			new BudgetReading(Budget.LOGICAL_SLOTS, BudgetUsage.unavailable()),
			new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 27, 0, 0, 0, 0, 0))));
		assertEquals("ServerPrev: Wire 0/0B Reserved 0B Rem 0B Admissible 0B; N/A", onlyText(prepare(
			metrics("server.preview.budget.logical_slots", "server.preview.budget.wire_bytes"), null, sample(preview, null, null))));
	}

	@Test void nonByteCounterRetainsEveryIntegerDigit() {
		var remote = sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 9_007_199_254_740_993L, false))), null, null);
		assertEquals("ServerPrev: Recv 9007199254740993", onlyText(prepare(metrics("server.preview.requests.received"), null, remote)));
	}

	@Test void zeroCountAndSaturatedTimingNeverBecomeAFalseMeanButRawTotalRemains() {
		assertEquals("ServerPrev: Ingress Total 0ms N 0; N/A", onlyText(prepare(metrics("server.preview.timing.ingress"), null,
			sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, 0, 0, 0), null, null))));
		String saturated = onlyText(prepare(metrics("server.preview.timing.ingress", "server.preview.timing.ingress.max"), null,
			sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, Long.MAX_VALUE, 2, Long.MAX_VALUE), null, null)));
		assertEquals("ServerPrev: Ingress Peak >=9223372036.85s Total >=9223372036.85s N 2; N/A", saturated);
		assertFalse(saturated.contains("Avg"));
	}

	@Test void peakNeedsAnObservedIntervalWhileMeasuredZeroDurationIsStillZero() {
		assertEquals("ServerPrev: N/A", onlyText(prepare(metrics("server.preview.timing.ingress.max"), null,
			sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, 0, 0, 0), null, null))));
		assertEquals("ServerPrev: Ingress Avg 0ms Peak 0ms Total 0ms N 1", onlyText(prepare(
			metrics("server.preview.timing.ingress", "server.preview.timing.ingress.max"), null,
			sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, 0, 1, 0), null, null))));
	}

	@Test void unknownHandoffOnlyBoundsKnownHandoffBytesAndUsesActualElapsedSeconds() {
		var remote = sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(
			new CounterReading(Counter.FRAMES_PREVIEW, 20, false), new CounterReading(Counter.HANDOFF_BYTES, 10_240, false),
			new CounterReading(Counter.BUDGET_CHARGED_BYTES, 15_360, false), new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, 1, false))), null, null);
		var model = prepare(List.of(Metric.SUMMARY_SYNC_RATE, metric("server.preview.sync.handoff_bytes"),
			metric("server.preview.sync.budget_charged_bytes")), null, remote);
		assertEquals("ServerPrev: PreviewFrames 4/s; Charged 15KB; Handoff >=2048B/s Total >=10KB; UnknownHandoff 0.2/s", model.rows().getFirst().text().getString());
		assertEquals(1, occurrences(model.rows().getFirst().text().getString(), "Handoff >="));
		assertFalse(model.rows().getFirst().text().getString().contains("CREATED"));
	}

	@Test void missingUnknownHandoffFlagCannotCertifyExactBytesButOtherCountersStayExact() {
		var remote = sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(
			new CounterReading(Counter.HANDOFF_BYTES, 100, false), new CounterReading(Counter.FRAMES_PREVIEW, 0, false))), null, null);
		assertEquals("ServerPrev: PreviewFrames 0; Handoff >=100B", onlyText(prepare(metrics("server.preview.sync.handoff_bytes", "server.preview.sync.frames_preview"), null, remote)));
		assertEquals("ServerPrev: N/A", onlyText(prepare(metrics("server.preview.sync.handoff_bytes"), null,
			sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of()), null, null))));
		assertEquals("ServerPrev: Handoff >=0B", onlyText(prepare(metrics("server.preview.sync.handoff_bytes"), null,
			sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.HANDOFF_BYTES, 0, false))), null, null))));
	}

	@Test void measuredNoUnknownHandoffsAllowsExactBytesButSaturatedUnknownDoesNot() {
		var selected = metrics("server.preview.sync.handoff_bytes");
		assertEquals("ServerPrev: Handoff 100B", onlyText(prepare(selected, null, sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(
			new CounterReading(Counter.HANDOFF_BYTES, 100, false), new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, 0, false))), null, null))));
		assertEquals("ServerPrev: Handoff >=100B", onlyText(prepare(selected, null, sample(lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(
			new CounterReading(Counter.HANDOFF_BYTES, 100, false), new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, Long.MAX_VALUE, true))), null, null))));
	}

	@Test void saturatedCountersAndWarmupHaveNoRateWhileKnownRawTotalsStayAvailable() {
		var preview = lane(Source.SERVER, Lane.PREVIEW, List.of(), List.of(
			new CounterReading(Counter.FRAMES_PREVIEW, Long.MAX_VALUE, true), new CounterReading(Counter.HANDOFF_BYTES, Long.MAX_VALUE, true),
			new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, 0, false)));
		var model = prepare(List.of(Metric.SUMMARY_SYNC_RATE, metric("server.preview.sync.frames_preview")), null, sample(preview, null, null));
		assertEquals("ServerPrev: PreviewFrames Total >=9223372036854775807; UnknownHandoff 0/s; N/A", model.rows().getFirst().text().getString());
		var warmup = new MonitorSample(1, 1, 1, 10, 10, 0, lane(Source.SERVER, Lane.PREVIEW, List.of(),
			List.of(new CounterReading(Counter.FRAMES_PREVIEW, 20, false))), null, null);
		assertEquals("ServerPrev: PreviewFrames Total 20; N/A", prepare(List.of(Metric.SUMMARY_SYNC_RATE,
			metric("server.preview.sync.frames_preview")), null, warmup).rows().getFirst().text().getString());
	}

	@Test void wrongEndpointSampleCannotLeakLocalReadingsIntoServerRow() {
		var wrong = sample(lane(Source.LOCAL, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.ACCEPTED, 7, false))), null, null);
		assertEquals("ServerPrev: N/A", onlyText(prepare(metrics("server.preview.requests.received"), null, wrong)));
	}

	@Test void nativeWidthWrappingSplitsLongUnicodeTokensByCodePointAndKeepsMetricIds() {
		String token = "LongUnspaced汉字𠜎Token汉字𠜎End";
		Labels longLabels = new FixtureLabels() { @Override public Component prefix(Scope scope) { return Component.literal(token); } };
		var original = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, metrics("local.preview.apply.accepted"),
			OFF, OFF, DEPENDENCY_OFF, null, null), longLabels);
		var splitter = new StringSplitter((code, style) -> code > 127 ? 2f : 1f);
		var fitted = original.fit(8, splitter);
		assertTrue(fitted.rows().size() > 1);
		assertTrue(fitted.rows().stream().allMatch(row -> splitter.stringWidth(row.text()) <= 8));
		String recomposed = String.join("", fitted.rows().stream().map(row -> row.text().getString()).toList());
		assertTrue(recomposed.startsWith(token));
		assertEquals(List.of("local.preview.apply.accepted"), fitted.rows().stream().flatMap(row -> row.metricIds().stream()).toList());
		for (var row : fitted.rows()) {
			String value = row.text().getString();
			assertFalse(Character.isLowSurrogate(value.charAt(0)));
			assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
		}
		assertSame(MonitorDisplayModel.EMPTY, original.fit(0, splitter));
	}

	@Test void nativeWrappingRetainsRedOffStyleEvenWhenTokenItselfWraps() {
		var original = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, metrics("server.preview.requests.received"), OFF,
			OFF, DEPENDENCY_OFF, null, null), LABELS);
		var fitted = original.fit(2, new StringSplitter((code, style) -> 1f));
		String red = String.join("", fitted.rows().stream().flatMap(row -> row.segments().stream())
			.filter(segment -> segment.tone() == MonitorDisplayModel.Tone.OFF).map(segment -> segment.text().getString()).toList());
		assertEquals("OFF", red);
		assertTrue(fitted.rows().stream().allMatch(row -> row.text().getString().length() <= 2));
	}

	@Test void fixedGroupBreaksKeepSemanticBoundariesWhenValueWidthChanges() {
		var splitter = new StringSplitter((code, style) -> 1f);
		var selected = new ArrayList<>(metrics("server.preview.timing.ingress", "server.preview.requests.open",
			"server.preview.requests.received"));
		selected.add(Metric.SUMMARY_BUDGET);
		selected.addAll(metrics("server.preview.sync.frames_preview", "server.preview.sync.handoff_bytes"));
		var narrow = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, selected, ACTIVE, ACTIVE, ACTIVE, null,
			sample(lane(Source.SERVER, Lane.PREVIEW, List.of(new Timing(Phase.INGRESS, new TimingStats(9_000_000, 1, 9_000_000))),
				List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 999, false),
					new CounterReading(Counter.HANDOFF_BYTES, 8192, false), new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, 0, false),
					new CounterReading(Counter.FRAMES_PREVIEW, 20, false)),
				List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 9)),
				List.of(new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 1, 10_000, 999, 0, 9_001, 9_001)),
					new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 1, 512, 128, 0, 384, 384)))), null, null)), LABELS);
		var wide = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, selected, ACTIVE, ACTIVE, ACTIVE, null,
			sample(lane(Source.SERVER, Lane.PREVIEW, List.of(new Timing(Phase.INGRESS, new TimingStats(10_000_000, 1, 10_000_000))),
				List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 1000, false),
					new CounterReading(Counter.HANDOFF_BYTES, 8193, false), new CounterReading(Counter.UNOBSERVED_HANDOFF_FRAMES, 0, false),
					new CounterReading(Counter.FRAMES_PREVIEW, 20, false)),
				List.of(new GaugeReading(Gauge.REQUESTS_OPEN, 10)),
				List.of(new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 1, 10_000, 1000, 0, 9_000, 9_000)),
					new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 1, 512, 128, 0, 384, 384)))), null, null)), LABELS);
		var wideGroups = narrow.fit(Integer.MAX_VALUE, splitter);
		int width = (int) Math.ceil(Math.max(widest(wideGroups, splitter),
			widest(wide.fit(Integer.MAX_VALUE, splitter), splitter))) + 8;
		var fittedNarrow = narrow.fit(width, splitter);
		var fittedWide = wide.fit(width, splitter);
		List<MonitorDisplayModel.Row> previewNarrow = fittedRows(fittedNarrow, "scope.server.preview");
		List<MonitorDisplayModel.Row> previewWide = fittedRows(fittedWide, "scope.server.preview");
		assertEquals(List.of("Ingress", "Open", "Slots", "Wire", "PreviewFrames"), groupStarts(previewNarrow));
		assertEquals(groupStarts(previewNarrow), groupStarts(previewWide), "value width changed a fixed group boundary");
		assertEquals(5, previewNarrow.size());
		assertEquals(5, previewWide.size());
		for (int candidate : List.of(width, width + 17, 10_000)) {
			assertEquals(List.of("Ingress", "Open", "Slots", "Wire", "PreviewFrames"),
				groupStarts(fittedRows(narrow.fit(candidate, splitter), "scope.server.preview")));
			assertEquals(List.of("Ingress", "Open", "Slots", "Wire", "PreviewFrames"),
				groupStarts(fittedRows(wide.fit(candidate, splitter), "scope.server.preview")));
		}
		for (List<MonitorDisplayModel.Row> fitted : List.of(previewNarrow, previewWide)) {
			for (var row : fitted) {
				assertTrue(splitter.stringWidth(row.text()) <= width, "every fixed group fits the chosen width");
				assertFalse(row.text().getString().endsWith("; "), "no visual trailing separator");
			}
			assertEquals(1, occurrences(textOf(fitted), "ServerPrev"));
			assertEquals(narrow.rows().getFirst().metricIds(), fitted.stream().flatMap(row -> row.metricIds().stream()).toList());
		}
		String logicalNarrow = narrow.rows().getFirst().text().getString();
		String logicalWide = wide.rows().getFirst().text().getString();
		String visualNarrow = textOf(previewNarrow);
		String visualWide = textOf(previewWide);
		assertTrue(splitter.stringWidth(narrow.rows().getFirst().text()) > width, "the former unsplit row crosses the test width");
		assertTrue(splitter.stringWidth(wide.rows().getFirst().text()) > width, "the former unsplit row crosses the test width");
		assertTrue(occurrences(logicalNarrow, "; ") > occurrences(visualNarrow, "; "), "logical text keeps inter-group separators");
		assertTrue(logicalWide.startsWith("ServerPrev: Ingress"), "logical text keeps one scope prefix");
		String legacyFirstNarrow = splitter.splitLines(narrow.rows().getFirst().text(), width,
			net.minecraft.network.chat.Style.EMPTY).getFirst().getString();
		String legacyFirstWide = splitter.splitLines(wide.rows().getFirst().text(), width,
			net.minecraft.network.chat.Style.EMPTY).getFirst().getString();
		assertNotEquals(legacyFirstNarrow, legacyFirstWide, "the former joined line still moves with value width");
		assertFalse(legacyFirstNarrow.contains("Slots"), "a later semantic group is pulled onto the former first line");
		assertTrue(visualNarrow.contains("Open 9"));
		assertTrue(visualNarrow.contains("Recv 999"));
		assertTrue(visualNarrow.contains("8192B"));
		assertTrue(visualNarrow.contains("9.99%"));
		assertTrue(visualWide.contains("Open 10"));
		assertTrue(visualWide.contains("Recv 1000"));
		assertTrue(visualWide.contains("8KB"));
		assertTrue(visualWide.contains("10%"));
		assertFalse(visualWide.contains("N/A"));
	}

	@Test void wideViewKeepsFixedBreaksAndOnePhaseKeepsMainPeakTogether() {
		var preview = lane(Source.SERVER, Lane.PREVIEW, List.of(
			new Timing(Phase.INGRESS, new TimingStats(12_000_000, 3, 8_000_000)),
			new Timing(Phase.TICK_WORK, new TimingStats(3_000_000, 1, 3_000_000))), List.of(), List.of(), List.of(
			new BudgetReading(Budget.LOGICAL_SLOTS, new BudgetUsage(true, 1, 64, 7, 0, 57, 57)),
			new BudgetReading(Budget.WIRE_BYTES, new BudgetUsage(true, 1, 512, 128, 0, 384, 384))));
		var selected = new ArrayList<>(metrics("server.preview.timing.ingress", "server.preview.timing.ingress.max",
			"server.preview.timing.tick_work"));
		selected.add(Metric.SUMMARY_BUDGET);
		var logical = prepare(selected, null, sample(preview, null, null));
		var fitted = logical.fit(10_000, new StringSplitter((code, style) -> 1f));
		List<MonitorDisplayModel.Row> previewLines = fittedRows(fitted, "scope.server.preview");
		assertEquals(List.of("Ingress", "Tick", "Slots", "Wire"), groupStarts(previewLines));
		assertTrue(previewLines.getFirst().text().getString().contains("Peak 8ms"), "main and peak share one phase group");
		assertEquals(1, occurrences(textOf(previewLines), "ServerPrev"));
		assertEquals(logical.rows().getFirst().metricIds(),
			previewLines.stream().flatMap(row -> row.metricIds().stream()).toList());
	}

	@Test void sharedWorkStateReadAttemptsStayOneGroupWhileBudgetPeriodsAndMemorySeparate() {
		var sharedSample = shared(List.of(new CounterReading(Counter.READ_ATTEMPTS, 7, false),
				new CounterReading(Counter.READ_FAILURES, 1, false), new CounterReading(Counter.DEFER_ATTEMPTS, 3, false)),
			List.of(new GaugeReading(Gauge.CONSUMERS, 4), new GaugeReading(Gauge.ACTIVE_OBSERVATIONS, 2),
				new GaugeReading(Gauge.SESSIONS, 1)),
			List.of(new BudgetReading(Budget.PHYSICAL_SLOTS, new BudgetUsage(true, 1, 512, 256, 0, 256, 256)),
				new BudgetReading(Budget.PROCESSING_CHARGE, new BudgetUsage(true, 2, 12, 3, 0, 9, 9))),
			MemoryUsage.accounted(96 * MB, 32 * MB, 512 * MB));
		var selected = metrics("shared.work.read_attempts", "shared.work.read_failures", "shared.work.defer_attempts",
			"shared.state.consumers", "shared.state.active_observations", "shared.state.sessions",
			"shared.budget.physical_slots", "shared.budget.processing_charge", "shared.memory");
		var model = prepare(selected, null, sample(null, null, sharedSample));
		var fitted = model.fit(10_000, new StringSplitter((code, style) -> 1f));
		assertEquals(List.of("work.defer_attempts", "physical_slots", "processing_charge", "Mem"), groupStarts(fitted));
		String work = fitted.rows().getFirst().text().getString();
		assertTrue(work.contains(LABELS.field(metric("shared.work.read_attempts")).getString()));
		assertTrue(work.contains(LABELS.field(metric("shared.state.consumers")).getString()));
		assertFalse(fitted.rows().get(1).text().getString().contains("processing_charge"));
		assertTrue(fitted.rows().get(1).text().getString().contains(LABELS.field(metric("shared.budget.physical_slots")).getString()));
		assertTrue(fitted.rows().get(2).text().getString().contains(LABELS.field(metric("shared.budget.processing_charge")).getString()));
		assertTrue(fitted.rows().getLast().text().getString().startsWith("Mem"));
	}

	@Test void localMemoryClassAndSyncGroupsStayIndependent() {
		var local = lane(Source.LOCAL, Lane.PREVIEW, List.of(new Timing(Phase.APPLY, new TimingStats(2_000_000, 2, 1_500_000))),
			List.of(new CounterReading(Counter.ACCEPTED, 8, false), new CounterReading(Counter.REJECTED, 1, false),
				new CounterReading(Counter.FRAMES_PREVIEW, 4, false)),
			List.of(new GaugeReading(Gauge.BUFFER_ESTIMATED_BYTES, 2560)), List.of());
		var selected = metrics("local.preview.timing.apply", "local.preview.apply.accepted", "local.preview.apply.rejected",
			"local.preview.buffer.estimated_bytes", "local.preview.sync.frames_preview");
		var fitted = prepare(selected, sample(local, null, null), null).fit(10_000, new StringSplitter((code, style) -> 1f));
		assertEquals(List.of("Apply", "Applied", "Buf", "PreviewFrames"), groupStarts(fitted));
		assertTrue(fitted.rows().get(2).text().getString().contains("Buf"));
		assertFalse(fitted.rows().get(2).text().getString().contains("PreviewFrames"));
		assertTrue(fitted.rows().get(3).text().getString().contains("PreviewFrames"));
		assertFalse(fitted.rows().get(3).text().getString().contains("Buf"));
	}

	@Test void fittedSegmentsReformFromCurrentAvailabilitySelectionAndStatus() {
		var preview = lane(Source.SERVER, Lane.PREVIEW, List.of(),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 999, false), new CounterReading(Counter.FRAMES_PREVIEW, 20, false)),
			List.of(), List.of());
		var selected = metrics("server.preview.timing.ingress", "server.preview.requests.received",
			"server.preview.sync.frames_preview");
		var model = prepare(selected, null, sample(preview, null, null));
		var fitted = model.fit(10_000, new StringSplitter((code, style) -> 1f));
		assertEquals(List.of("Recv", "PreviewFrames"), groupStarts(fitted), "the missing phase leaves no empty group");
		assertEquals(2, fitted.rows().size());
		assertEquals(1, occurrences(joinedText(fitted), "N/A"), "one scope-level N/A only");
		assertTrue(fitted.rows().getLast().text().getString().endsWith("; N/A"));
		assertTrue(fitted.rows().stream().noneMatch(row -> row.text().getString().isBlank()));
		var absent = prepare(selected, null, null).fit(10_000, new StringSplitter((code, style) -> 1f));
		assertEquals(1, absent.rows().size());
		assertEquals("ServerPrev: N/A", absent.rows().getFirst().text().getString());
		var narrowed = prepare(metrics("server.preview.requests.received"), null, sample(preview, null, null))
			.fit(10_000, new StringSplitter((code, style) -> 1f));
		assertEquals(List.of("Recv"), groupStarts(narrowed), "a new selection does not retain earlier groups");
		assertFalse(joinedText(narrowed).contains("PreviewFrames"));
		var off = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, selected, OFF, OFF, DEPENDENCY_OFF, null,
			sample(preview, null, null)), LABELS).fit(10_000, new StringSplitter((code, style) -> 1f));
		assertEquals(1, off.rows().size());
		assertEquals("ServerPrev: OFF", off.rows().getFirst().text().getString());
		assertEquals(1, off.rows().getFirst().segments().stream().filter(segment -> segment.tone() == MonitorDisplayModel.Tone.OFF).count());
		assertFalse(joinedText(off).contains("N/A"));
	}

	@Test void narrowWidthWrapsOnlyAnOverwideGroupAndKeepsCompleteValuesAndIdentity() {
		var preview = lane(Source.SERVER, Lane.PREVIEW, List.of(),
			List.of(new CounterReading(Counter.REQUESTS_RECEIVED, 9_007_199_254_740_993L, false),
				new CounterReading(Counter.FRAMES_PREVIEW, 20, false)), List.of(), List.of());
		var model = prepare(metrics("server.preview.requests.received", "server.preview.sync.frames_preview"), null,
			sample(preview, null, null));
		var splitter = new StringSplitter((code, style) -> 1f);
		var fitted = model.fit(20, splitter);
		assertTrue(fitted.rows().size() > 2, "the over-wide request group wraps natively");
		for (var row : fitted.rows()) assertTrue(splitter.stringWidth(row.text()) <= 20);
		String joined = withoutWhitespace(joinedText(fitted));
		assertTrue(joined.contains("9007199254740993"), "the full non-byte integer is retained");
		assertEquals(1, occurrences(joined, "ServerPrev"), "the scope prefix is not repeated after group or native wrapping");
		assertEquals(1, fitted.rows().stream().filter(row -> row.text().getString().startsWith("ServerPrev")).count());
		assertTrue(fitted.rows().stream().anyMatch(row -> row.text().getString().startsWith("PreviewFrames")),
			"the sync group still starts a new visual line after native wrapping");
		assertEquals(model.rows().getFirst().metricIds(), fitted.rows().stream().flatMap(row -> row.metricIds().stream()).toList());
		assertTrue(fitted.rows().stream().flatMap(row -> row.segments().stream())
			.allMatch(segment -> segment.tone() == MonitorDisplayModel.Tone.NORMAL));
	}

	@Test void publishedTextAndVisualOrderAreCachedImmutableWithoutCallingLabelsDuringRead() {
		int[] calls = { 0 };
		Labels counting = new FixtureLabels() {
			@Override public Component format(Format format, Object... args) { calls[0]++; return super.format(format, args); }
		};
		var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, metrics("local.preview.apply.accepted"),
			OFF, OFF, OFF, null, null), counting);
		int preparedCalls = calls[0];
		var row = model.rows().getFirst();
		assertEquals("ClientPrev: N/A", row.text().getString());
		assertSame(row.text().getVisualOrderText(), row.text().getVisualOrderText());
		assertEquals(preparedCalls, calls[0]);
		assertFalse(row.text() instanceof MutableComponent);
		assertFalse(row.segments().getFirst().text() instanceof MutableComponent);
		assertThrows(UnsupportedOperationException.class, () -> model.rows().clear());
		assertThrows(UnsupportedOperationException.class, () -> row.segments().clear());
		assertThrows(UnsupportedOperationException.class, () -> row.metricIds().clear());
		assertThrows(UnsupportedOperationException.class, () -> row.text().getSiblings().clear());
	}

	@Test void preparedTextDoesNotRetainCallerMutableLabelComponents() {
		MutableComponent prefix = Component.literal("OriginalScope");
		MutableComponent field = Component.literal("OriginalField");
		Labels mutable = new FixtureLabels() {
			@Override public Component prefix(Scope scope) { return prefix; }
			@Override public Component field(Metric metric) { return field; }
		};
		var local = sample(lane(Source.LOCAL, Lane.PREVIEW, List.of(), List.of(new CounterReading(Counter.ACCEPTED, 8, false))), null, null);
		var model = MonitorDisplayModel.prepare(new ClientMonitorState.View(true, metrics("local.preview.apply.accepted"),
			OFF, OFF, OFF, local, null), mutable);
		prefix.append(" mutated"); field.append(" mutated");
		assertEquals("OriginalScope: OriginalField 8", onlyText(model));
	}

	@Test void legacyPrepareDelegatesScopeAndPhaseLabelsWithoutCallingFullSelectorLabels() {
		var remote = sample(timed(Source.SERVER, Lane.PREVIEW, Phase.INGRESS, 12_000_000, 3, 8_000_000), null, null);
		var view = new ClientMonitorState.View(true, metrics("server.preview.timing.ingress.max"), ACTIVE, ACTIVE, ACTIVE, null, remote);
		List<Metric> fieldRequests = new ArrayList<>();
		var adapted = MonitorDisplayModel.prepare(view, metric -> { throw new AssertionError("full selector label used"); }, metric -> {
			fieldRequests.add(metric); return Component.literal("CustomPhase");
		}, scope -> Component.literal("CustomScope"));
		assertEquals(List.of(metric("server.preview.timing.ingress")), fieldRequests);
		String expected = MonitorHudLabels.DEFAULT.format(Format.ROW, Component.literal("CustomScope"),
			MonitorHudLabels.DEFAULT.format(Format.ENTRY, Component.literal("CustomPhase"),
				MonitorHudLabels.DEFAULT.format(Format.STAT, MonitorHudLabels.DEFAULT.stat(Stat.PEAK), Component.literal("8ms")))).getString();
		assertEquals(expected, onlyText(adapted));
		assertEquals(MonitorHudLabels.DEFAULT.format(Format.STAT, MonitorHudLabels.DEFAULT.stat(Stat.PEAK), Component.literal("8ms")).getString(),
			MonitorDisplayModel.format(metric("server.preview.timing.ingress.max"), remote, false).getString());
	}

	@NotNullByDefault
	private static class FixtureLabels implements Labels {
		@Override public Component prefix(Scope scope) {
			return Component.literal(scope.source() == Source.SHARED ? "ServerShared"
				: (scope.source() == Source.LOCAL ? "Client" : "Server") + (scope.lane() == Lane.PREVIEW ? "Prev" : "Track"));
		}
		@Override public Component field(Metric metric) {
			assertNotEquals(Metric.Kind.SUMMARY, metric.kind(), "summary names must not be headings");
			assertNotEquals(Metric.Kind.TIMING_MAX, metric.kind(), "peak shares its phase label");
			String text = switch (metric.kind()) {
				case TIMING -> switch (Objects.requireNonNull(metric.phase())) {
					case INGRESS -> "Ingress"; case TICK_WORK -> "Tick"; case CAPTURE -> "Capture"; case READ -> "Read";
					case DETACHED_ENCODE -> "Encode"; case MEASURE -> "Measure"; case PUBLISH -> "Publish";
					case APPLY -> "Apply"; case PREPARE -> "Prepare"; case DRAW -> "Draw";
				};
				case COUNTER -> switch (Objects.requireNonNull(metric.counter())) {
					case REQUESTS_RECEIVED -> "Recv"; case REQUESTS_ADMITTED -> "Admitted"; case ACCEPTED -> "Applied"; case REJECTED -> "Rejected";
					case FRAMES_PREVIEW -> "PreviewFrames"; case FRAMES_SNAPSHOT -> "Snapshots"; case FRAMES_STREAM -> "Streams";
					case HANDOFF_BYTES -> "Handoff"; case BUDGET_CHARGED_BYTES -> "Charged"; case UNOBSERVED_HANDOFF_FRAMES -> "UnknownHandoff";
					default -> metric.counter().id();
				};
				case GAUGE -> switch (Objects.requireNonNull(metric.gauge())) {
					case REQUESTS_OPEN -> "Open"; case QUEUE_LOGICAL_COST -> "QCost"; case CHANNELS -> "Channels"; case CONSUMERS -> "Consumers";
					case BUFFER_ESTIMATED_BYTES -> "Buf"; default -> metric.gauge().id();
				};
				case BUDGET -> switch (Objects.requireNonNull(metric.budget())) {
					case LOGICAL_SLOTS -> "Slots"; case WIRE_BYTES -> "Wire"; default -> metric.budget().id();
				};
				case MEMORY -> "Mem";
				case SUMMARY, TIMING_MAX -> throw new AssertionError("base fields only");
			};
			return Component.literal(text);
		}
		@Override public Component stat(Stat stat) {
			return Component.literal(switch (stat) {
				case AVG -> "Avg"; case TOTAL -> "Total"; case COUNT -> "N"; case PEAK -> "Peak"; case RATE -> "Rate";
				case RESERVED -> "Reserved"; case REMAINING -> "Rem"; case ADMISSIBLE -> "Admissible";
			});
		}
		@Override public Component status(ClientMonitorState.Status status) { return Component.literal(status == DEPENDENCY_OFF ? "Shared dependency off" : status.name()); }
		@Override public Component unavailable() { return Component.literal("N/A"); }
		@Override public Component format(Format format, Object... args) {
			String text = switch (format) {
				case ROW -> text(args[0]) + ": " + text(args[1]);
				case ENTRY, STAT -> text(args[0]) + " " + text(args[1]);
				case MEMORY -> text(args[0]) + " " + (text(args[1]).isEmpty() ? "" : text(args[1]) + " ") + text(args[2]);
				case FIELD_SEPARATOR -> "; "; case DETAIL_SEPARATOR -> " ";
			};
			return Component.literal(text);
		}
		private static String text(Object argument) { return argument instanceof Component component ? component.getString() : argument.toString(); }
	}
}
