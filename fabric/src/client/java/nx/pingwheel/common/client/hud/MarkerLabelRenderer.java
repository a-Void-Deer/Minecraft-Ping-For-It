package nx.pingwheel.common.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import nx.pingwheel.common.client.marker.MarkerView;
import nx.pingwheel.common.resource.LanguageUtils;

/**
 * White text labels of one marker, in the marker's already-scaled frame.
 *
 * <p>A 1:1 port of upstream {@code deferred/render/DrawContext.java:35-62}
 * ({@code renderLabel} + {@code renderPlayerHead}) as used by
 * {@code deferred/render/PingLocationRenderer.java:40-57}.
 *
 * <p>Layout, verbatim from upstream:
 * <pre>
 *   extraWidth = (player != null) ? 10 : 0           // 8px head + 2px gap
 *   w          = font.width(text) + extraWidth
 *   offset     = (-w*0.5, lineHeight*yOffset - lineHeight*0.5)
 *   translate(offset)
 *   fill(-2, -2, w + 1, lineHeight, 0x40000000)       // SHADOW_BLACK backdrop
 *   text(font, text, extraWidth, 0, color, dropShadow = FALSE)
 *   if (player != null) { translate(-0.5, -0.5); renderPlayerHead(player); }
 * </pre>
 *
 * <p>Two parity details matter:
 * <ul>
 *   <li>the four-argument {@code text(...)} overload defaults to
 *       {@code dropShadow = true} ({@code GuiGraphicsExtractor.java:251-259}),
 *       while upstream draws with {@code drawString(..., false)} - so the
 *       five-argument overload is required;</li>
 *   <li>the {@code -0.5 * lineHeight} part of the vertical offset comes from
 *       {@code textMetrics.scale(-0.5f)}, not from the configured
 *       {@code yOffset}; dropping it would shift every label up by 4.5px.</li>
 * </ul>
 *
 * <p>The 8x8 player head uses 26.3's own {@code PlayerFaceExtractor}, which
 * samples exactly the skin regions upstream sampled (face at {@code u=8,v=8},
 * hat overlay at {@code u=40,v=8}, both 8x8 of a 64x64 skin) and resolves the
 * skin through the vanilla {@code PlayerSkinRenderCache}, so an asynchronously
 * downloaded skin can never end in a missing-texture blit.
 */
final class MarkerLabelRenderer {

	/** Upstream {@code DrawContext.java:21}: {@code ARGB.color(64, 0, 0, 0)}. */
	private static final int BACKDROP_COLOR = 0x40000000;

	/** Upstream {@code RenderColorPolicy}: every label is opaque white. */
	private static final int LABEL_COLOR = 0xFFFFFFFF;

	/** Upstream {@code PingLocationRenderer.java:40-41}: distance label below. */
	private static final float DISTANCE_Y_OFFSET = -1.5f;

	/** Upstream {@code PingLocationRenderer.java:44-47}: target name above. */
	private static final float TARGET_Y_OFFSET = -2.75f;

	/** Upstream {@code PingLocationRenderer.java:56}: player name further below. */
	private static final float PLAYER_NAME_Y_OFFSET = 1.75f;

	/** Upstream {@code DrawContext.java:56-62}: an 8x8 head of a 64x64 skin. */
	private static final int PLAYER_HEAD_SIZE = 8;

	/** Upstream {@code DrawContext.java:36}: the head plus its 2px gap. */
	private static final int PLAYER_HEAD_EXTRA_WIDTH = 10;

	private MarkerLabelRenderer() {}

	/**
	 * The {@code %,.1f} meters label, e.g. {@code "12.5m"}; in
	 * {@code PlayerInfoMode.COMPACT} it carries the owner head on its left
	 * (upstream {@code PingLocationRenderer.java:38-41}).
	 */
	static void drawDistance(
		GuiGraphicsExtractor graphics,
		Font font,
		MarkerView view,
		@Nullable PlayerInfo player
	) {
		drawLabel(graphics, font, distanceText(view), DISTANCE_Y_OFFSET, player, LABEL_COLOR);
	}

	/**
	 * The target's display name above the distance label
	 * (upstream {@code PingLocationRenderer.java:44-47}); always plain white and
	 * never given an owner head.
	 */
	static void drawTargetName(GuiGraphicsExtractor graphics, Font font, MarkerView view) {
		drawLabel(graphics, font, view.getTargetName(), TARGET_Y_OFFSET, null, LABEL_COLOR);
	}

	/**
	 * The optional owner name below the marker
	 * (upstream {@code PingLocationRenderer.java:49-57}), carrying the 8x8 head.
	 */
	static void drawPlayerName(
		GuiGraphicsExtractor graphics,
		Font font,
		Component displayName,
		PlayerInfo player
	) {
		drawLabel(graphics, font, displayName, PLAYER_NAME_Y_OFFSET, player, LABEL_COLOR);
	}

	/**
	 * The exact distance component upstream renders: the localized
	 * {@code unit.pingforit.meters} pattern fed with a {@code %,.1f} distance.
	 */
	static Component distanceText(MarkerView view) {
		return LanguageUtils.UNIT_METERS.get("%,.1f".formatted(view.getDistance()));
	}

	/**
	 * The player-name component upstream builds
	 * ({@code PingLocationRenderer.java:53-56}): the team-formatted profile name,
	 * reset to plain text unless the configured {@code TeamColorMode} keeps label
	 * team colors.
	 */
	static Component playerDisplayName(PlayerInfo player, boolean labelUseTeamColor) {
		// 26.3: withStyle(...) lives on MutableComponent; formatNameForTeam already
		// returns one, so the upstream reset stays a one-liner.
		MutableComponent displayName = PlayerTeam.formatNameForTeam(
			player.getTeam(),
			Component.literal(player.getProfile().name()));

		return labelUseTeamColor ? displayName : displayName.withStyle(ChatFormatting.RESET);
	}

	/**
	 * Draws one centered label with its translucent black backdrop at
	 * {@code yOffset} line heights, plus the optional owner head. The caller has
	 * already translated to the marker origin and applied the marker scale.
	 */
	static void drawLabel(
		GuiGraphicsExtractor graphics,
		Font font,
		@Nullable Component text,
		float yOffset,
		@Nullable PlayerInfo player,
		int color
	) {
		if (font == null || text == null) {
			return;
		}

		int lineHeight = font.lineHeight;

		if (lineHeight <= 0) {
			return;
		}

		int extraWidth = player != null ? PLAYER_HEAD_EXTRA_WIDTH : 0;
		int width = font.width(text) + extraWidth;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();

		try {
			pose.translate(-0.5f * width, lineHeight * yOffset - 0.5f * lineHeight);
			graphics.fill(-2, -2, width + 1, lineHeight, BACKDROP_COLOR);
			// dropShadow must stay false to match upstream drawString(..., false).
			graphics.text(font, text, extraWidth, 0, color, false);

			if (player != null) {
				pose.translate(-0.5f, -0.5f);
				drawPlayerHead(graphics, player);
			}
		} finally {
			pose.popMatrix();
		}
	}

	/** Upstream {@code DrawContext.java:56-62}: face + hat overlay, 8x8 each. */
	private static void drawPlayerHead(GuiGraphicsExtractor graphics, PlayerInfo player) {
		PlayerSkin skin = player.getSkin();

		if (skin == null) {
			return;
		}

		PlayerFaceExtractor.extractRenderState(graphics, skin, 0, 0, PLAYER_HEAD_SIZE);
	}
}
