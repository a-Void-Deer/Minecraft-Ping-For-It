package nx.pingwheel.common.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.locale.Language;
import nx.pingwheel.common.monitor.client.ClientMonitorState;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Pure paint of prepared text in GUI-scaled pixels; no sampling or runtime access. */
@NotNullByDefault
public final class InventoryMonitorHud {

	private static final int MARGIN = 4;
	private static final int GAP = 2;
	private static final int SHADOW_WIDTH = 1;
	private static final TextState NATIVE_TEXT_STATE = new TextState();
	private static final Supplier<@Nullable ShaderInstance> RESTORE_SHADER = () -> NATIVE_TEXT_STATE.shader;
	private static final NativePainter NATIVE_PAINTER = new NativePainter();
	private InventoryMonitorHud() {}

	@NotNullByDefault
	public record Viewport(int width, int height) {
		public Viewport {
			if (width < 0 || height < 0)
				throw new IllegalArgumentException("GUI viewport");
		}
	}

	@NotNullByDefault
	public record Layout(int left, int top, int right, int rowCount, int rowHeight) {}

	/** Width fitting reserves the shadow as well as the native left/right margin. */
	public static int availableTextWidth(int guiWidth) {
		return guiWidth <= MARGIN * 2 + SHADOW_WIDTH ? 0 : guiWidth - MARGIN * 2 - SHADOW_WIDTH;
	}

	/** No mutable session/layout cache: resize and GUI scale use the new viewport immediately. */
	public static Layout layout(MonitorDisplayModel model, Viewport viewport, int lineHeight) {
		if (lineHeight < 1 || lineHeight > Integer.MAX_VALUE - GAP) throw new IllegalArgumentException("line height");
		int rowHeight = lineHeight + GAP;
		return new Layout(MARGIN, MARGIN, MARGIN + availableTextWidth(viewport.width()), model.rows().size(), rowHeight);
	}

	/** Recording seam exercises the exact placement loop without native graphics. */
	@NotNullByDefault
	@FunctionalInterface
	public interface Painter { void row(MonitorDisplayModel.Row row, int x, int y, int maximumWidth); }

	public static void paint(MonitorDisplayModel model, Viewport viewport, int lineHeight, Painter painter) {
		Objects.requireNonNull(painter, "painter");
		if (lineHeight < 1 || lineHeight > Integer.MAX_VALUE - GAP) throw new IllegalArgumentException("line height");
		int maximumWidth = availableTextWidth(viewport.width());
		for (int index = 0; index < model.rows().size(); index++)
			painter.row(model.rows().get(index), MARGIN, MARGIN + index * (lineHeight + GAP), maximumWidth);
	}

	public static void draw(GuiGraphics graphics, Font font, MonitorDisplayModel model, Viewport viewport) {
		if (model.rows().isEmpty()) return;
		NATIVE_TEXT_STATE.capture(NATIVE_STATE);
		try {
			graphics.enableScissor(Math.min(MARGIN, viewport.width()), 0,
				Math.min(viewport.width(), MARGIN + availableTextWidth(viewport.width()) + SHADOW_WIDTH), viewport.height());
			try {
				RenderSystem.disableDepthTest();
				RenderSystem.setShaderColor(1, 1, 1, 1);
				// SEE_THROUGH is the native font's no-depth-write text pass. NORMAL
				// would leave monitor depth in front of the later totem animation.
				// Neither pose nor the world/GUI projections are changed here.
				NATIVE_PAINTER.graphics = graphics; NATIVE_PAINTER.font = font;
				try { paint(model, viewport, font.lineHeight, NATIVE_PAINTER); }
				finally { NATIVE_PAINTER.graphics = null; NATIVE_PAINTER.font = null; }
			} finally {
				// Finish while the horizontal guard is active, before any vanilla
				// overlay can enqueue its own geometry. No per-row unmanaged flush.
				try { graphics.flush(); }
				finally { graphics.disableScissor(); }
			}
		} finally { NATIVE_TEXT_STATE.restore(NATIVE_STATE); }
	}

	@NotNullByDefault
	private static final class NativePainter implements Painter {
		private @Nullable GuiGraphics graphics;
		private @Nullable Font font;

		@Override public void row(MonitorDisplayModel.Row row, int x, int y, int maximumWidth) {
			GuiGraphics target = Objects.requireNonNull(graphics);
			Objects.requireNonNull(font).drawInBatch(row.text().getVisualOrderText(), x, y, 0xFFFFFFFF, true,
				target.pose().last().pose(), target.bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, 15728880);
		}
	}

	/** Only the scalar state changed by native SEE_THROUGH text and GUI flush. */
	@NotNullByDefault
	interface StateAccess {
		boolean depthTest();
		boolean depthMask();
		boolean blend();
		int sourceRgb();
		int destinationRgb();
		int sourceAlpha();
		int destinationAlpha();
		boolean scissor();
		void captureScissor();
		int scissorBox(int index);
		float color(int channel);
		@Nullable ShaderInstance shader();
		int shaderTexture(int slot);
		void depthTest(boolean value);
		void depthMask(boolean value);
		void blend(boolean value);
		void blendFunction(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha);
		void color(float red, float green, float blue, float alpha);
		void shader(@Nullable ShaderInstance value);
		void shaderTexture(int slot, int value);
		void scissor(boolean enabled, int left, int bottom, int width, int height);
	}

