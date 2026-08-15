package nx.pingwheel.common.client;

import java.util.Objects;

import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;

/**
 * The pure, phase-7 client dispatcher between {@link PingInteractionAction}s
 * and the network/local-feedback boundaries.
 *
 * <p>It holds no Minecraft state and performs no validation: the frozen
 * capture produced by the phase-5 state machine is mapped 1:1 onto the phase-6
 * wire packets, and a failed local validation is surfaced through the injected
 * error sink. Neither the packets nor the debug logs carry owner identities,
 * target type ids, colors, or display names: only the request id (the
 * interaction token sequence), the frozen {@link nx.pingwheel.common.domain.Target},
 * the ping type id, the marker id, and the {@code TargetGoneReason} are safe to
 * emit.
 *
 * <p>Both dependencies are injected as functional interfaces so the mapping
 * can be tested with fakes; the Minecraft adapter that shows the local error
 * lives in {@link MinecraftLocalErrorSink} and the packet sender is wired to
 * {@link nx.pingwheel.common.platform.IPlatformNetworkService} by
 * {@link ClientPingRuntime}.
 */
public final class ClientPingActionDispatcher {

	/**
	 * Sends one client-to-server packet.
	 */
	@FunctionalInterface
	public interface PacketSender {

		void sendToServer(IPacket packet);
	}

	/**
	 * Shows a purely local error to the local player.
	 *
	 * <p>The exact message and 24-bit color are supplied by the dispatcher;
	 * implementations must not reword or re-theme them.
	 */
	@FunctionalInterface
	public interface LocalErrorSink {

		void showLocalError(String message, int color);
	}

	private final PacketSender packetSender;
	private final LocalErrorSink errorSink;
	private final PingInteractionLogger logger;

	public ClientPingActionDispatcher(
		PacketSender packetSender,
		LocalErrorSink errorSink,
		PingInteractionLogger logger
	) {
		this.packetSender = Objects.requireNonNull(packetSender, "packetSender");
		this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
		this.logger = Objects.requireNonNull(logger, "logger");
	}

	/**
	 * Maps one interaction outcome onto its side effects.
	 */
	public void dispatch(PingInteractionAction action) {
		Objects.requireNonNull(action, "action");

		switch (action) {
			case PingInteractionAction.CreatePing create -> dispatchCreate(create);
			case PingInteractionAction.CancelMarker cancel -> dispatchCancel(cancel);
			case PingInteractionAction.TargetGone gone -> dispatchTargetGone(gone);
		}
	}

	private void dispatchCreate(PingInteractionAction.CreatePing create) {
		long requestId = create.context().token().sequence();
		var target = create.context().resolvedTarget().target();

		packetSender.sendToServer(new MarkerCreateC2SPacket(requestId, target, create.pingType().id()));

		logger.debug("dispatch create: requestId={} kind={} pingType={}",
			requestId, target.kind(), create.pingType().id());
	}

	private void dispatchCancel(PingInteractionAction.CancelMarker cancel) {
		MarkerId markerId = cancel.markerId();

		packetSender.sendToServer(new MarkerRemoveC2SPacket(markerId));

		logger.debug("dispatch cancel: markerId={}", markerId.value());
	}

	private void dispatchTargetGone(PingInteractionAction.TargetGone gone) {
		errorSink.showLocalError(
			PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE,
			PingInteractionAction.TargetGone.TARGET_GONE_COLOR);

		logger.debug("dispatch target gone: kind={} reason={}",
			gone.context().resolvedTarget().target().kind(), gone.reason());
	}
}
