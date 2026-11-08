package nx.pingwheel.common.monitor.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
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
import nx.pingwheel.common.monitor.MonitorDemand;
import nx.pingwheel.common.monitor.MonitorSchema.Budget;
import nx.pingwheel.common.monitor.MonitorSchema.BudgetUsage;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.CounterReading;
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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Immutable, preformatted HUD rows. Build on tick/status/selection/locale changes,
	 * never while painting. Component caches are immutable literal leaves and
	 * immutable sibling lists, not MutableComponent. No clock, transport, history or selector
 * mutation is available to a painter. Label ownership stays in the injected UI
 * label provider, shared with the selector controls.
 */
@NotNullByDefault
public final class MonitorDisplayModel {

	public static final String PREFIX = "monitor.pingforit.";
	public static final MonitorDisplayModel EMPTY = new MonitorDisplayModel(List.of());

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
		private final Component text;

		private Row(String id, List<Segment> segments, List<String> metricIds) {
			this.id = id; this.segments = List.copyOf(segments); this.metricIds = List.copyOf(metricIds);
			List<Component> leaves = segments.stream().map(segment -> (Component) new CachedText(segment.text().getString(),
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
			List<FormattedText> lines = splitter.splitLines(row.text(), maximumWidth, Style.EMPTY);
			for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
				List<Segment> segments = new ArrayList<>();
				lines.get(lineIndex).visit((style, text) -> {
					segments.add(new Segment(Component.literal(text), ChatFormatting.RED.getColor().equals(
						style.getColor() == null ? null : style.getColor().getValue()) ? Tone.OFF : Tone.NORMAL));
					return java.util.Optional.empty();
				}, Style.EMPTY);
				fitted.add(new Row(row.id() + ".line." + lineIndex, segments, lineIndex == 0 ? row.metricIds() : List.of()));
			}
		}
		return new MonitorDisplayModel(fitted);
	}

