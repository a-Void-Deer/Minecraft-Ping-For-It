package nx.pingwheel.common.monitor.client;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.monitor.MonitorHudLabels;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels.Format;
import nx.pingwheel.common.monitor.MonitorHudLabels.Labels.Stat;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
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
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.MonitorSchema.SharedSample;
import nx.pingwheel.common.monitor.MonitorSchema.Source;
import nx.pingwheel.common.monitor.MonitorSchema.TimingStats;
import nx.pingwheel.common.monitor.client.MonitorNumberFormat.Quality;
import nx.pingwheel.common.monitor.client.MonitorNumberFormat.Result;
import nx.pingwheel.common.monitor.client.MonitorNumberFormat.Value;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Immutable, preformatted HUD rows. Build on tick/status/selection/locale changes,
 * never while painting. Component caches are immutable literal leaves and
 * immutable sibling lists, not MutableComponent. No clock, transport, history or
 * selector mutation is available to a painter. HUD label ownership stays in the
 * injected label provider rather than the full selector vocabulary.
 */
@NotNullByDefault
public final class MonitorDisplayModel {

	public static final String PREFIX = "monitor.pingforit.";
	public static final MonitorDisplayModel EMPTY = new MonitorDisplayModel(List.of());
	private static final @Unmodifiable List<Scope> DISPLAY_SCOPES = List.of(Scope.SERVER_PREVIEW,
		Scope.SERVER_TRACKING, Scope.SHARED, Scope.LOCAL_PREVIEW, Scope.LOCAL_TRACKING);

	@NotNullByDefault
	public enum Tone { NORMAL, OFF }

	@NotNullByDefault
	public record Segment(Component text, Tone tone) {
		public Segment {
			// Resolve translations and nested numeric grammar at preparation,
			// not on the first render. Rebuild explicitly after a locale update.
			text = new CachedText(text.getString(), Style.EMPTY, List.of());
			Objects.requireNonNull(tone, "tone");
		}
	}

	@NotNullByDefault
	public static final class Row {
		private final String id;
		private final @Unmodifiable List<Segment> segments;
		private final @Unmodifiable List<String> metricIds;
		private final @Unmodifiable List<Component> groups;
		private final Component text;

		private Row(String id, List<Segment> segments, List<String> metricIds) {
			this(id, segments, metricIds, List.of());
		}
		private Row(String id, List<Segment> segments, List<String> metricIds, List<Component> groups) {
			this.id = id; this.segments = List.copyOf(segments); this.metricIds = List.copyOf(metricIds);
			this.groups = List.copyOf(groups);
			@Unmodifiable List<Component> leaves = segments.stream().map(segment -> (Component) new CachedText(segment.text().getString(),
				segment.tone() == Tone.OFF ? Style.EMPTY.withColor(ChatFormatting.RED) : Style.EMPTY, List.of())).toList();
			text = new CachedText("", Style.EMPTY, leaves);
		}
		public String id() { return id; }
		public @Unmodifiable List<Segment> segments() { return segments; }
		public @Unmodifiable List<String> metricIds() { return metricIds; }
		public Component text() { return text; }
	}

	private final @Unmodifiable List<Row> rows;
	private MonitorDisplayModel(List<Row> rows) { this.rows = List.copyOf(rows); }
	public @Unmodifiable List<Row> rows() { return rows; }

	@NotNullByDefault
	private static final class CachedText implements Component {
		private final Style style;
		private final ComponentContents contents;
		private final @Unmodifiable List<Component> siblings;
		private final FormattedCharSequence visual;
		private final String flattened;
		CachedText(String value, Style style, List<Component> siblings) {
			this.style = style; this.siblings = List.copyOf(siblings);
			contents = PlainTextContents.create(value);
			MutableComponent copy = Component.literal(value).setStyle(style);
			for (Component sibling : siblings) copy.append(sibling.copy());
			flattened = copy.getString();
			visual = copy.getVisualOrderText();
		}
		@Override public String getString() { return flattened; }
		@Override public Style getStyle() { return style; }
		@Override public ComponentContents getContents() { return contents; }
		@Override public @Unmodifiable List<Component> getSiblings() { return siblings; }
		@Override public FormattedCharSequence getVisualOrderText() { return visual; }
	}

