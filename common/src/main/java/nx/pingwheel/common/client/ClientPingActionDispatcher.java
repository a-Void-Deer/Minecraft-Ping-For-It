package nx.pingwheel.common.client;

import java.util.List;
import java.util.Objects;

import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.MarkerCreateC2SPacket;
import nx.pingwheel.common.network.MarkerRemoveC2SPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;

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
	/** Local sender outcome, never server acceptance. */
	public enum DispatchOutcome { CREATE_SENT, OTHER, NOT_READY, THROTTLED, TRANSPORT_FAILED }

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
	 * <p>The language-resource key of the exact message and the 24-bit color
	 * are supplied by the dispatcher; implementations must resolve the key and
	 * must not reword or re-theme the result.
	 */
	@FunctionalInterface
	public interface LocalErrorSink {

		void showLocalError(String messageKey, int color);
	}

	private final PacketSender packetSender;
	private final LocalErrorSink errorSink;
	private final PingInteractionLogger logger;
	private final CreateRequestTracker createRequestTracker;
	private final ClientCreateRateLimiter createRateLimiter;
	private final ClientPresentation presentation;

	public ClientPingActionDispatcher(
		PacketSender packetSender,
		LocalErrorSink errorSink,
		PingInteractionLogger logger,
		CreateRequestTracker createRequestTracker,
		ClientCreateRateLimiter createRateLimiter
	) {
		this(packetSender, errorSink, logger, createRequestTracker, createRateLimiter, null);
	}

	/** The production route requires a negotiated presentation session. */
	public ClientPingActionDispatcher(
		PacketSender packetSender,
		LocalErrorSink errorSink,
		PingInteractionLogger logger,
		CreateRequestTracker createRequestTracker,
		ClientCreateRateLimiter createRateLimiter,
		ClientPresentation presentation
	) {
		this.packetSender = Objects.requireNonNull(packetSender, "packetSender");
		this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.createRequestTracker = Objects.requireNonNull(createRequestTracker, "createRequestTracker");
		this.createRateLimiter = Objects.requireNonNull(createRateLimiter, "createRateLimiter");
		this.presentation = presentation;
	}

	/**
	 * Convenience overload retaining the existing dispatcher construction shape
	 * for callers that do not need to share the runtime-owned dependencies.
	 */
	public ClientPingActionDispatcher(
		PacketSender packetSender,
		LocalErrorSink errorSink,
		PingInteractionLogger logger
	) {
		this(
			packetSender,
			errorSink,
			logger,
			new CreateRequestTracker(),
			new ClientCreateRateLimiter(InteractionTimeSource.system()));
	}

	/**
	 * Maps one interaction outcome onto its side effects.
	 */
	public void dispatch(PingInteractionAction action) {
		dispatch(action, List.of());
	}

	/** Programmatic property-ping entry point; payloads remain detached observations. */
	public void dispatch(PingInteractionAction action, List<PresentationPropertyIntent> properties) {
		dispatchOutcome(action, properties);
	}

	public DispatchOutcome dispatchOutcome(PingInteractionAction action) {
		return dispatchOutcome(action, List.of());
	}

	public DispatchOutcome dispatchOutcome(PingInteractionAction action, List<PresentationPropertyIntent> properties) {
		Objects.requireNonNull(action, "action");
		List<PresentationPropertyIntent> requested = List.copyOf(properties);
		return switch (action) {
			case PingInteractionAction.CreatePing create -> dispatchCreate(create, requested);
			case PingInteractionAction.CancelMarker cancel -> { dispatchCancel(cancel); yield DispatchOutcome.OTHER; }
			case PingInteractionAction.TargetGone gone -> { dispatchTargetGone(gone); yield DispatchOutcome.OTHER; }
		};
	}

	private DispatchOutcome dispatchCreate(PingInteractionAction.CreatePing create, List<PresentationPropertyIntent> properties) {
		if (presentation != null && !presentation.ready()) return DispatchOutcome.NOT_READY;
		if (presentation != null && properties.stream().anyMatch(property ->
			!presentation.propertyAllowed(create.context().resolvedTarget().targetType().id(), property.ref())))
			return DispatchOutcome.NOT_READY;
		long requestId = create.context().token().sequence();
		var target = create.context().resolvedTarget().target();
		var policy = createRateLimiter.policy();
		if (!createRateLimiter.tryAcquire()) {
			logger.debugCreateThrottled(requestId, policy.rateLimit(), policy.msToRegenerate());
			return DispatchOutcome.THROTTLED;
		}
		var attempt = createRequestTracker.beginDispatch(CreateRequestTracker.Route.PRESENTATION, requestId);
		try {
			packetSender.sendToServer(presentation == null
				? new MarkerCreateC2SPacket(requestId, target, create.pingType().id())
				: PresentationC2SPacket.create(presentation.epoch(), requestId, target, create.pingType().id(), properties));
		} catch (RuntimeException failed) {
			createRequestTracker.failedDispatch(attempt);
			return DispatchOutcome.TRANSPORT_FAILED;
		}
		logger.debug("dispatch create: requestId={} kind={} pingType={}", requestId, target.kind(), create.pingType().id());
		return DispatchOutcome.CREATE_SENT;
	}
	public ClientInventory.DispatchOutcome dispatchInventory(InventoryC2SPacket packet) {
		if (packet == null || packet.isCorrupt() || packet.kind() != InventoryC2SPacket.Kind.SELECT || presentation == null || !presentation.ready()
			|| packet.presentationEpoch() != presentation.epoch() || packet.view() != presentation.sessionView()) return ClientInventory.DispatchOutcome.NOT_READY;
		if (!createRateLimiter.tryAcquire()) return ClientInventory.DispatchOutcome.THROTTLED;
		try { packetSender.sendToServer(packet); }
		catch (RuntimeException failed) { return ClientInventory.DispatchOutcome.TRANSPORT_FAILED; }
		createRequestTracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, packet.commitId());
		return ClientInventory.DispatchOutcome.SENT;
	}

	private void dispatchCancel(PingInteractionAction.CancelMarker cancel) {
		if (presentation != null && !presentation.ready()) {
			return;
		}
		MarkerId markerId = cancel.markerId();

		packetSender.sendToServer(presentation == null
			? new MarkerRemoveC2SPacket(markerId)
			: PresentationC2SPacket.remove(presentation.epoch(), markerId));

		logger.debug("dispatch cancel: markerId={}", markerId.value());
	}

	private void dispatchTargetGone(PingInteractionAction.TargetGone gone) {
		errorSink.showLocalError(
			PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY,
			PingInteractionAction.TargetGone.TARGET_GONE_COLOR);

		logger.debug("dispatch target gone: kind={} reason={}",
			gone.context().resolvedTarget().target().kind(), gone.reason());
	}
}
