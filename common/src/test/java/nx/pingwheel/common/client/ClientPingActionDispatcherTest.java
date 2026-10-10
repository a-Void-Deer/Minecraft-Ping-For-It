package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.TargetGoneReason;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.network.PresentationS2CPacket;
import nx.pingwheel.common.presentation.PresentationField;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.presentation.PresentationPropertyRef;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.Set;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPingActionDispatcherTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final UUID ENTITY_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

	private static final class RecordingPacketSender implements ClientPingActionDispatcher.PacketSender {

		final List<IPacket> sent = new ArrayList<>();
		Runnable onSend = () -> {};

		@Override
		public void sendToServer(IPacket packet) {
			onSend.run();
			sent.add(packet);
		}
	}

	private static final class RecordingErrorSink implements ClientPingActionDispatcher.LocalErrorSink {

		final List<String> messages = new ArrayList<>();
		final List<Integer> colors = new ArrayList<>();

		@Override
		public void showLocalError(String message, int color) {
			messages.add(message);
			colors.add(color);
		}
	}

	private static final class RecordingLogger implements PingInteractionLogger {

		final List<String> rendered = new ArrayList<>();
		final List<String> throttled = new ArrayList<>();

		@Override
		public void debug(String message, Object... args) {
			rendered.add(render(message, args));
		}

		@Override
		public void debugCreateThrottled(long requestId, int rateLimit, int msToRegenerate) {
			throttled.add("requestId=" + requestId + " rateLimit=" + rateLimit
				+ " msToRegenerate=" + msToRegenerate);
		}
	}

	private static final class Harness {

		final RecordingPacketSender sender = new RecordingPacketSender();
		final RecordingErrorSink sink = new RecordingErrorSink();
		final RecordingLogger logger = new RecordingLogger();
		final ClientPingActionDispatcher dispatcher;

		Harness() {
			this.dispatcher = new ClientPingActionDispatcher(
				sender,
				sink,
				logger,
				new CreateRequestTracker(),
				new ClientCreateRateLimiter(new ManualTime(), new ClientRateLimitPolicy(0, 0)));
		}

		Harness(ClientPingActionDispatcher dispatcher) {
			this.dispatcher = dispatcher;
		}
	}

	private static Harness harness() {
		return new Harness();
	}

	private static CapturedPingContext capture(TargetSnapshot snapshot, InteractionToken token) {
		var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.noop())
			.resolve(snapshot.target(), snapshot.matchContext());
		return new CapturedPingContext(token, resolved);
	}

	@Test
	void createPingMapsRequestIdTargetAndPingType() {
		Harness h = harness();
		ActiveInteraction interaction = new ActiveInteraction();
		InteractionToken token = interaction.begin();
		TargetSnapshot snapshot = TargetSnapshotFactory.location(OVERWORLD, 1, 2, 3);
		CapturedPingContext context = capture(snapshot, token);
		PingType selected = context.resolvedTarget().targetType().pingTypes().get(0);

		h.dispatcher.dispatch(new PingInteractionAction.CreatePing(context, selected));

		assertEquals(1, h.sender.sent.size());
		assertTrue(h.sink.messages.isEmpty());

		MarkerCreateC2SPacket packet = assertInstanceOf(MarkerCreateC2SPacket.class, h.sender.sent.get(0));
		assertFalse(packet.isCorrupt());
		assertEquals(token.sequence(), packet.requestId());
		assertEquals(snapshot.target(), packet.target());
		assertEquals(selected.id(), packet.pingTypeId());
	}

	@Test
	void allowedCreateUpdatesTrackerBeforeSending() {
		Harness h = harness();
		CreateRequestTracker tracker = new CreateRequestTracker();
		ManualTime time = new ManualTime();
		ClientPingActionDispatcher dispatcher = new ClientPingActionDispatcher(
			h.sender,
			h.sink,
			h.logger,
			tracker,
			new ClientCreateRateLimiter(time, ClientRateLimitPolicy.DEFAULT));
		ActiveInteraction interaction = new ActiveInteraction();
		InteractionToken token = interaction.begin();
		CapturedPingContext context = capture(TargetSnapshotFactory.location(OVERWORLD, 1, 2, 3), token);
		AtomicBoolean latestWhenSent = new AtomicBoolean(false);
		h.sender.onSend = () -> latestWhenSent.set(tracker.isLatest(token.sequence()));

		dispatcher.dispatch(new PingInteractionAction.CreatePing(
			context, context.resolvedTarget().targetType().defaultPingType()));

		assertEquals(1, h.sender.sent.size());
		assertTrue(latestWhenSent.get(),
			"the tracker must already hold the request id when the packet sender runs");
		assertTrue(tracker.isLatest(token.sequence()));
	}

	@Test
	void blockedCreateIsDroppedWithoutTrackerUpdateOrRetry() {
		Harness h = harness();
		CreateRequestTracker tracker = new CreateRequestTracker();
		ManualTime time = new ManualTime();
		ClientPingActionDispatcher dispatcher = new ClientPingActionDispatcher(
			h.sender,
			h.sink,
			h.logger,
			tracker,
			new ClientCreateRateLimiter(time, new ClientRateLimitPolicy(1, 1000)));
		ActiveInteraction interaction = new ActiveInteraction();
		PingInteractionAction.CreatePing first = createAction(interaction.begin());
		PingInteractionAction.CreatePing blocked = createAction(interaction.begin());

		dispatcher.dispatch(first);
		dispatcher.dispatch(blocked);

		assertEquals(1, h.sender.sent.size());
		assertTrue(tracker.isLatest(first.context().token().sequence()));
		assertEquals(1, h.logger.throttled.size());
		time.now = 1000;
		assertEquals(1, h.sender.sent.size(), "a dropped action must not be queued for later");
	}

	@Test
	void cancelIsStillSentWhenCreatesAreBlocked() {
		Harness h = harness();
		CreateRequestTracker tracker = new CreateRequestTracker();
		ManualTime time = new ManualTime();
		ClientPingActionDispatcher dispatcher = new ClientPingActionDispatcher(
			h.sender,
			h.sink,
			h.logger,
			tracker,
			new ClientCreateRateLimiter(time, new ClientRateLimitPolicy(1, 1000)));
		ActiveInteraction interaction = new ActiveInteraction();
		PingInteractionAction.CreatePing first = createAction(interaction.begin());

		dispatcher.dispatch(first);
		dispatcher.dispatch(createAction(interaction.begin()));
		dispatcher.dispatch(new PingInteractionAction.CancelMarker(new MarkerId(8L)));

		assertEquals(2, h.sender.sent.size());
		assertInstanceOf(MarkerRemoveC2SPacket.class, h.sender.sent.get(1));
	}

	@Test
	void createPingRequestIdFollowsTheInteractionTokenSequence() {
		Harness h = harness();
		ActiveInteraction interaction = new ActiveInteraction();
		InteractionToken firstToken = interaction.begin();
		InteractionToken secondToken = interaction.begin();
		TargetSnapshot snapshot = TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0);
		CapturedPingContext first = capture(snapshot, firstToken);
		CapturedPingContext second = capture(snapshot, secondToken);

		h.dispatcher.dispatch(new PingInteractionAction.CreatePing(first,
			first.resolvedTarget().targetType().defaultPingType()));
		h.dispatcher.dispatch(new PingInteractionAction.CreatePing(second,
			second.resolvedTarget().targetType().defaultPingType()));

		assertEquals(2, h.sender.sent.size());
		assertEquals(firstToken.sequence(),
			assertInstanceOf(MarkerCreateC2SPacket.class, h.sender.sent.get(0)).requestId());
		assertEquals(secondToken.sequence(),
			assertInstanceOf(MarkerCreateC2SPacket.class, h.sender.sent.get(1)).requestId());
		assertEquals(firstToken.sequence() + 1, secondToken.sequence());
	}

	@Test
	void cancelMarkerMapsTheMarkerId() {
		Harness h = harness();
		MarkerId markerId = new MarkerId(7L);

		h.dispatcher.dispatch(new PingInteractionAction.CancelMarker(markerId));

		assertEquals(1, h.sender.sent.size());
		assertTrue(h.sink.messages.isEmpty());

		MarkerRemoveC2SPacket packet = assertInstanceOf(MarkerRemoveC2SPacket.class, h.sender.sent.get(0));
		assertFalse(packet.isCorrupt());
		assertEquals(markerId, packet.markerId());
	}

	@Test
	void targetGoneShowsTheLocalizedFeedbackKeyAndSendsNothing() {
		Harness h = harness();
		ActiveInteraction interaction = new ActiveInteraction();
		CapturedPingContext context = capture(
			TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0), interaction.begin());

		h.dispatcher.dispatch(new PingInteractionAction.TargetGone(context, TargetGoneReason.ENTITY_GONE_OR_DEAD));

		assertTrue(h.sender.sent.isEmpty());
		assertEquals(1, h.sink.messages.size());
		assertEquals(PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY, h.sink.messages.get(0));
		assertEquals(Integer.valueOf(PingInteractionAction.TargetGone.TARGET_GONE_COLOR), h.sink.colors.get(0));
	}

	@Test
	void logsCarryOnlySafeFields() {
		Harness h = harness();
		ActiveInteraction interaction = new ActiveInteraction();
		CapturedPingContext context = capture(
			TargetSnapshotFactory.entity(OVERWORLD, ENTITY_ID, "minecraft:zombie"),
			interaction.begin());
		PingType selected = context.resolvedTarget().targetType().pingTypes().get(0);

		h.dispatcher.dispatch(new PingInteractionAction.CreatePing(context, selected));
		h.dispatcher.dispatch(new PingInteractionAction.CancelMarker(new MarkerId(42L)));
		h.dispatcher.dispatch(new PingInteractionAction.TargetGone(context, TargetGoneReason.DIMENSION_CHANGED));

		assertFalse(h.logger.rendered.isEmpty());

		for (String line : h.logger.rendered) {
			assertFalse(line.contains(ENTITY_ID.toString()), "must never log an entity UUID: " + line);
			assertFalse(line.contains("0.0"), "must never log positions/colors: " + line);
		}

		String createLine = h.logger.rendered.get(0);
		assertTrue(createLine.contains("requestId=" + context.token().sequence()));
		assertTrue(createLine.contains(selected.id()));

		assertTrue(h.logger.rendered.get(1).contains("markerId=42"));
		assertTrue(h.logger.rendered.get(2).contains("DIMENSION_CHANGED"));
	}

	private static String render(String message, Object... args) {
		StringBuilder rendered = new StringBuilder();
		int argIndex = 0;
		int from = 0;

		while (argIndex < args.length) {
			int placeholder = message.indexOf("{}", from);

			if (placeholder < 0) {
				break;
			}

			rendered.append(message, from, placeholder).append(args[argIndex++]);
			from = placeholder + 2;
		}

		rendered.append(message, from, message.length());
		return rendered.toString();
	}

	private static PingInteractionAction.CreatePing createAction(InteractionToken token) {
		CapturedPingContext context = capture(TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0), token);
		return new PingInteractionAction.CreatePing(
			context, context.resolvedTarget().targetType().defaultPingType());
	}

	@Test
	void programmaticPropertyCreateUsesFrozenTargetAndActualTypedWire() {
		Harness h = harness();
		ClientPresentation presentation = new ClientPresentation(h.sender::sendToServer);
		presentation.tick(true);
		assertTrue(presentation.offer(PresentationS2CPacket.offer(31L,
			Map.of(ClientPresentation.BASIC, List.of(new PresentationField(
				"minecraft:block.state", PresentationField.Kind.RECORD, true, 0, "State"))),
			Map.of(ClientPresentation.BASIC, 1))));
		assertTrue(presentation.reset(PresentationS2CPacket.reset(31L, 1L,
			Map.of("location", Map.of(ClientPresentation.BASIC, Set.of("minecraft:block.state"))))));
		var dispatcher = new ClientPingActionDispatcher(h.sender, h.sink, h.logger,
			new CreateRequestTracker(), new ClientCreateRateLimiter(new ManualTime(), new ClientRateLimitPolicy(0, 0)),
			presentation);
		var action = createAction(new ActiveInteraction().begin());
		var ref = new PresentationPropertyRef(ClientPresentation.BASIC, "minecraft:block.state",
			List.of("counts", "minecraft:cobblestone"));
		var property = PresentationPropertyIntent.of(ref, new PresentationValue.NumberValue(64), "request");
		dispatcher.dispatch(action, List.of(property));
		PresentationC2SPacket sent = assertInstanceOf(PresentationC2SPacket.class, h.sender.sent.get(1));
		FriendlyByteBuf bytes = new FriendlyByteBuf(Unpooled.buffer());
		try {
			sent.write(bytes);
			var decoded = PresentationC2SPacket.readSafe(bytes);
			assertFalse(decoded.isCorrupt());
			assertEquals(action.context().resolvedTarget().target(), decoded.target());
			assertEquals(action.pingType().id(), decoded.pingType());
			assertEquals(List.of(property), decoded.properties());
			assertEquals(0, bytes.readableBytes());
		} finally { bytes.release(); }
		dispatcher.dispatch(action);
		assertEquals(List.of(), ((PresentationC2SPacket) h.sender.sent.get(2)).properties());
		var denies = new java.util.LinkedHashMap<>(PresentationS2CPacket.emptyChildBlack());
		denies.put("location", List.of(ref));
		assertTrue(presentation.reset(PresentationS2CPacket.reset(31L, 2L,
			Map.of("location", Map.of(ClientPresentation.BASIC, Set.of("minecraft:block.state"))), denies)));
		assertEquals(ClientPingActionDispatcher.DispatchOutcome.NOT_READY, dispatcher.dispatchOutcome(action, List.of(property)));
		assertEquals(3, h.sender.sent.size(), "revocation recheck sends no create and never silently sends a plain fallback");
	}

	private static final class ManualTime implements InteractionTimeSource {
		private long now;

		@Override
		public long nowMillis() {
			return now;
		}
	}
}
