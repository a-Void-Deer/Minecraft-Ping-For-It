package nx.pingwheel.common.render;

/** Pure GUI-space geometry. No independently chosen viewport or row ordering. */
public record SpatialInventoryLayout(int first, int count, double centerX, double centerY,
	double width, double height, double headerHeight, double rowHeight, double footerHeight) {

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
