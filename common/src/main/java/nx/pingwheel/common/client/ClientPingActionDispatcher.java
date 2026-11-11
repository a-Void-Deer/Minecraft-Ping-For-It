package nx.pingwheel.common.client;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.diagnostics.DiagnosticChannel;
import nx.pingwheel.common.diagnostics.Diagnostics;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.SelectorReleaseRejection;
import nx.pingwheel.common.network.IPacket;
import nx.pingwheel.common.network.PresentationC2SPacket;
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
import nx.pingwheel.common.network.InventoryC2SPacket;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.inventory.client.ClientInventoryDiagnostics;
import nx.pingwheel.common.presentation.inventory.InventoryFailureReason;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The pure client dispatcher between {@link PingInteractionAction}s
 * and the network/local-feedback boundaries.
 *
 * <p>It holds no Minecraft state. Frozen create intents require a ready
 * presentation session and authorized property selections before consuming a
 * courtesy token, recording the request, and sending. Removes require readiness
 * but bypass the create limiter. Inventory selections share that limiter and
 * retain their presentation epoch/view fences. Local target validation failures
 * are surfaced through the injected error sink.
 *
 * <p>Both dependencies are injected as functional interfaces so the mapping
 * can be tested with fakes; the Minecraft adapter that shows the local error
 * lives in {@link MinecraftLocalErrorSink} and the packet sender is wired to
 * {@link nx.pingwheel.common.platform.IPlatformNetworkService} by
 * {@link ClientPingRuntime}.
 */
public final class ClientPingActionDispatcher {
	private static final DiagnosticChannel CREATE_TRANSPORT_FAILURE =
		DiagnosticChannel.interval("client-send-create-transport", Duration.ofSeconds(10));
	private static final DiagnosticChannel SELECT_TRANSPORT_FAILURE =
		DiagnosticChannel.interval("client-send-select-transport", Duration.ofSeconds(10));

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

