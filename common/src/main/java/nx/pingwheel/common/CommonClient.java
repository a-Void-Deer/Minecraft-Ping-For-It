package nx.pingwheel.common;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.world.entity.Entity;

import java.util.Map;

import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.MinecraftLocalErrorSink;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.marker.MarkerOverlayState;
import nx.pingwheel.common.client.outline.BlockModelOutlineState;
import nx.pingwheel.common.client.outline.BlockOutlineLogger;
import nx.pingwheel.common.client.outline.BlockOutlineRenderer;
import nx.pingwheel.common.client.outline.BlockOutlineRenderType;
import nx.pingwheel.common.client.outline.BlockOutlineState;
import nx.pingwheel.common.client.outline.EntityBlockGeometryOutcome;
import nx.pingwheel.common.client.outline.EntityOutlineContext;
import nx.pingwheel.common.client.outline.EntityOutlineFrameState;
import nx.pingwheel.common.client.outline.EntityOutlineLocatorResolver;
import nx.pingwheel.common.client.outline.EntityOutlineLogger;
import nx.pingwheel.common.client.outline.EntityOutlineRunner;
import nx.pingwheel.common.client.outline.EntityOutlineSourceRegistry;
import nx.pingwheel.common.client.outline.EntityOutlineSpec;
import nx.pingwheel.common.client.outline.EntityOutlineState;
import nx.pingwheel.common.client.outline.LevelRendererOutlineRequest;
import nx.pingwheel.common.client.outline.VirtualBlockDisplayRenderer;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.interaction.MinecraftEntityTargetAdapter;
import nx.pingwheel.common.name.ClientTargetNameDecoder;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.ServerConfigSnapshotS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformClientEventService;
import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.platform.IPlatformNetworkService;
import nx.pingwheel.common.interaction.state.InteractionTimeSource;
import nx.pingwheel.common.interaction.state.PingInteractionPhase;
import nx.pingwheel.common.render.OverlayRenderer;
import nx.pingwheel.common.render.WheelOverlayRenderer;
import nx.pingwheel.common.render.WorldRenderContext;
import nx.pingwheel.common.screen.SettingsScreen;
import nx.pingwheel.common.util.InputUtils;

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.util.InputUtils.KEY_BINDING_PING;
import static nx.pingwheel.common.util.InputUtils.KEY_BINDING_SETTINGS;

public class CommonClient {

	public static final CommonClient INSTANCE = new CommonClient();
	public static Minecraft Game = null;

	private static ClientPingRuntime pingRuntime;
	private static ClientRateLimitPolicy storedRateLimitPolicy = ClientRateLimitPolicy.DEFAULT;
	private static final InteractionTimeSource INTERACTION_TIME_SOURCE = InteractionTimeSource.system();

	/** Runs the registered entity-outline sources over the production registry. */
	private static final EntityOutlineRunner ENTITY_OUTLINE_RUNNER =
		new EntityOutlineRunner(EntityOutlineSourceRegistry.INSTANCE);

	private CommonClient() {}

