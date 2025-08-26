package nx.pingwheel.common.render;

import net.minecraft.world.scores.PlayerTeam;
import nx.pingwheel.common.compat.Component;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.PingData;
import nx.pingwheel.common.resource.LanguageUtils;

import static nx.pingwheel.common.util.InputUtils.KEY_BINDING_NAME_LABELS;

public class PingLocationRenderer {
	private PingLocationRenderer() {}

	private static final ClientConfig CLIENT_CONFIG = ClientConfig.HANDLER.getConfig();

	public static void draw(DrawContext ctx, PingData ping, float pingScale) {
		final var screenPos = ping.getScreenPos();

		if (screenPos == null) {
			return;
		}

		final var m = ctx.getMatrices();

		m.pushPose();
		m.translate(screenPos.x, screenPos.y, 0);
		m.scale(pingScale, pingScale, 1f);

		final var distanceText = LanguageUtils.UNIT_METERS.get("%,.1f".formatted(ping.getDistance()));
		ctx.renderLabel(distanceText, -1.5f, null);
		ctx.renderPing(ping.getItemStack(), CLIENT_CONFIG.isItemIconVisible());

		final var author = ping.getAuthor();
		final var showNameLabels = CLIENT_CONFIG.isNameLabelForced() || KEY_BINDING_NAME_LABELS.isDown();

		if (showNameLabels && author != null) {
			final var displayName = PlayerTeam.formatNameForTeam(author.getTeam(), Component.literal(author.getProfile().getName()));
			ctx.renderLabel(displayName, 1.75f, author);
		}

		m.popPose();
	}
}