		default void showTrackingFailure(InventoryFailureReason reason) {
			showLocalError(reason.translationKey(), PingInteractionAction.TargetGone.TARGET_GONE_COLOR);
		}
	}

	private final PacketSender packetSender;
	private final LocalErrorSink errorSink;
	private final PingInteractionLogger logger;
	private final CreateRequestTracker createRequestTracker;
	private final ClientCreateRateLimiter createRateLimiter;
	private final ClientPresentation presentation;
	private record InventoryFailureKey(long epoch, long view, long commit) {}
	private final Set<InventoryFailureKey> inventoryFailures = new LinkedHashSet<>();

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
		this.presentation = Objects.requireNonNull(presentation, "presentation");
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
		@Unmodifiable List<PresentationPropertyIntent> requested = List.copyOf(properties);
		return switch (action) {
			case PingInteractionAction.CreatePing create -> dispatchCreate(create, requested);
			case PingInteractionAction.CancelMarker cancel -> { dispatchCancel(cancel); yield DispatchOutcome.OTHER; }
			case PingInteractionAction.TargetGone gone -> { dispatchTargetGone(gone); yield DispatchOutcome.OTHER; }
		};
	}

	private DispatchOutcome dispatchCreate(PingInteractionAction.CreatePing create, List<PresentationPropertyIntent> properties) {
		long requestId = create.context().token().sequence();
		long revision = presentation.epoch();
		if (!presentation.ready()) {
			rejectSelectorRelease(SelectorReleaseRejection.PRESENTATION_NOT_READY, requestId, revision, "presentation");
			return DispatchOutcome.NOT_READY;
		}
		if (properties.stream().anyMatch(property ->
			!presentation.propertyAllowed(create.context().resolvedTarget().targetType().id(), property.ref()))) {
			rejectSelectorRelease(SelectorReleaseRejection.PROPERTY_DENIED, requestId, revision, "presentation");
			return DispatchOutcome.NOT_READY;
		}
		var target = create.context().resolvedTarget().target();
		var policy = createRateLimiter.policy();
		if (!createRateLimiter.tryAcquire()) {
			logger.debugCreateThrottled(requestId, policy.rateLimit(), policy.msToRegenerate());
			return DispatchOutcome.THROTTLED;
		}
		var attempt = createRequestTracker.beginDispatch(CreateRequestTracker.Route.PRESENTATION, requestId);
		try {
			packetSender.sendToServer(PresentationC2SPacket.create(
				presentation.epoch(), requestId, target, create.pingType().id(), properties));
		} catch (RuntimeException failed) {
			createRequestTracker.failedDispatch(attempt);
			Diagnostics.client().atNormal().rateLimited(CREATE_TRANSPORT_FAILURE).failure(Level.WARN,
				"client-send/create", "transport",
				() -> "requestId=" + requestId + " kind=" + target.kind() + " pingType=" + create.pingType().id(),
				failed);
			return DispatchOutcome.TRANSPORT_FAILED;
		}
		logger.debug("dispatch create: requestId={} kind={} pingType={}", requestId, target.kind(), create.pingType().id());
		return DispatchOutcome.CREATE_SENT;
	}
	public ClientInventory.DispatchOutcome dispatchInventory(@Nullable InventoryC2SPacket packet) {
		return dispatchInventory(packet, () -> false);
	}
	/** An accepted synchronous response takes precedence over an exception after transport handoff. */
	public ClientInventory.DispatchOutcome dispatchInventory(@Nullable InventoryC2SPacket packet, BooleanSupplier terminalAccepted) {
		if (packet == null || packet.isCorrupt() || packet.kind() != InventoryC2SPacket.Kind.SELECT)
			return ClientInventory.DispatchOutcome.NOT_READY;
		long revision = presentation.epoch();
		if (!presentation.ready()) {
			rejectSelectorRelease(SelectorReleaseRejection.PRESENTATION_NOT_READY, packet.commitId(), revision, "inventory");
			inventoryFailure(packet, InventoryFailureReason.SESSION_TIMEOUT);
			return ClientInventory.DispatchOutcome.NOT_READY;
		}
		if (packet.presentationEpoch() != presentation.epoch() || packet.view() != presentation.sessionView()) {
			rejectSelectorRelease(SelectorReleaseRejection.INVENTORY_VIEW_MISMATCH, packet.commitId(), revision, "inventory");
			inventoryFailure(packet, InventoryFailureReason.RETIRED_SELECTION);
			return ClientInventory.DispatchOutcome.NOT_READY;
		}
		if (!createRateLimiter.tryAcquire()) {
			rejectSelectorRelease(SelectorReleaseRejection.COURTESY_THROTTLED, packet.commitId(), revision, "inventory");
			inventoryFailure(packet, InventoryFailureReason.RATE_LIMITED);
			return ClientInventory.DispatchOutcome.THROTTLED;
		}
		try { packetSender.sendToServer(packet); }
		catch (RuntimeException failed) {
			Diagnostics.client().atNormal().rateLimited(SELECT_TRANSPORT_FAILURE).failure(Level.WARN,
				"client-send/select", "transport",
				() -> "commitId=" + packet.commitId() + " epoch=" + packet.presentationEpoch() + " view=" + packet.view(),
				failed);
			if (terminalAccepted.getAsBoolean()) {
				createRequestTracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, packet.commitId());
				return ClientInventory.DispatchOutcome.SENT;
			}
			inventoryFailure(packet, InventoryFailureReason.TRANSPORT_FAILURE);
			return ClientInventory.DispatchOutcome.TRANSPORT_FAILED;
		}
		createRequestTracker.onCreateDispatched(CreateRequestTracker.Route.INVENTORY, packet.commitId());
		return ClientInventory.DispatchOutcome.SENT;
	}
	private void inventoryFailure(InventoryC2SPacket packet, InventoryFailureReason reason) {
		if (!inventoryFailures.add(new InventoryFailureKey(packet.presentationEpoch(), packet.view(), packet.commitId()))) return;
		while (inventoryFailures.size() > 64) inventoryFailures.remove(inventoryFailures.iterator().next());
		if (reason != InventoryFailureReason.TRANSPORT_FAILURE)
			ClientInventoryDiagnostics.selectionRejected(packet.requestId(), packet.commitId(), reason);
		errorSink.showTrackingFailure(reason);
	}

	private void dispatchCancel(PingInteractionAction.CancelMarker cancel) {
		if (!presentation.ready()) {
			rejectSelectorRelease(SelectorReleaseRejection.PRESENTATION_NOT_READY, cancel.markerId().value(),
				presentation.epoch(), "presentation");
			return;
		}
		MarkerId markerId = cancel.markerId();

		packetSender.sendToServer(PresentationC2SPacket.remove(presentation.epoch(), markerId));

		logger.debug("dispatch cancel: markerId={}", markerId.value());
	}

	private void dispatchTargetGone(PingInteractionAction.TargetGone gone) {
		errorSink.showLocalError(
			PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY,
			PingInteractionAction.TargetGone.TARGET_GONE_COLOR);

		logger.debug("dispatch target gone: kind={} reason={}",
			gone.context().resolvedTarget().target().kind(), gone.reason());
	}

	private void rejectSelectorRelease(SelectorReleaseRejection rejection, long token, long revision, String route) {
		logger.debug(() -> "selector release rejected: reason=" + rejection.code()
			+ " token=" + token + " revision=" + revision + " route=" + route + " sent=false");
	}
}