	/**
	 * View-update preparation for the actual GUI width. Native font wrapping is
	 * performed here, not in draw; it retains OFF's token style. Re-run when GUI
	 * scale, viewport, locale or font resources change, without altering demand.
	 */
	public MonitorDisplayModel fit(int maximumWidth, StringSplitter splitter) {
		if (maximumWidth < 1 || rows.isEmpty()) return EMPTY;
		List<Row> fitted = new ArrayList<>();
		for (Row row : rows) {
			if (row.groups.isEmpty()) {
				appendLines(fitted, row, row.text(), maximumWidth, splitter, 0);
				continue;
			}
			int lineIndex = 0;
			for (Component group : row.groups) {
				lineIndex = appendLines(fitted, row, group, maximumWidth, splitter, lineIndex);
			}
		}
		return new MonitorDisplayModel(fitted);
	}

	/**
	 * A fixed semantic group starts on its own visual line. Native glyph wrapping
	 * remains the inner fallback and may push later groups down, never merge them.
	 */
	private static int appendLines(List<Row> fitted, Row row, Component source, int maximumWidth,
		StringSplitter splitter, int firstLineIndex) {
		List<FormattedText> lines = splitter.splitLines(source, maximumWidth, Style.EMPTY);
		for (int index = 0; index < lines.size(); index++) {
			List<Segment> segments = new ArrayList<>();
			lines.get(index).visit((style, text) -> {
				segments.add(new Segment(Component.literal(text), ChatFormatting.RED.getColor().equals(
					style.getColor() == null ? null : style.getColor().getValue()) ? Tone.OFF : Tone.NORMAL));
				return java.util.Optional.empty();
			}, Style.EMPTY);
			int lineIndex = firstLineIndex + index;
			fitted.add(new Row(row.id() + ".line." + lineIndex, segments, lineIndex == 0 ? row.metricIds() : List.of()));
		}
		return firstLineIndex + lines.size();
	}

	/** Compose selected fields once per endpoint/lane; neither selection nor sampling is changed. */
	public static MonitorDisplayModel prepare(ClientMonitorState.View view, Labels labels) {
		if (!view.enabled()) return EMPTY;
		Set<Metric> displayed = new HashSet<>();
		Set<Metric> rates = new HashSet<>();
		for (Metric selected : view.selected()) {
			for (Metric metric : selected.expanded()) {
				if (selected.kind() == Metric.Kind.SUMMARY && !principal(selected, metric)) continue;
				displayed.add(metric);
				if (selected == Metric.SUMMARY_SYNC_RATE) rates.add(metric);
			}
		}
		List<Row> rows = new ArrayList<>();
		for (Scope scope : DISPLAY_SCOPES) {
			@Unmodifiable List<Metric> metrics = Metric.rawMetrics().stream()
				.filter(metric -> scope.equals(metric.scope()) && displayed.contains(metric))
				.sorted(Comparator.comparingInt(MonitorDisplayModel::category).thenComparingInt(MonitorDisplayModel::fieldOrder)).toList();
			if (metrics.isEmpty()) continue;
			ClientMonitorState.Status status = status(view, scope);
			if (status != ClientMonitorState.Status.ACTIVE) {
				rows.add(statusRow(scope, status, labels));
				continue;
			}
			List<GroupedField> fields = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			boolean missing = false;
			for (Metric metric : metrics) {
				Metric base = baseMetric(metric);
				if (metric.kind() == Metric.Kind.TIMING_MAX && displayed.contains(base)) continue;
				boolean main = metric.kind() != Metric.Kind.TIMING_MAX;
				@Nullable Metric peak = base.kind() == Metric.Kind.TIMING ? Metric.byId(base.id() + ".max") : null;
				boolean showPeak = peak != null && displayed.contains(peak);
				Field field = field(base, sample(view, scope), main, showPeak, view.selected().contains(base), rates.contains(base), labels, true);
				if (field.text() != null) fields.add(new GroupedField(groupKey(base), field.text()));
				missing |= field.missing();
				if (main) ids.add(base.id());
				if (showPeak) ids.add(Objects.requireNonNull(peak).id());
			}
			@Unmodifiable List<Component> groups = groupTexts(scope, fields, missing || fields.isEmpty(), labels);
			rows.add(groupedRow("scope." + scope.id(), groups, ids, labels));
		}
		return new MonitorDisplayModel(rows);
	}

