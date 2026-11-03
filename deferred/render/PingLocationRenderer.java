package nx.pingwheel.common.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import nx.pingwheel.common.client.marker.MarkerView;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.resource.LanguageUtils;

import static nx.pingwheel.common.CommonClient.Game;

public class PingLocationRenderer {
	private PingLocationRenderer() {}

	private static final int WHITE = 0xFFFFFFFF;

	public static void draw(DrawContext ctx, MarkerView ping) {
		final var config = ClientConfig.HANDLER.getConfig();
		final var screenPos = ping.getScreenPos();

		if (screenPos == null) {
			return;
		}

		final var m = ctx.getMatrices();
		final var pingScale = ping.getScale();

		m.pushPose();
		m.translate(screenPos.x, screenPos.y, 0);
		m.scale(pingScale, pingScale, 1f);

		final var labelUseTeamColor = config.getTeamColorMode() == TeamColorMode.FULL || config.getTeamColorMode() == TeamColorMode.LABELS_ONLY;
		final var pingColor = RenderColorPolicy.markerColor(ping.getPingColor());

		final var author = ping.getPlayerInfo();
		final var compactPlayerInfo = config.getPlayerInfoMode() == PlayerInfoMode.COMPACT ? author : null;

		final var distanceText = LanguageUtils.UNIT_METERS.get("%,.1f".formatted(ping.getDistance()));
		ctx.renderLabel(distanceText, -1.5f, compactPlayerInfo, RenderColorPolicy.distanceTextColor());
		ctx.renderPing(ping.getItemStack(), config.isItemIconVisible(), pingColor);

		// Authoritative target name. Always plain opaque white: the target
		// text never inherits a ping-type or team color. Its label sits above
		// the distance label so the two never overlap at common GUI scales.
		ctx.renderLabel(ping.getTargetName(), -2.75f, null, RenderColorPolicy.targetTextColor());

		final var isPlayerListHeld = config.getPlayerInfoMode() == PlayerInfoMode.HOLD && Game.options.keyPlayerList.isDown();
		final var showVerbosePlayerInfo = config.getPlayerInfoMode() == PlayerInfoMode.ALWAYS || isPlayerListHeld;

		if (showVerbosePlayerInfo && author != null) {
			var displayName = PlayerTeam.formatNameForTeam(author.getTeam(), Component.literal(author.getProfile().getName()));
			if (!labelUseTeamColor) displayName = displayName.withStyle(ChatFormatting.RESET);

			ctx.renderLabel(displayName, 1.75f, author, WHITE);
		}

		m.popPose();
	}
}
