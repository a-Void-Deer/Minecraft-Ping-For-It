package nx.pingwheel.common;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.compat.LegacyMigrationHandler;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.core.PingController;
import nx.pingwheel.common.core.PingManager;
import nx.pingwheel.common.domain.TargetKind;
import nx.pingwheel.common.marker.TargetKey;
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

import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.util.InputUtils.KEY_BINDING_PING;
import static nx.pingwheel.common.util.InputUtils.KEY_BINDING_SETTINGS;

public class CommonClient {

	public static final CommonClient INSTANCE = new CommonClient();
	public static Minecraft Game = null;

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
		IPlatformNetworkService.INSTANCE.sendToServer(new UpdateChannelC2SPacket(ClientConfig.HANDLER.getConfig().getChannel()));
	}

	public void onLeaveServer() {
		PingManager.clearPings();
	}

	public void onTickStart() {
		Game = Minecraft.getInstance();
		GameContext.updateDimension();

		LegacyMigrationHandler.onTick();

		if (InputUtils.consumePingHotkey()) {
			PingController.queuePingAction();
		}

		if (KEY_BINDING_SETTINGS.consumeClick()) {
			Game.setScreen(new SettingsScreen());
		}
	}

	public void onRenderWorld(WorldRenderContext ctx) {
		PingManager.updatePings(ctx);
		PingController.pollPingAction(ctx.tickDelta);
	}

	public void onRenderGUI(GuiGraphics guiGraphics, float tickDelta) {
		OverlayRenderer.draw(guiGraphics, tickDelta);
	}

	public void onPingLocationPacket(PingLocationS2CPacket packet) {
		PingManager.acceptPingPacket(packet);
	}

	/**
	 * Marker S2C handlers (Phase 6).
	 *
	 * <p>The loader network services invoke every handler below on the client
	 * main thread; callers must keep that convention so Phase 7 can build the
	 * client marker store, rendering, UI, and chat on top of these entry
	 * points without threading hazards.
	 *
	 * <p>For now these handlers only emit debug logging with safe fields
	 * (marker/request ids, target kind, catalog type ids, reasons). They never
	 * touch client marker state, the HUD, or chat, and they never log names,
	 * UUIDs, positions, or registry ids.
	 */
	public void onMarkerCreatedPacket(MarkerCreatedS2CPacket packet) {
		if (packet.isCorrupt()) {
			return;
		}

		final var snapshot = packet.snapshot();

		LOGGER.debug(() -> "marker created: markerId=%d kind=%s targetType=%s pingType=%s".formatted(
			snapshot.id().value(),
			snapshot.target().kind(),
			snapshot.targetTypeId(),
			snapshot.pingTypeId()));
	}

	public void onMarkerRemovedPacket(MarkerRemovedS2CPacket packet) {
		if (packet.isCorrupt()) {
			return;
		}

		LOGGER.debug(() -> "marker removed: markerId=%d reason=%s".formatted(packet.markerId().value(), packet.reason()));
	}

	public void onMarkerRejectedPacket(MarkerRejectedS2CPacket packet) {
		if (packet.isCorrupt()) {
			return;
		}

		LOGGER.debug(() -> "marker rejected: requestId=%d requestKind=%s reason=%s".formatted(
			packet.requestId(), packet.requestKind(), packet.reason()));
	}

	public void onMarkerWinnerChangedPacket(MarkerWinnerChangedS2CPacket packet) {
		if (packet.isCorrupt()) {
			return;
		}

		LOGGER.debug(() -> "marker winner changed: kind=%s winner=%s".formatted(
			targetKindOf(packet.targetKey()),
			packet.winnerId().map(id -> Long.toString(id.value())).orElse("none")));
	}

	private static TargetKind targetKindOf(TargetKey targetKey) {
		return switch (targetKey) {
			case TargetKey.EntityKey ignored -> TargetKind.ENTITY;
			case TargetKey.BlockKey ignored -> TargetKind.BLOCK;
			case TargetKey.LocationKey ignored -> TargetKind.LOCATION;
		};
	}
}