	/** Compatibility adapter. Full selector names are not used as a second HUD vocabulary. */
	public static MonitorDisplayModel prepare(ClientMonitorState.View view, Function<Metric, Component> fullLabel,
		Function<Metric, Component> shortLabel, Function<Scope, Component> scopeLabel) {
		return prepare(view, new Labels() {
			@Override public Component prefix(Scope scope) { return scopeLabel.apply(scope); }
			@Override public Component field(Metric metric) { return shortLabel.apply(baseMetric(metric)); }
			@Override public Component stat(Stat stat) { return MonitorHudLabels.DEFAULT.stat(stat); }
			@Override public Component status(ClientMonitorState.Status status) { return MonitorHudLabels.DEFAULT.status(status); }
			@Override public Component unavailable() { return MonitorHudLabels.DEFAULT.unavailable(); }
			@Override public Component format(Format format, Object... args) { return MonitorHudLabels.DEFAULT.format(format, args); }
		});
	}

	private static boolean principal(Metric summary, Metric metric) {
		if (summary == Metric.SUMMARY_BUDGET) {
			return metric.budget() == Budget.LOGICAL_SLOTS || metric.budget() == Budget.WIRE_BYTES
				|| metric.budget() == Budget.PHYSICAL_SLOTS || metric.budget() == Budget.PROCESSING_CHARGE;
		}
		if (summary == Metric.SUMMARY_SYNC_RATE) {
			return metric.counter() == Counter.FRAMES_PREVIEW || metric.counter() == Counter.FRAMES_SNAPSHOT
				|| metric.counter() == Counter.FRAMES_STREAM || metric.counter() == Counter.HANDOFF_BYTES
				|| metric.counter() == Counter.UNOBSERVED_HANDOFF_FRAMES;
		}
		if (summary == Metric.SUMMARY_REQUESTS_QUEUE) {
			return metric.counter() == Counter.REQUESTS_RECEIVED || metric.counter() == Counter.REQUESTS_ADMITTED || metric.counter() == Counter.ACCEPTED
				|| metric.counter() == Counter.REJECTED || metric.gauge() != null
				&& (metric.gauge() == Gauge.REQUESTS_OPEN || metric.gauge() == Gauge.QUEUE_LOGICAL_COST
					|| metric.gauge() == Gauge.CHANNELS || metric.gauge() == Gauge.CONSUMERS);
		}
		return true;
	}

	@NotNullByDefault
	private enum GroupKind { TIMING, REQUESTS, BUDGET, MEMORY, SYNC }

	@NotNullByDefault
	private record GroupKey(GroupKind kind, String id) {}

	@NotNullByDefault
	private record GroupedField(GroupKey key, Component text) {}

	private static final GroupKey REQUESTS_GROUP = new GroupKey(GroupKind.REQUESTS, "");
	private static final GroupKey MEMORY_GROUP = new GroupKey(GroupKind.MEMORY, "");
	private static final GroupKey SYNC_GROUP = new GroupKey(GroupKind.SYNC, "");

	/**
	 * Fixed semantic identity, not display width or localized prose: a phase is
	 * one group, each budget one group, and the non-sync work, memory and sync
	 * classes each one group.
	 */
	private static GroupKey groupKey(Metric metric) {
		return switch (metric.kind()) {
			case TIMING -> new GroupKey(GroupKind.TIMING, metric.id());
			case BUDGET -> new GroupKey(GroupKind.BUDGET, metric.id());
			case MEMORY -> MEMORY_GROUP;
			case GAUGE, COUNTER -> switch (category(metric)) {
				case 1 -> REQUESTS_GROUP;
				case 3 -> MEMORY_GROUP;
				case 4 -> SYNC_GROUP;
				default -> throw new IllegalArgumentException("unexpected HUD group category: " + metric.id());
			};
			case TIMING_MAX, SUMMARY -> throw new IllegalArgumentException("field requires a raw base metric");
		};
	}

