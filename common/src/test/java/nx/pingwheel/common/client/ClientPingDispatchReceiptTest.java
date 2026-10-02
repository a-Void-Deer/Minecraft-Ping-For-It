package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import static org.junit.jupiter.api.Assertions.*;

class ClientPingDispatchReceiptTest {
	private static PingInteractionAction.CreatePing create(ActiveInteraction active) {
		var snapshot = TargetSnapshotFactory.location("minecraft:overworld", 1, 2, 3);
		var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()).resolve(snapshot.target(), snapshot.matchContext());
		return new PingInteractionAction.CreatePing(new CapturedPingContext(active.begin(), resolved), resolved.targetType().defaultPingType());
	}
	@Test void receiptRequiresActualSendAndFailureRollsBackTrackerWithoutQueueingOrRefund() {
		long[] time = {0}; var tracker = new CreateRequestTracker(); var active = new ActiveInteraction(); List<IPacket> sent = new ArrayList<>();
		boolean[] fail = {false};
		var dispatcher = new ClientPingActionDispatcher(packet -> {
			assertFalse(tracker.isEmpty(), "recording precedes a possible synchronous sender callback");
			if (fail[0]) throw new IllegalStateException("transport"); sent.add(packet);
		}, (key, color) -> {}, PingInteractionLogger.noop(), tracker, new ClientCreateRateLimiter(() -> time[0], new ClientRateLimitPolicy(1, 1000)));
		var first = create(active);
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.CREATE_SENT, dispatcher.dispatchOutcome(first));
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.THROTTLED, dispatcher.dispatchOutcome(create(active)));
		time[0] = 1000; fail[0] = true;
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchOutcome(create(active)));
		assertTrue(tracker.isLatest(first.context().token().sequence()));
		fail[0] = false;
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.THROTTLED, dispatcher.dispatchOutcome(create(active)));
		time[0] = 2000; assertEquals(1, sent.size(), "failure is not retained for a later send");
	}
	@Test void failedFirstSenderRestoresEmptyTracker() {
		var tracker = new CreateRequestTracker();
		var dispatcher = new ClientPingActionDispatcher(packet -> { throw new IllegalStateException("transport"); },
			(key, color) -> {}, PingInteractionLogger.noop(), tracker, new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(0, 0)));
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchOutcome(create(new ActiveInteraction())));
		assertTrue(tracker.isEmpty());
	}
	@Test void outerTransportFailureNeverRollsBackNewerReentrantRouteReceipt() {
		var tracker = new CreateRequestTracker();
		var dispatcher = new ClientPingActionDispatcher(packet -> {
			tracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, 55);
			throw new IllegalStateException("outer transport");
		}, (key, color) -> {}, PingInteractionLogger.noop(), tracker, new ClientCreateRateLimiter(() -> 0, new ClientRateLimitPolicy(0, 0)));
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.TRANSPORT_FAILED, dispatcher.dispatchOutcome(create(new ActiveInteraction())));
		assertTrue(tracker.isLatest(CreateRequestTracker.Route.INVENTORY, 55));
	}
}