	private static final StateAccess NATIVE_STATE = new StateAccess() {
		private final IntBuffer scissorBox = BufferUtils.createIntBuffer(4);
		public boolean depthTest() { return GL11.glIsEnabled(GL11.GL_DEPTH_TEST); }
		public boolean depthMask() { return GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK); }
		public boolean blend() { return GL11.glIsEnabled(GL11.GL_BLEND); }
		public int sourceRgb() { return GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB); }
		public int destinationRgb() { return GL11.glGetInteger(GL14.GL_BLEND_DST_RGB); }
		public int sourceAlpha() { return GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA); }
		public int destinationAlpha() { return GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA); }
		public boolean scissor() { return GL11.glIsEnabled(GL11.GL_SCISSOR_TEST); }
		public void captureScissor() { GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox); }
		public int scissorBox(int index) { return scissorBox.get(index); }
		public float color(int channel) { return RenderSystem.getShaderColor()[channel]; }
		public @Nullable ShaderInstance shader() { return RenderSystem.getShader(); }
		public int shaderTexture(int slot) { return RenderSystem.getShaderTexture(slot); }
		public void depthTest(boolean value) { if (value) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest(); }
		public void depthMask(boolean value) { RenderSystem.depthMask(value); }
		public void blend(boolean value) { if (value) RenderSystem.enableBlend(); else RenderSystem.disableBlend(); }
		public void blendFunction(int sourceRgb, int destinationRgb, int sourceAlpha, int destinationAlpha) {
			RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
		}
		public void color(float red, float green, float blue, float alpha) { RenderSystem.setShaderColor(red, green, blue, alpha); }
		public void shader(@Nullable ShaderInstance value) { RenderSystem.setShader(RESTORE_SHADER); }
		public void shaderTexture(int slot, int value) { RenderSystem.setShaderTexture(slot, value); }
		public void scissor(boolean enabled, int left, int bottom, int width, int height) {
			if (enabled) RenderSystem.enableScissor(left, bottom, width, height); else RenderSystem.disableScissor();
		}
	};

	/** Reused on the render thread; no layout or state-snapshot allocation per paint. */
	@NotNullByDefault
	static final class TextState {
		private boolean depthTest, depthMask, blend, scissor;
		private int sourceRgb, destinationRgb, sourceAlpha, destinationAlpha, texture, lightmap;
		private int scissorLeft, scissorBottom, scissorWidth, scissorHeight;
		private float red, green, blue, alpha;
		private @Nullable ShaderInstance shader;

		void capture(StateAccess host) {
			depthTest = host.depthTest(); depthMask = host.depthMask(); blend = host.blend();
			sourceRgb = host.sourceRgb(); destinationRgb = host.destinationRgb();
			sourceAlpha = host.sourceAlpha(); destinationAlpha = host.destinationAlpha();
			red = host.color(0); green = host.color(1); blue = host.color(2); alpha = host.color(3);
			shader = host.shader(); texture = host.shaderTexture(0); lightmap = host.shaderTexture(2);
			scissor = host.scissor();
			if (scissor) {
				host.captureScissor();
				scissorLeft = host.scissorBox(0); scissorBottom = host.scissorBox(1);
				scissorWidth = host.scissorBox(2); scissorHeight = host.scissorBox(3);
			}
		}

		void restore(StateAccess host) {
			host.shader(shader); host.shaderTexture(0, texture); host.shaderTexture(2, lightmap);
			host.color(red, green, blue, alpha);
			host.depthMask(depthMask); host.depthTest(depthTest);
			host.blendFunction(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha); host.blend(blend);
			host.scissor(scissor, scissorLeft, scissorBottom, scissorWidth, scissorHeight);
		}
	}

	/** The monitor's preparation identity, not a general-purpose or height-dependent layout cache. */
	@NotNullByDefault
	public static final class PreparationKey {
		private @Nullable ClientMonitorState.View view;
		private @Nullable Font font;
		private @Nullable Language language;
		private int width = -1;
		private double guiScale = -1;
		private boolean forceUnicodeFont;
		private boolean japaneseGlyphVariants;

		/** Called only during frame/tick/packet preparation; unchanged frames allocate nothing. */
		public boolean update(ClientMonitorState.View nextView, int nextWidth, double nextGuiScale, Font nextFont,
			Language nextLanguage, boolean nextForceUnicodeFont, boolean nextJapaneseGlyphVariants) {
			if (view == nextView && width == nextWidth && guiScale == nextGuiScale && font == nextFont
				&& language == nextLanguage && forceUnicodeFont == nextForceUnicodeFont
				&& japaneseGlyphVariants == nextJapaneseGlyphVariants) return false;
			view = nextView; width = nextWidth; guiScale = nextGuiScale; font = nextFont; language = nextLanguage;
			forceUnicodeFont = nextForceUnicodeFont; japaneseGlyphVariants = nextJapaneseGlyphVariants;
			return true;
		}

		public void clear() { view = null; font = null; language = null; }
	}
}