	/** Group metadata stays private; Row.text() remains the complete logical text. */
	private static @Unmodifiable List<Component> groupTexts(Scope scope, List<GroupedField> fields,
		boolean unavailable, Labels labels) {
		Map<GroupKey, List<Component>> grouped = new LinkedHashMap<>();
		for (GroupedField field : fields) grouped.computeIfAbsent(field.key(), key -> new ArrayList<>()).add(field.text());
		List<List<Component>> groups = new ArrayList<>(grouped.values());
		if (groups.isEmpty()) groups.add(new ArrayList<>());
		if (unavailable) groups.getLast().add(labels.unavailable());
		List<Component> result = new ArrayList<>(groups.size());
		for (int index = 0; index < groups.size(); index++) {
			Component joined = join(groups.get(index), labels, Format.FIELD_SEPARATOR);
			Component text = index == 0 ? labels.format(Format.ROW, labels.prefix(scope), joined) : joined;
			result.add(new CachedText(text.getString(), Style.EMPTY, List.of()));
		}
		return List.copyOf(result);
	}

	private static Row groupedRow(String id, List<Component> groups, List<String> ids, Labels labels) {
		return new Row(id, List.of(new Segment(join(groups, labels, Format.FIELD_SEPARATOR), Tone.NORMAL)), ids, groups);
	}

	private static Row statusRow(Scope scope, ClientMonitorState.Status status, Labels labels) {
		// Row grammar remains localized while the exact OFF token alone is red.
		Component token = labels.status(status);
		// Resolve localized grammar with a sentinel so OFF remains independently
		// styled even in locales whose value precedes the label.
		String sentinel = "\u0001";
		String grammar = labels.format(Format.ROW, labels.prefix(scope), sentinel).getString();
		String @Unmodifiable [] parts = grammar.split(sentinel, -1);
		List<Segment> segments = new ArrayList<>();
		for (int index = 0; index < parts.length; index++) {
			segments.add(new Segment(Component.literal(parts[index]), Tone.NORMAL));
			if (index < parts.length - 1) segments.add(new Segment(token, status == ClientMonitorState.Status.OFF ? Tone.OFF : Tone.NORMAL));
		}
		if (parts.length == 1) segments.add(new Segment(token, status == ClientMonitorState.Status.OFF ? Tone.OFF : Tone.NORMAL));
		return new Row("status." + scope.id(), segments, List.of());
	}

	private static Component join(List<Component> cells, Labels labels, Format separator) {
		MutableComponent result = Component.empty();
		boolean first = true;
		for (Component cell : cells) {
			if (!first) result.append(labels.format(separator));
			result.append(cell); first = false;
		}
		return result;
	}

	private static ClientMonitorState.Status status(ClientMonitorState.View view, Scope scope) {
		return switch (scope.source()) {
			case SERVER -> scope.lane() == Lane.PREVIEW ? view.previewStatus() : view.trackingStatus();
			case SHARED -> view.sharedStatus();
			case LOCAL -> ClientMonitorState.Status.ACTIVE;
		};
	}
	private static @Nullable MonitorSample sample(ClientMonitorState.View view, Scope scope) {
		return scope.source() == Source.LOCAL ? view.local() : view.remote();
	}

	/** Compatibility value formatter, backed by the same field and exact-number preparation. */
	public static Component format(Metric metric, @Nullable MonitorSample sample, boolean rate) {
		Labels labels = MonitorHudLabels.DEFAULT;
		Field field = field(baseMetric(metric), sample, metric.kind() != Metric.Kind.TIMING_MAX,
			metric.kind() == Metric.Kind.TIMING_MAX, !rate, rate, labels, false);
		if (field.text() == null) return labels.unavailable();
		return field.missing() ? join(List.of(field.text(), labels.unavailable()), labels, Format.DETAIL_SEPARATOR) : field.text();
	}

	@NotNullByDefault
	private record Field(@Nullable Component text, boolean missing) {}

