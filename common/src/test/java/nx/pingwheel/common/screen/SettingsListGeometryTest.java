package nx.pingwheel.common.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the 1.20.1 settings-list scrollbar geometry.
 *
 * <p>The row is 310 px wide, but 1.20.1's default scrollbar cutoff is
 * {@code width / 2 + 124}. At a 400 px GUI that rejected clicks from x = 324
 * even though the row reached x = 357, and it also rejected that band when no
 * scrollbar was shown.</p>
 */
class SettingsListGeometryTest {

	@Test
	void scrollbarSitsJustRightOfTheRow() {
		// width 400: getRowLeft = 47, rowRight = 357.
		assertEquals(363, SettingsListGeometry.scrollbarX(357, 400, 120));
	}

	@Test
	void noScrollableContentRemovesTheHitBand() {
		assertEquals(400, SettingsListGeometry.scrollbarX(357, 400, 0),
			"without scrolling the band must be outside the mouse-reachable width");
		assertEquals(400, SettingsListGeometry.scrollbarX(357, 400, -5));
	}

	@Test
	void narrowScreensKeepTheScrollbarInsideTheList() {
		int width = 320;
		int rowWidth = SettingsListGeometry.rowWidth(width);
		int rowLeft = width / 2 - rowWidth / 2 + 2;
		int rowRight = rowLeft + rowWidth;

		assertEquals(300, rowWidth);
		assertEquals(312, rowRight);

		int scrollbar = SettingsListGeometry.scrollbarX(rowRight, width, 120);
		assertTrue(scrollbar >= rowRight,
			"the scrollbar hit band must not overlap the clickable row");
		assertTrue(scrollbar + SettingsListGeometry.SCROLLBAR_GAP <= width,
			"the scrollbar track must remain reachable inside the window");
	}

	@Test
	void rowWidthShrinksInsteadOfOverflowingAPane() {
		assertEquals(SettingsScreenLayout.LARGE_WIDGET_WIDTH, SettingsListGeometry.rowWidth(400));
		assertEquals(300, SettingsListGeometry.rowWidth(320));
		assertEquals(180, SettingsListGeometry.rowWidth(200));
		assertEquals(0, SettingsListGeometry.rowWidth(10));
	}

	@Test
	void wideRowNeverPutsTheScrollbarOnTopOfTheRow() {
		int width = 400;
		int rowWidth = SettingsListGeometry.rowWidth(width);
		int rowRight = width / 2 - rowWidth / 2 + 2 + rowWidth;

		int scrollbar = SettingsListGeometry.scrollbarX(rowRight, width, 1);
		assertEquals(rowRight + SettingsListGeometry.SCROLLBAR_GAP, scrollbar);
	}
}
