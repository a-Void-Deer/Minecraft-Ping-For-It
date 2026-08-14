package nx.pingwheel.common.core;

import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.core.Position;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.math.Raycast;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;

import java.util.UUID;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.config.ClientConfig.MAX_CORRECTION_PERIOD;
import static nx.pingwheel.common.config.ClientConfig.TPS;

public class PingController {
	private PingController() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	private static boolean pingQueued = false;
	private static int pingSequence = 0;
	private static int lastPing = 0;

	public static void queuePingAction() {
		pingQueued = true;
	}

	public static void pollPingAction(float tickDelta) {
		if (!pingQueued || Game.level == null) {
			return;
		}

		var time = (int)Game.level.getGameTime();

		if (CLIENT_CONFIG.getCorrectionPeriod() < MAX_CORRECTION_PERIOD && time - lastPing > CLIENT_CONFIG.getCorrectionPeriod() * TPS) {
			++pingSequence;
		}

		lastPing = time;
		pingQueued = false;
		performPingAction(tickDelta);
	}

	private static void performPingAction(float tickDelta) {
		var cameraEntity = Game.cameraEntity;

		if (cameraEntity == null || Game.level == null) {
			return;
		}

		var cameraDirection = cameraEntity.getViewVector(tickDelta);
		var hitResult = Raycast.traceDirectional(
			cameraDirection,
			tickDelta,
			Math.min(CLIENT_CONFIG.getRaycastDistance(), CLIENT_CONFIG.getPingDistance()),
			cameraEntity.isCrouching());

		if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
			if (ModContext.HasDistantHorizons) {
				Raycast.traceDistantAsync(cameraDirection, tickDelta, (distantHitResult) -> {
					IPlatformNetworkService.INSTANCE.sendToServer(new PingLocationC2SPacket(CLIENT_CONFIG.getChannel(), distantHitResult.getLocation(), null, pingSequence, GameContext.getDimension()));
				});
			}

			return;
		}

		if (ModContext.HasSable && hitResult.getType() == HitResult.Type.BLOCK) {
			var pos = hitResult.getLocation();
			var subLevelAccess = SableCompanion.INSTANCE.getContainingClient(pos);

			if (subLevelAccess != null) {
				var realPos = SableCompanion.INSTANCE.projectOutOfSubLevel(Game.level, (Position)pos);
				IPlatformNetworkService.INSTANCE.sendToServer(new PingLocationC2SPacket(CLIENT_CONFIG.getChannel(), realPos, null, pingSequence, GameContext.getDimension()));

				return;
			}
		}

		UUID uuid = null;

		if (hitResult.getType() == HitResult.Type.ENTITY) {
			uuid = ((EntityHitResult)hitResult).getEntity().getUUID();
		}

		IPlatformNetworkService.INSTANCE.sendToServer(new PingLocationC2SPacket(CLIENT_CONFIG.getChannel(), hitResult.getLocation(), uuid, pingSequence, GameContext.getDimension()));
	}
}
