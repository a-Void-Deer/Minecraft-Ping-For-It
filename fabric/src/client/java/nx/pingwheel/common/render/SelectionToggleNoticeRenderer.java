package nx.pingwheel.common.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import nx.pingwheel.common.client.SelectionToggleNoticeState;
import nx.pingwheel.common.resource.LanguageUtils;

/**
 * Draws the latest target-selection toggle notice above the crosshair.
 *
 * <p>26.3 has no {@code RenderSystem#setShaderColor}, so the fade that upstream
 * applied around the whole batch is folded into the alpha byte of the colour
 * handed to {@link GuiGraphicsExtractor#text}.  Vanilla keeps the style RGB and
 * takes the alpha from that colour ({@code Font.PreparedTextBuilder#getTextColor}),
 * so both runs still fade together exactly as before.
 */
public final class SelectionToggleNoticeRenderer {
	private static final int WHITE = 0xFFFFFF;
	private static final int LIGHT_GREEN = 0x55FF55;
	private static final int LIGHT_RED = 0xFF5555;

	private SelectionToggleNoticeRenderer() {}

	public static void draw(
		GuiGraphicsExtractor graphics,
		SelectionToggleNoticeState state,
		int sizePercent,
		long nowMillis) {
		if (graphics == null || state == null
			|| !SelectionToggleNoticeRenderPolicy.isVisibleAtSize(sizePercent)) {
			return;
		}

		final SelectionToggleNoticeState.Snapshot snapshot = state.snapshot(nowMillis).orElse(null);
		if (snapshot == null) {
			return;
		}

		final Minecraft minecraft = Minecraft.getInstance();
		final Font font = minecraft.font;
		final MutableComponent prefix = Component.translatable(snapshot.kind().translationKey())
			.withStyle(style -> style.withColor(TextColor.fromRgb(WHITE)));
		final MutableComponent stateWord = Component.translatable(
				snapshot.enabled()
					? LanguageUtils.keyOf("notice", "on")
					: LanguageUtils.keyOf("notice", "off"))
			.withStyle(style -> style.withColor(TextColor.fromRgb(
				snapshot.enabled() ? LIGHT_GREEN : LIGHT_RED)));

		final int prefixWidth = font.width(prefix);
		final int stateWidth = font.width(stateWord);
		final int totalWidth = prefixWidth + stateWidth;
		final float scale = SelectionToggleNoticeRenderPolicy.scaleFor(sizePercent);
		final float anchorX = SelectionToggleNoticeRenderPolicy.anchorX(graphics.guiWidth());
		final float anchorY = SelectionToggleNoticeRenderPolicy.anchorY(graphics.guiHeight());
		final int alphaByte = snapshot.alpha();
		final int prefixColor = (alphaByte << 24) | WHITE;
		final int stateColor = (alphaByte << 24) | (snapshot.enabled() ? LIGHT_GREEN : LIGHT_RED);

		final var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(anchorX, anchorY);
		pose.scale(scale, scale);

		try {
			graphics.text(font, prefix, -totalWidth / 2, -font.lineHeight / 2, prefixColor, true);
			graphics.text(font, stateWord, -totalWidth / 2 + prefixWidth, -font.lineHeight / 2, stateColor, true);
		} finally {
			pose.popMatrix();
		}
	}
}
