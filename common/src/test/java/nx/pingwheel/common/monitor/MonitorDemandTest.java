package nx.pingwheel.common.monitor;

import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.Metric;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class MonitorDemandTest {

	@Test
	void localOnlySelectionNeverRequestsRemoteStatsWhileSharedHasNoInventedLane() {
		var local = Objects.requireNonNull(Metric.byId("local.preview.timing.apply"));
		assertEquals(MonitorDemand.NONE, MonitorDemand.fromMetrics(List.of(local)));
		var memory = Objects.requireNonNull(Metric.byId("shared.memory"));
		var shared = MonitorDemand.fromMetrics(List.of(memory));
		assertEquals(0, shared.laneMask());
		assertTrue(shared.shared());
		assertTrue(shared.serverRequested());
		assertFalse(shared.includes(Lane.PREVIEW));
		assertFalse(shared.includes(Lane.TRACKING));
	}

	@Test
	void summariesAndDetailsProduceTheSameBoundedDemandUnion() {
		var preview = Objects.requireNonNull(Metric.byId("server.preview.requests.received"));
		var demand = MonitorDemand.fromMetrics(List.of(preview, preview));
		assertTrue(demand.includes(Lane.PREVIEW));
		assertFalse(demand.includes(Lane.TRACKING));
		assertFalse(demand.shared());
		assertEquals(MonitorDemand.fromMetrics(Metric.expand(Metric.defaults())), MonitorDemand.fromMetrics(Metric.defaults()));
		assertEquals(new MonitorDemand(3, true), MonitorDemand.fromMetrics(Metric.defaults()));
		assertThrows(IllegalArgumentException.class, () -> new MonitorDemand(4, false));
		assertThrows(IllegalArgumentException.class, () -> new MonitorDemand(-1, true));
	}
}
