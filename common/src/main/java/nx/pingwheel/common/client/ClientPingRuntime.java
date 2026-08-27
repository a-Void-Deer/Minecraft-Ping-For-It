package nx.pingwheel.common.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.marker.EntityMarkerPoint;
import nx.pingwheel.common.client.marker.MarkerOverlayState;
import nx.pingwheel.common.client.duration.ClientMarkerDisplayDuration;
import nx.pingwheel.common.client.rate.ClientCreateRateLimiter;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.chat.PingChatBuilder;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.PingTypeCatalog;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.CapturedPingContext;
import nx.pingwheel.common.interaction.CapturedRay;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.TargetSnapshot;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.MarkerCandidatePosition;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.PingInteractionStateMachine;
import nx.pingwheel.common.interaction.wheel.WheelSelection;
import nx.pingwheel.common.client.LongPressCompatibilityController;
import nx.pingwheel.common.integration.DistantHorizonsIntegration;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.integration.SableIntegration;
import nx.pingwheel.common.integration.sable.client.SableClientProvider;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.math.Raycast;
import nx.pingwheel.common.name.ClientTargetNameStore;
import nx.pingwheel.common.name.ClientTargetNameDecoder;
import nx.pingwheel.common.name.TargetNameComposer;
import nx.pingwheel.common.name.TargetNameJson;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import nx.pingwheel.common.util.DirectionalSoundInstance;
import nx.pingwheel.common.util.InputUtils;
import nx.pingwheel.common.math.RaycastPolicy;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.resource.ResourceConstants.PING_SOUND_EVENT;

/**
 * The phase-7 client runtime: the main-thread-confined composition root that
 * wires capture, interaction, dispatch, and authoritative marker state
 * together.
 *
 * <p>Ownership:
 * <ul>
 *   <li>the {@link ClientMarkerStore} (with the local fallback-expiry grace)
 *       applies authoritative S2C marker state;</li>
 *   <li>the {@link ClientTargetNameStore} applies the authoritative target
 *       display names carried by created-marker packets and is kept in exact
 *       step with the marker store (created, removed, and fallback-expired);</li>
 *   <li>the shared {@link ActiveInteraction} plus
 *       {@link PingCaptureCoordinator} freeze the key-down capture;</li>
 *   <li>the {@link PingInteractionStateMachine} applies short/long-press,
 *       wheel, cancellation, and timeout rules;</li>
 *   <li>the {@link WheelMouseCapture} releases the mouse for the open wheel
 *       and re-grabs it only when this runtime released it;</li>
 *   <li>the {@link ClientPingActionDispatcher} maps the single emitted action
 *       onto the wire or the local error sink.</li>
 * </ul>
 *
	 * <p>Press and release events arrive directly from the client-thread
	 * {@code KeyMapping} hooks. Each rendered frame advances monotonic timing,
	 * selection, pending asynchronous capture completion, and mouse capture once;
	 * the client tick retains only fallback marker expiry housekeeping. One fresh
	 * runtime is created per world join and dropped on leave (see
	 * {@code CommonClient}), so interaction and marker state can never leak across
	 * connections.
 *
 * <p>Logging only ever carries safe fields: token sequences, request/marker
 * ids, ping type ids, target kinds, candidate counts, and reasons. UUIDs,
 * positions, names, and colors are never logged.
 */
public final class ClientPingRuntime {

	/**
	 * Extra client ticks a marker may outlive its server expiry before the
	 * local fallback drops it (loss recovery when the authoritative removal
	 * packet is lost).
	 */
	public static final long FALLBACK_EXPIRY_GRACE_TICKS = 40L;

	/**
	 * Constant privacy-safe debug reason logged when an asynchronous distant
	 * hit is abandoned because the client level is gone or changed; never any
	 * ids, level references, or coordinates.
	 */
	private static final String DISTANT_HIT_ABANDONED_LEVEL_CHANGE = "distant hit abandoned: level unavailable or changed";

	private final ClientMarkerStore markerStore;
	private final ActiveInteraction activeInteraction;
	private final PingCaptureCoordinator captureCoordinator;
	private final PingInteractionStateMachine machine;
	private final ClientPingActionDispatcher dispatcher;
	private final ClientPingActionDispatcher.LocalErrorSink errorSink;
	private final PingInteractionLogger logger;
	private final WheelMouseCapture wheelMouseCapture;
	private final CreateRequestTracker createRequestTracker;
	private final ClientCreateRateLimiter createRateLimiter;
	private final InteractionTimeSource timeSource;
	private final LongPressCompatibilityController compatibilityController;
	private final SelectedLocaleTranslationKeyCache selectedLocaleTranslationKeys =
		new SelectedLocaleTranslationKeyCache();
	private static final CancellationContext EMPTY_CANCELLATION_CONTEXT = createEmptyCancellationContext();
	private ClientLevel observedLevel;
	private String observedDimension;

	/**
	 * Authoritative target display names keyed by marker id, main-thread
	 * confined like the marker store. Created exactly once per runtime and
	 * dropped with it on leave, so names can never leak across connections.
	 */
	private final ClientTargetNameStore nameStore = new ClientTargetNameStore();

	private long localTick;
	private WheelSelection wheelSelection = WheelSelection.NONE;
	/**
	 * The ray captured at the current press edge. It remains available while an
	 * asynchronous target resolution is pending and is cleared when that
	 * resolution fails or the interaction ends.
	 */
	private CapturedRay pendingRay;

