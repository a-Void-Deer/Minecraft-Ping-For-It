package nx.pingwheel.common.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.scores.PlayerTeam;
import nx.pingwheel.common.compat.Component;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.core.PingManager;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.math.MathUtils;
import nx.pingwheel.common.resource.LanguageUtils;

import static nx.pingwheel.common.ClientGlobal.Game;
import static nx.pingwheel.common.ClientGlobal.KEY_BINDING_NAME_LABELS;

public class PingRenderer {
	private PingRenderer() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	public static void onRenderGUI(PoseStack m, float tickDelta) {
		final var pingRepo = PingManager.PING_REPO;

		if (Game.player == null || pingRepo.isEmpty()) {
			return;
		}

		var ctx = new DrawContext(m);
		var wnd = Game.getWindow();
		var screenSize = new Vec2(wnd.getGuiScaledWidth(), wnd.getGuiScaledHeight());
		var safeZoneTopLeft = new Vec2(CLIENT_CONFIG.getSafeZoneLeft(), CLIENT_CONFIG.getSafeZoneTop());
		var safeZoneBottomRight = new Vec2(screenSize.x - CLIENT_CONFIG.getSafeZoneRight(), screenSize.y - CLIENT_CONFIG.getSafeZoneBottom());
		var safeScreenCenter = new Vec2((safeZoneBottomRight.x - safeZoneTopLeft.x) * 0.5f, (safeZoneBottomRight.y - safeZoneTopLeft.y) * 0.5f);
		final var showDirectionIndicator = CLIENT_CONFIG.isDirectionIndicatorVisible();
		final var showNameLabels = CLIENT_CONFIG.isNameLabelForced() || KEY_BINDING_NAME_LABELS.isDown();

		m.pushPose();
		m.translate(0f, 0f, -pingRepo.size() * 16f);

		for (var ping : pingRepo) {
			var screenPos = ping.getScreenPos();

			if (screenPos == null || ping.getDimension() != GameContext.getDimension() || (screenPos.isBehindCamera() && !showDirectionIndicator)) {
				continue;
			}

			m.translate(0f, 0f, 16f);

			var pingSize = CLIENT_CONFIG.getPingSize() / 100f;
			var pingScale = getDistanceScale(ping.getDistance()) * pingSize * 0.4f;

			var pingDirectionVec = new Vec2(screenPos.x - safeZoneTopLeft.x - safeScreenCenter.x, screenPos.y - safeZoneTopLeft.y - safeScreenCenter.y);
			var behindCamera = screenPos.isBehindCamera();

			if (behindCamera) {
				pingDirectionVec = pingDirectionVec.scale(-1);
			}

			var pingAngle = (float)Math.atan2(pingDirectionVec.y, pingDirectionVec.x);
			var isOffScreen = behindCamera || !screenPos.isInBounds(Vec2.ZERO, screenSize);

			if (isOffScreen && showDirectionIndicator) {
				var indicator = MathUtils.calculateAngleRectIntersection(pingAngle, safeZoneTopLeft, safeZoneBottomRight);

				m.pushPose();
				m.translate(indicator.x, indicator.y, 0f);

				m.pushPose();
				m.scale(pingScale, pingScale, 1f);
				var indicatorOffsetX = Math.cos(pingAngle + Math.PI) * 12;
				var indicatorOffsetY = Math.sin(pingAngle + Math.PI) * 12;
				m.translate(indicatorOffsetX, indicatorOffsetY, 0);
				ctx.renderPing(ping.getItemStack(), CLIENT_CONFIG.isItemIconVisible());
				m.popPose();

				m.pushPose();
				MathUtils.rotateZ(m, pingAngle);
				m.scale(pingSize, pingSize, 1f);

				m.scale(0.25f, 0.25f, 1f);
				m.translate(-5f, 0f, 0f);
				ctx.renderArrowIcon();
				m.popPose();

				m.popPose();
			}

			if (!behindCamera) {
				m.pushPose();
				m.translate(screenPos.x, screenPos.y, 0);
				m.scale(pingScale, pingScale, 1f);

				var text = LanguageUtils.UNIT_METERS.get("%,.1f".formatted(ping.getDistance()));
				ctx.renderLabel(text, -1.5f, null);
				ctx.renderPing(ping.getItemStack(), CLIENT_CONFIG.isItemIconVisible());

				var author = ping.getAuthor();

				if (showNameLabels && author != null) {
					var displayName = PlayerTeam.formatNameForTeam(author.getTeam(), Component.literal(author.getProfile().getName()));
					ctx.renderLabel(displayName, 1.75f, author);
				}

				m.popPose();
			}
		}

		m.popPose();
	}

	private static float getDistanceScale(double distance) {
		var scale = 2.0 / Math.pow(distance, 0.3);

		return (float)Math.max(1.0, scale);
	}
}
