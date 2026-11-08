package nx.pingwheel.common.presentation.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nx.pingwheel.common.config.InventorySettings;
import nx.pingwheel.common.monitor.MonitorSchema.Counter;
import nx.pingwheel.common.monitor.MonitorSchema.Gauge;
import nx.pingwheel.common.monitor.MonitorSchema.Lane;
import nx.pingwheel.common.monitor.MonitorSchema.MonitorSample;
import nx.pingwheel.common.monitor.MonitorTuning;
import nx.pingwheel.common.monitor.server.ServerInventoryMonitor;
import nx.pingwheel.common.network.InventoryS2CPacket;
import nx.pingwheel.common.platform.TransportHandoff;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.source.RetainedMemoryLedger;
import nx.pingwheel.common.presentation.source.SyncPublisher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class InventoryMonitorPublisherTest {
	@BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

	@NotNullByDefault
	static final class Transport implements InventorySyncPublisher.Transport {
		final InventoryMonitorBackendTest.Clock clock;
		final List<InventoryS2CPacket> sent = new ArrayList<>();
		TransportHandoff handoff = TransportHandoff.HANDED_OFF;
		boolean fail, defer;
		int measurements;
		Transport(InventoryMonitorBackendTest.Clock clock) { this.clock = clock; }
		@Override public boolean authorized(SyncPublisher.Context context) { return true; }
		@Override public int encodedBytes(InventoryS2CPacket packet) { throw new AssertionError("must use effective caps"); }
		@Override public int encodedBytes(InventoryS2CPacket packet, int display, int frame) {
			measurements++; clock.time += 11; return packet.encodedSize(display, frame);
		}
		@Override public InventorySyncPublisher.SendDecision beforeSend(SyncPublisher.Context context, InventoryS2CPacket packet,
			@Nullable InventoryPublicationEvidence evidence) {
			if (defer) return InventorySyncPublisher.SendDecision.DEFER;
			clock.proofToSend = true; return InventorySyncPublisher.SendDecision.ALLOW;
		}
		@Override public void send(UUID recipient, InventoryS2CPacket packet) {
			assertTrue(clock.proofToSend); clock.proofToSend = false; clock.time += 13;
			if (fail) { fail = false; throw new IllegalStateException("handoff outcome unknown"); }
			sent.add(packet);
		}
		@Override public TransportHandoff sendObserved(UUID recipient, InventoryS2CPacket packet) { send(recipient, packet); return handoff; }
	}

	@NotNullByDefault
	static final class Fixture implements AutoCloseable {
		final InventoryMonitorBackendTest.Clock clock = new InventoryMonitorBackendTest.Clock();
		final ServerInventoryMonitor monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), clock);
		final RetainedMemoryLedger memory = new RetainedMemoryLedger(16_000_000);
		final Transport transport = new Transport(clock);
		final InventorySettings settings = InventorySettings.serverDefaults();
		final InventorySyncPublisher publisher = new InventorySyncPublisher(memory, transport, BudgetDelaySink.NONE, monitor);
		final SyncPublisher.Context context = InventorySyncPublisherTest.context("preview/fixed", 1);
		Fixture(boolean enabled) {
			monitor.configure(enabled, false); publisher.advance(0, settings);
			assertTrue(publisher.register(context, new InventorySyncPublisher.Route(7, 100, 1, 1, null, "fixed", null)));
		}
		void publish(long revision, long amount) { InventorySyncPublisherTest.publish(publisher, context, InventorySyncPublisherTest.capture(revision, 1, 0, amount)); }
		MonitorSample sample(long tick) { publisher.sampleMonitor(); monitor.memory(memory); return java.util.Objects.requireNonNull(monitor.completeTick(tick)); }
		@Override public void close() { publisher.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved()); }
	}

	@Test void allNormalHandoffOutcomesPreserveBusinessProgressAndDoNotInventUnknownBytes() {
		@Nullable InventoryS2CPacket expected = null;
		for (TransportHandoff outcome : TransportHandoff.values()) try (var f = new Fixture(true)) {
			f.transport.handoff = outcome; f.publish(1, 7); f.publisher.drain();
			assertFalse(f.publisher.pending(f.context)); assertEquals(1, f.transport.sent.size());
			var packet = f.transport.sent.getFirst();
			if (expected == null) expected = packet; else assertEquals(expected, packet, "full packet, including keys and checksum");
			f.publisher.drain(); assertEquals(1, f.transport.sent.size(), "NOT and UNOBSERVED are observations, not a new retry policy");
			var sample = f.sample(0); var lane = sample.preview(); long bytes = packet.encodedSize(f.publisher.effectiveDisplayBytes(), f.publisher.effectiveFrameBytes());
			assertEquals(bytes, lane.counter(Counter.BUDGET_CHARGED_BYTES).value());
			assertEquals(1, lane.counter(Counter.FRAMES_PREVIEW).value());
			if (outcome == TransportHandoff.HANDED_OFF) {
				assertEquals(bytes, lane.counter(Counter.HANDOFF_BYTES).value()); assertEquals(1, lane.counter(Counter.HANDOFF_FRAMES).value());
			} else {
				assertNull(lane.counter(Counter.HANDOFF_BYTES), "unknown is omitted, never fabricated as observed zero");
				assertEquals(1, lane.counter(outcome == TransportHandoff.UNOBSERVED ? Counter.UNOBSERVED_HANDOFF_FRAMES : Counter.NOT_HANDED_OFF_FRAMES).value());
			}
			if (outcome == TransportHandoff.UNOBSERVED) assertEquals(bytes, lane.counter(Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES).value());
			else { assertNull(lane.counter(Counter.UNOBSERVED_HANDOFF_FRAMES)); assertNull(lane.counter(Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES)); }
			if (outcome != TransportHandoff.NOT_HANDED_OFF) assertNull(lane.counter(Counter.NOT_HANDED_OFF_FRAMES));
		}
	}

	@Test void throwingTransportKeepsConservativeChargeAndRetryButSeparatesKnownAndUnknownHandoff() {
		try (var f = new Fixture(true)) {
			f.publish(1, 7); f.transport.fail = true; f.publisher.drain();
			assertTrue(f.publisher.pending(f.context)); assertTrue(f.transport.sent.isEmpty());
			f.publisher.drain(); assertFalse(f.publisher.pending(f.context)); assertEquals(1, f.transport.sent.size());
			var lane = f.sample(0).preview(); long bytes = f.transport.sent.getFirst().encodedSize(f.publisher.effectiveDisplayBytes(), f.publisher.effectiveFrameBytes());
			assertEquals(2 * bytes, lane.counter(Counter.BUDGET_CHARGED_BYTES).value());
			assertEquals(bytes, lane.counter(Counter.HANDOFF_BYTES).value());
			assertEquals(bytes, lane.counter(Counter.UNOBSERVED_HANDOFF_CANDIDATE_BYTES).value());
			assertEquals(1, lane.counter(Counter.HANDOFF_FRAMES).value()); assertEquals(1, lane.counter(Counter.UNOBSERVED_HANDOFF_FRAMES).value());
			assertEquals(2, f.transport.measurements, "one existing measurement per attempt, no monitor encoding");
		}
	}

	@NotNullByDefault
	record Trace(@Unmodifiable List<InventoryS2CPacket> packets, @Unmodifiable List<Long> queues, @Unmodifiable List<Long> charges,
		long retained, int measurements) {
		Trace { packets = List.copyOf(packets); queues = List.copyOf(queues); charges = List.copyOf(charges); }
	}
	static Trace trace(boolean enabled) throws Exception {
		try (var f = new Fixture(enabled)) {
			var queues = new ArrayList<Long>(); var charges = new ArrayList<Long>();
			for (int tick = 0; tick < 4; tick++) {
				f.publisher.advance(tick, f.settings); f.publish(tick + 1, tick + 7); f.transport.defer = tick == 0;
				f.publisher.drain(); f.transport.defer = false; f.transport.fail = tick == 1; f.publisher.drain();
				queues.add((long) InventoryBackendTest.field(f.publisher, "previewQueued"));
				charges.add(InventoryPublicationAuthorizationTest.wireSpent(f.publisher));
				f.clock.time += 100; f.publisher.sampleMonitor(); f.monitor.completeTick(tick);
			}
			return new Trace(f.transport.sent, queues, charges, f.memory.retained(), f.transport.measurements);
		}
	}
	@Test void offAndOnKeepFullFixedKeyPacketsChecksumQueueAndBudgetTrajectoriesIdentical() throws Exception {
		var off = trace(false); var on = trace(true); assertEquals(off, on);
		assertEquals(4, off.packets().size()); assertTrue(off.packets().stream().allMatch(packet -> !packet.entries().isEmpty() && packet.checksum() != 0));
		assertTrue(off.charges().getLast() > off.charges().getFirst()); assertTrue(off.measurements() > 4);
		assertTrue(off.queues().stream().allMatch(value -> value == 0), "every retried fixed-key cut completes");
	}

	@Test void alreadyQueuedBaselineAndUniqueRetainedMemoryRemainImmutableAcrossCapReductionAndRelease() {
		try (var f = new Fixture(false)) {
			f.publish(1, 7); long queued = (long) uncheckedField(f.publisher, "previewQueued"); assertTrue(queued > 0);
			f.monitor.configure(true, false); var baseline = f.sample(0);
			assertEquals(queued, baseline.preview().gauge(Gauge.QUEUE_LOGICAL_COST).value());
			assertNull(baseline.preview().counter(Counter.BUDGET_CHARGED_BYTES));
		}
		var clock = new InventoryMonitorBackendTest.Clock(); var monitor = new ServerInventoryMonitor(new MonitorTuning(1, 8), clock);
		var memory = new RetainedMemoryLedger(100); var shared = memory.tryReserve(40).orElseThrow(); shared.commit(40);
		var firstCursor = memory.tryReserve(5).orElseThrow(); firstCursor.commit(5);
		var secondCursor = memory.tryReserve(5).orElseThrow(); secondCursor.commit(5);
		var reservation = memory.tryReserve(20).orElseThrow(); memory.setCap(60);
		monitor.configure(true, false); monitor.memory(memory); var first = java.util.Objects.requireNonNull(monitor.completeTick(0));
		assertEquals(50, first.shared().memory().retained()); assertEquals(20, first.shared().memory().reserved()); assertEquals(-10, first.shared().memory().remaining());
		assertEquals(60, first.shared().memory().cap()); assertEquals(0, first.shared().memory().admissibleRemaining());
		firstCursor.close(); monitor.memory(memory); var second = java.util.Objects.requireNonNull(monitor.completeTick(1)); assertEquals(-5, second.shared().memory().remaining());
		reservation.close(); monitor.memory(memory); var third = java.util.Objects.requireNonNull(monitor.completeTick(2)); assertEquals(15, third.shared().memory().remaining());
		assertEquals(0, second.shared().memory().admissibleRemaining()); assertEquals(15, third.shared().memory().admissibleRemaining());
		assertEquals(-10, first.shared().memory().remaining()); assertEquals(-5, second.shared().memory().remaining());
		secondCursor.close(); shared.close(); assertEquals(0, memory.retained()); assertEquals(0, memory.reserved());
	}
	private static Object uncheckedField(Object owner, String name) {
		try { return InventoryBackendTest.field(owner, name); }
		catch (Exception failure) { throw new AssertionError(failure); }
	}
}
