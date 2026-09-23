package nx.pingwheel.common.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsScreenLayoutTest {
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