	private ClientPingRuntime(
		ClientMarkerStore markerStore,
		ActiveInteraction activeInteraction,
		PingCaptureCoordinator captureCoordinator,
		PingInteractionStateMachine machine,
		ClientPingActionDispatcher dispatcher,
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		PingInteractionLogger logger,
		WheelMouseCapture wheelMouseCapture,
		CreateRequestTracker createRequestTracker,
		ClientCreateRateLimiter createRateLimiter,
		InteractionTimeSource timeSource
	) {
		this.markerStore = Objects.requireNonNull(markerStore, "markerStore");
		this.activeInteraction = Objects.requireNonNull(activeInteraction, "activeInteraction");
		this.captureCoordinator = Objects.requireNonNull(captureCoordinator, "captureCoordinator");
		this.machine = Objects.requireNonNull(machine, "machine");
		this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
		this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.wheelMouseCapture = Objects.requireNonNull(wheelMouseCapture, "wheelMouseCapture");
		this.createRequestTracker = Objects.requireNonNull(createRequestTracker, "createRequestTracker");
		this.createRateLimiter = Objects.requireNonNull(createRateLimiter, "createRateLimiter");
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
		this.compatibilityController = new LongPressCompatibilityController(
			new RuntimeInteractionPort(),
			timeSource,
			() -> ClientConfig.HANDLER.getConfig().isLongPressCompatibilityMode(),
			() -> ClientConfig.HANDLER.getConfig().getWheelHoldMillis(),
			() -> ClientConfig.HANDLER.getConfig().getLongPressCompatibilitySliceMillis(),
			logger,
			InputUtils::resetPingHold);
	}

