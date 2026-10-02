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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationPreviewServerTest {
	private static final String ADAPTER = "test:basic", FIELD = "test:remote";
	private static final UUID PLAYER = UUID.randomUUID();
	private static PresentationPreviewC2SPacket request(long id) {
		return PresentationPreviewC2SPacket.read(71, 1, id, new Target.LocationTarget("minecraft:overworld", 0, 0, 0), "block", ADAPTER, Set.of(FIELD));
	}
	private static class Host implements PresentationPreviewServer.Host {
		PresentationPreviewAccess access = ClientPresentationPreviewTest.access(1, Set.of(FIELD));
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
}
