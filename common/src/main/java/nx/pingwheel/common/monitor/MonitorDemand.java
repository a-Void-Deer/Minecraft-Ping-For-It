package nx.pingwheel.common.monitor;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Collection;

import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import nx.pingwheel.common.monitor.MonitorSchema.Scope;
import nx.pingwheel.common.monitor.MonitorSchema.Source;

/** Transmission demand only; never an instruction to enable or disable collection. */
@NotNullByDefault
public record MonitorDemand(int laneMask, boolean shared) {

	public static final int ALL_LANES = 3;
	public static final MonitorDemand NONE = new MonitorDemand(0, false);

	public MonitorDemand {
		if ((laneMask & ~ALL_LANES) != 0) throw new IllegalArgumentException("unknown monitor lane bits");
	}

	public boolean includes(Lane lane) { return (laneMask & lane.mask()) != 0; }
	public boolean serverRequested() { return laneMask != 0 || shared; }

	/** Local-only selectors require no subscription; summaries use their raw union. */
	public static MonitorDemand fromMetrics(Collection<Metric> selected) {
		int lanes = 0;
		boolean shared = false;
		for (Metric metric : Metric.expand(selected)) {
			Scope scope = java.util.Objects.requireNonNull(metric.scope(), "raw metric scope");
			if (scope.source() == Source.SHARED) shared = true;
			else if (scope.source() == Source.SERVER) lanes |= java.util.Objects.requireNonNull(scope.lane()).mask();
		}
		return new MonitorDemand(lanes, shared);
	}
}
