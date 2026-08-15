package nx.pingwheel.common;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.MinecraftLocalErrorSink;
import nx.pingwheel.common.client.marker.MarkerOverlayState;
import nx.pingwheel.common.client.outline.BlockOutlineLogger;
import nx.pingwheel.common.client.outline.BlockOutlineRenderer;
import nx.pingwheel.common.client.outline.BlockOutlineState;
import nx.pingwheel.common.client.outline.EntityOutlineLogger;
import nx.pingwheel.common.client.outline.EntityOutlineState;
import nx.pingwheel.common.compat.LegacyMigrationHandler;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.network.MarkerCreatedS2CPacket;
import nx.pingwheel.common.network.MarkerRejectedS2CPacket;
import nx.pingwheel.common.network.MarkerRemovedS2CPacket;
import nx.pingwheel.common.network.MarkerWinnerChangedS2CPacket;
import nx.pingwheel.common.network.PingLocationS2CPacket;
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

	private CommonClient() {}

	public void onInit() {
		ClientConfig.HANDLER.load();

		IPlatformClientEventService.INSTANCE.registerTickStartEvent(this::onTickStart);
		IPlatformClientEventService.INSTANCE.registerJoinServerEvent(this::onJoinServer);
		IPlatformClientEventService.INSTANCE.registerLeaveServerEvent(this::onLeaveServer);

		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_PING);
		IPlatformContextService.INSTANCE.registerKeyMapping(KEY_BINDING_SETTINGS);

		LegacyMigrationHandler.migrateKeyMappings();

		// The lazy global loggers only ever emit aggregate transition counts.
		EntityOutlineState.setLogger(EntityOutlineLogger.global());
		BlockOutlineState.setLogger(BlockOutlineLogger.global());
	}

	public void onJoinServer() {
		// Fresh per-connection runtime: interaction and marker state never
		// leak across worlds/servers. Created only when a live level/player
		// already exists; otherwise onTickStart creates it lazily once the
		// world is in.
		pingRuntime = createPingRuntimeIfInWorld();

		IPlatformNetworkService.INSTANCE.sendToServer(new UpdateChannelC2SPacket(ClientConfig.HANDLER.getConfig().getChannel()));
	}

	public void onLeaveServer() {
		if (pingRuntime != null) {
			pingRuntime.close();
		}

		pingRuntime = null;
		MarkerOverlayState.INSTANCE.clear();
		EntityOutlineState.INSTANCE.clear();
		BlockOutlineState.INSTANCE.clear();

		// A disconnect while the ping key is still held must not leak the
		// armed hold into the next connection.
		InputUtils.resetPingHold();
	}

	public void onTickStart() {
		Game = Minecraft.getInstance();
		GameContext.updateDimension();

		LegacyMigrationHandler.onTick();

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
		MarkerOverlayState.INSTANCE.prepare(ctx, pingRuntime == null ? null : pingRuntime.store());
		prepareEntityOutlines();
		prepareBlockOutlines();
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
	 * {@link #renderBlockOutlines(Camera, VertexConsumer)}.
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
	 * Draws the prepared block outlines into the {@code RenderType.lines()}
	 * buffer of the current frame.
	 *
	 * <p>Called from {@code LevelRendererMixin} right after the ordinal-0
	 * {@code applyModelViewMatrix} anchor, so the camera-relative model-view
	 * matrix is already applied and the vertices can be camera-relative. The
	 * buffer is never flushed here: vanilla flushes the lines batch later in
	 * {@code renderLevel}.
	 */
	public void renderBlockOutlines(Camera camera, VertexConsumer lineBuffer) {
		Minecraft game = Game;

		if (game == null || game.level == null) {
			return;
		}

		BlockOutlineRenderer.render(game.level, camera, lineBuffer, BlockOutlineState.INSTANCE);
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

		pingRuntime.applyCreated(packet.snapshot());
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
			IPlatformNetworkService.INSTANCE::sendToServer);
	}
}
