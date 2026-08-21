package nx.pingwheel.common.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsScreenLayoutTest {
	@Test
	void compactGuiKeepsTheChannelControlReachable() {
		assertLayout(320, 180);
	}

	@Test
	void standardGuiKeepsTheChannelControlReachable() {
		assertLayout(426, 240);
	}

	@Test
	void largeGuiKeepsTheListAndFooterSeparated() {
		assertLayout(1920, 1080);
	}

	private static void assertLayout(int width, int height) {
		final int headerHeight = 33;
		final int footerHeight = SettingsScreenLayout.footerHeightFor(height, headerHeight);
		final var layout = SettingsScreenLayout.calculate(width, height, headerHeight, footerHeight);

		assertEquals(layout.listBottom(), layout.footerTop());
		assertTrue(layout.listTop() <= layout.listBottom());
		assertTrue(layout.footerTop() < layout.footerBottom());
		assertTrue(layout.resetX() >= 0);
		assertTrue(layout.resetX() + SettingsScreenLayout.RESET_BUTTON_WIDTH <= Math.max(width, SettingsScreenLayout.RESET_BUTTON_WIDTH));
		assertTrue(layout.resetY() >= layout.footerTop());
		assertTrue(layout.resetY() + SettingsScreenLayout.RESET_BUTTON_HEIGHT <= layout.footerBottom());
		assertTrue(layout.doneX() >= 0);
		assertTrue(layout.doneX() + SettingsScreenLayout.DONE_BUTTON_WIDTH <= Math.max(width, SettingsScreenLayout.DONE_BUTTON_WIDTH));
		assertTrue(layout.doneY() >= layout.footerTop());
		assertTrue(layout.doneY() + SettingsScreenLayout.DONE_BUTTON_HEIGHT <= layout.footerBottom());
		assertTrue(
			layout.resetY() + SettingsScreenLayout.RESET_BUTTON_HEIGHT <= layout.doneY()
			|| layout.doneY() + SettingsScreenLayout.DONE_BUTTON_HEIGHT <= layout.resetY(),
			"Reset and Done must not overlap");
	}
}
