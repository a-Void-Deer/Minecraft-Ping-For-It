package nx.pingwheel.common.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import lombok.Getter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import nx.pingwheel.common.math.MathUtils;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.resource.ResourceConstants.ARROW_TEXTURE_ID;
import static nx.pingwheel.common.resource.ResourceConstants.PING_TEXTURE_ID;
import static nx.pingwheel.common.resource.ResourceReloadListener.hasCustomTexture;

public class DrawContext {

	private static final int SHADOW_BLACK = FastColor.ARGB32.color(64, 0, 0, 0);

	private GuiGraphics guiGraphics;
	@Getter
	private PoseStack matrices;

	public DrawContext(GuiGraphics guiGraphics) {
		this.guiGraphics = guiGraphics;
		this.matrices = guiGraphics.pose();
	}

	public void renderLabel(Component text, float yOffset, PlayerInfo player, int color) {
		var extraWidth = (player != null) ? 10 : 0;
		var textMetrics = new Vec2(
			Game.font.width(text) + extraWidth,
			Game.font.lineHeight
		);
		var textOffset = textMetrics.scale(-0.5f).add(new Vec2(0f, textMetrics.y * yOffset));

		matrices.pushPose();
		matrices.translate(textOffset.x, textOffset.y, 0);
		guiGraphics.fill(-2, -2, (int)textMetrics.x + 1, (int)textMetrics.y, SHADOW_BLACK);
		guiGraphics.drawString(Game.font, text, extraWidth, 0, color, false);

		if (player != null) {
			matrices.translate(-0.5, -0.5, 0);
			renderPlayerHead(player);
		}

		matrices.popPose();
	}

	public void renderPlayerHead(PlayerInfo player) {
		var texture = player.getSkin().texture();
		RenderSystem.enableBlend();
		guiGraphics.blit(texture, 0, 0, 0, 8, 8, 8, 8, 64, 64);
		guiGraphics.blit(texture, 0, 0, 0, 40, 8, 8, 8, 64, 64); // Overlay (hat)
		RenderSystem.disableBlend();
	}

	public void renderPing(ItemStack itemStack, boolean drawItemIcon, int color) {
		if (itemStack != null && drawItemIcon) {
			renderGuiItemModel(itemStack);
		} else if (hasCustomTexture()) {
			renderTexture(PING_TEXTURE_ID, 12, color);
		} else {
			renderDefaultPingIcon(color);
		}
	}

	public void renderGuiItemModel(ItemStack itemStack) {
		guiGraphics.renderItem(itemStack, -8, -8, 0, -150);
	}

	public void renderDefaultPingIcon(int color) {
		matrices.pushPose();
		MathUtils.rotateZ(matrices, (float)(Math.PI / 4f));
		matrices.translate(-2.5, -2.5, 0);
		guiGraphics.fill(0, 0, 5, 5, color);
		matrices.popPose();
	}

	public void renderTexture(ResourceLocation texture, int size, int color) {
		final var offset = size / -2;
		final float a = FastColor.ARGB32.alpha(color) / 255f;
		final float r = FastColor.ARGB32.red(color) / 255f;
		final float g = FastColor.ARGB32.green(color) / 255f;
		final float b = FastColor.ARGB32.blue(color) / 255f;

		RenderSystem.setShaderColor(r, g, b, a);
		RenderSystem.enableBlend();
		guiGraphics.blit(
			texture,
			offset,
			offset,
			0,
			0,
			0,
			size,
			size,
			size,
			size
		);
		RenderSystem.disableBlend();
	}

	public void renderArrowIcon(int color) {
		renderTexture(ARROW_TEXTURE_ID, 10, color);
	}
}
