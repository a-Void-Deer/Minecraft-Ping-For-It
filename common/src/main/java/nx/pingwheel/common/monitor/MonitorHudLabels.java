package nx.pingwheel.common.monitor;

import net.minecraft.network.chat.Component;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Phase;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.resource.LanguageUtils;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;
import java.util.Objects;

/**
 * Compact HUD-only vocabulary. It deliberately does not replace the full
 * selector labels owned by {@link MonitorLabels}; the finite
 * {@code monitor.pingforit.hud.*} keys are derived from the closed schema
 * enums so neither configuration nor the wire can add a label.
 */
@NotNullByDefault
public final class MonitorHudLabels {

	/** The single immutable default provider backed by bundled translations. */
	public static final Labels DEFAULT = new DefaultLabels();

	private MonitorHudLabels() {
	}

	/**
	 * Presentation seam consumed by the prepared HUD model. Implementations
	 * return localized components and never style tokens; the painter decides
	 * which status token is red.
	 */
	@NotNullByDefault
	public interface Labels {
		Component prefix(Scope scope);
		Component field(Metric metric);
		Component stat(Stat stat);
		Component status(ClientMonitorState.Status status);
		Component unavailable();
		Component format(Format format, Object... args);

		@NotNullByDefault
		enum Stat { AVG, TOTAL, COUNT, PEAK, RATE, RESERVED, REMAINING, ADMISSIBLE }

		@NotNullByDefault
		enum Format { ROW, ENTRY, STAT, MEMORY, FIELD_SEPARATOR, DETAIL_SEPARATOR }
	}

	@NotNullByDefault
	private static final class DefaultLabels implements Labels {
		@Override
		public Component prefix(Scope scope) {
			Objects.requireNonNull(scope, "scope");
			return switch (scope.source()) {
				case SERVER -> switch (Objects.requireNonNull(scope.lane(), "server lane")) {
					case PREVIEW -> hud("prefix", "server", "preview").get();
					case TRACKING -> hud("prefix", "server", "tracking").get();
				};
				case LOCAL -> switch (Objects.requireNonNull(scope.lane(), "local lane")) {
					case PREVIEW -> hud("prefix", "local", "preview").get();
					case TRACKING -> hud("prefix", "local", "tracking").get();
				};
				case SHARED -> hud("prefix", "shared").get();
			};
		}

		@Override
		public Component field(Metric metric) {
			Objects.requireNonNull(metric, "metric");
			return switch (metric.kind()) {
				case TIMING, TIMING_MAX -> phase(Objects.requireNonNull(metric.phase(), "timing phase"));
				case COUNTER -> hud("field", "counter", Objects.requireNonNull(metric.counter(), "counter").id()).get();
				case GAUGE -> hud("field", "gauge", Objects.requireNonNull(metric.gauge(), "gauge").id()).get();
				case BUDGET -> hud("field", "budget", Objects.requireNonNull(metric.budget(), "budget").id()).get();
				case MEMORY -> hud("field", "memory").get();
				case SUMMARY -> throw new IllegalArgumentException("summary is not a HUD field: " + metric.id());
			};
		}

		@Override
		public Component stat(Labels.Stat stat) {
			Objects.requireNonNull(stat, "stat");
			return hud("stat", stat.name().toLowerCase(Locale.ROOT)).get();
		}

		@Override
		public Component status(ClientMonitorState.Status status) {
			Objects.requireNonNull(status, "status");
			return hud("status", status.name().toLowerCase(Locale.ROOT)).get();
		}

		@Override
		public Component unavailable() {
			return hud("status", "unavailable").get();
		}

		@Override
		public Component format(Labels.Format format, Object... args) {
			Objects.requireNonNull(format, "format");
			return hud("format", format.name().toLowerCase(Locale.ROOT)).get(args);
		}

		private static Component phase(Phase phase) {
			return hud("phase", Objects.requireNonNull(phase, "phase").id()).get();
		}

		private static LanguageUtils hud(String... path) {
			return LanguageUtils.of("monitor", "hud").path(path);
		}
	}
}