	/** shortLabel excludes scope; fullLabel includes it. P5 supplies both from one vocabulary. */
	public static MonitorDisplayModel prepare(ClientMonitorState.View view, Function<Metric, Component> fullLabel,
		Function<Metric, Component> shortLabel, Function<Scope, Component> scopeLabel) {
		if (!view.enabled()) return EMPTY;
		List<Row> rows = new ArrayList<>();
		MonitorDemand demand = MonitorDemand.fromMetrics(view.selected());
		if (demand.includes(Lane.PREVIEW)) addStatus(rows, Scope.SERVER_PREVIEW, view.previewStatus(), scopeLabel);
		if (demand.includes(Lane.TRACKING)) addStatus(rows, Scope.SERVER_TRACKING, view.trackingStatus(), scopeLabel);
		if (demand.shared()) addStatus(rows, Scope.SHARED, view.sharedStatus(), scopeLabel);
		Set<Metric> represented = new HashSet<>();
		for (Metric summary : Metric.defaults()) {
			if (!view.selected().contains(summary)) continue;
			// One compact row per selected summary and region. Local has two
			// independent lane cells; no local/server duration or ledger sums.
			for (int group = 0; group < 4; group++) {
				List<Component> cells = new ArrayList<>();
				List<String> ids = new ArrayList<>();
				boolean applicable = false;
				for (Metric metric : summary.expanded()) {
					Scope scope = Objects.requireNonNull(metric.scope());
					if (group(scope) != group || !active(view, scope) || !principal(summary, metric)) continue;
					applicable = true;
					@Nullable MonitorSample sample = sample(view, scope);
					if (!available(metric, sample)) continue;
					Component value = compact(metric, sample, summary == Metric.SUMMARY_SYNC_RATE);
					Component field = shortLabel.apply(metric);
					if (scope.source() == Source.LOCAL) field = tr("format.scope", tr("lane." + Objects.requireNonNull(scope.lane()).id()), field);
					cells.add(tr("format.entry", field, value));
					ids.add(metric.id());
					represented.add(metric);
				}
				if (!applicable) continue;
				Component region = group == 3 ? tr("source.local") : scopeLabel.apply(groupScope(group));
				Component heading = tr("format.scope", region, shortLabel.apply(summary));
				rows.add(normalRow(summary.id() + "." + group, tr("format.row", heading, cells.isEmpty() ? unavailable() : join(cells)), ids));
			}
		}
		for (Metric metric : Metric.rawMetrics()) {
			if (!view.selected().contains(metric) || represented.contains(metric)) continue;
			Scope scope = Objects.requireNonNull(metric.scope());
			if (!active(view, scope)) continue;
			rows.add(normalRow(metric.id(), tr("format.row", fullLabel.apply(metric), format(metric, sample(view, scope), false)), List.of(metric.id())));
		}
		return new MonitorDisplayModel(rows);
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
			return metric.counter() == Counter.REQUESTS_RECEIVED || metric.counter() == Counter.ACCEPTED
				|| metric.counter() == Counter.REJECTED || metric.gauge() != null
				&& (metric.gauge() == nx.pingwheel.common.monitor.MonitorSchema.Gauge.REQUESTS_OPEN
					|| metric.gauge() == nx.pingwheel.common.monitor.MonitorSchema.Gauge.QUEUE_LOGICAL_COST
					|| metric.gauge() == nx.pingwheel.common.monitor.MonitorSchema.Gauge.CHANNELS
					|| metric.gauge() == nx.pingwheel.common.monitor.MonitorSchema.Gauge.CONSUMERS);
		}
		return true;
	}

	private static void addStatus(List<Row> rows, Scope scope, ClientMonitorState.Status status,
		Function<Scope, Component> labels) {
		if (status == ClientMonitorState.Status.ACTIVE) return;
		String key = status.name().toLowerCase(Locale.ROOT);
		// Row grammar remains localized while the exact OFF token alone is red.
		Component token = tr("status." + key);
		// Resolve localized grammar with a sentinel so OFF remains independently
		// styled even in locales whose value precedes the label.
		String sentinel = "\u0001";
		String grammar = tr("format.row", labels.apply(scope), sentinel).getString();
		String[] parts = grammar.split(sentinel, -1);
		List<Segment> segments = new ArrayList<>();
		for (int index = 0; index < parts.length; index++) {
			segments.add(new Segment(Component.literal(parts[index]), Tone.NORMAL));
			if (index < parts.length - 1) segments.add(new Segment(token, status == ClientMonitorState.Status.OFF ? Tone.OFF : Tone.NORMAL));
		}
		if (parts.length == 1) segments.add(new Segment(token, status == ClientMonitorState.Status.OFF ? Tone.OFF : Tone.NORMAL));
		rows.add(new Row("status." + scope.id(), segments, List.of()));
	}

	private static Row normalRow(String id, Component text, List<String> ids) {
		return new Row(id, List.of(new Segment(text, Tone.NORMAL)), ids);
	}

	private static Component compact(Metric metric, @Nullable MonitorSample sample, boolean rate) {
		if (sample == null) return unavailable();
		Scope scope = Objects.requireNonNull(metric.scope());
		@Nullable LaneSample lane = scope.lane() == Lane.PREVIEW ? sample.preview() : sample.tracking();
		if (metric.kind() == Metric.Kind.TIMING) {
			@Nullable TimingStats stats = lane == null ? null : lane.timing(Objects.requireNonNull(metric.phase()));
			return stats == null ? unavailable() : tr("format.milliseconds", stats.saturated()
				? tr("format.lower_bound", decimal(stats.totalNs() / 1_000_000.0)) : decimal(stats.totalNs() / 1_000_000.0));
		}
		if (metric.kind() == Metric.Kind.BUDGET) {
			@Nullable BudgetUsage usage = scope.source() == Source.SHARED
				? sample.shared() == null ? null : sample.shared().budget(Objects.requireNonNull(metric.budget()))
				: lane == null ? null : lane.budget(Objects.requireNonNull(metric.budget()));
			return usage == null || !usage.available() ? unavailable()
				: tr("format.budget_compact", Long.toString(usage.used()), Long.toString(usage.limit()));
		}
		return format(metric, sample, rate);
	}

	/** Missing optional details do not inflate a concise summary into dozens of unavailable cells. */
	private static boolean available(Metric metric, @Nullable MonitorSample sample) {
		if (sample == null) return false;
		Scope scope = Objects.requireNonNull(metric.scope());
		@Nullable LaneSample lane = scope.source() == Source.SHARED ? null : scope.lane() == Lane.PREVIEW ? sample.preview() : sample.tracking();
		@Nullable SharedSample shared = scope.source() == Source.SHARED ? sample.shared() : null;
		return switch (metric.kind()) {
			case TIMING, TIMING_MAX -> lane != null && lane.timing(Objects.requireNonNull(metric.phase())) != null;
			case COUNTER -> lane != null ? lane.counter(Objects.requireNonNull(metric.counter())) != null
				: shared != null && shared.counter(Objects.requireNonNull(metric.counter())) != null;
			case GAUGE -> lane != null ? lane.gauge(Objects.requireNonNull(metric.gauge())) != null
				: shared != null && shared.gauge(Objects.requireNonNull(metric.gauge())) != null;
			case BUDGET -> {
				@Nullable BudgetUsage usage = lane != null ? lane.budget(Objects.requireNonNull(metric.budget()))
					: shared == null ? null : shared.budget(Objects.requireNonNull(metric.budget()));
				yield usage != null && usage.available();
			}
			case MEMORY -> shared != null && shared.memory() != null;
			case SUMMARY -> false;
		};
	}

	private static Component join(Collection<Component> cells) {
		MutableComponent result = Component.empty();
		boolean first = true;
		for (Component cell : cells) {
			if (!first) result.append(tr("format.separator"));
			result.append(cell); first = false;
		}
		return result;
	}

	private static boolean active(ClientMonitorState.View view, Scope scope) {
		return scope.source() == Source.LOCAL || switch (scope.source()) {
			case SERVER -> (scope.lane() == Lane.PREVIEW ? view.previewStatus() : view.trackingStatus()) == ClientMonitorState.Status.ACTIVE;
			case SHARED -> view.sharedStatus() == ClientMonitorState.Status.ACTIVE;
			case LOCAL -> true;
		};
	}
	private static int group(Scope scope) { return scope.source() == Source.LOCAL ? 3
		: scope.source() == Source.SHARED ? 2 : scope.lane() == Lane.PREVIEW ? 0 : 1; }
	private static Scope groupScope(int group) { return switch (group) {
		case 0 -> Scope.SERVER_PREVIEW; case 1 -> Scope.SERVER_TRACKING; case 2 -> Scope.SHARED;
		default -> throw new IllegalArgumentException("local has independent lanes");
	}; }
	private static @Nullable MonitorSample sample(ClientMonitorState.View view, Scope scope) {
		return scope.source() == Source.LOCAL ? view.local() : view.remote();
	}

	/** One field only. Inclusive phases are never added; every rate uses its own endpoint's window. */
	public static Component format(Metric metric, @Nullable MonitorSample sample, boolean rate) {
		if (sample == null) return unavailable();
		Scope scope = Objects.requireNonNull(metric.scope(), "raw metric");
		@Nullable LaneSample lane = scope.source() == Source.SHARED ? null
			: scope.lane() == Lane.PREVIEW ? sample.preview() : sample.tracking();
		@Nullable SharedSample shared = scope.source() == Source.SHARED ? sample.shared() : null;
		if (lane != null && lane.source() != scope.source()) return unavailable();
		return switch (metric.kind()) {
			case TIMING, TIMING_MAX -> {
				@Nullable TimingStats stats = lane == null ? null : lane.timing(Objects.requireNonNull(metric.phase()));
				if (stats == null) yield unavailable();
				if (metric.kind() == Metric.Kind.TIMING_MAX) yield tr("format.peak", decimal(stats.maxNs() / 1_000_000.0));
				OptionalDouble mean = stats.meanNs();
				yield tr("format.timing", stats.saturated() ? tr("format.lower_bound", decimal(stats.totalNs() / 1_000_000.0))
					: decimal(stats.totalNs() / 1_000_000.0), stats.count() == Long.MAX_VALUE
						? tr("format.lower_bound", Long.toString(stats.count())) : Long.toString(stats.count()),
					mean.isPresent() ? Component.literal(decimal(mean.getAsDouble() / 1_000_000.0)) : unavailable());
			}
			case COUNTER -> {
				Counter counter = Objects.requireNonNull(metric.counter());
				@Nullable CounterReading reading = lane != null ? lane.counter(counter) : shared == null ? null : shared.counter(counter);
				if (reading == null) yield unavailable();
				Component value;
				if (rate) {
					OptionalDouble measuredRate = sample.ratePerSecond(reading);
					if (measuredRate.isEmpty()) yield unavailable();
					value = tr("format.rate", decimal(measuredRate.getAsDouble()));
				} else value = tr("format.counter", Long.toString(reading.value()));
				// Known bytes remain a lower bound when any handoff was unobserved.
				@Nullable CounterReading unknown = lane == null ? null : lane.counter(Counter.UNOBSERVED_HANDOFF_FRAMES);
				if (reading.saturated() || counter == Counter.HANDOFF_BYTES && (unknown == null || unknown.value() > 0))
					yield tr("format.lower_bound", value);
				yield value;
			}
			case GAUGE -> {
				@Nullable GaugeReading reading = lane != null ? lane.gauge(Objects.requireNonNull(metric.gauge()))
					: shared == null ? null : shared.gauge(Objects.requireNonNull(metric.gauge()));
				yield reading == null ? unavailable() : metric.gauge() == nx.pingwheel.common.monitor.MonitorSchema.Gauge.BUFFER_ESTIMATED_BYTES
					? tr("format.bytes", Long.toString(reading.value())) : tr("format.counter", Long.toString(reading.value()));
			}
			case BUDGET -> {
				@Nullable BudgetUsage usage = lane != null ? lane.budget(Objects.requireNonNull(metric.budget()))
					: shared == null ? null : shared.budget(Objects.requireNonNull(metric.budget()));
				yield usage == null || !usage.available() ? unavailable() : tr("format.budget", Long.toString(usage.used()),
					Long.toString(usage.limit()), Long.toString(usage.reserved()), Long.toString(usage.remaining()), Long.toString(usage.admissibleRemaining()));
			}
			case MEMORY -> {
				@Nullable MemoryUsage memory = shared == null ? null : shared.memory();
				yield memory == null ? unavailable() : tr("format.memory", tr("format.bytes", Long.toString(memory.retained())),
					tr("format.bytes", Long.toString(memory.reserved())), tr("format.bytes", Long.toString(memory.cap())),
					tr("format.bytes", Long.toString(memory.remaining())));
			}
			case SUMMARY -> throw new IllegalArgumentException("summary is not a measurement");
		};
	}

	private static Component unavailable() { return tr("status.unavailable"); }
	private static String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
	private static Component tr(String suffix, Object... args) { return Component.translatable(PREFIX + suffix, args); }
}
