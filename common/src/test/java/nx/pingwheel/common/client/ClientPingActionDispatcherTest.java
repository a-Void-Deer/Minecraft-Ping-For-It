package nx.pingwheel.common.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.TargetGoneReason;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
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

		@Override
		public void sendToServer(IPacket packet) {
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

		@Override
		public void debug(String message, Object... args) {
			rendered.add(render(message, args));
		}
	}

	private static final class Harness {

		final RecordingPacketSender sender = new RecordingPacketSender();
		final RecordingErrorSink sink = new RecordingErrorSink();
		final RecordingLogger logger = new RecordingLogger();
		final ClientPingActionDispatcher dispatcher =
			new ClientPingActionDispatcher(sender, sink, logger);
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
	void targetGoneShowsTheExactLocalErrorAndSendsNothing() {
		Harness h = harness();
		ActiveInteraction interaction = new ActiveInteraction();
		CapturedPingContext context = capture(
			TargetSnapshotFactory.location(OVERWORLD, 0, 0, 0), interaction.begin());

		h.dispatcher.dispatch(new PingInteractionAction.TargetGone(context, TargetGoneReason.ENTITY_GONE_OR_DEAD));

		assertTrue(h.sender.sent.isEmpty());
		assertEquals(1, h.sink.messages.size());
		assertEquals(PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE, h.sink.messages.get(0));
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
		assertTrue(createLine.contains("dispatch create"));
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
}
