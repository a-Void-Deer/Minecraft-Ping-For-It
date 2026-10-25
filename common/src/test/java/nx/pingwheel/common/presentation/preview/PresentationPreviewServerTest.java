package nx.pingwheel.common.presentation.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.network.PresentationPreviewC2SPacket;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.presentation.PresentationAdapter;
import nx.pingwheel.common.presentation.PresentationSection;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayEvent;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.DelayState;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Module;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Reason;
import nx.pingwheel.common.presentation.budget.BudgetDelaySink.Unit;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;

class PresentationPreviewServerTest {
	private static final String ADAPTER = "test:basic", FIELD = "test:remote";
	private static final UUID PLAYER = UUID.randomUUID();
	private static PresentationPreviewC2SPacket request(long id) {
		return PresentationPreviewC2SPacket.read(71, 1, id, new Target.LocationTarget("minecraft:overworld", 0, 0, 0), "block", ADAPTER, Set.of(FIELD));
	}
	private static class Host implements PresentationPreviewServer.Host {
		@Nullable PresentationPreviewAccess access = ClientPresentationPreviewTest.access(1, Set.of(FIELD));
		List<String> events = new ArrayList<>(); List<PresentationPreviewS2CPacket> sent = new ArrayList<>();
		boolean revoke;
		public Optional<PresentationPreviewAccess> access(UUID player, String type) { events.add("authorize"); return Optional.ofNullable(access); }
		public int intervalTicks(UUID player, String adapter) { return 1; }
		public int scanBudget() { return 3; }
		public PresentationPreviewServer.Capture capture(UUID player, PresentationPreviewC2SPacket request, PresentationPreviewAccess authorization, PresentationAdapter.CaptureBudget budget) {
			assertTrue(budget.scan(), "host receives admitted bounded work"); events.add("read");
			if (revoke) access = ClientPresentationPreviewTest.access(1, Set.of());
			return new PresentationPreviewServer.Capture(new PresentationSection(ADAPTER, 1,
				Map.of(FIELD, new PresentationValue.NumberValue(0)), false), PresentationPreviewS2CPacket.Status.RESULT);
		}
		public boolean send(UUID player, PresentationPreviewS2CPacket packet) { events.add("send"); sent.add(packet); return true; }
	}
	@Test void packetIngressNoReadAndSharedBudgetChargedOnlyOnce() {
		Host host = new Host(); var server = new PresentationPreviewServer(host);
		assertTrue(server.handle(PLAYER, request(1), 0)); assertFalse(host.events.contains("read"));
		assertEquals(0, server.drain(0, new PresentationAdapter.CaptureBudget(0), 1));
		assertFalse(host.events.contains("read"));
		var work = new PresentationAdapter.CaptureBudget(5);
		assertEquals(1, server.drain(0, work, 1)); assertEquals(3, work.remaining());
		assertEquals(List.of("read", "send"), host.events.stream().filter(event -> !event.equals("authorize")).toList());
		assertFalse(server.handle(PLAYER, request(1), 100));
		assertEquals(0, server.drain(100, work, 1)); assertEquals(1, host.sent.size());
	}
	@Test void revokedBeforeSendNeverPublishesAndCancellationCannotRecapture() {
		Host host = new Host(); host.revoke = true; var server = new PresentationPreviewServer(host);
		server.handle(PLAYER, request(1), 0); server.drain(0, new PresentationAdapter.CaptureBudget(4), 1);
		assertTrue(host.sent.isEmpty());
		host.access = ClientPresentationPreviewTest.access(1, Set.of(FIELD));
		assertTrue(server.handle(PLAYER, request(2), PresentationPreviewLimits.MIN_REQUEST_TICKS));
		assertTrue(server.handle(PLAYER, PresentationPreviewC2SPacket.cancel(71, 1, 2), PresentationPreviewLimits.MIN_REQUEST_TICKS));
		assertFalse(server.handle(PLAYER, request(2), 200));
		assertEquals(0, server.queuedCount());
	}
	@Test void finiteQueueAndCaptureAttemptsCannotExceedSharedQuota() {
		Host host = new Host(); var server = new PresentationPreviewServer(host);
		for (int i = 0; i < PresentationPreviewLimits.MAX_QUEUED; i++) assertTrue(server.handle(new UUID(0, i), request(1), 0));
		assertFalse(server.handle(new UUID(1, 0), request(1), 0));
		assertEquals(1, server.drain(0, new PresentationAdapter.CaptureBudget(20), 1));
		assertEquals(PresentationPreviewLimits.MAX_QUEUED - 1, server.queuedCount());
		server.drain(PresentationPreviewLimits.REQUEST_TICKS, new PresentationAdapter.CaptureBudget(0), 0);
		assertEquals(0, server.queuedCount());
		assertEquals(1, host.events.stream().filter("read"::equals).count());
	}
	@Test void deniedBeforeDrainAndSameAdapterCadenceCannotBeResetByNewIds() {
		Host host = new Host() {
			@Override public int intervalTicks(UUID player, String adapter) { return (int) PresentationPreviewLimits.REQUEST_TICKS / 2; }
		};
		var server = new PresentationPreviewServer(host); server.handle(PLAYER, request(1), 0);
		host.access = ClientPresentationPreviewTest.access(1, Set.of());
		assertEquals(0, server.drain(0, new PresentationAdapter.CaptureBudget(10), 5));
		assertFalse(host.events.contains("read"));
		host.access = ClientPresentationPreviewTest.access(1, Set.of(FIELD));
		server.handle(PLAYER, request(2), PresentationPreviewLimits.MIN_REQUEST_TICKS);
		assertEquals(1, server.drain(PresentationPreviewLimits.MIN_REQUEST_TICKS, new PresentationAdapter.CaptureBudget(10), 5));
		server.handle(PLAYER, request(3), PresentationPreviewLimits.MIN_REQUEST_TICKS * 2);
		assertEquals(0, server.drain(PresentationPreviewLimits.MIN_REQUEST_TICKS * 2, new PresentationAdapter.CaptureBudget(10), 5));
		assertEquals(1, host.events.stream().filter("read"::equals).count());
	}
	@Test void transportRefusalDoesNotRepeatRead() {
		Host host = new Host() {
			@Override public boolean send(UUID player, PresentationPreviewS2CPacket packet) { return false; }
		};
		var server = new PresentationPreviewServer(host); server.handle(PLAYER, request(1), 0);
		assertEquals(1, server.drain(0, new PresentationAdapter.CaptureBudget(10), 2));
		assertEquals(0, server.drain(1, new PresentationAdapter.CaptureBudget(10), 2));
		assertEquals(1, host.events.stream().filter("read"::equals).count());
	}
	private static class Sink implements BudgetDelaySink {
		final List<DelayEvent> events = new ArrayList<>();
		public void observe(DelayEvent event) { events.add(event); }
		public void state(Module module, long cycle, long now, DelayState state) { fail("presentation tick owns final state"); }
		public void endCycle(Module module, long cycle, long now) { fail("preview must not close the shared cycle"); }
	}
	@Test void monitoringReportsOnlyActualDueAuthorizedRefusalAndDeduplicatesReasonsPerRequestCycle() {
		Host host = new Host(); var sink = new Sink(); var server = new PresentationPreviewServer(host, sink);
		assertTrue(server.handle(PLAYER, request(1), 0));
		assertEquals(0, server.drain(50, new PresentationAdapter.CaptureBudget(1), 0, 10));
		assertEquals(DelayState.DELAYED, server.delayState());
		assertEquals(1, sink.events.size());
		assertEquals(Reason.CAPTURE_COUNT.bit() | Reason.SOURCE_WORK.bit(), sink.events.getFirst().newReasonBits());
		assertTrue(sink.events.getFirst().firstItemObservation());
		assertEquals(Unit.REQUEST_ADAPTER, sink.events.getFirst().quantity().unit());
		assertEquals(50, sink.events.getFirst().serverTick(), "accounting cycle 10 is not server tick 50");
		server.drain(50, new PresentationAdapter.CaptureBudget(1), 0, 10);
		assertEquals(1, sink.events.size(), "same refused request/reasons are not counted twice in one shared cycle");
		assertEquals(1, server.drain(50, new PresentationAdapter.CaptureBudget(8), 1, 10));
		assertEquals(DelayState.HEALTHY, server.delayState()); assertEquals(1, host.sent.size());
		server.drain(51, new PresentationAdapter.CaptureBudget(0), 0, 11);
		assertEquals(DelayState.IDLE, server.delayState()); assertEquals(1, sink.events.size());
	}
	@Test void ordinaryDrainUsesSchedulingTickForMonitoringWithoutASeparateClock() {
		Host host = new Host(); var sink = new Sink(); var server = new PresentationPreviewServer(host, sink);
		assertTrue(server.handle(PLAYER, request(1), 1234));
		assertEquals(0, server.drain(1234, new PresentationAdapter.CaptureBudget(0), 0));
		assertEquals(1234, sink.events.getFirst().serverTick());
		assertEquals(1234, sink.events.getFirst().cycleId());
		server.drain(1234, new PresentationAdapter.CaptureBudget(0), 0);
		assertEquals(1, sink.events.size(), "a repeated drain in one tick does not create another cycle");
		server.drain(1254, new PresentationAdapter.CaptureBudget(0), 0);
		assertEquals(1254, sink.events.getLast().serverTick());
		assertFalse(host.events.contains("read"));
	}
	@Test void monitoringExcludesCadenceRevocationExpiryUnavailableAndTransportRefusal() {
		Host host = new Host() {
			@Override public int intervalTicks(UUID player, String adapter) { return 100; }
			@Override public PresentationPreviewServer.@Nullable Capture capture(UUID player, PresentationPreviewC2SPacket request,
			PresentationPreviewAccess authorization, PresentationAdapter.CaptureBudget budget) { return null; }
			@Override public boolean send(UUID player, PresentationPreviewS2CPacket packet) { return false; }
		};
		var sink = new Sink(); var server = new PresentationPreviewServer(host, sink);
		server.handle(PLAYER, request(1), 0); assertEquals(1, server.drain(0, new PresentationAdapter.CaptureBudget(10), 1, 0));
		assertEquals(DelayState.HEALTHY, server.delayState()); assertTrue(sink.events.isEmpty());
		server.handle(PLAYER, request(2), PresentationPreviewLimits.MIN_REQUEST_TICKS);
		server.drain(PresentationPreviewLimits.MIN_REQUEST_TICKS, new PresentationAdapter.CaptureBudget(0), 0, 1);
		assertEquals(DelayState.NEUTRAL, server.delayState()); assertTrue(sink.events.isEmpty());
		host.access = ClientPresentationPreviewTest.access(1, Set.of());
		server.drain(101, new PresentationAdapter.CaptureBudget(0), 0, 2);
		assertEquals(DelayState.IDLE, server.delayState()); assertTrue(sink.events.isEmpty());
		host.access = ClientPresentationPreviewTest.access(1, Set.of(FIELD));
		server.handle(PLAYER, request(3), 200);
		server.drain(200 + PresentationPreviewLimits.REQUEST_TICKS, new PresentationAdapter.CaptureBudget(0), 0, 3);
		assertEquals(DelayState.IDLE, server.delayState()); assertTrue(sink.events.isEmpty());
	}
	@Test void insufficientPerCallAllowanceRetainsAcceptedRequestUntilRestoredWithoutValidationRead() {
		int[] configured = {2};
		Host host = new Host() { @Override public int scanBudget() { return configured[0]; } };
		var sink = new Sink(); var server = new PresentationPreviewServer(host, sink);
		assertTrue(server.handle(PLAYER, request(1), 0));
		assertEquals(0, server.drain(0, new PresentationAdapter.CaptureBudget(10), 2, 0));
		assertEquals(1, server.queuedCount()); assertFalse(host.events.contains("read"));
		assertEquals(Reason.PER_CALL_ALLOWANCE.bit(), sink.events.getFirst().newReasonBits());
		configured[0] = 3;
		assertEquals(1, server.drain(1, new PresentationAdapter.CaptureBudget(10), 2, 1));
		assertEquals(0, server.queuedCount()); assertEquals(DelayState.HEALTHY, server.delayState());
	}
}
