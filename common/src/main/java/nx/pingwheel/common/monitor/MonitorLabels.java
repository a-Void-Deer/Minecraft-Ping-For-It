package nx.pingwheel.common.monitor;

import net.minecraft.network.chat.MutableComponent;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.resource.LanguageUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Localized display vocabulary shared by monitor selection rows and the HUD.
 * The closed schema supplies stable identifiers; every visible word comes from
 * the finite {@code monitor.pingforit.*} resource families, so the 121 selectors
 * do not need 121 hand-written translations per locale.
 */
@NotNullByDefault
public final class MonitorLabels {
	private MonitorLabels() {
	}

	/** Human-readable metric name, including its source and lane for raw metrics. */
	public static MutableComponent metric(Metric metric) {
		Objects.requireNonNull(metric, "metric");
		if (metric.kind() == Metric.Kind.SUMMARY) {
			return shortLabel(metric);
		}
		return monitor("format", "label").get(scope(Objects.requireNonNull(metric.scope(), "raw metric scope")),
			shortLabel(metric));
	}

	/** Scope-free label for a detail cell or summary heading in the prepared HUD. */
	public static MutableComponent shortLabel(Metric metric) {
		Objects.requireNonNull(metric, "metric");
		return metric.kind() == Metric.Kind.SUMMARY
			? monitor("summary", summarySuffix(metric)).get()
			: metricName(metric);
	}

	/** Localized source and optional lane; Shared deliberately has no lane. */
	public static MutableComponent scope(Scope scope) {
		Objects.requireNonNull(scope, "scope");
		final MutableComponent source = monitor("source", scope.source().id()).get();
		final @Nullable var lane = scope.lane();
		return lane == null
			? source
			: monitor("format", "scope").get(source, monitor("lane", lane.id()).get());
	}

	/**
	 * Native help for one selector. The detail is intentionally about what the
	 * measurement does and does not mean; value formatting stays with the HUD.
	 */
	public static MutableComponent tooltip(Metric metric) {
		Objects.requireNonNull(metric, "metric");
		final MutableComponent result = monitor("format", "tooltip").get(metric(metric), kindTooltip(metric));
		result.append("\n").append(scopeTooltip(metric));
		if (metric == Metric.SUMMARY_SYNC_RATE || isSyncCounter(metric) || metric.budget() == MonitorSchema.Budget.WIRE_BYTES) {
			result.append("\n").append(monitor("tooltip", "sync").get());
		}
		if (metric == Metric.SUMMARY_MEMORY) result.append("\n").append(monitor("tooltip", "memory").get());
		if (metric == Metric.SUMMARY_MEMORY || metric.gauge() == Gauge.BUFFER_ESTIMATED_BYTES) {
			result.append("\n").append(monitor("tooltip", "buffer_estimate").get());
		}
		if (metric == Metric.SUMMARY_SYNC_RATE || metric.counter() == Counter.HANDOFF_BYTES || metric.counter() == Counter.HANDOFF_FRAMES
			|| metric.counter() == Counter.NOT_HANDED_OFF_FRAMES || metric.counter() == Counter.UNOBSERVED_HANDOFF_FRAMES
			|| metric.counter() == Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES) {
			result.append("\n").append(monitor("tooltip", "handoff").get());
		}
		return result;
	}

	private static MutableComponent metricName(Metric metric) {
		return switch (metric.kind()) {
			case TIMING -> monitor("phase", Objects.requireNonNull(metric.phase()).id()).get();
			case TIMING_MAX -> monitor("format", "statistic").get(
				monitor("phase", Objects.requireNonNull(metric.phase()).id()).get(),
				monitor("statistic", "max").get());
			case COUNTER -> monitor("counter", Objects.requireNonNull(metric.counter()).id()).get();
			case GAUGE -> monitor("gauge", Objects.requireNonNull(metric.gauge()).id()).get();
			case BUDGET -> monitor("budget", Objects.requireNonNull(metric.budget()).id()).get();
			case MEMORY -> monitor("memory", "shared").get();
			case SUMMARY -> throw new IllegalArgumentException("summary is handled separately");
		};
	}

	private static MutableComponent kindTooltip(Metric metric) {
		return switch (metric.kind()) {
			case SUMMARY -> monitor("tooltip", "summary").get();
			case TIMING -> monitor("tooltip", "timing").get();
			case TIMING_MAX -> monitor("tooltip", "timing_max").get();
			case COUNTER -> monitor("tooltip", "counter").get();
			case GAUGE -> monitor("tooltip", "gauge").get();
			case BUDGET -> monitor("tooltip", "budget").get();
			case MEMORY -> monitor("tooltip", "memory").get();
		};
	}

	private static MutableComponent scopeTooltip(Metric metric) {
		final @Nullable Scope scope = metric.scope();
		if (scope == null) {
			return monitor("tooltip", "scope", "summary").get();
		}
		return monitor("tooltip", "scope", scope.source().id()).get();
	}

	private static boolean isSyncCounter(Metric metric) {
		return metric.kind() == Metric.Kind.COUNTER
			&& Objects.requireNonNull(metric.counter()).id().startsWith("sync.");
	}

	private static String summarySuffix(Metric metric) {
		final String id = metric.id();
		final String prefix = "summary.";
		if (!id.startsWith(prefix)) throw new IllegalArgumentException("not a summary metric: " + id);
		return id.substring(prefix.length());
	}

	private static LanguageUtils monitor(String... path) {
		return LanguageUtils.of("monitor", path);
	}
}
