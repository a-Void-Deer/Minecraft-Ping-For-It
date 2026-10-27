package nx.pingwheel.common.screen;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class SettingsScreenLayoutTest {
	@Test
	void contentColumnCapsAtTheLargeWidthAndStaysEvenAndCentered() {
		assertContent(426, 310, 58);
		assertContent(334, 310, 12);
		assertContent(335, 310, 12);
		assertContent(320, 296, 12);
		assertContent(333, 308, 12);
		assertContent(310, 286, 12);
	}

	@Test
	void contentColumnShrinksToZeroWithoutNegativeGeometry() {
		assertContent(24, 0, 12);
		assertContent(23, 0, 11);
		assertContent(12, 0, 6);
		assertContent(0, 0, 0);
		assertContent(-5, 0, 0);
	}

	@Test
	void pairedColumnsSplitTheContentAndLeaveNoNegativeHalfWidth() {
		assertEquals(150, SettingsScreenLayout.contentFor(334).halfWidth());
		assertEquals(149, SettingsScreenLayout.contentFor(333).halfWidth());
		assertEquals(143, SettingsScreenLayout.contentFor(320).halfWidth());
		assertEquals(0, SettingsScreenLayout.contentFor(12).halfWidth());
	}

	@Test
	void pairLayoutRequiresFullWidthAndLabelsWithin146Pixels() {
		var wide = SettingsScreenLayout.contentFor(426);
		assertTrue(wide.fitsPair(146, 146));
		assertFalse(wide.fitsPair(147, 146));
		assertFalse(wide.fitsPair(146, 147));
		assertFalse(wide.fitsPair(147, 147));

		var threshold = SettingsScreenLayout.contentFor(334);
		assertTrue(threshold.fitsPair(146, 146));

		assertFalse(SettingsScreenLayout.contentFor(333).fitsPair(0, 0),
			"a viewport below 334 must stack even short paired controls");
		assertFalse(SettingsScreenLayout.contentFor(320).fitsPair(0, 0));
		assertFalse(SettingsScreenLayout.contentFor(0).fitsPair(0, 0));
	}

	@Test
	void footerActionWidthsClampToTheAvailableScreen() {
		assertEquals(37, SettingsScreenLayout.buttonWidthFor(320, 37));
		assertEquals(320, SettingsScreenLayout.buttonWidthFor(320, 999));
		assertEquals(99, SettingsScreenLayout.buttonWidthFor(99, 150));
		assertEquals(80, SettingsScreenLayout.buttonWidthFor(80, 150));
		assertEquals(80, SettingsScreenLayout.buttonWidthFor(80, 999));
		assertEquals(0, SettingsScreenLayout.buttonWidthFor(0, 150));
		assertEquals(0, SettingsScreenLayout.buttonWidthFor(-1, 150));
		assertEquals(0, SettingsScreenLayout.buttonWidthFor(320, -1));
		assertEquals(0, SettingsScreenLayout.buttonWidthFor(-1, -1));
	}

	@Test
	void narrowFooterActionsAreCenteredAtTheClampedWidth() {
		var narrow = SettingsScreenLayout.calculate(
			80,
			180,
			SettingsScreenLayout.ROOT_HEADER_HEIGHT,
			SettingsScreenLayout.ROOT_FOOTER_HEIGHT);

		assertEquals(0, narrow.resetX());
		assertEquals(0, narrow.doneX());
		assertTrue(narrow.resetY() >= narrow.footerTop());
		assertTrue(narrow.doneY() >= narrow.footerTop());
		assertTrue(narrow.resetY() + SettingsScreenLayout.RESET_BUTTON_HEIGHT <= narrow.footerBottom());
		assertTrue(narrow.doneY() + SettingsScreenLayout.PRIMARY_BUTTON_HEIGHT <= narrow.footerBottom());
	}

	@Test
	void zeroSizedLayoutHasNoNegativeGeometry() {
		var zero = SettingsScreenLayout.calculate(0, 0, 0, 0);

		assertEquals(0, zero.listTop());
		assertEquals(0, zero.listBottom());
		assertEquals(0, zero.footerTop());
		assertEquals(0, zero.footerBottom());
		assertEquals(0, zero.resetX());
		assertEquals(0, zero.resetY());
		assertEquals(0, zero.doneX());
		assertEquals(0, zero.doneY());
	}

	private static void assertContent(int screenWidth, int expectedWidth, int expectedLeft) {
		var content = SettingsScreenLayout.contentFor(screenWidth);
		final int safeWidth = Math.max(0, screenWidth);
		final int rightMargin = safeWidth - content.left() - content.width();

		assertEquals(expectedWidth, content.width(), () -> "content width for " + screenWidth);
		assertEquals(expectedLeft, content.left(), () -> "content left for " + screenWidth);
		assertEquals(0, content.width() % 2, () -> "content width must be even for " + screenWidth);
		assertTrue(content.width() <= SettingsScreenLayout.LARGE_WIDGET_WIDTH);
		assertTrue(content.left() >= 0);
		assertTrue(content.left() + content.width() <= safeWidth);
		assertTrue(Math.abs(rightMargin - content.left()) <= 1,
			() -> "content must stay centered within one pixel for " + screenWidth);
		assertTrue(content.scrollbarX() >= 0);
		assertTrue(content.scrollbarX() <= safeWidth);
		assertTrue(content.scrollbarX() >= content.left() + content.width(),
			() -> "scrollbar must follow the content column for " + screenWidth);
	}

	@Test
	void compactGuiKeepsRootAndLeafViewportsAboveTheirFooters() {
		assertLayout(320, 180);
	}

	@Test
	void standardGuiKeepsRootAndLeafViewportsAboveTheirFooters() {
		assertLayout(426, 240);
	}

	@Test
	void largeGuiKeepsTheListAndFooterSeparated() {
		assertLayout(1920, 1080);
	}

	@Test
	void tinyGuiClampsRootAndLeafViewportsWithoutNegativeSpace() {
		assertClampedViewport(
			320,
			SettingsScreenLayout.ROOT_HEADER_HEIGHT - 1,
			SettingsScreenLayout.ROOT_HEADER_HEIGHT,
			false);
		assertClampedViewport(
			320,
			SettingsScreenLayout.ROOT_HEADER_HEIGHT + SettingsScreenLayout.ROOT_FOOTER_HEIGHT - 1,
			SettingsScreenLayout.ROOT_HEADER_HEIGHT,
			false);
		assertClampedViewport(
			320,
			SettingsScreenLayout.LEAF_HEADER_HEIGHT - 1,
			SettingsScreenLayout.LEAF_HEADER_HEIGHT,
			true);
		assertClampedViewport(
			320,
			SettingsScreenLayout.LEAF_HEADER_HEIGHT + SettingsScreenLayout.LEAF_FOOTER_HEIGHT - 1,
			SettingsScreenLayout.LEAF_HEADER_HEIGHT,
			true);
	}

	private static void assertLayout(int width, int height) {
		assertLayout(width, height, SettingsScreenLayout.ROOT_HEADER_HEIGHT, false);
		assertLayout(width, height, SettingsScreenLayout.LEAF_HEADER_HEIGHT, true);
	}

	private static void assertLayout(int width, int height, int headerHeight, boolean leafPage) {
		final int footerHeight = SettingsScreenLayout.footerHeightFor(height, headerHeight, leafPage);
		final var layout = SettingsScreenLayout.calculate(width, height, headerHeight, footerHeight);

		assertEquals(layout.listBottom(), layout.footerTop());
		assertTrue(layout.listTop() <= layout.listBottom());
		assertTrue(layout.footerTop() < layout.footerBottom());
		assertTrue(layout.resetX() >= 0);
		assertTrue(layout.resetX() + SettingsScreenLayout.RESET_BUTTON_WIDTH <= Math.max(width, SettingsScreenLayout.RESET_BUTTON_WIDTH));
		assertTrue(layout.resetY() >= layout.footerTop());
		assertTrue(layout.resetY() + SettingsScreenLayout.RESET_BUTTON_HEIGHT <= layout.footerBottom());
		assertTrue(layout.doneX() >= 0);
		assertTrue(layout.doneX() + SettingsScreenLayout.PRIMARY_BUTTON_WIDTH <= Math.max(width, SettingsScreenLayout.PRIMARY_BUTTON_WIDTH));
		assertTrue(layout.doneY() >= layout.footerTop());
		assertTrue(layout.doneY() + SettingsScreenLayout.PRIMARY_BUTTON_HEIGHT <= layout.footerBottom());
		if (!leafPage) {
			assertTrue(
				layout.resetY() + SettingsScreenLayout.RESET_BUTTON_HEIGHT <= layout.doneY()
				|| layout.doneY() + SettingsScreenLayout.PRIMARY_BUTTON_HEIGHT <= layout.resetY(),
				"Reset and Done must not overlap on an overview");
		}
	}

	private static void assertClampedViewport(int width, int height, int headerHeight, boolean leafPage) {
		final int footerHeight = SettingsScreenLayout.footerHeightFor(height, headerHeight, leafPage);
		final var layout = SettingsScreenLayout.calculate(width, height, headerHeight, footerHeight);

		assertEquals(Math.min(height, headerHeight), layout.listTop());
		assertEquals(layout.listTop(), layout.listBottom());
		assertEquals(layout.listBottom(), layout.footerTop());
		assertEquals(height, layout.footerBottom());
		assertTrue(footerHeight >= 0);
	}
}
