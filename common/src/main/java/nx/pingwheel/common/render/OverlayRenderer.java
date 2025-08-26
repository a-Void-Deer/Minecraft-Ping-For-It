package nx.pingwheel.common.render;

import com.mojang.blaze3d.vertex.PoseStack;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.core.PingManager;
import nx.pingwheel.common.config.ClientConfig;

import static nx.pingwheel.common.CommonClient.Game;

public class OverlayRenderer {
	private OverlayRenderer() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	public static void draw(PoseStack m, float tickDelta) {
		final var pingRepo = PingManager.PING_REPO;

		if (Game.player == null || pingRepo.isEmpty()) {
			return;
		}

		final var ctx = new DrawContext(m);
		final var showDirectionIndicator = CLIENT_CONFIG.isDirectionIndicatorVisible();

		if (showDirectionIndicator) {
			DirectionIndicatorRenderer.prepareSafeZone();
		}

		m.pushPose();
		m.translate(0f, 0f, -pingRepo.size() * 16f);

		for (var ping : pingRepo) {
			final var screenPos = ping.getScreenPos();

			if (screenPos == null || ping.getDimension() != GameContext.getDimension()) {
				continue;
			}

			final var behindCamera = screenPos.isBehindCamera();

			if (behindCamera && !showDirectionIndicator) {
				continue;
			}

			m.translate(0f, 0f, 16f);

			final var pingSize = CLIENT_CONFIG.getPingSize() / 100f;
			final var pingScale = getDistanceScale(ping.getDistance()) * pingSize;

			if (showDirectionIndicator) {
				DirectionIndicatorRenderer.draw(ctx, ping, pingSize, pingScale);
			}

			if (!behindCamera) {
				PingLocationRenderer.draw(ctx, ping, pingScale);
			}
		}

		m.popPose();
	}

	private static float getDistanceScale(double distance) {
		final var scale = 2.0 / Math.pow(distance, 0.3);

		return (float)Math.max(1.0, scale) * 0.4f;
	}
}
