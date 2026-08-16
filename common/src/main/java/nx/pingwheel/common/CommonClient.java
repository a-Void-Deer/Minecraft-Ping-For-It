package nx.pingwheel.common;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.MinecraftLocalErrorSink;
import nx.pingwheel.common.client.rate.ClientRateLimitPolicy;
import nx.pingwheel.common.client.marker.MarkerOverlayState;
import nx.pingwheel.common.client.outline.BlockModelOutlineState;
import nx.pingwheel.common.client.outline.BlockOutlineLogger;
import nx.pingwheel.common.client.outline.BlockOutlineRenderer;
import nx.pingwheel.common.client.outline.BlockOutlineRenderType;
import nx.pingwheel.common.client.outline.BlockOutlineState;
import nx.pingwheel.common.client.outline.EntityOutlineLogger;
import nx.pingwheel.common.client.outline.EntityOutlineState;
import nx.pingwheel.common.client.outline.VirtualBlockDisplayRenderer;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.name.ClientTargetNameDecoder;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
import nx.pingwheel.common.network.RateLimitPolicyS2CPacket;
import nx.pingwheel.common.network.UpdateChannelC2SPacket;
import nx.pingwheel.common.platform.IPlatformClientEventService;
import nx.pingwheel.common.platform.IPlatformContextService;
import nx.pingwheel.common.platform.IPlatformNetworkService;
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
		VirtualBlockDisplayRenderer.INSTANCE.clear();

		// A disconnect while the ping key is still held must not leak the
		// armed hold into the next connection.
		InputUtils.resetPingHold();
	}

	public void onTickStart() {
		Game = Minecraft.getInstance();
		GameContext.updateDimension();

		if (pingRuntime == null) {
			pingRuntime = createPingRuntimeIfInWorld();
		}

		if (pingRuntime != null) {
			var pingPressEdge = InputUtils.consumePingHotkey();
			pingRuntime.onTick(pingPressEdge, InputUtils.isPingHotkeyDown());
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

		// Fresh per-frame success record for the model-outline pass: keys that
		// successfully emit glow geometry this frame are skipped by the late
		// VoxelShape fallback pass.
		BlockModelOutlineState.INSTANCE.beginFrame();
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
	 * Runs the model-outline pass (actual {@code BlockEntity} geometry and
	 * virtual {@code BlockDisplay} glow) immediately before the vanilla
	 * entity-outline {@code endOutlineBatch()} call inside
	 * {@code renderLevel}.
	 *
	 * <p>Each render attempt now draws through its own attempt-local transient
	 * buffer that is flushed immediately on success, so no vanilla outline
	 * buffer source is passed, captured, or written to here and a failed
	 * attempt can never corrupt a vanilla batch. The vanilla entity-outline
	 * buffer, entity glowing routing, and post-chain are untouched.
	 *
	 * <p>The {@code LevelRendererMixin} gate ensures this only runs when the
	 * vanilla entity-outline pipeline is available
	 * ({@code shouldShowEntityOutlines()}); otherwise every block keeps its
	 * VoxelShape fallback. Keys that emit at least one outline vertex are
	 * recorded in {@link BlockModelOutlineState}, which the late
	 * {@link #renderBlockOutlines(Camera, MultiBufferSource.BufferSource)}
	 * pass consults to avoid doubling.
	 */
	public void renderModelOutlines(Camera camera, float partialTick) {
		Minecraft game = Game;

		if (game == null || game.level == null) {
			return;
		}

		VirtualBlockDisplayRenderer.INSTANCE.render(
			game.level, camera, partialTick,
			BlockOutlineState.INSTANCE, BlockModelOutlineState.INSTANCE);
	}

	/**
	 * Whether the model-outline pass emitted at least one vertex this frame;
	 * the mixin AFTER the {@code endOutlineBatch()} call uses this to decide
	 * whether the vanilla entity-outline post-process must run even when no
	 * vanilla entity glowed.
	 */
	public boolean modelOutlinesEmittedThisFrame() {
		return BlockModelOutlineState.INSTANCE.emitted();
	}

	/**
	 * Drops the per-frame model-outline success record; used when the outline
	 * pipeline turned out to be unavailable after all, so every block falls
	 * back to the VoxelShape outline instead of silently disappearing.
	 */
	public void resetModelOutlinesForFrame() {
		BlockModelOutlineState.INSTANCE.beginFrame();
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
	 * {@link BlockModelOutlineState#successKeys()}), so the custom batch is
	 * not even acquired for all-glow frames. The vanilla {@code lines()}
	 * batch is never touched. Blocks whose model-outline pass succeeded
	 * this frame are skipped here.
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
	 * world) is dropped safely. Logging happens inside the runtime and only
	 * ever carries safe fields (marker/request ids, target kind, catalog type
	 * ids, reasons); names, UUIDs, positions, and registry ids are never
	 * logged.
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

		LOGGER.debug("client rate limit policy changed: rateLimit={} msToRegenerate={}",
			nextPolicy.rateLimit(), nextPolicy.msToRegenerate());
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
			storedRateLimitPolicy);
	}
}
