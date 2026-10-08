package nx.pingwheel.common.client;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
import nx.pingwheel.common.presentation.client.ClientPresentation;
import nx.pingwheel.common.presentation.PresentationValue;
import nx.pingwheel.common.presentation.PresentationPropertyIntent;
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
import nx.pingwheel.common.interaction.state.SelectorReleaseProposal;
import nx.pingwheel.common.client.spatial.NativeSelectorInput;
import nx.pingwheel.common.client.spatial.SelectorIntent;
import nx.pingwheel.common.client.spatial.SpatialController;
import nx.pingwheel.common.client.spatial.SpatialSelectorSession;
import nx.pingwheel.common.client.spatial.PreciseCaptureRefresh;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.interaction.candidate.FrozenCandidateAcquisition;
import nx.pingwheel.common.interaction.candidate.CandidateWorkLimits;
import nx.pingwheel.common.interaction.candidate.Candidate;
import nx.pingwheel.common.interaction.candidate.CaptureEquivalenceKey;
import nx.pingwheel.common.interaction.candidate.PreciseSlot;
import nx.pingwheel.common.interaction.candidate.PreciseTargetType;
import nx.pingwheel.common.presentation.inventory.client.ClientInventory;
import nx.pingwheel.common.presentation.preview.ClientPresentationPreview;
import nx.pingwheel.common.presentation.preview.client.MinecraftPreviewReadContext;
import nx.pingwheel.common.presentation.preview.client.PreviewLocalReaders;
import nx.pingwheel.common.presentation.PresentationPropertyPingTypes;
import nx.pingwheel.common.network.PresentationPreviewS2CPacket;
import nx.pingwheel.common.render.SpatialInventoryView;
import nx.pingwheel.common.render.SpatialOverlayRenderer;
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
import nx.pingwheel.common.network.PresentationS2CPacket;
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
 *       wheel, and cancellation rules;</li>
 *   <li>the {@link WheelMouseCapture} releases the mouse for the open wheel
 *       and re-grabs it only when this runtime released it;</li>
 *   <li>the {@link ClientPingActionDispatcher} maps the single emitted action
 *       onto the wire or the local error sink.</li>
 * </ul>
 *
	 * <p>Press and release events arrive directly from the client-thread
	 * {@code KeyMapping} hooks. Each rendered frame advances monotonic timing,
	 * selection, pending asynchronous capture completion, and mouse capture once;
	 * the client tick handles marker expiry and the independent Precise refresh. One fresh
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
	private final ClientPresentation presentation;
	private final PresentationReceiptFeedback presentationReceiptFeedback;

	/** Narrow receipt-effect port: a metadata upsert must never invoke either callback. */
	interface PresentationReceiptFeedback {
		void play(MarkerSnapshot snapshot);
		void chat(String ownerName, MarkerSnapshot snapshot, Component targetName);
	}
	private final InteractionTimeSource timeSource;
	private final LongPressCompatibilityController compatibilityController;
	private final SelectedLocaleTranslationKeyCache selectedLocaleTranslationKeys =
		new SelectedLocaleTranslationKeyCache();
	private static final CancellationContext EMPTY_CANCELLATION_CONTEXT = createEmptyCancellationContext();
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
	private InteractionAccess interactionAccess;
	private ClientPresentationPreview contentPreview;
	private NativeSelectorContent selectorContent;
	private SpatialSelectorSession<ClientInventory.PreviewEntryReference> selector;
	private NativeSelectorInput selectorInput;
	private final SpatialOverlayRenderer.Session selectorPaint = new SpatialOverlayRenderer.Session();
	private final Map<String, CapturedPingContext> selectorContexts = new LinkedHashMap<>();
	private SpatialSelectorSettings.Snapshot selectorSettings;
	private RaycastPolicy selectorPolicy;
	private double selectorNativeDistance, selectorPingDistance;
	private PreciseCaptureRefresh preciseRefresh;
	private SpatialOverlayRenderer.Style selectorStyle;
	private final SelectorToggleLabels selectorToggleLabels = new SelectorToggleLabels(
		() -> raycastPolicy(ClientConfig.HANDLER.getConfig()));
	private InteractionToken baselineToken;
	private boolean baselineHeld;
	private boolean baselineMenuOpened;
	private boolean abortingScreenTransition;
	private Object observedWorldIdentity;
	private Object observedScreenIdentity;
	private List<nx.pingwheel.common.render.InventoryTrackingRenderer.MarkerLines> trackingPaint = List.of();

	/** Production-used Minecraft boundary; recordings exercise the same runtime/coordinator/selector path. */
	interface InteractionAccess {
		record Lifecycle(Object level, String dimension, Object screen, boolean player, boolean focused, boolean overlay) {}
		Lifecycle lifecycle();
		Optional<CapturedRay> capturePressRay();
		void capture(InteractionToken token, CapturedRay ray, CaptureCompletion completion);
		default PreciseCaptureRefresh.Scan capturePrecise(PreciseCaptureRefresh.Request request) {
			return PreciseCaptureRefresh.Scan.incomplete();
		}
		default boolean capturePreciseDistant(PreciseCaptureRefresh.Request request,
			java.util.function.Consumer<PreciseCaptureRefresh.Outcome> completion) { return false; }
		CancellationContext cancellation(CapturedRay ray);
		void syncMouse(PingInteractionPhase phase);
		void disposeMouse(boolean screenTransition);
		Optional<NativeSelectorInput.Frame> inputFrame();
		SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style);
		void resetInput();
		void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis);
	}
	@FunctionalInterface
	interface CaptureCompletion {
		void complete(TargetSnapshot snapshot, Optional<FrozenCandidateAcquisition> candidates);
	}

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
		InteractionTimeSource timeSource,
		ClientPresentation presentation,
		PresentationReceiptFeedback presentationReceiptFeedback
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
		this.presentation = presentation;
		this.presentationReceiptFeedback = presentationReceiptFeedback == null ? new PresentationReceiptFeedback() {
			@Override public void play(MarkerSnapshot snapshot) { playCreatedSoundOnce(snapshot); }
			@Override public void chat(String ownerName, MarkerSnapshot snapshot, Component targetName) {
				sendCreatedChat(ownerName, snapshot, targetName);
			}
		} : presentationReceiptFeedback;
		this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
		this.interactionAccess = new MinecraftInteractionAccess();
		this.compatibilityController = new LongPressCompatibilityController(
			new RuntimeInteractionPort(),
			timeSource,
			() -> ClientConfig.HANDLER.getConfig().isLongPressCompatibilityMode(),
			() -> ClientConfig.HANDLER.getConfig().getWheelHoldMillis(),
			() -> ClientConfig.HANDLER.getConfig().getLongPressCompatibilitySliceMillis(),
			logger,
			() -> interactionAccess.resetInput());
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
		return create(errorSink, packetSender, rateLimitPolicy, timeSource, false);
	}

	/** The production connection uses the versioned presentation transport exclusively. */
	public static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender,
		ClientRateLimitPolicy rateLimitPolicy,
		InteractionTimeSource timeSource,
		boolean negotiatePresentation
	) {
		return create(
			errorSink,
			packetSender,
			rateLimitPolicy,
			timeSource,
			snapshot -> ClientMarkerDisplayDuration.durationTicks(
				ClientConfig.HANDLER.getConfig().getEffectiveMarkerDisplayDuration(),
				snapshot),
			negotiatePresentation);
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
		return create(errorSink, packetSender, rateLimitPolicy, timeSource, displayDurationPolicy, false);
	}

	private static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender,
		ClientRateLimitPolicy rateLimitPolicy,
		InteractionTimeSource timeSource,
		ClientMarkerStore.DisplayDurationPolicy displayDurationPolicy,
		boolean negotiatePresentation
	) {
		return create(errorSink, packetSender, rateLimitPolicy, timeSource, displayDurationPolicy,
			negotiatePresentation, null);
	}

	static ClientPingRuntime create(
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		ClientPingActionDispatcher.PacketSender packetSender,
		ClientRateLimitPolicy rateLimitPolicy,
		InteractionTimeSource timeSource,
		ClientMarkerStore.DisplayDurationPolicy displayDurationPolicy,
		boolean negotiatePresentation,
		PresentationReceiptFeedback feedback
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
			() -> ClientConfig.HANDLER.getConfig().getWheelHoldMillis());
		ClientPresentation presentation = negotiatePresentation
			? new ClientPresentation(packetSender::sendToServer)
			: null;

		ClientPingRuntime runtime = new ClientPingRuntime(
			new ClientMarkerStore(FALLBACK_EXPIRY_GRACE_TICKS, displayDurationPolicy),
			activeInteraction,
			coordinator,
			machine,
			new ClientPingActionDispatcher(
				packetSender,
				errorSink,
				logger,
				createRequestTracker,
				createRateLimiter,
				presentation),
			errorSink,
			logger,
			new WheelMouseCapture(logger),
			createRequestTracker,
			createRateLimiter,
			timeSource,
			presentation,
			feedback);
		if (presentation != null) runtime.contentPreview = new ClientPresentationPreview(presentation::previewAccess,
			() -> Game == null || Game.level == null ? null : new MinecraftPreviewReadContext(Game.level, runtime.localTick),
			PreviewLocalReaders.create(), (target, type) -> Optional.empty(), packetSender::sendToServer);
		return runtime;
	}

	/** Headless construction keeps all interaction behavior in this runtime; only world/input ports differ. */
	static ClientPingRuntime createForInteraction(ClientPingActionDispatcher.LocalErrorSink errors,
		ClientPingActionDispatcher.PacketSender sender, ClientRateLimitPolicy policy, InteractionTimeSource clock,
		nx.pingwheel.common.interaction.state.TargetValidator validator, InteractionAccess access,
		ClientPresentation presentation, ClientInventory inventory, ClientPresentationPreview preview) {
		var active = new ActiveInteraction();
		var coordinator = new PingCaptureCoordinator(DefaultTargetResolver.builtIn(TargetResolutionLogger.noop()),
			active, PingCaptureLogger.noop());
		var logger = PingInteractionLogger.noop();
		var limiter = new ClientCreateRateLimiter(clock, policy);
		var tracker = new CreateRequestTracker();
		var machine = new PingInteractionStateMachine(coordinator, active, clock, validator, new CancelCandidatePicker(),
			logger, () -> ClientConfig.HANDLER.getConfig().getWheelHoldMillis());
		var runtime = new ClientPingRuntime(new ClientMarkerStore(FALLBACK_EXPIRY_GRACE_TICKS), active, coordinator, machine,
			new ClientPingActionDispatcher(sender, errors, logger, tracker, limiter, presentation), errors, logger,
			new WheelMouseCapture(logger), tracker, limiter, clock, presentation, new PresentationReceiptFeedback() {
				public void play(MarkerSnapshot snapshot) {}
				public void chat(String owner, MarkerSnapshot snapshot, Component name) {}
			});
		runtime.interactionAccess = Objects.requireNonNull(access);
		runtime.contentPreview = preview;
		runtime.inventory(inventory);
		return runtime;
	}

	/** Marker expiry plus periodic Precise capture; ordinary interaction remains event/frame driven. */
	public void onTick() {
		observeInteractionLifecycle();
		var lifecycle = interactionAccess.lifecycle();
		if (lifecycle.level() == null || !lifecycle.player()) return;

		if (presentation != null) {
			presentation.tick(Game != null && Game.getConnection() != null);
			syncPresentationNames();
		}
		localTick++;
		synchronizePreciseBranch();
		if (preciseRefresh != null) preciseRefresh.tick(localTick);
		expireFallbackMarkers();
	}

	/** Handles a claimed physical press immediately on the client thread. */
	public void onPress(long eventTimeMillis) {
		if (!observeInteractionLifecycle()) return;
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
		if (!observeInteractionLifecycle()) return;
		compatibilityController.onRelease();
	}

	/**
	 * Advances presentation-only interaction timing for one GUI/render frame.
	 *
	 * <p>The frame path makes a capture-ready held interaction visible as an open
	 * selector and completes a release whose asynchronous capture arrived
	 * after the release event. It is the sole per-frame interaction path.
	 * Mouse capture is synchronized on this single frame path (and immediate
	 * release/press events) so visible transitions preserve the existing
	 * release/re-grab semantics without duplicate transitions.
	 */
	public void onRenderFrame(boolean keyDown) {
		if (!observeInteractionLifecycle()) return;
		compatibilityController.onRenderFrame(keyDown);
		synchronizeNativeInput();
	}

	/**
	 * Explicitly abandons the active interaction without creating a default
	 * ping. The token is invalidated first so a late asynchronous capture cannot
	 * resurrect the interaction.
	 */
	public void abort() {
		compatibilityController.abort();
		interactionAccess.resetInput();
	}

	public void abortForScreenTransition() {
		abortingScreenTransition = true;
		try { abort(); }
		finally { abortingScreenTransition = false; }
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
		markerStore.clear();
		if (presentation != null) presentation.close();
		nameStore.clear();
		MarkerOverlayState.INSTANCE.clear();
		observedWorldIdentity = null;
		observedScreenIdentity = null;
		trackingPaint = List.of();
		observedDimension = null;
	}

	/**
	 * True when the compatibility wrapper still owns a seeded or in-flight
	 * sequence even though the baseline phase may already be idle.
	 */
	public boolean hasCompatibilityState() {
		return compatibilityController.hasPendingState();
	}

	private boolean observeInteractionLifecycle() {
		var lifecycle = interactionAccess.lifecycle();
		boolean screenChanged = observedScreenIdentity != lifecycle.screen();
		boolean discontinuity = observedWorldIdentity != null && (observedWorldIdentity != lifecycle.level()
			|| !Objects.equals(observedDimension, lifecycle.dimension()) || screenChanged);
		if (discontinuity || lifecycle.level() == null || !lifecycle.player() || !lifecycle.focused()
			|| lifecycle.screen() != null || lifecycle.overlay()) {
			if (lifecycle.level() != null && lifecycle.player() && lifecycle.focused() && !lifecycle.overlay()
				&& (screenChanged || lifecycle.screen() != null)) abortForScreenTransition();
			else abort();
		}
		observedWorldIdentity = lifecycle.level();
		observedDimension = lifecycle.dimension();
		observedScreenIdentity = lifecycle.screen();
		return !discontinuity && lifecycle.level() != null && lifecycle.player() && lifecycle.focused()
			&& lifecycle.screen() == null && !lifecycle.overlay();
	}

	/** The baseline press path used by the compatibility controller. */
	private Optional<PingInteractionAction> baselinePress(long rawPressTimestamp) {
		return baselinePress(rawPressTimestamp, interactionAccess.capturePressRay().orElse(null));
	}

	/** Baseline press path with a ray captured by a rapid second physical press. */
	private Optional<PingInteractionAction> baselinePress(
		long rawPressTimestamp,
		CapturedRay pressRay
	) {
		InteractionToken token = machine.pressAt(rawPressTimestamp);
		finishSelector(false);
		if (!activeInteraction.isCurrent(token)) return Optional.empty();
		baselineToken = token;
		baselineHeld = true;
		baselineMenuOpened = false;
		ClientConfig config = ClientConfig.HANDLER.getConfig();
		selectorSettings = config.getSpatialSelector().snapshot();
		selectorPolicy = raycastPolicy(config);
		selectorPingDistance = config.getPingDistance();
		selectorNativeDistance = Math.min(config.getRaycastDistance(), selectorPingDistance);
		selectorStyle = SpatialOverlayRenderer.Style.fromLegacyFontSizes(config.getWheelOpacity(), config.getWheelTargetOpacity(), config.getWheelFontSize(),
			config.getWheelTargetFontSize(), selectorSettings.rootDistance(), selectorSettings.submenuRadiusScale().doubleValue(),
			selectorSettings.showTrail(), selectorSettings.reduceMotion());
		pendingRay = null;
		if (pressRay == null) machine.abort();
		else {
			pendingRay = pressRay;
			try { interactionAccess.capture(token, pressRay, (snapshot, candidates) -> completeCapture(token, snapshot, pressRay, candidates)); }
			catch (RuntimeException | LinkageError failure) { if (baselineToken == token) baselineAbort(); throw failure; }
		}
		interactionAccess.syncMouse(machine.phase());
		return Optional.empty();
	}

	/**
	 * The one mapping from the persistent target-selection settings to the
	 * immutable raycast policy: sampled at hold start for capture and re-sampled
	 * live by the selector toggle labels.
	 */
	private static RaycastPolicy raycastPolicy(ClientConfig config) {
		return RaycastPolicy.from(config.isPassThroughTransparentBlocks(), config.isMarkBlacklistedTargets(), config.isMarkFluids());
	}

	private LongPressCompatibilityController.BaselineOutcome baselineRelease() {
		InteractionToken releasing = baselineToken;
		baselineHeld = false;
		if (preciseRefresh != null) preciseRefresh.leave();
		long now = timeSource.nowMillis();
		Optional<PingInteractionAction> action;
		ClientPingActionDispatcher.DispatchOutcome sent = ClientPingActionDispatcher.DispatchOutcome.OTHER;
		try {
			if (machine.phase() == PingInteractionPhase.WHEEL_OPEN && selector != null && baselineToken != null) {
				var opened = selector;
				var releaseContexts = new LinkedHashMap<>(selectorContexts);
				opened.presentedPrecise().ifPresent(painted -> releaseContexts.putAll(painted.contexts()));
				var candidates = Map.copyOf(releaseContexts);
				var ray = pendingRay;
				var content = selectorContent;
				var result = machine.releaseSelectorAt(releasing, now, () -> proposal(opened.releaseIntent(now)),
					id -> Optional.ofNullable(candidates.get(id)), () -> interactionAccess.cancellation(ray));
				action = result.action();
				if (baselineToken != releasing || !activeInteraction.isCurrent(releasing)) action = Optional.empty();
				else if (result.admittedIntent().isPresent()) {
					SelectorIntent<ClientInventory.PreviewEntryReference> intent = result.admittedIntent().get();
					if (intent instanceof SelectorIntent.ToggleNextCapture<ClientInventory.PreviewEntryReference> toggle)
						interactionAccess.applyToggle(toggle.toggle(), now);
					else if (intent instanceof SelectorIntent.SelectInventory<ClientInventory.PreviewEntryReference> selected) {
						dispatchInventory(selected.reference(), selected.itemType().id());
					} else if (intent instanceof SelectorIntent.CreateProperty<ClientInventory.PreviewEntryReference> property) {
						var authorized = content == null ? Optional.<PresentationPropertyIntent>empty()
							: content.intent(property.property().ref(), property.property().pingTypeId());
						if (baselineToken == releasing && activeInteraction.isCurrent(releasing) && authorized.isPresent() && action.isPresent()
							&& propertyTypes(candidates.get(property.candidate().candidateId())).contains(
								PingTypeCatalog.builtIn().findById(property.property().pingTypeId()).orElseThrow()))
							sent = dispatcher.dispatchOutcome(action.get(), List.of(property.property()));
					} else if (action.isPresent()) sent = dispatcher.dispatchOutcome(action.get());
				} else if (action.isPresent()) sent = dispatcher.dispatchOutcome(action.get());
			} else {
				action = machine.updateAt(false, WheelSelection.NONE, emptyCancellationContext(), now);
				if (baselineToken != releasing || !activeInteraction.isCurrent(releasing)) action = Optional.empty();
				if (action.isPresent()) sent = dispatcher.dispatchOutcome(action.get());
			}
			return baselineOutcome(action, sent);
		} finally {
			if (baselineToken == releasing && machine.phase() == PingInteractionPhase.IDLE) finishSelector(false);
			interactionAccess.syncMouse(machine.phase());
			synchronizeNativeInput();
		}
	}

	private LongPressCompatibilityController.BaselineOutcome baselinePresentFrame(boolean keyDown) {
		return baselinePresentFrame(keyDown, timeSource.nowMillis());
	}

	private LongPressCompatibilityController.BaselineOutcome baselinePresentFrame(
		boolean keyDown,
		long frameTimeMillis
	) {
		InteractionToken presented = baselineToken;
		if (!keyDown && machine.phase() != PingInteractionPhase.WHEEL_OPEN) baselineHeld = false;
		boolean presentable = interactionAccess.inputFrame().isPresent();
		machine.presentFrameAt(keyDown && presentable, frameTimeMillis);
		if (selectorContent != null && machine.phase() != PingInteractionPhase.IDLE) {
			try { selectorContent.tick(); }
			catch (RuntimeException | LinkageError unavailable) { logger.debug("selector content update unavailable"); }
		}
		if (machine.phase() == PingInteractionPhase.WHEEL_OPEN) {
			baselineMenuOpened = true;
			if (selector == null) openSelector(frameTimeMillis);
			if (selector != null && presentable) selector.tick(frameTimeMillis);
			synchronizePreciseBranch();
		}
		// Raw release edges own wheel commit/cancellation.  A stale false key
		// state on a later frame must not make the frame path walk every owned
		// marker; keep an already-open wheel logically held until its release
		// event arrives.  A pressed interaction still uses false here so an
		// asynchronous capture that completes after release can commit its one
		// short action on this frame.
		boolean actionKeyDown = keyDown || machine.phase() == PingInteractionPhase.WHEEL_OPEN;
		Optional<PingInteractionAction> action = machine.updateAt(actionKeyDown, WheelSelection.NONE,
			emptyCancellationContext(), frameTimeMillis);
		if (baselineToken != presented || !activeInteraction.isCurrent(presented)) action = Optional.empty();
		var sent = action.isPresent() ? dispatcher.dispatchOutcome(action.get()) : ClientPingActionDispatcher.DispatchOutcome.OTHER;
		var outcome = baselineOutcome(action, sent);
		if (baselineToken == presented && machine.phase() == PingInteractionPhase.IDLE) finishSelector(false);
		interactionAccess.syncMouse(machine.phase());
		return outcome;
	}

	private LongPressCompatibilityController.BaselineOutcome baselineOutcome(Optional<PingInteractionAction> action,
		ClientPingActionDispatcher.DispatchOutcome sent) {
		return new LongPressCompatibilityController.BaselineOutcome(action,
			sent == ClientPingActionDispatcher.DispatchOutcome.CREATE_SENT
				? LongPressCompatibilityController.DispatchOutcome.CREATE_SENT : LongPressCompatibilityController.DispatchOutcome.NOT_SENT,
			baselineMenuOpened);
	}

	private void baselineAbort() {
		machine.abort();
		if (baselineToken != null) activeInteraction.invalidate(baselineToken);
		baselineHeld = false;
		baselineToken = null;
		finishSelector(true);
		interactionAccess.disposeMouse(abortingScreenTransition);
	}

	private void startHeldPreview(CapturedPingContext capture) {
		if (!baselineHeld || baselineToken != capture.token() || selectorContent != null) return;
		selectorContent = new NativeSelectorContent(capture, contentPreview, () -> inventory, this::propertyTypes,
			ref -> {
				String key = "settings.pingforit.presentation.field." + ref.fieldId().replace(':', '_').replace('.', '_') + ".name";
				if (Language.getInstance().has(key)) return Component.translatable(key);
				String advertised = presentation == null ? ref.fieldId() : presentation.previewAccess(capture.resolvedTarget().targetType().id())
					.map(access -> access.adapters().get(ref.adapterId())).map(adapter -> adapter.fields().get(ref.fieldId()))
					.map(nx.pingwheel.common.presentation.PresentationField::label).orElse(ref.fieldId());
				return Component.literal(advertised.substring(0, Math.min(64, advertised.length())));
			},
			json -> {
				if (Game == null || Game.level == null) return null;
				try { return Component.Serializer.fromJson(json, Game.level.registryAccess()); }
				catch (RuntimeException | LinkageError invalid) { return null; }
			});
		try { selectorContent.begin(interactionAccess.lifecycle().level()); }
		catch (RuntimeException | LinkageError unavailable) {
			logger.debug("selector preview unavailable");
		}
	}

	private List<PingType> propertyTypes(CapturedPingContext context) {
		Target target = context.resolvedTarget().target();
		String registry = null;
		Set<String> tags = Set.of();
		if (target instanceof Target.BlockTarget block) registry = block.blockRegistryId();
		else if (target instanceof Target.ExternalBlockTarget external) registry = external.expectedBlockRegistryId();
		else if (target instanceof Target.EntityTarget entity) {
			Entity live = Game == null || Game.level == null ? null : GameContext.getEntity(entity.locator());
			if (live != null && !live.isRemoved() && target.dimensionId().equals(Game.level.dimension().location().toString())) {
				registry = BuiltInRegistries.ENTITY_TYPE.getKey(live.getType()).toString();
				tags = BuiltInRegistries.ENTITY_TYPE.getHolder(BuiltInRegistries.ENTITY_TYPE.getKey(live.getType())).stream()
					.flatMap(holder -> holder.tags()).map(tag -> tag.location().toString()).collect(java.util.stream.Collectors.toSet());
			}
		}
		if (registry != null && !(target instanceof Target.EntityTarget)) {
			var id = ResourceLocation.tryParse(registry);
			if (id != null && BuiltInRegistries.BLOCK.containsKey(id))
				tags = BuiltInRegistries.BLOCK.getHolder(id).stream().flatMap(holder -> holder.tags()).map(tag -> tag.location().toString())
					.collect(java.util.stream.Collectors.toSet());
		}
		return PresentationPropertyPingTypes.builtIn().effective(registry, tags).stream()
			.flatMap(id -> PingTypeCatalog.builtIn().findById(id).stream()).toList();
	}

	private void openSelector(long now) {
		CapturedPingContext context = activeInteraction.currentContext().filter(value -> value.token() == baselineToken).orElse(null);
		if (context == null) return;
		startHeldPreview(context);
		if (context.token() != baselineToken || !activeInteraction.isCurrent(context.token())
			|| machine.phase() != PingInteractionPhase.WHEEL_OPEN) return;
		selectorContexts.clear();
		SpatialSelectorSession.CapturedTarget ordinary = new SpatialSelectorSession.CapturedTarget("ordinary", context.resolvedTarget(),
			context.blockHitFace(), Optional.empty());
		selectorContexts.put("ordinary", context);
		if (context.selectorCandidates().isPresent()) {
			var set = context.selectorCandidates().get();
			var candidate = set.ordinary();
			selectorContexts.put(Integer.toString(candidate.candidateId()), context);
			ordinary = new SpatialSelectorSession.CapturedTarget(Integer.toString(candidate.candidateId()), candidate.resolvedTarget(),
				context.blockHitFace(), Optional.of(candidate.worldHit()));
		}
		long request = selectorContent == null ? ClientInventory.NO_REQUEST : selectorContent.requestId();
		var fence = new SpatialSelectorSession.ContentFence(context.token().sequence(), request, context.token().sequence(), ordinary.candidateId());
		var content = selectorContent;
		var opened = new SpatialSelectorSession<>(ordinary, Map.of(), selectorSettings, fence,
			(target, bound) -> {
				try { return content == null ? null : content.read(target, bound); }
				catch (RuntimeException | LinkageError unavailable) { logger.debug("selector content projection unavailable"); return null; }
			}, interactionAccess.listGeometry(selectorStyle));
		selector = opened;
		Object level = interactionAccess.lifecycle().level();
		opened.beginLivePrecise(context.token(), level);
		var token = context.token();
		preciseRefresh = new PreciseCaptureRefresh(token, level, selectorSettings.preciseCapturePeriodTicks(),
			() -> interactionAccess.capturePressRay().map(ray -> new PreciseCaptureRefresh.Inputs(token, level, ray,
				selectorPolicy, selectorNativeDistance, selectorPingDistance)),
			() -> selector == opened && baselineHeld && baselineToken == token && activeInteraction.isCurrent(token)
				&& interactionAccess.lifecycle().level() == level && machine.phase() == PingInteractionPhase.WHEEL_OPEN,
			interactionAccess::capturePrecise, interactionAccess::capturePreciseDistant,
			update -> { if (selector == opened && baselineToken == token && interactionAccess.lifecycle().level() == level)
				opened.updatePrecise(update, timeSource.nowMillis()); });
		opened.open(now);
		if (selector != opened || context.token() != baselineToken || !activeInteraction.isCurrent(context.token())) return;
		selectorInput = new NativeSelectorInput(opened);
		selectorInput.open();
	}

	private SelectorReleaseProposal<SelectorIntent<ClientInventory.PreviewEntryReference>> proposal(
		SelectorIntent<ClientInventory.PreviewEntryReference> intent) {
		return switch (intent) {
			case SelectorIntent.None<ClientInventory.PreviewEntryReference> ignored -> new SelectorReleaseProposal.None<>();
			case SelectorIntent.CancelOwnMarker<ClientInventory.PreviewEntryReference> cancel -> new SelectorReleaseProposal.Cancel<>(cancel);
			case SelectorIntent.ToggleNextCapture<ClientInventory.PreviewEntryReference> toggle -> new SelectorReleaseProposal.Local<>(toggle);
			case SelectorIntent.CreateTarget<ClientInventory.PreviewEntryReference> create -> new SelectorReleaseProposal.Create<>(
				create.candidate().candidateId(), create.candidate().resolvedTarget(), create.pingType(), create,
				create.admission(), create.presentationRevision());
			case SelectorIntent.CreateProperty<ClientInventory.PreviewEntryReference> property -> new SelectorReleaseProposal.Create<>(
				property.candidate().candidateId(), property.candidate().resolvedTarget(), property.mainType(), property);
			case SelectorIntent.SelectInventory<ClientInventory.PreviewEntryReference> selected -> new SelectorReleaseProposal.Create<>(
				selected.candidate().candidateId(), selected.candidate().resolvedTarget(), selected.mainType(), selected);
		};
	}

	private void finishSelector(boolean hard) {
		if (preciseRefresh != null) preciseRefresh.end();
		preciseRefresh = null;
		if (baselineToken != null) activeInteraction.invalidate(baselineToken);
		baselineToken = null;
		baselineHeld = false;
		if (selectorInput != null) selectorInput.release();
		if (selector != null) selector.abort();
		selector = null; selectorInput = null;
		NativeSelectorContent old = selectorContent;
		selectorContent = null;
		selectorContexts.clear(); pendingRay = null; wheelSelection = WheelSelection.NONE;
		if (hard) selectorPaint.reset();
		try { if (old != null) old.close(); }
		catch (RuntimeException | LinkageError unavailable) { logger.debug("selector preview close unavailable"); }
	}

	public Optional<SpatialSelectorSession.Snapshot> selectorSnapshot() {
		return selector == null ? Optional.empty() : Optional.of(selector.snapshot());
	}
	public void drawSelector(GuiGraphics graphics) {
		if (interactionAccess.inputFrame().isEmpty()) return;
		if (selectorStyle == null || (selector == null && !selectorPaint.isAnimating())) return;
		var opened = selector;
		var snapshot = opened == null ? null : opened.snapshot();
		selectorPaint.drawFrame(graphics, snapshot == null ? null : snapshot.radial(),
			snapshot == null ? null : snapshot.inventoryView(), this::selectorChoiceLabel, selectorStyle,
			java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeSource.nowMillis()));
		acknowledgeSelectorPaint(opened, snapshot, selectorPaint.paintedChoiceIds());
	}

	/**
	 * The selector's live label resolver: target-selection toggles gain their
	 * live ON/OFF state; every other choice keeps the held-content or default
	 * translation-key behavior unchanged.
	 */
	private Component selectorChoiceLabel(SpatialController.ChoiceView choice) {
		Component toggleLabel = selectorToggleLabels.label(choice);
		if (toggleLabel != null) return toggleLabel;
		return selectorContent == null
			? choice.label() == null ? Component.empty() : Component.translatable(choice.label())
			: selectorContent.label(choice.label());
	}
	/** Actual renderer handoff; a headless recording may acknowledge only paint it exercised. */
	void acknowledgeSelectorPaint(SpatialSelectorSession.Snapshot snapshot, Set<String> paintedChoiceIds) {
		acknowledgeSelectorPaint(selector, snapshot, paintedChoiceIds);
	}
	private void acknowledgeSelectorPaint(SpatialSelectorSession<ClientInventory.PreviewEntryReference> opened,
		SpatialSelectorSession.Snapshot snapshot, Set<String> paintedChoiceIds) {
		if (opened == null || selector != opened || baselineToken == null || !activeInteraction.isCurrent(baselineToken)) return;
		opened.markPresented(snapshot, paintedChoiceIds).ifPresent(frame ->
			machine.markSelectorPresented(baselineToken, frame.revision(), frame.contexts()));
	}
	private void synchronizePreciseBranch() {
		if (preciseRefresh == null) return;
		if (selector != null && selector.isPreciseBranchActive() && baselineHeld) preciseRefresh.enter(localTick);
		else preciseRefresh.leave();
	}
	public void prepareInventoryTracking(nx.pingwheel.common.render.WorldRenderContext frame) {
		if (inventory == null || Game == null || Game.level == null) { trackingPaint = List.of(); return; }
		String dimension = Game.level.dimension().location().toString();
		var lines = new java.util.ArrayList<nx.pingwheel.common.render.InventoryTrackingRenderer.MarkerLines>();
		for (var marker : markerStore.hudRenderMarkers()) {
			if (!dimension.equals(marker.target().dimensionId())) continue;
			var entries = nx.pingwheel.common.render.InventoryTrackingRenderer.lines(inventory.tracking(marker.id()));
			if (entries.isEmpty()) continue;
			WorldVector point = MarkerOverlayState.INSTANCE.lookupPresentationPosition(marker.id(), marker.target(), dimension)
				.orElseGet(() -> candidatePosition(marker, dimension));
			Vec3 position = new Vec3(point.x(), point.y(), point.z());
			var screen = nx.pingwheel.common.math.MathUtils.worldToScreen(position, frame.modelViewMatrix, frame.projectionMatrix);
			if (screen == null || screen.isBehindCamera()) continue;
			double distance = frame.camera.getPosition().distanceTo(position);
			float scale = (float) Math.max(1.0, 2.0 / Math.pow(distance, 0.3)) * 0.5f * ClientConfig.HANDLER.getConfig().getPingSize() / 100f;
			lines.add(new nx.pingwheel.common.render.InventoryTrackingRenderer.MarkerLines(screen.x, screen.y, scale, entries));
		}
		trackingPaint = List.copyOf(lines);
	}
	public void drawInventoryTracking(GuiGraphics graphics) {
		nx.pingwheel.common.render.InventoryTrackingRenderer.draw(graphics, trackingPaint);
	}
	public void onPresentationPreview(PresentationPreviewS2CPacket packet) {
		if (contentPreview != null && selectorContent != null && observeInteractionLifecycle()) contentPreview.accept(packet);
	}
	public void rePrimeSelectorInput() { if (selectorInput != null) selectorInput.rePrime(); }
	public boolean isSelectorMouseTransition() { return selectorInput != null || wheelMouseCapture.isTransitioning(); }
	private void synchronizeNativeInput() {
		if (selectorInput == null) return;
		interactionAccess.inputFrame().ifPresentOrElse(selectorInput::synchronize, selectorInput::rePrime);
	}
	public void onMouseMove(long window, double x, double y) {
		if (!observeInteractionLifecycle() || selectorInput == null || machine.phase() != PingInteractionPhase.WHEEL_OPEN) return;
		interactionAccess.inputFrame().ifPresentOrElse(
			frame -> selectorInput.onMove(window, x, y, frame, timeSource.nowMillis()), selectorInput::rePrime);
		synchronizePreciseBranch();
	}
	public boolean onMouseScroll(long window, double horizontal, double vertical) {
		if (!observeInteractionLifecycle() || selectorInput == null || machine.phase() != PingInteractionPhase.WHEEL_OPEN) return false;
		var frame = interactionAccess.inputFrame();
		if (frame.isEmpty()) { selectorInput.rePrime(); return false; }
		return selectorInput.onScroll(window, horizontal, vertical, frame.get(), timeSource.nowMillis());
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
			return interactionAccess.capturePressRay();
		}

		@Override
		public Optional<PingInteractionAction> release() {
			return releaseOutcome().action();
		}
		@Override public LongPressCompatibilityController.BaselineOutcome releaseOutcome() { return baselineRelease(); }

		@Override
		public Optional<PingInteractionAction> presentFrame(boolean keyDown) {
			return presentFrameOutcome(keyDown).action();
		}
		@Override public LongPressCompatibilityController.BaselineOutcome presentFrameOutcome(boolean keyDown) {
			return baselinePresentFrame(keyDown);
		}

		@Override
		public Optional<PingInteractionAction> presentFrame(boolean keyDown, long frameTimeMillis) {
			return presentFrameOutcome(keyDown, frameTimeMillis).action();
		}
		@Override public LongPressCompatibilityController.BaselineOutcome presentFrameOutcome(boolean keyDown, long time) {
			return baselinePresentFrame(keyDown, time);
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

	private final class MinecraftInteractionAccess implements InteractionAccess {
		public Lifecycle lifecycle() {
			Minecraft game = Game;
			return new Lifecycle(game == null ? null : game.level,
				game == null || game.level == null ? null : game.level.dimension().location().toString(),
				game == null ? null : game.screen, game != null && game.player != null,
				game != null && game.isWindowActive(), game != null && game.getOverlay() != null);
		}
		public Optional<CapturedRay> capturePressRay() { return ClientPingRuntime.this.capturePressRay(); }
		public void capture(InteractionToken token, CapturedRay ray, CaptureCompletion completion) { captureImmediately(token, ray, completion); }
		public PreciseCaptureRefresh.Scan capturePrecise(PreciseCaptureRefresh.Request request) { return capturePreciseNative(request); }
		public boolean capturePreciseDistant(PreciseCaptureRefresh.Request request,
			java.util.function.Consumer<PreciseCaptureRefresh.Outcome> completion) { return startPreciseDistant(request, completion); }
		public CancellationContext cancellation(CapturedRay ray) { return buildCancellationContext(); }
		public void syncMouse(PingInteractionPhase phase) {
			if (Game == null) return;
			boolean before = Game.mouseHandler.isMouseGrabbed();
			wheelMouseCapture.sync(phase, Game);
			if (before != Game.mouseHandler.isMouseGrabbed()) rePrimeSelectorInput();
		}
		public void disposeMouse(boolean screenTransition) { wheelMouseCapture.close(Game, screenTransition); rePrimeSelectorInput(); }
		public Optional<NativeSelectorInput.Frame> inputFrame() {
			if (Game == null) return Optional.empty();
			var window = Game.getWindow();
			if (window.getScreenWidth() <= 0 || window.getScreenHeight() <= 0
				|| window.getGuiScaledWidth() <= 0 || window.getGuiScaledHeight() <= 0) return Optional.empty();
			return Optional.of(new NativeSelectorInput.Frame(window.getWindow(), window.getScreenWidth(), window.getScreenHeight(),
				window.getGuiScaledWidth(), window.getGuiScaledHeight(), Game.isWindowActive(), Game.mouseHandler.isMouseGrabbed()));
		}
		public SpatialSelectorSession.ListGeometry listGeometry(SpatialOverlayRenderer.Style style) {
			var view = new SpatialInventoryView("layout", false, List.of(), -1, 0, 1, true, SpatialInventoryView.Status.UNKNOWN, 0, 0);
			var layout = SpatialOverlayRenderer.inventoryLayout(view, 0, 0, Game.font.lineHeight, style);
			int rows = Math.max(1, (int) ((Game.getWindow().getGuiScaledHeight() - layout.headerHeight() - layout.footerHeight())
				/ layout.rowHeight()));
			return SpatialSelectorSession.ListGeometry.fromLayout(rows, layout, selectorSettings.deadzone());
		}
		public void resetInput() {
			if (abortingScreenTransition) InputUtils.resetPingInteraction();
			else InputUtils.resetPingHold();
		}
		public void applyToggle(SelectorIntent.CaptureToggle toggle, long timeMillis) { InputUtils.applySelectorToggle(toggle, timeMillis); }
	}

	/**
	 * Supplies a valid, allocation-free context for machine paths that cannot
	 * cancel. The real context (and its owned-marker scan) is built only for an
	 * explicitly committed native Cancel.
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

	/** Live scan never completes the ordinary coordinator or writes its pending ray. */
	private PreciseCaptureRefresh.Scan capturePreciseNative(PreciseCaptureRefresh.Request request) {
		var inputs = request.inputs();
		Minecraft game = Game;
		if (game == null || game.level != inputs.level() || game.cameraEntity == null
			|| !activeInteraction.isCurrent(inputs.token())) return PreciseCaptureRefresh.Scan.incomplete();
		ClientLevel level = game.level;
		var trace = Raycast.traceDirectionalCandidates(inputs.token(), inputs.ray(), inputs.nativeDistance(),
			inputs.pingDistance(), inputs.policy(), CandidateWorkLimits.defaults());
		if (trace.isEmpty()) return PreciseCaptureRefresh.Scan.incomplete();
		var selection = trace.orElseThrow().ordinarySelection();
		HitResult hit = selection.hitResult();
		TargetSnapshot reference;
		Vec3 origin = vector(inputs.ray().origin()), direction = vector(inputs.ray().direction());
		if (hit.getType() == HitResult.Type.BLOCK && ModContext.HasSable) {
			var external = SableClientProvider.capture(level, (BlockHitResult) hit, origin,
				origin.add(direction.scale(inputs.nativeDistance())));
			var projected = external.isPresent() ? Optional.<Vec3>empty() : SableIntegration.projectOutOfSubLevel(level, hit.getLocation());
			reference = external.orElseGet(() -> projected.map(point -> TargetSnapshotFactory.location(
				level.dimension().location().toString(), point.x, point.y, point.z))
				.orElseGet(() -> MinecraftTargetSnapshotFactory.fromCandidateSelection(level, selection)));
		} else reference = MinecraftTargetSnapshotFactory.fromCandidateSelection(level, selection);
		var resolver = DefaultTargetResolver.builtIn(TargetResolutionLogger.global());
		var resolved = resolver.resolve(reference.target(), reference.matchContext());
		var allocated = trace.orElseThrow().candidates().finish(reference, resolved, resolver);
		var nativeSlots = new java.util.EnumMap<PreciseTargetType, PreciseCaptureRefresh.Outcome>(PreciseTargetType.class);
		for (var type : PreciseTargetType.values()) if (type != PreciseTargetType.LOCATION) {
			var slot = allocated.slot(type);
			nativeSlots.put(type, slot.candidateId().map(id -> PreciseCaptureRefresh.Outcome.available(allocated.candidate(id).orElseThrow()))
				.orElseGet(() -> slot.availability() == PreciseSlot.Availability.INCOMPLETE
					? PreciseCaptureRefresh.Outcome.incomplete() : PreciseCaptureRefresh.Outcome.missing()));
		}
		var location = allocated.slot(PreciseTargetType.LOCATION).candidateId()
			.map(id -> PreciseCaptureRefresh.Outcome.available(allocated.candidate(id).orElseThrow()))
			.orElseGet(PreciseCaptureRefresh.Outcome::incomplete);
		return new PreciseCaptureRefresh.Scan(nativeSlots, location, hit.getType() == HitResult.Type.MISS && ModContext.HasDistantHorizons);
	}
	private static Vec3 vector(WorldVector value) { return new Vec3(value.x(), value.y(), value.z()); }
	private boolean startPreciseDistant(PreciseCaptureRefresh.Request request,
		java.util.function.Consumer<PreciseCaptureRefresh.Outcome> completion) {
		Minecraft game = Game;
		var inputs = request.inputs();
		if (game == null || game.level != inputs.level() || !ModContext.HasDistantHorizons) return false;
		ClientLevel level = game.level;
		try {
			return DistantHorizonsIntegration.traceDistantAsync(vector(inputs.ray().origin()), vector(inputs.ray().direction()),
				distant -> game.execute(() -> {
					if (game.level != level || baselineToken != inputs.token() || !activeInteraction.isCurrent(inputs.token())) return;
					// A no-hit/failure uses this request's native miss, not the latest camera.
					Vec3 point = distant.map(HitResult::getLocation).orElseGet(() -> vector(inputs.ray().origin())
						.add(vector(inputs.ray().direction()).scale(inputs.nativeDistance())));
					var snapshot = TargetSnapshotFactory.location(level.dimension().location().toString(), point.x, point.y, point.z);
					var resolved = DefaultTargetResolver.builtIn(TargetResolutionLogger.global()).resolve(snapshot.target(), snapshot.matchContext());
					WorldVector worldPoint = new WorldVector(point.x, point.y, point.z);
					completion.accept(PreciseCaptureRefresh.Outcome.available(new Candidate(0, resolved, worldPoint,
						FrozenCandidateAcquisition.distance(inputs.ray().origin(), worldPoint), Optional.empty(), Optional.empty(),
						CaptureEquivalenceKey.nativeTarget(resolved.target()))));
				}));
		} catch (LinkageError failure) {
			ModContext.HasDistantHorizons = false;
			DistantHorizonsIntegration.logUnguardedLinkFailure(failure);
			return false;
		}
	}

	private void captureImmediately(InteractionToken token, CapturedRay pressRay, CaptureCompletion completion) {
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
		var distance = selectorNativeDistance;
		var trace = Raycast.traceDirectionalCandidates(token, pressRay, distance, selectorPingDistance, selectorPolicy,
			CandidateWorkLimits.defaults());
		var raycastSelection = trace.map(Raycast.SelectorTrace::ordinarySelection);
		Optional<FrozenCandidateAcquisition> acquisition = trace.map(Raycast.SelectorTrace::candidates);
		var hitResult = raycastSelection.map(nx.pingwheel.common.math.RaycastSelection::hitResult).orElse(null);

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
						distantHit -> completeDistantHit(game, level, token, fallbackMiss, pressRay, distantHit, acquisition, completion));
				} catch (LinkageError error) {
					ModContext.HasDistantHorizons = false;
					DistantHorizonsIntegration.logUnguardedLinkFailure(error);
					scheduled = false;
				}

				if (!scheduled) {
					completion.complete(MinecraftTargetSnapshotFactory.fromCandidateHitResult(level, missHit), acquisition);
				}
			} else {
				completion.complete(MinecraftTargetSnapshotFactory.fromCandidateHitResult(level, missHit), acquisition);
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
				completion.complete(sableCandidate.get(), acquisition);
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
				completion.complete(TargetSnapshotFactory.location(
					game.level.dimension().location().toString(),
					projectedPos.x, projectedPos.y, projectedPos.z), acquisition);
				return;
			}
		}

		if (sableCaptureAttempted) {
			SableClientProvider.logCaptureFallback(
				"VANILLA_TARGET_FACTORY", "external-and-projected-capture-failed", "vanilla-target-factory",
				"hit_location", hitResult.getLocation(),
				"block_pos", hitResult instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null);
		}

		completion.complete(raycastSelection
			.map(selection -> MinecraftTargetSnapshotFactory.fromCandidateSelection(game.level, selection))
			.orElseGet(() -> MinecraftTargetSnapshotFactory.fromCandidateHitResult(game.level, hitResult)), acquisition);
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
		Optional<BlockHitResult> distantHit,
		Optional<FrozenCandidateAcquisition> acquisition,
		CaptureCompletion completion
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

			completion.complete(MinecraftTargetSnapshotFactory.fromCandidateHitResult(levelAtPress, appliedHit), acquisition);
		});
	}

	/**
	 * Completes a capture with its immutable press-time ray. A failed current
	 * completion clears only this interaction's pending ray; a stale callback or
	 * a duplicate completion can never clear a newer/accepted capture.
	 */
	private void completeCapture(InteractionToken token, TargetSnapshot snapshot, CapturedRay pressRay,
		Optional<FrozenCandidateAcquisition> candidates) {
		if (baselineToken != token || !activeInteraction.isCurrent(token)) return;
		if (!observeInteractionLifecycle() || baselineToken != token || !activeInteraction.isCurrent(token)) return;
		Optional<CapturedPingContext> completed = captureCoordinator.complete(token, snapshot, pressRay, candidates);
		completed.ifPresent(this::startHeldPreview);

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
			if (inventory != null) inventory.markerRemoved(marker.id());
			if (presentation != null) presentation.evict(marker.id());
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
		if (markerStore.isAuthoritativelyRemoved(snapshot.id())) {
			return;
		}

		boolean newlySeen = isNewMarkerReceipt(markerStore, snapshot.id());

		List<ClientMarker> superseded = markerStore.onCreated(snapshot, localTick);
		for (ClientMarker marker : superseded) {
			nameStore.onRemoved(marker.id());
			if (presentation != null) presentation.evict(marker.id());
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

	/** Sole production S2C path for versioned marker and presentation messages. */
	public void onPresentationPacket(PresentationS2CPacket packet) {
		if (presentation == null || packet == null || packet.isCorrupt()) return;
		switch (packet.kind()) {
			case OFFER -> presentation.offer(packet);
			case RESET -> {
				if (presentation.reset(packet)) {
					if (inventory != null) inventory.presentationReset(packet.epoch(), packet.view());
					syncPresentationNames();
				}
			}
			case CREATED -> {
				if (!presentation.current(packet) || packet.markerId() == null
					|| markerStore.isAuthoritativelyRemoved(packet.markerId())) return;
				if (!presentation.initial(packet)) return;
				updatePresentationName(packet.markerId());
				applyPresentationCreated(packet.snapshot(), packet.ownerName());
				if (inventory != null) inventory.markerCreated(packet.snapshot());
			}
			case SECTION -> {
				// A store tombstone is also "known". Do not decode a section for
				// a marker absent from the marker runtime.
				if (!presentation.current(packet) || packet.markerId() == null
					|| markerStore.marker(packet.markerId()).isEmpty()) return;
				if (presentation.section(packet)) {
					updatePresentationName(packet.markerId());
				}
			}
			case REMOVED -> {
				if (!presentation.current(packet)) return;
				presentation.removed(packet.markerId(), packet.revision(),
					packet.removalReason() == MarkerRemovalReason.EXPIRED);
				applyRemoved(packet.markerId(), packet.removalReason());
				if (inventory != null) inventory.markerRemoved(packet.markerId());
				if (markerStore.marker(packet.markerId()).isEmpty()) presentation.evict(packet.markerId());
			}
			case WINNER -> {
				if (presentation.current(packet)) applyWinnerChanged(packet.targetKey(), packet.winnerId());
			}
			case REJECT -> {
				if (presentation.current(packet))
					handleRejected(packet.requestId(), packet.requestKind(), packet.rejectReason());
			}
		}
	}

	/** Read-only UI entry point; negotiation and raw values remain runtime-private. */
	public ClientPresentation presentation() {
		return presentation;
	}
	private nx.pingwheel.common.presentation.inventory.client.ClientInventory inventory;
	public void inventory(nx.pingwheel.common.presentation.inventory.client.ClientInventory inventory) {
		this.inventory = inventory;
		if (inventory != null && presentation != null && presentation.ready()) {
			inventory.presentationReset(presentation.epoch(), presentation.sessionView());
		}
	}
	public nx.pingwheel.common.presentation.inventory.client.ClientInventory.DispatchOutcome dispatchInventory(
		nx.pingwheel.common.presentation.inventory.client.ClientInventory.PreviewEntryReference reference, String itemPingType) {
		return inventory == null ? nx.pingwheel.common.presentation.inventory.client.ClientInventory.DispatchOutcome.NOT_READY
			: inventory.select(reference, itemPingType, dispatcher::dispatchInventory);
	}
	public void onInventoryPacket(nx.pingwheel.common.network.InventoryS2CPacket packet) {
		if (inventory == null) return;
		var previous = packet == null ? null : inventory.selectionResult(packet.commitId());
		inventory.accept(packet);
		if (previous == null && packet != null && !packet.isCorrupt() && packet.kind() == nx.pingwheel.common.network.InventoryS2CPacket.Kind.REJECT
			&& inventory.selectionResult(packet.commitId()) != null && packet.rejection() == MarkerRejectReason.TARGET_GONE
			&& createRequestTracker.isLatest(CreateRequestTracker.Route.INVENTORY, packet.commitId()))
			errorSink.showLocalError(PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY, PingInteractionAction.TargetGone.TARGET_GONE_COLOR);
	}

	/** Caller supplies already-observed typed values; this method never samples the world. */
	public void dispatchPropertyPing(PingInteractionAction.CreatePing frozenAction,
		List<PresentationPropertyIntent> properties) {
		dispatcher.dispatch(frozenAction, properties);
	}

	/** Keep the legacy name-render facade synchronized before each world frame. */
	public void refreshPresentationDisplay() {
		if (presentation != null) syncPresentationNames();
	}

	private void applyPresentationCreated(MarkerSnapshot snapshot, String ownerName) {
		if (snapshot == null || ownerName == null || markerStore.isAuthoritativelyRemoved(snapshot.id())) return;
		boolean newlySeen = isNewMarkerReceipt(markerStore, snapshot.id());
		List<ClientMarker> superseded = markerStore.onCreated(snapshot, localTick);
		for (ClientMarker marker : superseded) {
			nameStore.onRemoved(marker.id());
			presentation.evict(marker.id());
			if (inventory != null) inventory.markerRemoved(marker.id());
		}
		if (newlySeen) {
			presentationReceiptFeedback.play(snapshot);
			presentationReceiptFeedback.chat(ownerName, snapshot, presentationTargetName(snapshot.id()));
		}
	}

	private Component presentationTargetName(MarkerId id) {
		var value = presentation.view(id).field(ClientPresentation.BASIC, ClientPresentation.NAME);
		if (!(value instanceof PresentationValue.Text text) || Game == null || Game.level == null) {
			return TargetNameComposer.unknown();
		}
		try {
			return ClientTargetNameDecoder.decode(id, new TargetNameJson(text.value()), Game.level.registryAccess());
		} catch (IllegalArgumentException invalid) {
			return TargetNameComposer.unknown();
		}
	}

	private void updatePresentationName(MarkerId id) {
		var value = presentation.view(id).field(ClientPresentation.BASIC, ClientPresentation.NAME);
		if (value instanceof PresentationValue.Text text) {
			try {
				nameStore.onCreated(id, new TargetNameJson(text.value()));
				return;
			} catch (IllegalArgumentException invalid) {
				// A malformed name is never displayed or kept after a policy change.
			}
		}
		nameStore.onRemoved(id);
	}

	private void syncPresentationNames() {
		for (ClientMarker marker : markerStore.allMarkers()) updatePresentationName(marker.id());
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
		Component resolvedTargetName = game == null || game.level == null
			? TargetNameComposer.unknown()
			: ClientTargetNameDecoder.decode(snapshot.id(), targetName, game.level.registryAccess());
		sendCreatedChat(packet.ownerName(), snapshot, resolvedTargetName);
	}

	private void sendCreatedChat(String ownerName, MarkerSnapshot snapshot, Component resolvedTargetName) {
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
				template, ownerName, pingType.get(), resolvedTargetName);
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
		if (inventory != null) inventory.markerRemoved(markerId);

		boolean knownBeforeRemoval = markerStore.marker(markerId).isPresent();
		List<ClientMarker> removed = markerStore.onRemoved(markerId, reason, localTick);
		boolean markerWasRemoved = removed.stream().anyMatch(marker -> marker.id().equals(markerId));

		if (!knownBeforeRemoval && !markerWasRemoved) {
			nameStore.onRemoved(markerId);
		}

		for (ClientMarker removedMarker : removed) {
			nameStore.onRemoved(removedMarker.id());
			if (presentation != null) presentation.evict(removedMarker.id());
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
			if (presentation != null) presentation.evict(marker.id());
			if (inventory != null) inventory.markerRemoved(marker.id());
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
					PingInteractionAction.TargetGone.TARGET_GONE_MESSAGE_KEY,
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
	 * release, or idle phase.
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

	/** Retired renderer's compatibility slot; it cannot commit native selector actions. */
	public WheelSelection wheelSelection() {
		return wheelSelection;
	}

	/** Retained for source compatibility with the inactive legacy renderer, not native input. */
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
