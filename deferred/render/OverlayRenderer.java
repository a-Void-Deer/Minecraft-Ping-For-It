package nx.pingwheel.common.render;

import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.client.marker.MarkerOverlayState;
import nx.pingwheel.common.config.ClientConfig;

import static nx.pingwheel.common.CommonClient.Game;

public class OverlayRenderer {
	private OverlayRenderer() {}

	public static void draw(GuiGraphics guiGraphics, float tickDelta) {
		final var config = ClientConfig.HANDLER.getConfig();
		final var renderViews = MarkerOverlayState.INSTANCE.renderViews();

		if (Game.player == null || Game.level == null || renderViews.isEmpty()) {
			return;
		}

		final var m = guiGraphics.pose();
		final var ctx = new DrawContext(guiGraphics);
		final var showDirectionIndicator = config.isDirectionIndicatorVisible();
		final var currentDimension = Game.level.dimension().location().toString();

		if (showDirectionIndicator) {
			DirectionIndicatorRenderer.prepareSafeZone();
		}

		m.pushPose();
		m.translate(0f, 0f, -renderViews.size() * 16f);

		for (var view : renderViews) {
			final var screenPos = view.getScreenPos();

			if (screenPos == null || !view.getDimension().equals(currentDimension)) {
				continue;
			}

			final var behindCamera = screenPos.isBehindCamera();

			if (behindCamera && !showDirectionIndicator) {
				continue;
			}

			m.translate(0f, 0f, 16f);

			if (showDirectionIndicator) {
				DirectionIndicatorRenderer.draw(ctx, view);
			}

			if (!behindCamera) {
				PingLocationRenderer.draw(ctx, view);
			}
		}

		m.popPose();
	}
}
