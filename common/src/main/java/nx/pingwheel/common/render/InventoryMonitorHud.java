package nx.pingwheel.common.render;

import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import nx.pingwheel.common.monitor.client.MonitorDisplayModel;
import org.jetbrains.annotations.NotNullByDefault;

/** Pure paint of prepared text in GUI-scaled pixels; no sampling or runtime access. */
@NotNullByDefault
public final class InventoryMonitorHud {

	private static final int MARGIN = 4;
	private static final int GAP = 2;
	private InventoryMonitorHud() {}

	/** bottomReserved includes visible chat/vanilla bars; topReserved can exclude a notice. */
	@NotNullByDefault
	public record Viewport(int width, int height, int bottomReserved, int topReserved) {
		public Viewport {
			if (width < 0 || height < 0 || bottomReserved < 0 || topReserved < 0)
				throw new IllegalArgumentException("GUI viewport");
		}
	}

	@NotNullByDefault
	public record Layout(int left, int top, int right, int bottom, int visibleRows, int rowHeight) {}

	/** No mutable session/layout cache: resize and GUI scale use the new viewport immediately. */
	public static Layout layout(MonitorDisplayModel model, Viewport viewport, int lineHeight) {
		if (lineHeight < 1) throw new IllegalArgumentException("line height");
		int rowHeight = lineHeight + GAP;
		int left = Math.min(MARGIN, viewport.width());
		int right = Math.max(left, viewport.width() - MARGIN);
		int bottom = Math.max(0, viewport.height() - Math.min(viewport.height(), viewport.bottomReserved()) - MARGIN);
		int minimumTop = Math.min(bottom, Math.min(viewport.height(), viewport.topReserved()) + MARGIN);
		int rows = Math.min(model.rows().size(), Math.max(0, (bottom - minimumTop) / rowHeight));
		if (right == left) rows = 0;
		return new Layout(left, bottom - rows * rowHeight, right, bottom, rows, rowHeight);
	}

	/** Recording seam exercises the exact placement loop without native graphics. */
	@NotNullByDefault
	@FunctionalInterface
	public interface Painter { void row(MonitorDisplayModel.Row row, int x, int y, int maximumWidth); }

	public static void paint(MonitorDisplayModel model, Viewport viewport, int lineHeight, Painter painter) {
		Objects.requireNonNull(painter, "painter");
		Layout layout = layout(model, viewport, lineHeight);
		for (int index = 0; index < layout.visibleRows(); index++)
			painter.row(model.rows().get(index), layout.left(), layout.top() + index * layout.rowHeight(), layout.right() - layout.left());
	}

	public static void draw(GuiGraphics graphics, Font font, MonitorDisplayModel model, Viewport viewport) {
		Layout layout = layout(model, viewport, font.lineHeight);
		if (layout.visibleRows() == 0) return;
		graphics.enableScissor(layout.left(), layout.top(), layout.right(), layout.bottom());
		try {
			// Clipping preserves prepared Components and OFF's token style. There
			// is no font splitting, formatting or measurement in the paint path.
			for (int index = 0; index < layout.visibleRows(); index++)
				graphics.drawString(font, model.rows().get(index).text(), layout.left(), layout.top() + index * layout.rowHeight(), 0xFFFFFFFF, true);
		} finally { graphics.disableScissor(); }
	}
}
