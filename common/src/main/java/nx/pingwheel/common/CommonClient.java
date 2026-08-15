package nx.pingwheel.common;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.client.ClientPingRuntime;
import nx.pingwheel.common.client.MinecraftLocalErrorSink;
import nx.pingwheel.common.compat.LegacyMigrationHandler;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.core.PingManager;
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
import nx.pingwheel.common.render.WorldRenderContext;
import nx.pingwheel.common.screen.SettingsScreen;
import nx.pingwheel.common.util.InputUtils;

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
		pingRuntime = null;
		PingManager.clearPings();
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
		PingManager.updatePings(ctx);
	}

	public void onRenderGUI(GuiGraphics guiGraphics, float tickDelta) {
		OverlayRenderer.draw(guiGraphics, tickDelta);
	}

	public void onPingLocationPacket(PingLocationS2CPacket packet) {
		PingManager.acceptPingPacket(packet);
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