	public void onInit() {
		ClientConfig.HANDLER.load();

		IPlatformClientEventService.INSTANCE.registerTickStartEvent(this::onTickStart);
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(this::onJoinServer);
		IPlatformClientEventService.INSTANCE.registerLeaveServerEvent(this::onLeaveServer);

		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_PING);
		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_SETTINGS);

		// The lazy global loggers only ever emit aggregate transition counts.
		EntityOutlineState.setLogger(EntityOutlineLogger.global());
		BlockOutlineState.setLogger(BlockOutlineLogger.global());
		ClientTargetNameDecoder.setLogger(ClientTargetNameDecoder.Logger.global());
	}

	public void onJoinServer() {
		// Fresh per-connection runtime: interaction and marker state never
		// leak across worlds/servers. Created only when a live level/player
		// already exists; otherwise onTickStart creates it lazily once the
		// world is in.
		storedRateLimitPolicy = ClientRateLimitPolicy.DEFAULT;
		pingRuntime = createPingRuntimeIfInWorld();

		IPlatformNetworkService.INSTANCE.sendToServer(new UpdateChannelC2SPacket(ClientConfig.HANDLER.getConfig().getChannel()));
	}

	public void onLeaveServer() {
		if (pingRuntime != null) {
			pingRuntime.close();
		}

		pingRuntime = null;
		storedRateLimitPolicy = ClientRateLimitPolicy.DEFAULT;
		MarkerOverlayState.INSTANCE.clear();
		EntityOutlineState.INSTANCE.clear();
		BlockOutlineState.INSTANCE.clear();
		BlockModelOutlineState.INSTANCE.clear();
		EntityOutlineFrameState.INSTANCE.clear();
		VirtualBlockDisplayRenderer.INSTANCE.clear();

		// A disconnect while the ping key is still held must not leak the
		// armed hold into the next connection.
		InputUtils.resetPingHold();

		SettingsScreen.notifyServerDisconnected();
	}

	/**
	 * Receives the post-{@code KeyMapping.click} raw press edge. Arbitration is
	 * resolved here while the mapping counters still represent this physical
	 * event; a claimed edge is captured and minted immediately on the client
	 * thread.
	 */
	public void onKeyMappingClick(InputConstants.Key rawKey) {
		// Sample before any mapping arbitration or world lookup.  This is the
		// physical event timestamp, not the later tick/frame time.
		long eventTimeMillis = INTERACTION_TIME_SOURCE.nowMillis();
		Game = Minecraft.getInstance();

		if (!InputUtils.claimPingClick(rawKey)) {
			return;
		}

		if (Game.screen != null) {
			abortInteractionIfActive();
			return;
		}

		if (pingRuntime == null) {
			pingRuntime = createPingRuntimeIfInWorld();
		}

		if (pingRuntime != null) {
			pingRuntime.onPress(eventTimeMillis);
			if (Game.level == null || Game.player == null) {
				// The runtime may have become invalid between arbitration and the
				// client-thread capture. Do not carry the claimed raw key into a
				// later world.
				InputUtils.resetPingHold();
			}
		} else {
			// A click in a menu must never arm an interaction that can leak into
			// a later world.
			InputUtils.resetPingHold();
		}
	}

	/** Receives a post-{@code KeyMapping.set} raw release edge. */
	public void onKeyMappingState(InputConstants.Key rawKey, boolean isDown) {
		Game = Minecraft.getInstance();

		if (!InputUtils.observeKeyState(rawKey, isDown)) {
			return;
		}

		if (pingRuntime != null) {
			pingRuntime.onRelease();
		}
	}

	/**
	 * Aborts the current interaction when Minecraft clears all key mappings,
	 * such as on focus loss. No default ping is produced.
	 */
	public void onInputReset() {
		abortInteractionIfActive();
	}

	/**
	 * Aborts an interaction at the screen transition boundary.  The identity
	 * check keeps repeated calls for the same screen cheap while still handling
	 * both opening and closing a menu before a swallowed release can arrive.
	 */
	public void onScreenChanged(Screen nextScreen) {
		Game = Minecraft.getInstance();

		if (Game.screen == nextScreen) {
			return;
		}

		abortInteractionIfActive();
	}

	private void abortInteractionIfActive() {
		boolean claimedInput = InputUtils.isPingHotkeyDown();
		boolean activeInteraction = pingRuntime != null
			&& pingRuntime.phase() != PingInteractionPhase.IDLE;
		boolean compatibilityState = pingRuntime != null && pingRuntime.hasCompatibilityState();

		InputUtils.resetPingHold();
		if (activeInteraction || claimedInput || compatibilityState) {
			if (pingRuntime != null) {
				pingRuntime.abort();
			}
		}
	}

	public void onTickStart() {
		Game = Minecraft.getInstance();
		GameContext.updateDimension();

		if (pingRuntime == null) {
			pingRuntime = createPingRuntimeIfInWorld();
		}

		if (pingRuntime != null) {
			pingRuntime.onTick();
		}

		if (Game.level == null || Game.player == null) {
			// onTick() aborts the runtime interaction when the world disappears;
			// this also clears the raw claim so a later join cannot inherit it.
			InputUtils.resetPingHold();
		}

		if (KEY_BINDING_SETTINGS.consumeClick()) {
			Game.setScreen(new SettingsScreen());
		}
	}

	public void onRenderWorld(WorldRenderContext ctx) {
		MarkerOverlayState.INSTANCE.prepare(
			ctx,
			pingRuntime == null ? null : pingRuntime.store(),
			pingRuntime == null ? null : pingRuntime.nameStore());
		prepareEntityOutlines();
		prepareBlockOutlines();

		// Fresh per-frame bookkeeping must precede the reflection probe. The
		// probe runs after both entity and block snapshots are prepared so a
		// block-only frame can establish the shared outline pipeline too.
		EntityOutlineFrameState.INSTANCE.beginFrame();
		BlockModelOutlineState.INSTANCE.beginFrame();
		requestEntityOutlineEffect();
	}

	/**
	 * Synchronizes the entity outline state for this render frame from the
	 * current runtime store and level dimension; clears it when the runtime or
	 * level is absent. Runs on every world render frame, before the entity
	 * pass of {@link net.minecraft.client.renderer.LevelRenderer#renderLevel}.
	 */
	private static void prepareEntityOutlines() {
		Minecraft game = Game;

		if (pingRuntime == null || game == null || game.level == null) {
			EntityOutlineState.INSTANCE.clear();
			return;
		}

		EntityOutlineState.INSTANCE.prepare(
			pingRuntime.store(), game.level.dimension().location().toString());
	}

	/**
	 * Synchronizes the block outline state for this render frame from the
	 * current runtime store and level dimension; clears it when the runtime or
	 * level is absent. Runs on every world render frame, right after
	 * {@link #prepareEntityOutlines()} and before
	 * {@link #renderBlockOutlines(Camera, MultiBufferSource.BufferSource)}.
	 */
	private static void prepareBlockOutlines() {
		Minecraft game = Game;

		if (pingRuntime == null || game == null || game.level == null) {
			BlockOutlineState.INSTANCE.clear();
			return;
		}

		BlockOutlineState.INSTANCE.prepare(
			pingRuntime.store(), game.level.dimension().location().toString());
	}

	/**
	 * Invokes the cached reflection {@code requestOutlineEffect()} probe on the
	 * current {@link LevelRenderer} once per frame, only while live entity
	 * entity or block outlines exist. A missing method (the normal vanilla 1.21.1 case) records
	 * {@code false}; a successful invocation records {@code true} on the frame
	 * state so the model and entity-outline passes in {@code LevelRendererMixin}
	 * can run even when vanilla's {@code shouldShowEntityOutlines()} gate is off.
	 */
	private static void requestEntityOutlineEffect() {
		Minecraft game = Game;

		if (game == null || game.levelRenderer == null
			|| (!EntityOutlineState.INSTANCE.hasOutlines()
				&& !BlockOutlineState.INSTANCE.hasOutlines())) {
			EntityOutlineFrameState.INSTANCE.markRequestSucceeded(false);
			return;
		}

		EntityOutlineFrameState.INSTANCE.markRequestSucceeded(
			LevelRendererOutlineRequest.request(game.levelRenderer));
	}

	/**
	 * Runs the model-outline pass (actual {@code BlockEntity} geometry and
	 * virtual {@code BlockDisplay} glow) immediately before the vanilla
	 * entity-outline {@code endOutlineBatch()} call inside
	 * {@code renderLevel}.
	 *
	 * <p>Built-in attempts still draw through their own attempt-local transient
	 * buffers. The optional Create/Flywheel source first encodes a complete
	 * immutable attempt-local mask, then commits its texture batches to the
	 * vanilla outline buffer without flushing it; vanilla's existing
	 * {@code endOutlineBatch()} call remains the sole flush point. A failure
	 * before that commit cannot leave a partial shared-buffer mask.
	 *
	 * <p>The {@code LevelRendererMixin} gate ensures this only runs when the
	 * vanilla entity-outline pipeline is available
	 * ({@code shouldShowEntityOutlines()}); otherwise every block keeps its
	 * VoxelShape fallback. Keys that emit at least one outline vertex are
	 * recorded in {@link BlockModelOutlineState}, which the late
	 * {@link #renderBlockOutlines(Camera, MultiBufferSource.BufferSource)}
	 * pass consults to avoid doubling.
	 */
	public void renderModelOutlines(
		Camera camera,
		float builtInPartialTick,
		float flywheelPartialTick
	) {
		Minecraft game = Game;

		if (game == null || game.level == null) {
			return;
		}

		VirtualBlockDisplayRenderer.INSTANCE.render(
			game.level, camera, builtInPartialTick, flywheelPartialTick,
			BlockOutlineState.INSTANCE, BlockModelOutlineState.INSTANCE);
	}

	/**
	 * Runs the registered entity-outline sources for every live selected
	 * entity, immediately before the vanilla {@code OutlineBufferSource
	 * endOutlineBatch()} call, writing silhouette geometry into the shared
	 * vanilla outline buffer (flushed by that same vanilla call).
	 *
	 * <p>Each entity locator is resolved to its canonical live entity
	 * ({@link EntityOutlineLocatorResolver}); unresolved or mismatched locators
	 * are skipped. Every resolved entity gets one fresh
	 * {@link EntityOutlineContext} carrying the selected
	 * {@link EntityOutlineSpec}, the camera position, the frame partial tick,
	 * the current frame id, and the shared outline buffer, then the runner
	 * iterates the registry in order. If any source reports
	 * {@link EntityBlockGeometryOutcome#RENDERED}, the frame state is marked
	 * emitted so the combined post-process handoff runs the entity-outline
	 * effect.
	 */
	public void renderEntityOutlines(Camera camera, float partialTick, OutlineBufferSource outlineBuffer) {
		Minecraft game = Game;

		if (game == null || game.level == null || outlineBuffer == null
			|| !EntityOutlineState.INSTANCE.hasOutlines()) {
			return;
		}

		boolean emitted = false;

		for (Map.Entry<EntityLocator, EntityOutlineSpec> entry
			: EntityOutlineState.INSTANCE.snapshot().entrySet()) {
			Entity entity = EntityOutlineLocatorResolver.resolve(entry.getKey(), GameContext::getEntity);

			if (entity == null) {
				continue;
			}

			EntityOutlineContext context = new EntityOutlineContext(
				game.level,
				entity,
				entry.getValue(),
				camera.getPosition(),
				partialTick,
				EntityOutlineFrameState.INSTANCE.frameId(),
				outlineBuffer);

			if (ENTITY_OUTLINE_RUNNER.run(context) == EntityBlockGeometryOutcome.RENDERED) {
				emitted = true;
			}
		}

		if (emitted) {
			EntityOutlineFrameState.INSTANCE.markEmitted();
		}
	}

	/**
	 * Whether the cached reflection {@code requestOutlineEffect()} probe
	 * succeeded this frame. The {@code LevelRendererMixin} entity-source gate
	 * combines this with vanilla's {@code shouldShowEntityOutlines()} so the
	 * entity-outline sources run when either path is live.
	 */
	public boolean entityOutlineRequestSucceededThisFrame() {
		return EntityOutlineFrameState.INSTANCE.requestSucceeded();
	}

	/**
	 * Whether the model-outline pass and/or the entity-outline sources emitted
	 * at least one vertex this frame; the mixin AFTER the
	 * {@code endOutlineBatch()} call uses this to decide whether the vanilla
	 * entity-outline post-process must run even when no vanilla entity glowed.
	 */
	public boolean modelOutlinesEmittedThisFrame() {
		return BlockModelOutlineState.INSTANCE.emitted()
			|| EntityOutlineFrameState.INSTANCE.emitted();
	}

	/**
	 * Drops the per-frame outline success records (model and entity); used
	 * when the outline pipeline turned out to be unavailable after all, so
	 * every block falls back to the VoxelShape outline instead of silently
	 * disappearing.
	 */
	public void resetModelOutlinesForFrame() {
		BlockModelOutlineState.INSTANCE.beginFrame();
		EntityOutlineFrameState.INSTANCE.beginFrame();
	}

	/**
	 * Draws the prepared block outlines into the custom
	 * {@link BlockOutlineRenderType#BLOCK_OUTLINE} buffer of the current
	 * frame and flushes that batch explicitly.
	 *
	 * <p>Called from {@code LevelRendererMixin} at the end of
	 * {@code renderLevel}, right before the world model-view matrix is
	 * popped (after all 3D batches and composites have been flushed), so
	 * the camera-relative model-view matrix is still applied and the
	 * vertices can be camera-relative. The batch is acquired only when
	 * {@link BlockOutlineState#hasOutlines()} is true, so a frame without
	 * block outlines never creates or flushes an empty batch; and the frame
	 * is skipped entirely when every current block outline is already
	 * covered by the model-outline success set (see
	 * {@link BlockModelOutlineState#successKeys()}). The vanilla
	 * {@code lines()} batch is never touched. Blocks whose model-outline pass
	 * succeeded suppress only their VoxelShape geometry.
	 */
	public void renderBlockOutlines(Camera camera, MultiBufferSource.BufferSource bufferSource) {
		Minecraft game = Game;

		if (game == null || game.level == null) {
			return;
		}

		if (!BlockOutlineState.INSTANCE.hasOutlines()) {
			return;
		}

		if (BlockOutlineState.INSTANCE.allCoveredBy(BlockModelOutlineState.INSTANCE.successKeys())) {
			return;
		}

		VertexConsumer lines = bufferSource.getBuffer(BlockOutlineRenderType.BLOCK_OUTLINE);
		BlockOutlineRenderer.render(
			game.level, camera, lines,
			BlockOutlineState.INSTANCE, BlockModelOutlineState.INSTANCE.successKeys());
		bufferSource.endBatch(BlockOutlineRenderType.BLOCK_OUTLINE);
	}

	public void onRenderGUI(GuiGraphics guiGraphics, float tickDelta) {
		OverlayRenderer.draw(guiGraphics, tickDelta);
		WheelOverlayRenderer.draw(guiGraphics, tickDelta);
	}

	/** Advances interaction timing once from the GameRenderer frame boundary. */
	public void onRenderFrame() {
		Game = Minecraft.getInstance();

		if (pingRuntime != null) {
			pingRuntime.onRenderFrame(InputUtils.isPingHotkeyDown());
		}

		if (Game.level == null || Game.player == null) {
			InputUtils.resetPingHold();
		}
	}

	/**
	 * The nullable current ping runtime: present only while in a live
	 * world/connection. The wheel renderer uses this getter so a missing
	 * runtime can never crash the GUI pass.
	 */
	public ClientPingRuntime getPingRuntime() {
		return pingRuntime;
	}

	/**
	 * Legacy S2C ping location handler.
	 *
	 * <p>Ping locations of the original protocol are no longer rendered: all
	 * ping state now arrives as authoritative marker packets (Phase 7). The
	 * handler stays registered so legacy servers keep receiving the channel
	 * handshake, but valid legacy packets are ignored without touching any
	 * client state; corrupt packets are warned about exactly like before.
	 */
	public void onPingLocationPacket(PingLocationS2CPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.warn("received invalid ping location from server");
			return;
		}

		LOGGER.debug("ignoring legacy ping location packet");
	}

	/**
	 * Marker S2C handlers (Phase 6/7).
	 *
	 * <p>The loader network services invoke every handler below on the client
	 * main thread, so the phase-7 {@link ClientPingRuntime} applies each
	 * authoritative mutation directly to its main-thread-confined marker
	 * store. A corrupt packet or a runtime that does not exist (not in a
	 * world) is dropped safely. Detailed optional-render diagnostics may carry
	 * complete target, component, payload, and exception data under their own
	 * rate control.
	 */
	public void onMarkerCreatedPacket(MarkerCreatedS2CPacket packet) {
		if (packet.isCorrupt() || pingRuntime == null) {
			return;
		}

		pingRuntime.applyCreated(packet);
	}

	public void onMarkerRemovedPacket(MarkerRemovedS2CPacket packet) {
		if (packet.isCorrupt() || pingRuntime == null) {
			return;
		}

		pingRuntime.applyRemoved(packet.markerId(), packet.reason());
	}

	public void onMarkerRejectedPacket(MarkerRejectedS2CPacket packet) {
		if (packet.isCorrupt() || pingRuntime == null) {
			return;
		}

		pingRuntime.handleRejected(packet.requestId(), packet.requestKind(), packet.reason());
	}

	public void onMarkerWinnerChangedPacket(MarkerWinnerChangedS2CPacket packet) {
		if (packet.isCorrupt() || pingRuntime == null) {
			return;
		}

		pingRuntime.applyWinnerChanged(packet.targetKey(), packet.winnerId());
	}

	/**
	 * Applies the server-authoritative client create policy. The loader network
	 * adapters invoke this method on the client thread. The stored value is
	 * updated even while no runtime exists so a lazily-created runtime starts
	 * with the latest policy received for this connection.
	 */
	public void onRateLimitPolicyPacket(RateLimitPolicyS2CPacket packet) {
		if (packet.isCorrupt()) {
			LOGGER.warn("received invalid rate limit policy from server");
			return;
		}

		ClientRateLimitPolicy nextPolicy = new ClientRateLimitPolicy(
			packet.rateLimit(), packet.msToRegenerate());

		if (storedRateLimitPolicy.equals(nextPolicy)) {
			return;
		}

		storedRateLimitPolicy = nextPolicy;

		if (pingRuntime != null) {
			pingRuntime.applyRateLimitPolicy(nextPolicy);
		}

		LOGGER.debug("client rate limit policy changed");
	}

	/**
	 * Routes server settings to the latest live settings session. No snapshot is
	 * retained globally, so a later connection cannot inherit a previous
	 * server's configuration; the session callback also works under a
	 * confirmation screen.
	 */
	public void onServerConfigSnapshotPacket(ServerConfigSnapshotS2CPacket packet) {
		if (packet.isCorrupt()) {
			return;
		}

		Game = Minecraft.getInstance();
		SettingsScreen.notifyServerConfigSnapshot(packet.requestId(), packet.snapshot());
	}

	/**
	 * Creates a fresh runtime only when a live level and player exist. Returns
	 * {@code null} otherwise so a menu state (including the post-leave main
	 * menu) never creates or recreates a runtime.
	 */
	private static ClientPingRuntime createPingRuntimeIfInWorld() {
		if (Game == null || Game.level == null || Game.player == null) {
			return null;
		}

		return createPingRuntime();
	}

	private static ClientPingRuntime createPingRuntime() {
		return ClientPingRuntime.create(
			new MinecraftLocalErrorSink(),
			IPlatformNetworkService.INSTANCE::sendToServer,
			storedRateLimitPolicy,
			INTERACTION_TIME_SOURCE);
	}
}
