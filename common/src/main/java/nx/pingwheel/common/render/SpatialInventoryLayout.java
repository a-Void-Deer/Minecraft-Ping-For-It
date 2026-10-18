package nx.pingwheel.common.render;

import java.util.function.ToIntFunction;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/** Pure GUI-space geometry. No independently chosen viewport or row ordering. */
public record SpatialInventoryLayout(int first, int count, double centerX, double centerY,
	double width, double height, double headerHeight, double rowHeight, double footerHeight) {

	private static final double MAX_WIDTH_FRACTION = 0.2;
	private static final double MAX_HEIGHT_FRACTION = 0.6;
	public static final int ITEM_ICON_SIZE = 16;

	/** Shared unanimated metrics for input, row capacity and paint, in scaled GUI pixels. */
	public record Metrics(int visibleRows, double width, double headerHeight, double rowHeight, double footerHeight) {
		public SpatialInventoryLayout layout(SpatialInventoryView view, double guiCenterX, double guiCenterY) {
			return of(window(view), guiCenterX, guiCenterY, width, headerHeight, rowHeight, footerHeight);
		}

		/** Rewindow detached/independent views too: a retained old capacity cannot bypass a resize. */
		public SpatialInventoryView window(SpatialInventoryView view) {
			int capacity = Math.min(view.visibleRows(), visibleRows);
			int first = view.windowFirst();
			if (capacity != view.visibleRows()) {
				first = Math.max(0, Math.min(Math.max(0, view.selectedIndex()) - capacity / 2,
					Math.max(0, view.rows().size() - capacity)));
			}
			return view.withWindow(first, capacity);
		}
	}

	/**
	 * Caps the content box, not its external navigation. Natural text/icon chrome
	 * is never scaled down: when even one row cannot fit, the readable minimum
	 * takes precedence. Width reserves the icon, existing insets/gap and two
	 * line-height units for clipped name/count glyphs, rather than a new fixed
	 * minimum that would override the cap on ordinary Minecraft GUI viewports.
	 * Zero viewport dimensions retain the dimensionless compatibility measure.
	 */
	public static Metrics measure(double guiWidth, double guiHeight, int fontLineHeight, double textScale) {
		return measure(guiWidth, guiHeight, fontLineHeight, textScale, 0.0);
	}

	/**
	 * Measure all bounded discovered rows, not the moving window or selection.
	 * Prepared display text/icon metadata is detached once by the presentation
	 * owner; this pass only reads it and font widths, never item/world providers.
	 * Reserving the largest position label and Forward slot keeps scrolling,
	 * Back focus and item retention from changing the panel's width.
	 */
	public static Metrics measure(SpatialInventoryView view, double guiWidth, double guiHeight,
		int fontLineHeight, double textScale, ToIntFunction<FormattedText> widths) {
		double naturalWidth = 0.0;
		if (view != null) {
			int total = view.rows().size();
			if (view.status().translationKey() != null)
				naturalWidth = Math.max(naturalWidth, 8.0 + widths.applyAsInt(Component.translatable(view.status().translationKey())) * textScale);
			if (view.forwardAvailable() || view.retainedRowKey() != null)
				naturalWidth = Math.max(naturalWidth, 16.0 + 2.0 * widths.applyAsInt(Component.translatable("pingforit.spatial.inventory.forward")) * textScale);
			for (int index = 0; index < total; index++) {
				naturalWidth = Math.max(naturalWidth,
					8.0 + widths.applyAsInt(Component.literal((index + 1) + " / " + total)) * textScale);
				var row = view.rows().get(index);
				var display = row.display();
				Component name = display == null ? Component.translatable(row.label()) : display.name();
				double icon = display != null && display.hasIcon() ? ITEM_ICON_SIZE + 3.0 : 0.0;
				double quantity = widths.applyAsInt(quantity(row.count())) * textScale;
				double nameWidth = widths.applyAsInt(name) * textScale;
				// The quantity slot uses at most half the row: give both complete
				// strings their natural room before the viewport cap clips either.
				double content = Math.max(nameWidth + 4.0 + quantity, quantity * 2.0);
				if (row.quality().translationKey() != null)
					content = Math.max(content, widths.applyAsInt(Component.translatable(row.quality().translationKey())) * textScale);
				naturalWidth = Math.max(naturalWidth, 9.0 + icon + content);
			}
		}
		return measure(guiWidth, guiHeight, fontLineHeight, textScale, naturalWidth);
	}

	public static Component quantity(Long count) {
		return count == null ? Component.empty() : Component.literal("x" + count);
	}

	private static Metrics measure(double guiWidth, double guiHeight, int fontLineHeight, double textScale, double contentWidth) {
		if (!Double.isFinite(guiWidth) || guiWidth < 0 || !Double.isFinite(guiHeight) || guiHeight < 0
			|| fontLineHeight <= 0 || !Double.isFinite(textScale) || textScale <= 0)
			throw new IllegalArgumentException("invalid inventory metrics");
		double textHeight = fontLineHeight * textScale;
		// Whole-pixel chrome prevents a final rounded border from exceeding a
		// fractional budget even when the font preference itself is fractional.
		double rowHeight = Math.ceil(Math.max(12.0, Math.max(ITEM_ICON_SIZE + 2.0, textHeight * 2.0 + 4.0)));
		double headerHeight = Math.ceil(textHeight * 2.0 + 8.0);
		double footerHeight = Math.ceil(textHeight + 6.0);
		double readableWidth = Math.ceil(5.0 + ITEM_ICON_SIZE + 3.0 + 8.0 + textHeight * 2.0);
		double naturalWidth = Math.max(Math.ceil(contentWidth), readableWidth);
		double width = guiWidth == 0 ? naturalWidth
			: Math.max(readableWidth, Math.min(naturalWidth, Math.floor(guiWidth * MAX_WIDTH_FRACTION)));
		int capacity = guiHeight == 0 ? Integer.MAX_VALUE : Math.max(1,
			(int) Math.floor((guiHeight * MAX_HEIGHT_FRACTION - headerHeight - footerHeight) / rowHeight));
		return new Metrics(capacity, width, headerHeight, rowHeight, footerHeight);
	}

	public static SpatialInventoryLayout of(SpatialInventoryView view, double guiCenterX, double guiCenterY,
		double width, double headerHeight, double rowHeight, double footerHeight) {
		if (!Double.isFinite(width) || width <= 0.0 || !Double.isFinite(rowHeight) || rowHeight <= 0.0
			|| !Double.isFinite(headerHeight) || headerHeight < 0.0
			|| !Double.isFinite(footerHeight) || footerHeight < 0.0) {
			throw new IllegalArgumentException("invalid panel dimensions");
		}
		int total = view.rows().size();
		int first = Math.max(0, Math.min(view.windowFirst(), Math.max(0, total - 1)));
		int count = Math.min(view.visibleRows(), total - first);
		double height = headerHeight + count * rowHeight + footerHeight;
		double centerY = guiCenterY + view.glideBaseY();
		if (!Double.isNaN(view.selectedRowCenterY()) && view.selectedIndex() >= first
			&& view.selectedIndex() < first + count) {
			double selectedOffset = -height / 2.0 + headerHeight + (view.selectedIndex() - first + 0.5) * rowHeight;
			centerY = guiCenterY + view.selectedRowCenterY() - selectedOffset;
		}
		return new SpatialInventoryLayout(first, count, guiCenterX + view.axisX(), centerY,
			width, height, headerHeight, rowHeight, footerHeight);
	}

	/** Absolute GUI Y; subtract GUI height / 2 exactly once before controller use. */
	public double rowCenterY(int index) {
		return index < first || index >= first + count ? Double.NaN
			: centerY - height / 2.0 + headerHeight + (index - first + 0.5) * rowHeight;
	}
}