	/** The only mutable builder is preparation-local; no missing placeholder enters a field. */
	@NotNullByDefault
	private static final class Details {
		private final Labels labels;
		private final List<Component> values = new ArrayList<>();
		private boolean missing;
		private Details(Labels labels) { this.labels = labels; }
		private void add(Result result) { add(result, null); }
		private void add(Result result, @Nullable Stat stat) {
			if (!result.available()) { missing = true; return; }
			Component value = Component.literal(result.text());
			values.add(stat == null ? value : labels.format(Format.STAT, labels.stat(stat), value));
		}
		private Field finish(Metric metric, boolean includeLabel) {
			if (values.isEmpty()) return new Field(null, true);
			Component value = join(values, labels, Format.DETAIL_SEPARATOR);
			return new Field(includeLabel ? labels.format(Format.ENTRY, labels.field(metric), value) : value, missing);
		}
	}

	private static Field field(Metric metric, @Nullable MonitorSample sample, boolean main, boolean peak,
		boolean explicit, boolean rate, Labels labels, boolean includeLabel) {
		Scope scope = Objects.requireNonNull(metric.scope(), "raw metric");
		if (sample == null) return new Field(null, true);
		@Nullable LaneSample lane = scope.source() == Source.SHARED ? null : scope.lane() == Lane.PREVIEW ? sample.preview() : sample.tracking();
		@Nullable SharedSample shared = scope.source() == Source.SHARED ? sample.shared() : null;
		if (lane != null && lane.source() != scope.source()) return new Field(null, true);
		Details details = new Details(labels);
		switch (metric.kind()) {
			case TIMING -> {
				@Nullable TimingStats stats = lane == null ? null : lane.timing(Objects.requireNonNull(metric.phase()));
				if (stats == null) return new Field(null, true);
				if (main) details.add(MonitorNumberFormat.timeNs(stats.count() == 0 || stats.saturated() ? null
					: new Value(BigInteger.valueOf(stats.totalNs()), BigInteger.valueOf(stats.count()), Quality.EXACT)), Stat.AVG);
				if (peak) details.add(MonitorNumberFormat.timeNs(stats.count() == 0 ? null : value(stats.maxNs(), timingQuality(stats.maxNs()))), Stat.PEAK);
				if (main && explicit) {
					details.add(MonitorNumberFormat.timeNs(value(stats.totalNs(), timingQuality(stats.totalNs()))), Stat.TOTAL);
					details.add(MonitorNumberFormat.number(value(stats.count(), timingQuality(stats.count()))), Stat.COUNT);
				}
			}
			case COUNTER -> {
				Counter counter = Objects.requireNonNull(metric.counter());
				@Nullable CounterReading reading = lane != null ? lane.counter(counter) : shared == null ? null : shared.counter(counter);
				if (reading == null) return new Field(null, true);
				Quality quality = reading.saturated() ? Quality.SATURATED : Quality.EXACT;
				if (counter == Counter.HANDOFF_BYTES && quality == Quality.EXACT) {
					@Nullable CounterReading unknown = lane == null ? null : lane.counter(Counter.UNOBSERVED_HANDOFF_FRAMES);
					if (unknown == null || unknown.saturated() || unknown.value() > 0) quality = Quality.LOWER_BOUND;
				}
				Value total = value(reading.value(), quality);
				boolean bytes = byteCounter(counter);
				if (rate) details.add(MonitorNumberFormat.ratePerSecond(total, sample.elapsedNs(), bytes));
				if (!rate || explicit) details.add(bytes ? MonitorNumberFormat.bytes(total) : MonitorNumberFormat.number(total),
					rate ? Stat.TOTAL : null);
			}
			case GAUGE -> {
				@Nullable GaugeReading reading = lane != null ? lane.gauge(Objects.requireNonNull(metric.gauge()))
					: shared == null ? null : shared.gauge(Objects.requireNonNull(metric.gauge()));
				if (reading == null) return new Field(null, true);
				details.add(metric.gauge() == Gauge.BUFFER_ESTIMATED_BYTES ? MonitorNumberFormat.bytes(value(reading.value()))
					: MonitorNumberFormat.number(value(reading.value())));
			}
			case BUDGET -> {
				@Nullable BudgetUsage usage = lane != null ? lane.budget(Objects.requireNonNull(metric.budget()))
					: shared == null ? null : shared.budget(Objects.requireNonNull(metric.budget()));
				if (usage == null || !usage.available()) return new Field(null, true);
				boolean bytes = metric.budget() == Budget.WIRE_BYTES;
				details.add(MonitorNumberFormat.percent(value(usage.used()), value(usage.limit())));
				details.add(MonitorNumberFormat.ratio(value(usage.used()), value(usage.limit()), bytes));
				if (explicit) {
					details.add(amount(usage.reserved(), bytes), Stat.RESERVED);
					details.add(amount(usage.remaining(), bytes), Stat.REMAINING);
					details.add(amount(usage.admissibleRemaining(), bytes), Stat.ADMISSIBLE);
				}
			}
			case MEMORY -> {
				@Nullable MemoryUsage memory = shared == null ? null : shared.memory();
				if (memory == null) return new Field(null, true);
				Value occupied = new Value(BigInteger.valueOf(memory.retained()).add(BigInteger.valueOf(memory.reserved())), BigInteger.ONE, Quality.EXACT);
				Result percent = MonitorNumberFormat.percent(occupied, value(memory.cap()));
				Result triplet = MonitorNumberFormat.byteTriplet(value(memory.retained()), occupied, value(memory.cap()));
				details.add(triplet);
				if (explicit) details.add(MonitorNumberFormat.bytes(value(memory.remaining())), Stat.REMAINING);
				Component value = join(details.values, labels, Format.DETAIL_SEPARATOR);
				if (includeLabel) return new Field(percent.available()
					? labels.format(Format.MEMORY, labels.field(metric), Component.literal(percent.text()), value)
					: labels.format(Format.ENTRY, labels.field(metric), value), !percent.available() || details.missing);
				if (percent.available()) details.values.addFirst(Component.literal(percent.text()));
				details.missing |= !percent.available();
			}
			case TIMING_MAX, SUMMARY -> throw new IllegalArgumentException("field requires a raw base metric");
		}
		return details.finish(metric, includeLabel);
	}

