package nx.pingwheel.common.helper;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import org.lwjgl.opengl.GL14;

import static nx.pingwheel.common.ClientGlobal.Game;
import static nx.pingwheel.common.ClientGlobal.PING_TEXTURE_ID;
import static nx.pingwheel.common.resource.ResourceReloadListener.hasCustomTexture;

public class DrawContext {

	private static final int WHITE = ARGB.color(255, 255, 255, 255);
	private static final int SHADOW_BLACK = ARGB.color(64, 0, 0, 0);

	private GuiGraphics guiGraphics;
	private PoseStack matrices;

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

		matrices.pushPose();
		matrices.translate(textOffset.x, textOffset.y, 0);
		guiGraphics.fill(-2, -2, (int)textMetrics.x + 1, (int)textMetrics.y, SHADOW_BLACK);
		guiGraphics.drawString(Game.font, text, extraWidth, 0, WHITE, false);

		if (player != null) {
			matrices.translate(-0.5, -0.5, 0);
			renderPlayerHead(player);
		}

		matrices.popPose();
	}

	public void renderPlayerHead(PlayerInfo player) {
		var texture = player.getSkin().texture();
		GlStateManager._enableBlend();
		guiGraphics.blit(RenderType::guiTextured, texture, 0, 0, 8, 8, 8, 8, 64, 64);
		guiGraphics.blit(RenderType::guiTextured, texture, 0, 0, 40, 8, 8, 8, 64, 64); // Overlay (hat)
		GlStateManager._disableBlend();
	}

	public void renderPing(ItemStack itemStack, boolean drawItemIcon) {
		if (itemStack != null && drawItemIcon) {
			renderGuiItemModel(itemStack);
		} else if (hasCustomTexture()) {
			renderCustomPingIcon();
		} else {
			renderDefaultPingIcon();
		}
	}

	public void renderGuiItemModel(ItemStack itemStack) {
		guiGraphics.renderItem(itemStack, -8, -8, 0, -150);
	}

	public void renderCustomPingIcon() {
		final var size = 12;
		final var offset = size / -2;

		GlStateManager._enableBlend();
		guiGraphics.blit(
			RenderType::guiTextured,
			PING_TEXTURE_ID,
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

	public void renderDefaultPingIcon() {
		matrices.pushPose();
		MathUtils.rotateZ(matrices, (float)(Math.PI / 4f));
		matrices.translate(-2.5, -2.5, 0);
		guiGraphics.fill(0, 0, 5, 5, WHITE);
		matrices.popPose();
	}

	public void renderArrow(boolean antialias) {
		if (antialias) {
			guiGraphics.flush();
			GL14.glEnable(GL14.GL_POLYGON_SMOOTH);
		}

		GlStateManager._enableBlend();
		GlStateManager._blendFuncSeparate(GL14.GL_SRC_ALPHA, GL14.GL_ONE_MINUS_SRC_ALPHA, GL14.GL_ONE, GL14.GL_ZERO);

		guiGraphics.drawSpecial((bufferSource) -> {
			var buffer = bufferSource.getBuffer(RenderType.gui());
			var mat = matrices.last().pose();
			buffer.addVertex(mat, 5f, 0f, 0f).setColor(1f, 1f, 1f, 1f);
			buffer.addVertex(mat, -5f, -5f, 0f).setColor(1f, 1f, 1f, 1f);
			buffer.addVertex(mat, -3f, 0f, 0f).setColor(1f, 1f, 1f, 1f);
			buffer.addVertex(mat, -5f, 5f, 0f).setColor(1f, 1f, 1f, 1f);
		});

		GlStateManager._disableBlend();
		GL14.glDisable(GL14.GL_POLYGON_SMOOTH);
	}
}
