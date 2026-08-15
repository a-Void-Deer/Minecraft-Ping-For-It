package nx.pingwheel.common.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.ClientMarkerStore;
import nx.pingwheel.common.client.marker.EntityMarkerPoint;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.domain.MarkerId;
import nx.pingwheel.common.domain.PingType;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.interaction.ActiveInteraction;
import nx.pingwheel.common.interaction.InteractionToken;
import nx.pingwheel.common.interaction.MinecraftTargetSnapshotFactory;
import nx.pingwheel.common.interaction.PingCaptureCoordinator;
import nx.pingwheel.common.interaction.PingCaptureLogger;
import nx.pingwheel.common.interaction.TargetSnapshotFactory;
import nx.pingwheel.common.interaction.cancel.CancelCandidatePicker;
import nx.pingwheel.common.interaction.cancel.CancelMarkerCandidate;
import nx.pingwheel.common.interaction.cancel.CancellationContext;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionAction;
import nx.pingwheel.common.interaction.state.PingInteractionLogger;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.interaction.state.PingInteractionStateMachine;
import nx.pingwheel.common.interaction.wheel.WheelSelection;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.marker.MarkerAnchor;
import nx.pingwheel.common.marker.MarkerRejectReason;
import nx.pingwheel.common.marker.MarkerRemovalReason;
import nx.pingwheel.common.marker.MarkerRequestKind;
import nx.pingwheel.common.marker.MarkerSnapshot;
import nx.pingwheel.common.marker.TargetKey;
import nx.pingwheel.common.math.Raycast;
import nx.pingwheel.common.resolve.DefaultTargetResolver;
import nx.pingwheel.common.resolve.TargetResolutionLogger;
import nx.pingwheel.common.util.DirectionalSoundInstance;

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
 * <p>{@link #onTick(boolean, boolean)} is called every client tick from the
 * game thread: it consumes the press edge, captures the target immediately at
 * key-down (never on release or wheel movement), advances the machine with the
 * current wheel selection and a cancellation context built from live local
 * state, dispatches the returned action at most once, and runs the store's
 * fallback expiry using a monotonic local tick counter. One fresh runtime is
 * created per world join and dropped on leave (see {@code CommonClient}), so
 * interaction and marker state can never leak across connections.
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

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	private final ClientMarkerStore markerStore;
	private final ActiveInteraction activeInteraction;
	private final PingCaptureCoordinator captureCoordinator;
	private final PingInteractionStateMachine machine;
	private final ClientPingActionDispatcher dispatcher;
	private final ClientPingActionDispatcher.LocalErrorSink errorSink;
	private final PingInteractionLogger logger;
	private final WheelMouseCapture wheelMouseCapture;

	/**
	 * The latest dispatched create request id; a single slot, never a growing
	 * set of rejected ids.
	 */
	private final CreateRequestTracker createRequestTracker = new CreateRequestTracker();

	private long localTick;
	private WheelSelection wheelSelection = WheelSelection.NONE;

	private ClientPingRuntime(
		ClientMarkerStore markerStore,
		ActiveInteraction activeInteraction,
		PingCaptureCoordinator captureCoordinator,
		PingInteractionStateMachine machine,
		ClientPingActionDispatcher dispatcher,
		ClientPingActionDispatcher.LocalErrorSink errorSink,
		PingInteractionLogger logger,
		WheelMouseCapture wheelMouseCapture
	) {
		this.markerStore = Objects.requireNonNull(markerStore, "markerStore");
		this.activeInteraction = Objects.requireNonNull(activeInteraction, "activeInteraction");
		this.captureCoordinator = Objects.requireNonNull(captureCoordinator, "captureCoordinator");
		this.machine = Objects.requireNonNull(machine, "machine");
		this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
		this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
		this.logger = Objects.requireNonNull(logger, "logger");
		this.wheelMouseCapture = Objects.requireNonNull(wheelMouseCapture, "wheelMouseCapture");
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
		Objects.requireNonNull(errorSink, "errorSink");
		Objects.requireNonNull(packetSender, "packetSender");

		ActiveInteraction activeInteraction = new ActiveInteraction();
		PingInteractionLogger logger = PingInteractionLogger.global();
		PingCaptureCoordinator coordinator = new PingCaptureCoordinator(
			DefaultTargetResolver.builtIn(TargetResolutionLogger.global()),
			activeInteraction,
			PingCaptureLogger.global());
		PingInteractionStateMachine machine = new PingInteractionStateMachine(
			coordinator,
			activeInteraction,
			InteractionTimeSource.system(),
			new MinecraftClientTargetValidator(),
			new CancelCandidatePicker(),
			logger);

		return new ClientPingRuntime(
			new ClientMarkerStore(FALLBACK_EXPIRY_GRACE_TICKS),
			activeInteraction,
			coordinator,
			machine,
			new ClientPingActionDispatcher(packetSender, errorSink, logger),
			errorSink,
			logger,
			new WheelMouseCapture(logger));
	}

	/**
	 * Advances the runtime by one client tick.
	 *
	 * <p>Does nothing while no valid level/player is available. On the press
	 * edge the machine is pressed and the target is captured immediately
	 * (synchronously on the game thread, or asynchronously for a Distant
	 * Horizons miss). Then the machine is updated with the current key state,
	 * the wheel selection queued by the latest GUI frame, and a live
	 * cancellation context, and the returned action is dispatched at most
	 * once. Finally the wheel mouse capture is synced with whatever phase the
	 * machine ended in, and the marker store's fallback expiry runs against
	 * the incremented local tick.
	 *
	 * <p>The queued wheel selection is consumed exactly once per tick and the
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
	public void onTick(boolean pressEdge, boolean keyDown) {
		Minecraft game = Game;

		if (game == null || game.level == null || game.player == null) {
			return;
		}

		localTick++;

		if (pressEdge) {
			InteractionToken token = machine.press();
			captureImmediately(token);
		}

		WheelSelection consumedSelection = wheelSelection;
		wheelSelection = WheelSelection.NONE;

		if (machine.phase() != PingInteractionPhase.IDLE) {
			Optional<PingInteractionAction> action = machine.update(keyDown, consumedSelection, buildCancellationContext());

			if (action.isPresent()) {
				PingInteractionAction dispatched = action.get();

				// Record the create request id before the dispatch so an
				// authoritative TARGET_GONE rejection can be matched against the
				// latest create request only.
				if (dispatched instanceof PingInteractionAction.CreatePing create) {
					createRequestTracker.onCreateDispatched(create.context().token().sequence());
				}

				dispatcher.dispatch(dispatched);
			}
		}

		// Keep the mouse capture in sync with whatever phase the update left
		// behind: open (release), or timeout/commit/cancel/stale (re-grab).
		wheelMouseCapture.sync(machine.phase(), game);

		expireFallbackMarkers();
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
		wheelMouseCapture.close(Game);
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
	 * asynchronous and may upgrade the miss to a distant block hit later.
	 */
	private void captureImmediately(InteractionToken token) {
		Minecraft game = Game;

		if (game == null || game.cameraEntity == null || game.level == null) {
			return;
		}

		ClientLevel level = game.level;
		var cameraEntity = game.cameraEntity;
		var cameraDirection = cameraEntity.getViewVector(1.0f);
		var distance = Math.min(
			CLIENT_CONFIG.getRaycastDistance(),
			CLIENT_CONFIG.getPingDistance());

		var hitResult = Raycast.traceDirectional(
			cameraDirection, 1.0f, distance, cameraEntity.isCrouching());

		if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
			HitResult missHit = hitResult;

			if (missHit == null) {
				var missPoint = cameraEntity.getEyePosition(1.0f).add(cameraDirection.scale(distance));
				missHit = BlockHitResult.miss(missPoint, Direction.UP, BlockPos.containing(missPoint));
			}

			if (ModContext.HasDistantHorizons) {
				// The Distant Horizons callback runs on its completion thread:
				// it only carries the client and level captured at press time
				// and schedules onto the client's main thread, never reading
				// CommonClient.Game from the pool thread.
				Raycast.traceDistantAsync(cameraDirection, 1.0f,
					(distantHitResult) -> completeDistantHit(game, level, token, distantHitResult));
			} else {
				captureCoordinator.complete(token, MinecraftTargetSnapshotFactory.from(level, missHit));
			}

			return;
		}

		if (ModContext.HasSable && hitResult.getType() == HitResult.Type.BLOCK) {
			var hitPosition = hitResult.getLocation();
			var subLevelAccess = SableCompanion.INSTANCE.getContainingClient(hitPosition);

			// Preserve the Sable behavior: a block hit inside a sub-level is
			// projected back out into the real level and captured as a pure
			// location target.
			if (subLevelAccess != null) {
				var projected = SableCompanion.INSTANCE.projectOutOfSubLevel(
					game.level, (Position) hitPosition);
				captureCoordinator.complete(token, TargetSnapshotFactory.location(
					game.level.dimension().location().toString(),
					projected.x, projected.y, projected.z));
				return;
			}
		}

		captureCoordinator.complete(
			token, MinecraftTargetSnapshotFactory.from(game.level, hitResult));
	}

	/**
	 * Applies an asynchronous Distant Horizons hit on the captured client's
	 * main thread.
	 *
	 * <p>This callback runs on the Distant Horizons completion thread and must
	 * never touch {@link nx.pingwheel.common.CommonClient.Game}: the
	 * {@link Minecraft} client and the {@link ClientLevel} captured at press
	 * time are the only live references it carries. The snapshot is taken
	 * inside {@link Minecraft#execute} (the client main thread) only when the
	 * current level is still exactly the captured level and the token is still
	 * the active interaction.
	 */
	private void completeDistantHit(Minecraft game, ClientLevel levelAtPress, InteractionToken token, HitResult distantHitResult) {
		game.execute(() -> {
			if (game.level == null || game.level != levelAtPress || !activeInteraction.isCurrent(token)) {
				return;
			}

			captureCoordinator.complete(
				token, MinecraftTargetSnapshotFactory.from(levelAtPress, distantHitResult));
		});
	}

	/**
	 * Builds the cancellation context from live local state: the local owner
	 * UUID, the current dimension, the eye position and look direction, and
	 * every marker owned by the local player in the current dimension.
	 *
	 * <p>A candidate's position is the live entity position when the marker's
	 * entity target is still resolvable in the same dimension, otherwise the
	 * authoritative marker anchor.
	 */
	private CancellationContext buildCancellationContext() {
		Minecraft game = Game;
		var player = game.player;
		var level = game.level;

		UUID ownerId = player.getUUID();
		String dimensionId = level.dimension().location().toString();
		var eyePosition = player.getEyePosition();
		var lookDirection = player.getViewVector(1.0f);

		List<CancelMarkerCandidate> candidates = markerStore
			.markersOwnedInDimension(dimensionId, ownerId)
			.stream()
			.map(marker -> new CancelMarkerCandidate(
				marker.id(),
				ownerId,
				dimensionId,
				candidatePosition(marker)))
			.toList();

		return new CancellationContext(
			ownerId,
			dimensionId,
			new WorldVector(eyePosition.x, eyePosition.y, eyePosition.z),
			new WorldVector(lookDirection.x, lookDirection.y, lookDirection.z),
			candidates);
	}

	private WorldVector candidatePosition(ClientMarker marker) {
		Target target = marker.target();

		if (target instanceof Target.EntityTarget entityTarget) {
			Entity entity = GameContext.getEntity(entityTarget.entityId());

			// GameContext only searches the current level, so a found entity is
			// already in the marker's dimension.
			if (entity != null && !entity.isRemoved()) {
				// Same top-center geometry the marker outline renders, using the
				// current-tick position (partialTick 1.0F); the renderer may
				// additionally sub-tick interpolate between positions.
				var topCenter = EntityMarkerPoint.forLiveEntity(entity, 1.0F);
				return new WorldVector(topCenter.x, topCenter.y, topCenter.z);
			}
		}

		var anchor = marker.anchor();
		return new WorldVector(anchor.x(), anchor.y(), anchor.z());
	}

	private void expireFallbackMarkers() {
		List<ClientMarker> expired = markerStore.expireFallback(localTick);

		if (expired.isEmpty()) {
			return;
		}

		logger.debug("marker fallback expired: count={} ids={}",
			expired.size(),
			expired.stream().map(marker -> Long.toString(marker.id().value())).toList());
	}

	/**
	 * Applies an authoritative created-marker snapshot on the main thread as of
	 * the runtime's current local tick.
	 *
	 * <p>The existing directional ping sound plays exactly once per newly seen
	 * marker id whose target lives in the current dimension; a retransmission
	 * or same-id replacement of an already known marker never replays it.
	 */
	public void applyCreated(MarkerSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");

		boolean newlySeen = markerStore.marker(snapshot.id()).isEmpty();

		markerStore.onCreated(snapshot, localTick);

		if (newlySeen) {
			playCreatedSoundOnce(snapshot);
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
			CLIENT_CONFIG.getPingVolume() / 100f,
			1f,
			new Vec3(anchor.x(), anchor.y(), anchor.z())));
	}

	/**
	 * Applies an authoritative marker removal on the main thread.
	 */
	public void applyRemoved(MarkerId markerId, MarkerRemovalReason reason) {
		Objects.requireNonNull(markerId, "markerId");
		Objects.requireNonNull(reason, "reason");

		markerStore.onRemoved(markerId);

		logger.debug("marker removed applied: markerId={} reason={}", markerId.value(), reason);
	}

	/**
	 * Applies an authoritative same-target winner change on the main thread.
	 */
	public void applyWinnerChanged(TargetKey targetKey, Optional<MarkerId> winnerId) {
		Objects.requireNonNull(targetKey, "targetKey");
		Objects.requireNonNull(winnerId, "winnerId");

		markerStore.onWinnerChanged(targetKey, winnerId);

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
	 * {@link #onTick(boolean, boolean)} call.
	 */
	public void setWheelSelection(WheelSelection selection) {
		this.wheelSelection = Objects.requireNonNull(selection, "selection");
	}

	private static TargetKind targetKindOf(TargetKey targetKey) {
		return switch (targetKey) {
			case TargetKey.EntityKey ignored -> TargetKind.ENTITY;
			case TargetKey.BlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.LocationKey ignored -> TargetKind.LOCATION;
		};
	}
}