	private static Result amount(long amount, boolean bytes) {
		return bytes ? MonitorNumberFormat.bytes(value(amount)) : MonitorNumberFormat.number(value(amount));
	}
	private static Value value(long value) { return value(value, Quality.EXACT); }
	private static Value value(long value, Quality quality) { return MonitorNumberFormat.integer(value, quality); }
	private static Quality timingQuality(long value) { return value == Long.MAX_VALUE ? Quality.SATURATED : Quality.EXACT; }
	private static boolean byteCounter(Counter counter) {
		return counter == Counter.BUDGET_CHARGED_BYTES || counter == Counter.HANDOFF_BYTES || counter == Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES;
	}
	private static Metric baseMetric(Metric metric) {
		return metric.kind() == Metric.Kind.TIMING_MAX ? Objects.requireNonNull(Metric.byId(metric.id().substring(0, metric.id().length() - 4))) : metric;
	}
	private static int category(Metric metric) {
		return switch (metric.kind()) {
			case TIMING, TIMING_MAX -> 0;
			case BUDGET -> 2;
			case MEMORY -> 3;
			case GAUGE -> metric.gauge() == Gauge.BUFFER_ESTIMATED_BYTES || metric.gauge() == Gauge.OUTSTANDING_LEASES ? 3 : 1;
			case COUNTER -> Objects.requireNonNull(metric.counter()).id().startsWith("sync.") ? 4 : 1;
			case SUMMARY -> throw new IllegalArgumentException("raw field required");
		};
	}
	private static int fieldOrder(Metric metric) {
		if (metric.gauge() == Gauge.REQUESTS_OPEN) return 0;
		if (metric.counter() == Counter.REQUESTS_RECEIVED) return 1;
		if (metric.counter() == Counter.REQUESTS_ADMITTED) return 2;
		if (metric.counter() == Counter.ACCEPTED) return 3;
		if (metric.counter() == Counter.REJECTED) return 4;
		if (metric.gauge() != null) return 200 + metric.gauge().wireId();
		if (metric.counter() != null) return 100 + metric.counter().wireId();
		return metric.wireId();
	}
}
