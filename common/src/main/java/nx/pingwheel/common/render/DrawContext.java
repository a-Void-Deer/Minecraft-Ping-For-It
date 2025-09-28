package nx.pingwheel.common.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import lombok.Getter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import nx.pingwheel.common.math.MathUtils;
import org.joml.Matrix3x2fStack;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.resource.ResourceConstants.ARROW_TEXTURE_ID;
import static nx.pingwheel.common.resource.ResourceConstants.PING_TEXTURE_ID;
import static nx.pingwheel.common.resource.ResourceReloadListener.hasCustomTexture;

public class DrawContext {

	private static final int WHITE = ARGB.color(255, 255, 255, 255);
	private static final int SHADOW_BLACK = ARGB.color(64, 0, 0, 0);

	private GuiGraphics guiGraphics;
	@Getter
	private Matrix3x2fStack matrices;

	public DrawContext(GuiGraphics guiGraphics) {
		this.guiGraphics = guiGraphics;
		this.matrices = guiGraphics.pose();
	}

	public void renderLabel(Component text, float yOffset, PlayerInfo player) {
		var extraWidth = (player != null) ? 10 : 0;
		var textMetrics = new Vec2(
			Game.font.width(text) + extraWidth,
			Game.font.lineHeight
		);
		var textOffset = textMetrics.scale(-0.5f).add(new Vec2(0f, textMetrics.y * yOffset));

		matrices.pushMatrix();
		matrices.translate(textOffset.x, textOffset.y);
		guiGraphics.fill(-2, -2, (int)textMetrics.x + 1, (int)textMetrics.y, SHADOW_BLACK);
		guiGraphics.drawString(Game.font, text, extraWidth, 0, WHITE, false);

		if (player != null) {
			matrices.translate(-0.5f, -0.5f);
			renderPlayerHead(player);
		}

		matrices.popMatrix();
	}

	public void renderPlayerHead(PlayerInfo player) {
		var texture = player.getSkin().texture();
		GlStateManager._enableBlend();
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8, 8, 8, 8, 64, 64);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40, 8, 8, 8, 64, 64); // Overlay (hat)
		GlStateManager._disableBlend();
	}

	public void renderPing(ItemStack itemStack, boolean drawItemIcon) {
		if (itemStack != null && drawItemIcon) {
			renderGuiItemModel(itemStack);
		} else if (hasCustomTexture()) {
			renderTexture(PING_TEXTURE_ID, 12);
		} else {
			renderDefaultPingIcon();
		}
	}

	public void renderGuiItemModel(ItemStack itemStack) {
		guiGraphics.renderItem(itemStack, -8, -8, -150);
	}

	public void renderDefaultPingIcon() {
		matrices.pushMatrix();
		MathUtils.rotateZ(matrices, (float)(Math.PI / 4f));
		matrices.translate(-2.5f, -2.5f);
		guiGraphics.fill(0, 0, 5, 5, WHITE);
		matrices.popMatrix();
	}

	public void renderTexture(ResourceLocation texture, int size) {
		final var offset = size / -2;

		GlStateManager._enableBlend();
		guiGraphics.blit(
			RenderPipelines.GUI_TEXTURED,
			texture,
			offset,
			offset,
			0,
			0,
			size,
			size,
			size,
			size
		);
		GlStateManager._disableBlend();
	}

	public void renderArrowIcon() {
		renderTexture(ARROW_TEXTURE_ID, 10);
	}
}
