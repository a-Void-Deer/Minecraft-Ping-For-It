package nx.pingwheel.common.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import nx.pingwheel.common.client.SelectionToggleNoticeState;
import nx.pingwheel.common.resource.LanguageUtils;

/** Draws the latest target-selection toggle notice above the crosshair. */
public final class SelectionToggleNoticeRenderer {
	private static final int WHITE = 0xFFFFFF;
	private static final int LIGHT_GREEN = 0x55FF55;
	private static final int LIGHT_RED = 0xFF5555;

	private SelectionToggleNoticeRenderer() {}

	public static void draw(
		GuiGraphics guiGraphics,
		SelectionToggleNoticeState state,
		int sizePercent,
		long nowMillis) {
		if (guiGraphics == null || state == null
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
		final float anchorX = SelectionToggleNoticeRenderPolicy.anchorX(guiGraphics.guiWidth());
		final float anchorY = SelectionToggleNoticeRenderPolicy.anchorY(guiGraphics.guiHeight());
		final float alpha = snapshot.alpha() / 255.0F;

		final var pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(anchorX, anchorY, 0.0F);
		pose.scale(scale, scale, 1.0F);

		// Style colors supply the separate white/status colors.  Shader alpha is
		// applied around both runs so a fade can never leave one run opaque.
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
		try {
			guiGraphics.drawString(font, prefix, -totalWidth / 2, -font.lineHeight / 2, WHITE, true);
			guiGraphics.drawString(font, stateWord, -totalWidth / 2 + prefixWidth, -font.lineHeight / 2, WHITE, true);
		} finally {
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			pose.popPose();
		}
	}
}
