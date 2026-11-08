package nx.pingwheel.common.monitor.client;

import java.util.concurrent.atomic.AtomicLong;
import nx.pingwheel.common.monitor.MonitorSchema.*;
import nx.pingwheel.common.monitor.MonitorTuning;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ClientMonitorCollectorTest {

	@Test void disabledCollectorDoesNotReadTheInjectedClockOrPublish() {
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 2), () -> { throw new AssertionError("disabled clock"); });
		collector.configure(false); collector.resetConnection(1);
		collector.begin(Lane.PREVIEW, Phase.APPLY); collector.end(Lane.PREVIEW, Phase.APPLY);
		collector.count(Lane.TRACKING, Counter.ACCEPTED, 1); collector.gauge(Lane.TRACKING, Gauge.CHANNELS, 2);
		assertNull(collector.completeTick(1)); assertNull(collector.latest());
	}

	@Test void nestedProductionApplyIntervalsCollapseAndDoNotMixLanesOrPhases() {
		AtomicLong clock = new AtomicLong();
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 2), clock::get);
		collector.configure(true);
		collector.begin(Lane.TRACKING, Phase.APPLY); clock.set(5);
		collector.begin(Lane.TRACKING, Phase.APPLY); clock.set(10);
		collector.begin(Lane.PREVIEW, Phase.APPLY); clock.set(15);
		collector.end(Lane.PREVIEW, Phase.APPLY); collector.end(Lane.TRACKING, Phase.APPLY); clock.set(20);
		collector.end(Lane.TRACKING, Phase.APPLY); collector.completeTick(1);
		var sample = java.util.Objects.requireNonNull(collector.latest());
		assertEquals(Source.LOCAL, sample.tracking().source());
		assertEquals(new TimingStats(20, 1, 20), sample.tracking().timing(Phase.APPLY));
		assertEquals(new TimingStats(5, 1, 5), sample.preview().timing(Phase.APPLY)); assertNull(sample.shared());
		assertTrue(sample.tracking().budgets().isEmpty(), "server allowances are not local gauges or budgets");
	}

	@Test void disablingDuringAnIntervalCannotPublishOrResurrectTheOldInterval() {
		AtomicLong clock = new AtomicLong();
		var collector = new ClientMonitorCollector(new MonitorTuning(1, 1), clock::get);
		collector.configure(true); collector.begin(Lane.PREVIEW, Phase.DRAW);
		collector.configure(false); clock.set(100); collector.end(Lane.PREVIEW, Phase.DRAW);
		collector.configure(true); collector.completeTick(1);
		assertNull(collector.latest().preview().timing(Phase.DRAW));
	}
}