	/**
	 * Creates a fresh runtime wired to the built-in resolver/catalog, the
	 * client-side Minecraft target validator, the default cancellation picker,
	 * and the existing global debug loggers.
	 *
	 * @param errorSink   shows local-only errors (for example the target-gone
	 *                    message); the Minecraft adapter is
	 *                    {@link MinecraftLocalErrorSink}
	 * @param packetSender sends C2S packets; wired to
	 *                     {@link nx.pingwheel.common.platform.IPlatformNetworkService}
	 *                     by the caller
	 */
	public static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender
	) {
		return create(errorSink, packetSender, ClientRateLimitPolicy.DEFAULT);
	}

	/**
	 * Creates a runtime using a supplied server-synchronized policy.
	 */
	public static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender,
		ClientRateLimitPolicy rateLimitPolicy
	) {
		return create(errorSink, packetSender, rateLimitPolicy, InteractionTimeSource.system());
	}

	/**
	 * Creates a runtime sharing the supplied monotonic clock across the state
	 * machine and client courtesy limiter.
	 */
	public static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender,
		ClientRateLimitPolicy rateLimitPolicy,
		InteractionTimeSource timeSource
	) {
		return create(
			errorSink,
			packetSender,
			rateLimitPolicy,
			timeSource,
			snapshot -> ClientMarkerDisplayDuration.durationTicks(
				ClientConfig.HANDLER.getConfig().getEffectiveMarkerDisplayDuration(),
				snapshot));
	}

	/**
	 * Creates a runtime with a caller-supplied client display-duration policy.
	 * The normal runtime path resolves the local setting per received marker;
	 * this overload remains a narrow seam for tests and alternate clients.
	 */
	public static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender,
		ClientRateLimitPolicy rateLimitPolicy,
		InteractionTimeSource timeSource,
		ClientMarkerStore.DisplayDurationPolicy displayDurationPolicy
	) {
		Objects.requireNonNull(errorSink, "errorSink");
		Objects.requireNonNull(packetSender, "packetSender");
		Objects.requireNonNull(timeSource, "timeSource");
		Objects.requireNonNull(rateLimitPolicy, "rateLimitPolicy");
		Objects.requireNonNull(displayDurationPolicy, "displayDurationPolicy");

		ActiveInteraction activeInteraction = new ActiveInteraction();
		PingInteractionLogger logger = PingInteractionLogger.global();
		CreateRequestTracker createRequestTracker = new CreateRequestTracker();
		ClientCreateRateLimiter createRateLimiter = new ClientCreateRateLimiter(timeSource, rateLimitPolicy);
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(
			DefaultTargetResolver.builtIn(TargetResolutionLogger.global()),
			activeInteraction,
			PingCaptureLogger.global());
		PingInteractionStateMachine machine = new PingInteractionStateMachine(
			coordinator,
			activeInteraction,
			timeSource,
			new MinecraftClientTargetValidator(),
			new CancelCandidatePicker(
				() -> ClientConfig.HANDLER.getConfig().getCancelHalfConeAngleDegrees()),
			logger,
			() -> ClientConfig.HANDLER.getConfig().getWheelHoldMillis(),
			() -> ClientConfig.HANDLER.getConfig().getWheelTimeoutMillis());

		return new ClientPingRuntime(
			new ClientMarkerStore(FALLBACK_EXPIRY_GRACE_TICKS, displayDurationPolicy),
			activeInteraction,
			coordinator,
			machine,
			new ClientPingActionDispatcher(
				packetSender,
				errorSink,
				logger,
				createRequestTracker,
				createRateLimiter),
			errorSink,
			logger,
			new WheelMouseCapture(logger),
			createRequestTracker,
			createRateLimiter,
			timeSource);
	}

	/**
	 * The client tick entry point below handles housekeeping only; interaction
	 * advancement is event/frame driven.
	 *
	 * <p>Does nothing while no valid level/player is available. Press and release
	 * edges are handled immediately by their event methods; rendered frames
	 * advance the machine with the current key state and queued wheel selection.
	 * A live cancellation context is built only for a wheel release that can
	 * perform cancellation. The returned action is dispatched at most once.
	 * Finally the wheel mouse capture is synced with whatever phase the machine
	 * ended in, and the marker store's fallback expiry runs against the
	 * incremented local tick.
	 *
	 * <p>The queued wheel selection is consumed exactly once per frame and the
	 * queued slot is reset to {@link WheelSelection#NONE} before the update,
	 * so a selection must be refreshed by a later GUI frame to be seen again:
	 * if rendering stops, a release commits {@code NONE} instead of a stale
	 * center/sector selection.
	 *
	 * <p>While the machine is idle there is no interaction to feed, so the
	 * live cancellation context (which walks every locally owned marker) is
	 * not built at all; only the mouse capture sync and the fallback expiry
	 * run.
	 */
	/** Handles client-tick marker expiry only; interaction input is event/frame driven. */
	public void onTick() {
		Minecraft game = Game;

		if (game == null || game.level == null || game.player == null) {
			compatibilityController.abort();
			InputUtils.resetPingHold();
			return;
		}

		observeWorldContinuity(game);

		localTick++;
		expireFallbackMarkers();
	}

	/** Handles a claimed physical press immediately on the client thread. */
	public void onPress(long eventTimeMillis) {
		Minecraft game = Game;

		if (game == null || game.level == null || game.player == null) {
			compatibilityController.abort();
			InputUtils.resetPingHold();
			return;
		}

		observeWorldContinuity(game);

		compatibilityController.onPress(eventTimeMillis);
	}

	/**
	 * Compatibility overload for non-event callers.  Production raw input uses
	 * {@link #onPress(long)} so the timestamp is sampled at the mapping callback.
	 */
	public void onPress() {
		onPress(timeSource.nowMillis());
	}

	/**
	 * Handles a claimed physical release immediately. If capture is still
	 * asynchronous, the release is remembered by the machine and the next
	 * render frame commits it once the frozen capture arrives.
	 */
	public void onRelease() {
		Minecraft game = Game;

		if (game == null || game.level == null || game.player == null) {
			compatibilityController.abort();
			InputUtils.resetPingHold();
			return;
		}

		observeWorldContinuity(game);

		compatibilityController.onRelease();
	}

	/**
	 * Advances presentation-only interaction timing for one GUI/render frame.
	 *
	 * <p>The frame path makes a capture-ready held interaction visible as an open
	 * wheel, silently closes timed-out wheels, consumes the current wheel
	 * selection, and completes a release whose asynchronous capture arrived
	 * after the release event. It is the sole per-frame interaction path.
	 * Mouse capture is synchronized on this single frame path (and immediate
	 * release/press events) so visible transitions preserve the existing
	 * release/re-grab semantics without duplicate transitions.
	 */
	public void onRenderFrame(boolean keyDown) {
		Minecraft game = Game;

		if (game == null || game.level == null || game.player == null) {
			compatibilityController.abort();
			InputUtils.resetPingHold();
			return;
		}

		observeWorldContinuity(game);

		compatibilityController.onRenderFrame(keyDown);
	}

	/**
	 * Explicitly abandons the active interaction without creating a default
	 * ping. The token is invalidated first so a late asynchronous capture cannot
	 * resurrect the interaction.
	 */
	public void abort() {
		compatibilityController.abort();
		InputUtils.resetPingHold();
	}

	/**
	 * Releases every client-side resource held by this runtime.
	 *
	 * <p>Called by {@code CommonClient} on disconnect before the runtime
	 * reference is dropped. The wheel mouse capture re-grabs the mouse here
	 * when this runtime released it and no screen is open, so a disconnect
	 * while the wheel is open can never leak a released mouse.
	 */
	public void close() {
		abort();
		InputUtils.resetPingHold();
		markerStore.clear();
		nameStore.clear();
		MarkerOverlayState.INSTANCE.clear();
		observedLevel = null;
		observedDimension = null;
	}

	/**
	 * True when the compatibility wrapper still owns a seeded or in-flight
	 * sequence even though the baseline phase may already be idle.
	 */
	public boolean hasCompatibilityState() {
		return compatibilityController.hasPendingState();
	}

	private void observeWorldContinuity(Minecraft game) {
		ClientLevel currentLevel = game.level;
		String currentDimension = currentLevel.dimension().location().toString();

		if (observedLevel != null
			&& (observedLevel != currentLevel || !Objects.equals(observedDimension, currentDimension))) {
			compatibilityController.abort();
			InputUtils.resetPingHold();
			pendingRay = null;
			wheelSelection = WheelSelection.NONE;
		}

		observedLevel = currentLevel;
		observedDimension = currentDimension;
	}

	/** The baseline press path used by the compatibility controller. */
	private Optional<PingInteractionAction> baselinePress(long rawPressTimestamp) {
		InteractionToken token = machine.pressAt(rawPressTimestamp);
		pendingRay = null;
		captureImmediately(token);
		wheelMouseCapture.sync(machine.phase(), Game);
		return Optional.empty();
	}

	/** Baseline press path with a ray captured by a rapid second physical press. */
	private Optional<PingInteractionAction> baselinePress(
		long rawPressTimestamp,
		CapturedRay pressRay
	) {
		InteractionToken token = machine.pressAt(rawPressTimestamp);
		pendingRay = null;
		captureImmediately(token, pressRay);
		wheelMouseCapture.sync(machine.phase(), Game);
		return Optional.empty();
	}

	private Optional<PingInteractionAction> baselineRelease() {
		Optional<PingInteractionAction> action = advanceInteraction(false);
		wheelMouseCapture.sync(machine.phase(), Game);
		return action;
	}

	private Optional<PingInteractionAction> baselinePresentFrame(boolean keyDown) {
		return baselinePresentFrame(keyDown, timeSource.nowMillis());
	}

	private Optional<PingInteractionAction> baselinePresentFrame(
		boolean keyDown,
		long frameTimeMillis
	) {
		machine.presentFrameAt(keyDown, frameTimeMillis);
		// Raw release edges own wheel commit/cancellation.  A stale false key
		// state on a later frame must not make the frame path walk every owned
		// marker; keep an already-open wheel logically held until its release
		// event arrives.  A pressed interaction still uses false here so an
		// asynchronous capture that completes after release can commit its one
		// short action on this frame.
		boolean actionKeyDown = keyDown || machine.phase() == PingInteractionPhase.WHEEL_OPEN;
		Optional<PingInteractionAction> action = advanceInteraction(actionKeyDown, frameTimeMillis);

		// A frame-side timeout/transition must not leave a prior GUI selection
		// queued for the next frame. Selection is meaningful only while the wheel
		// remains open; it is preserved between open frames by the runtime's normal
		// wheel input path.
		if (machine.phase() != PingInteractionPhase.WHEEL_OPEN) {
			wheelSelection = WheelSelection.NONE;
		}

		if (machine.phase() == PingInteractionPhase.IDLE) {
			pendingRay = null;
		}

		wheelMouseCapture.sync(machine.phase(), Game);
		return action;
	}

	private void baselineAbort() {
		machine.abort();
		pendingRay = null;
		wheelSelection = WheelSelection.NONE;
		wheelMouseCapture.close(Game);
	}

	private final class RuntimeInteractionPort implements LongPressCompatibilityController.InteractionPort {

		@Override
		public Optional<PingInteractionAction> pressAt(long rawPressTimestamp) {
			return baselinePress(rawPressTimestamp);
		}

		@Override
		public Optional<PingInteractionAction> pressAt(long rawPressTimestamp, CapturedRay pressRay) {
			return baselinePress(rawPressTimestamp, pressRay);
		}

		@Override
		public Optional<CapturedRay> capturePressRay() {
			return ClientPingRuntime.this.capturePressRay();
		}

		@Override
		public Optional<PingInteractionAction> release() {
			return baselineRelease();
		}

		@Override
		public Optional<PingInteractionAction> presentFrame(boolean keyDown) {
			return baselinePresentFrame(keyDown);
		}

		@Override
		public Optional<PingInteractionAction> presentFrame(boolean keyDown, long frameTimeMillis) {
			return baselinePresentFrame(keyDown, frameTimeMillis);
		}

		@Override
		public void abort() {
			baselineAbort();
		}

		@Override
		public PingInteractionPhase phase() {
			return machine.phase();
		}
	}

	private Optional<PingInteractionAction> advanceInteraction(boolean keyDown) {
		return advanceInteraction(keyDown, null);
	}

	private Optional<PingInteractionAction> advanceInteraction(boolean keyDown, Long observedTimeMillis) {
		if (machine.phase() == PingInteractionPhase.IDLE) {
			wheelSelection = WheelSelection.NONE;
			pendingRay = null;
			return Optional.empty();
		}

		WheelSelection consumedSelection = wheelSelection;
		wheelSelection = WheelSelection.NONE;
		// Cancellation is resolved only when the open wheel is released.  While
		// pressed, including every hover/selection frame, the machine only needs
		// O(1) state advancement and the empty sentinel below.
		boolean cancellationMayBeNeeded = machine.phase() == PingInteractionPhase.WHEEL_OPEN && !keyDown;
		var cancellationContext = cancellationMayBeNeeded
			? buildCancellationContext()
			: emptyCancellationContext();
		Optional<PingInteractionAction> action = observedTimeMillis == null
			? machine.update(keyDown, consumedSelection, cancellationContext)
			: machine.updateAt(keyDown, consumedSelection, cancellationContext, observedTimeMillis);

		if (action.isPresent()) {
			dispatcher.dispatch(action.get());
		}

		if (machine.phase() == PingInteractionPhase.IDLE) {
			pendingRay = null;
			wheelSelection = WheelSelection.NONE;
		}

		return action;
	}

	/**
	 * Supplies a valid, allocation-free context for machine paths that cannot
	 * cancel.  The real context (and its owned-marker scan) is built only for a
	 * wheel release or center selection.
	 */
	private static CancellationContext emptyCancellationContext() {
		return EMPTY_CANCELLATION_CONTEXT;
	}

	private static CancellationContext createEmptyCancellationContext() {
		CapturedRay ray = CapturedRay.defaultRay();
		return new CancellationContext(
			new UUID(0L, 0L),
			"minecraft:overworld",
			ray.origin(),
			ray.direction(),
			List.of());
	}

	/**
	 * Captures the target for {@code token} on the game thread using the
	 * current camera. Never re-rays on release or wheel movement: the
	 * interaction freezes whatever this capture produced.
	 *
	 * <p>A miss still captures a pure location target immediately. Without
	 * Distant Horizons there is no other capture path, so the location
	 * snapshot is completed synchronously instead of leaving the interaction
	 * waiting forever. With Distant Horizons the distant trace stays
	 * asynchronous: a distant hit upgrades the miss to a distant block hit, a
	 * no-hit or failed trace falls back to the vanilla miss as a location,
	 * and a scheduling failure completes the vanilla miss synchronously, so
	 * none of those outcomes strand the interaction. If the level is gone or
	 * changed by the time the asynchronous result lands, the interaction is
	 * abandoned for its exact token (see {@link #completeDistantHit}) so the
	 * state machine resets to idle instead of waiting forever.
	 */
	private void captureImmediately(InteractionToken token) {
		CapturedRay pressRay = capturePressRay().orElse(null);
		if (pressRay == null) {
			activeInteraction.invalidate(token);
			return;
		}

		captureImmediately(token, pressRay);
	}

	/** Captures only the immutable press ray; no target ray cast is performed. */
	private Optional<CapturedRay> capturePressRay() {
		Minecraft game = Game;

		if (game == null || game.cameraEntity == null || game.level == null) {
			return Optional.empty();
		}

		var cameraEntity = game.cameraEntity;
		var rayOrigin = cameraEntity.getEyePosition(1.0f);
		var cameraDirection = cameraEntity.getViewVector(1.0f);

		try {
			return Optional.of(new CapturedRay(
				new WorldVector(rayOrigin.x, rayOrigin.y, rayOrigin.z),
				new WorldVector(cameraDirection.x, cameraDirection.y, cameraDirection.z)));
		} catch (IllegalArgumentException invalidRay) {
			return Optional.empty();
		}
	}

	private void captureImmediately(InteractionToken token, CapturedRay pressRay) {
		Minecraft game = Game;

		if (game == null || game.cameraEntity == null || game.level == null) {
			activeInteraction.invalidate(token);
			return;
		}

		ClientLevel level = game.level;
		var cameraEntity = game.cameraEntity;
		var rayOrigin = new Vec3(
			pressRay.origin().x(),
			pressRay.origin().y(),
			pressRay.origin().z());
		var cameraDirection = new Vec3(
			pressRay.direction().x(),
			pressRay.direction().y(),
			pressRay.direction().z());

		pendingRay = pressRay;
		ClientConfig config = ClientConfig.HANDLER.getConfig();
		var distance = Math.min(
			config.getRaycastDistance(),
			config.getPingDistance());

		RaycastPolicy raycastPolicy = RaycastPolicy.from(
			config.isPassThroughTransparentBlocks(),
			config.isMarkBlacklistedTargets(),
			config.isMarkFluids());
		var hitResult = Raycast.traceDirectional(
			rayOrigin, cameraDirection, distance, raycastPolicy);

		if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
			HitResult missHit = hitResult;

			if (missHit == null) {
				var missPoint = rayOrigin.add(cameraDirection.scale(distance));
				missHit = BlockHitResult.miss(missPoint, Direction.UP, BlockPos.containing(missPoint));
			}

			if (ModContext.HasDistantHorizons) {
				// The Distant Horizons completion runs on its completion thread:
				// it only carries the client and level captured at press time
				// and schedules onto the client's main thread, never reading
				// CommonClient.Game from the pool thread. When the trace cannot
				// be scheduled — a LinkageError disables the integration, any
				// other scheduling rejection only debug logs — the vanilla miss
				// is completed synchronously below, so the ping can never
				// strand.
				//
				// An extreme LinkageError thrown while linking the trace call
				// itself must never crash the tick: the integration is switched
				// off for the rest of the session and the vanilla miss is
				// completed synchronously. Exact-one completion is preserved
				// because the coordinator rejects a duplicate completion for
				// the same token.
				final HitResult fallbackMiss = missHit;
				boolean scheduled;

				try {
					scheduled = DistantHorizonsIntegration.traceDistantAsync(
						rayOrigin, cameraDirection,
						distantHit -> completeDistantHit(game, level, token, fallbackMiss, pressRay, distantHit));
				} catch (LinkageError error) {
					ModContext.HasDistantHorizons = false;
					DistantHorizonsIntegration.logUnguardedLinkFailure(error);
					scheduled = false;
				}

				if (!scheduled) {
					completeCapture(token, MinecraftTargetSnapshotFactory.from(level, missHit), pressRay);
				}
			} else {
				completeCapture(token, MinecraftTargetSnapshotFactory.from(level, missHit), pressRay);
			}

			return;
		}

		boolean sableCaptureAttempted = ModContext.HasSable && hitResult.getType() == HitResult.Type.BLOCK;

		if (sableCaptureAttempted) {
			Optional<TargetSnapshot> sableCandidate = SableClientProvider.capture(
				level,
				(BlockHitResult) hitResult,
				rayOrigin,
				rayOrigin.add(cameraDirection.scale(distance)));

			if (sableCandidate.isPresent()) {
				completeCapture(token, sableCandidate.get(), pressRay);
				return;
			}

			var projected = SableIntegration.projectOutOfSubLevel(game.level, hitResult.getLocation());

			// If the provider cannot positively resolve the local block, preserve
			// the pre-existing projected-location fallback.
			if (projected.isPresent()) {
				var projectedPos = projected.get();
				SableClientProvider.logCaptureFallback(
					"PROJECTED_LOCATION", "external-capture-failed", "projection",
					"hit_location", hitResult.getLocation(),
					"projected_position", projectedPos);
				completeCapture(token, TargetSnapshotFactory.location(
					game.level.dimension().location().toString(),
					projectedPos.x, projectedPos.y, projectedPos.z), pressRay);
				return;
			}
		}

		if (sableCaptureAttempted) {
			SableClientProvider.logCaptureFallback(
				"VANILLA_TARGET_FACTORY", "external-and-projected-capture-failed", "vanilla-target-factory",
				"hit_location", hitResult.getLocation(),
				"block_pos", hitResult instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null);
		}

		completeCapture(token, MinecraftTargetSnapshotFactory.from(game.level, hitResult), pressRay);
	}

	/**
	 * Applies an asynchronous Distant Horizons result on the captured client's
	 * main thread: a distant hit upgrades the miss, otherwise the original
	 * vanilla miss is completed as a pure location target.
	 *
	 * <p>This callback runs on the Distant Horizons completion thread and must
	 * never touch {@link nx.pingwheel.common.CommonClient.Game}: the
	 * {@link Minecraft} client and the {@link ClientLevel} captured at press
	 * time are the only live references it carries. On the client main thread a
	 * stale (superseded) token is ignored. When the level is gone or no longer
	 * the level captured at press time, the interaction is abandoned for
	 * exactly that token (the captured level is never snapshotted against) and
	 * only a constant privacy-safe debug reason is logged: the cleared current
	 * ownership drives the state machine's superseded path back to idle on its
	 * next update, so a level change can never strand the interaction.
	 */
	private void completeDistantHit(
		Minecraft game,
		ClientLevel levelAtPress,
		InteractionToken token,
		HitResult vanillaMiss,
		CapturedRay pressRay,
		Optional<BlockHitResult> distantHit
	) {
		game.execute(() -> {
			if (!activeInteraction.isCurrent(token)) {
				return;
			}

			if (game.level == null || game.level != levelAtPress) {
				activeInteraction.invalidate(token);
				pendingRay = null;
				logger.debug(DISTANT_HIT_ABANDONED_LEVEL_CHANGE);
				return;
			}

			HitResult appliedHit = distantHit.map(hit -> (HitResult) hit).orElse(vanillaMiss);

			completeCapture(token, MinecraftTargetSnapshotFactory.from(levelAtPress, appliedHit), pressRay);
		});
	}

	/**
	 * Completes a capture with its immutable press-time ray. A failed current
	 * completion clears only this interaction's pending ray; a stale callback or
	 * a duplicate completion can never clear a newer/accepted capture.
	 */
	private void completeCapture(InteractionToken token, TargetSnapshot snapshot, CapturedRay pressRay) {
		Optional<CapturedPingContext> completed = captureCoordinator.complete(token, snapshot, pressRay);

		if (completed.isEmpty()
			&& activeInteraction.isCurrent(token)
			&& activeInteraction.currentContext().isEmpty()) {
			pendingRay = null;
		}
	}

	/**
	 * Builds the cancellation context from live local state: the local owner
	 * UUID, the current dimension, and live marker candidate positions. The
	 * origin and direction come from the immutable press-time ray, never from
	 * the current/release camera, while candidates still include every marker
	 * owned by the local player in the current dimension.
	 *
	 * <p>A candidate's position is the live entity position when the marker's
	 * entity target is still resolvable in the same dimension. If it is absent,
	 * the last matching rendered overlay position is used; the authoritative
	 * marker anchor is the fallback for an absent or never-rendered view.
	 */
	private CancellationContext buildCancellationContext() {
		Minecraft game = Game;
		var player = game.player;
		var level = game.level;

		UUID ownerId = player.getUUID();
		String dimensionId = level.dimension().location().toString();
		CapturedRay frozenRay = pendingRay;

		if (frozenRay == null && machine.phase() == PingInteractionPhase.WHEEL_OPEN) {
			frozenRay = activeInteraction.currentContext()
				.map(CapturedPingContext::ray)
				.orElse(null);
		}

		// A wheel cannot open without a completed capture. This compatibility value
		// is used only while a capture is pending/invalid and can never drive a
		// cancellation action; importantly, it does not read the later camera.
		if (frozenRay == null) {
			frozenRay = CapturedRay.defaultRay();
		}

		List<CancelMarkerCandidate> candidates = markerStore
			.markersOwnedInDimension(dimensionId, ownerId)
			.stream()
			.map(marker -> new CancelMarkerCandidate(
				marker.id(),
				ownerId,
				dimensionId,
				candidatePosition(marker, dimensionId)))
			.toList();

		return new CancellationContext(
			ownerId,
			dimensionId,
			frozenRay.origin(),
			frozenRay.direction(),
			candidates);
	}

	private WorldVector candidatePosition(ClientMarker marker, String currentDimension) {
		Target target = marker.target();
		var anchor = marker.anchor();
		WorldVector anchorPosition = new WorldVector(anchor.x(), anchor.y(), anchor.z());

		if (target instanceof Target.EntityTarget entityTarget) {
			Entity entity = GameContext.getEntity(entityTarget.locator());

			// GameContext only searches the current level, so a found entity is
			// already in the marker's dimension.
			if (entity != null && !entity.isRemoved()) {
				// Same top-center geometry the marker outline renders, using the
				// current-tick position (partialTick 1.0F); the renderer may
				// additionally sub-tick interpolate between positions.
				var topCenter = EntityMarkerPoint.forLiveEntity(entity, 1.0F);
				return new WorldVector(topCenter.x, topCenter.y, topCenter.z);
			}
		} else if (target instanceof Target.ExternalBlockTarget external
			&& Game != null && Game.level != null
			&& currentDimension.equals(Game.level.dimension().location().toString())) {
			Optional<Vec3> livePosition = SableClientProvider.resolvePosition(Game.level, external, 1.0F);

			if (livePosition.isPresent()) {
				Vec3 position = livePosition.get();
				return new WorldVector(position.x, position.y, position.z);
			}

			// A provider miss is temporary presentation unavailability, not a
			// local invalidation. Use the authoritative server fallback directly
			// rather than retaining a stale sub-level render position.
			return anchorPosition;
		}

		return MarkerCandidatePosition.resolve(
			anchorPosition,
			MarkerOverlayState.INSTANCE.lookupPresentationPosition(
				marker.id(), target, currentDimension));
	}

	private void expireFallbackMarkers() {
		List<ClientMarker> expired = markerStore.expireFallback(localTick);

		if (expired.isEmpty()) {
			return;
		}

		// A fallback transition may only make a marker stale. Names remain until
		// the independent display deadline causes the client record to be
		// removed, so cleanup follows final record removal rather than sync loss.
		for (ClientMarker marker : expired) {
			nameStore.onRemoved(marker.id());
		}

		logger.debug("marker client lifetime ended: count={} ids={}",
			expired.size(),
			expired.stream().map(marker -> Long.toString(marker.id().value())).toList());
	}

	/**
	 * Applies an authoritative created-marker packet on the main thread as of
	 * the runtime's current local tick.
	 *
	 * <p>The marker snapshot and its authoritative target display name are
	 * applied to their stores back to back on the main thread, so neither can
	 * be observed without the other. The existing directional ping sound plays
	 * exactly once per newly seen marker id whose target lives in the current
	 * dimension; a retransmission or same-id replacement of an already known
	 * marker never replays it.
	 */
	public void applyCreated(MarkerCreatedS2CPacket packet) {
		Objects.requireNonNull(packet, "packet");

		if (packet.isCorrupt()) {
			return;
		}

		MarkerSnapshot snapshot = Objects.requireNonNull(packet.snapshot(), "snapshot");
		TargetNameJson targetName = Objects.requireNonNull(packet.targetName(), "targetName");

		boolean newlySeen = isNewMarkerReceipt(markerStore, snapshot.id());

		List<ClientMarker> superseded = markerStore.onCreated(snapshot, localTick);
		for (ClientMarker marker : superseded) {
			nameStore.onRemoved(marker.id());
		}
		nameStore.onCreated(snapshot.id(), targetName);

		if (newlySeen) {
			playCreatedSoundOnce(snapshot);
			sendCreatedChat(packet, snapshot, targetName);
		}

		logger.debug("marker created applied: markerId={} kind={} targetType={} pingType={}",
			snapshot.id().value(),
			snapshot.target().kind(),
			snapshot.targetTypeId(),
			snapshot.pingTypeId());
	}

	/**
	 * Plays the existing directional ping sound at the marker anchor with the
	 * configured volume, but only for a marker in the current dimension.
	 */
	private void playCreatedSoundOnce(MarkerSnapshot snapshot) {
		Minecraft game = Game;

		if (game == null || game.level == null || game.player == null) {
			return;
		}

		if (!snapshot.target().dimensionId().equals(game.level.dimension().location().toString())) {
			return;
		}

		MarkerAnchor anchor = snapshot.anchor();

		game.getSoundManager().play(new DirectionalSoundInstance(
			PING_SOUND_EVENT,
			SoundSource.MASTER,
			ClientConfig.HANDLER.getConfig().getPingVolume() / 100f,
			1f,
			new Vec3(anchor.x(), anchor.y(), anchor.z())));
	}

	/**
	 * Displays the accepted marker chat line only for its first local receipt.
	 * The target dimension is intentionally not consulted here: sound playback
	 * keeps its existing current-dimension eligibility, while chat is relevant
	 * to every recipient regardless of where the target is located.
	 */
	private void sendCreatedChat(
		MarkerCreatedS2CPacket packet, MarkerSnapshot snapshot, TargetNameJson targetName
	) {
		Minecraft game = Game;

		if (game == null || game.player == null) {
			return;
		}

		try {
			String pingTypeId = snapshot.pingTypeId();

			if (pingTypeId == null) {
				return;
			}

			var pingType = PingTypeCatalog.builtIn().findById(pingTypeId);

			if (pingType.isEmpty()) {
				return;
			}

			Component resolvedTargetName = game.level == null
				? TargetNameComposer.unknown()
				: ClientTargetNameDecoder.decode(snapshot.id(), targetName, game.level.registryAccess());
			String template;

			try {
				Language language = Language.getInstance();
				String templateKey = PingChatBuilder.selectTemplateKey(
					pingType.get(),
					key -> selectedLocaleTranslationKeys.contains(
						game.getLanguageManager().getSelected(),
						language,
						game.getResourceManager(),
						key));
				template = language.getOrDefault(templateKey);
			} catch (RuntimeException ignored) {
				template = null;
			}

			Component message = PingChatBuilder.build(
				template, packet.ownerName(), pingType.get(), resolvedTargetName);
			game.player.displayClientMessage(message, false);
		} catch (RuntimeException exception) {
			logger.debugException("marker chat display failed: malformed server payload", exception);
		}
	}

	/**
	 * Applies an authoritative marker removal on the main thread. Hard removals
	 * and final display-lifetime removals clean their target names; an EXPIRED
	 * marker that becomes stale retains its name with the retained record.
	 */
	public void applyRemoved(MarkerId markerId, MarkerRemovalReason reason) {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(reason, "reason");

		boolean knownBeforeRemoval = markerStore.marker(markerId).isPresent();
		List<ClientMarker> removed = markerStore.onRemoved(markerId, reason, localTick);
		boolean markerWasRemoved = removed.stream().anyMatch(marker -> marker.id().equals(markerId));

		if (!knownBeforeRemoval && !markerWasRemoved) {
			nameStore.onRemoved(markerId);
		}

		for (ClientMarker removedMarker : removed) {
			nameStore.onRemoved(removedMarker.id());
		}

		logger.debug("marker removed applied: markerId={} reason={}", markerId.value(), reason);
	}

	/**
	 * Applies an authoritative same-target winner change on the main thread.
	 */
	public void applyWinnerChanged(TargetKey targetKey, Optional<MarkerId> winnerId) {
		Objects.requireNonNull(targetKey, "targetKey");
		Objects.requireNonNull(winnerId, "winnerId");

		List<ClientMarker> superseded = markerStore.onWinnerChanged(targetKey, winnerId);
		for (ClientMarker marker : superseded) {
			nameStore.onRemoved(marker.id());
		}

		logger.debug("marker winner applied: kind={} winner={}",
			targetKindOf(targetKey),
			winnerId.map(id -> Long.toString(id.value())).orElse("none"));
	}

	/**
	 * Handles an authoritative request rejection.
	 *
	 * <p>A {@code TARGET_GONE} create rejection surfaces the exact local
	 * target-gone error only when its request id is the latest dispatched
	 * create request; a rejection for an older (superseded) request is stale
	 * and only debug logged. Every other reason is debug logged only.
	 */
	public void handleRejected(long requestId, MarkerRequestKind requestKind, MarkerRejectReason reason) {
		Objects.requireNonNull(requestKind, "requestKind");
		Objects.requireNonNull(reason, "reason");

		if (reason == MarkerRejectReason.TARGET_GONE) {
			if (createRequestTracker.isLatest(requestId)) {
				errorSink.showLocalError(
					PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE,
					PingInteractionAction.TargetGone.TARGET_GONE_COLOR);
				logger.debug("marker rejected target gone: requestId={} requestKind={}",
					requestId, requestKind);
			} else {
				logger.debug("marker rejected target gone stale: requestId={} requestKind={}",
					requestId, requestKind);
			}

			return;
		}

		logger.debug("marker rejected: requestId={} requestKind={} reason={}",
			requestId, requestKind, reason);
	}

	/**
	 * The authoritative client marker store.
	 */
	public ClientMarkerStore store() {
		return markerStore;
	}

	/**
	 * The authoritative client target-name store, keyed by marker id. Exposed
	 * for the target-name HUD rendering added in a later slice; no parsing or
	 * rendering happens in this runtime.
	 */
	public ClientTargetNameStore nameStore() {
		return nameStore;
	}

	/**
	 * Applies a newly synchronized policy while preserving this runtime's
	 * limiter history.
	 */
	public void applyRateLimitPolicy(ClientRateLimitPolicy policy) {
		createRateLimiter.applyPolicy(policy);
	}

	/**
	 * The current interaction lifecycle phase.
	 */
	public PingInteractionPhase phase() {
		return machine.phase();
	}

	/**
	 * The frozen, ordered ping type list for the open wheel; empty when the
	 * wheel is not open.
	 */
	public List<PingType> wheelPingTypes() {
		return machine.wheelPingTypes();
	}

	/**
	 * Returns the frozen capture and wheel choices only while the wheel is
	 * visibly open.  GUI code must use this read-only snapshot rather than
	 * performing a new target selection; no snapshot is exposed during a press,
	 * release, timeout, or idle phase.
	 */
	public Optional<WheelPresentationSnapshot> wheelPresentation() {
		if (machine.phase() != PingInteractionPhase.WHEEL_OPEN) {
			return Optional.empty();
		}

		Optional<CapturedPingContext> context = activeInteraction.currentContext();
		Optional<InteractionToken> currentToken = machine.currentToken();

		if (context.isEmpty()
			|| currentToken.isEmpty()
			|| context.get().token() != currentToken.get()) {
			return Optional.empty();
		}

		return WheelPresentationSnapshot.visible(
			PingInteractionPhase.WHEEL_OPEN,
			context,
			machine.wheelPingTypes());
	}

	/**
	 * The machine's normalized wheel selection (never null).
	 */
	public WheelSelection selection() {
		return machine.selection();
	}

	/**
	 * The wheel selection currently queued as input for the next tick
	 * (initially {@link WheelSelection#NONE}).
	 */
	public WheelSelection wheelSelection() {
		return wheelSelection;
	}

	/**
	 * Replaces the wheel selection consumed by the next
	 * {@link #onRenderFrame(boolean)} call.
	 */
	public void setWheelSelection(WheelSelection selection) {
		this.wheelSelection = Objects.requireNonNull(selection, "selection");
	}

	/**
	 * The effect gate shared by created-marker handling: a same-id upsert is a
	 * payload refresh, not a new sound/chat receipt.
	 */
	static boolean isNewMarkerReceipt(ClientMarkerStore store, MarkerId markerId) {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(markerId, "markerId");
		return store.marker(markerId).isEmpty();
	}

	private static TargetKind targetKindOf(TargetKey targetKey) {
		return switch (targetKey) {
			case TargetKey.EntityKey ignored -> TargetKind.ENTITY;
			case TargetKey.BlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.ExternalBlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.LocationKey ignored -> TargetKind.LOCATION;
		};
	}
}
