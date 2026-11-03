package nx.pingwheel.common.screen;

/**
 * Pure geometry for the settings list.
 *
 * <p>1.20.1 {@code AbstractSelectionList} positions its scrollbar from
 * {@code width / 2 + 124} and treats the six pixels to its right as a scroll
 * drag before consulting the entry. That default does not match this screen's
 * fixed 310 px rows: the right side of the row became unclickable even when no
 * scrollbar was shown. These helpers keep the scrollbar just outside the row
 * and remove its hit band entirely when the list cannot scroll.</p>
 */
final class SettingsListGeometry {

	/** Gap between the row's right edge and the scrollbar track. */
	static final int SCROLLBAR_GAP = 6;

	/** Horizontal room reserved around the row so the scrollbar fits. */
	static final int HORIZONTAL_MARGIN = 20;

	private SettingsListGeometry() {}

	/**
	 * Scrollbar X for the given row and list width.
	 *
	 * <p>When {@code maxScroll <= 0} the returned position is the list's right
	 * edge, so {@code updateScrollingState}'s {@code [x, x + 6)} band can never
	 * be reached by the mouse and every pixel of the row remains clickable.
	 * The vanilla scrollbar draw itself is already guarded by
	 * {@code maxScroll > 0}.</p>
	 */
	static int scrollbarX(int rowRight, int width, int maxScroll) {
		if (maxScroll <= 0) {
			return width;
		}

		return Math.max(0, Math.min(rowRight + SCROLLBAR_GAP, width - SCROLLBAR_GAP));
	}

	/** Row width that leaves room for the scrollbar without overflowing. */
	static int rowWidth(int width) {
		return Math.max(0, Math.min(
			SettingsScreenLayout.LARGE_WIDGET_WIDTH,
			width - HORIZONTAL_MARGIN));
	}
}
