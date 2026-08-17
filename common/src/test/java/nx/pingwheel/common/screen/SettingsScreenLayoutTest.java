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
		assertTrue(layout.channelX() >= 0);
		assertTrue(layout.channelX() + SettingsScreenLayout.CHANNEL_WIDTH <= width);
		assertTrue(layout.channelY() >= layout.footerTop());
		assertTrue(layout.channelY() + SettingsScreenLayout.CHANNEL_HEIGHT <= layout.footerBottom());
		assertTrue(layout.channelLabelY() >= layout.footerTop());
	}
}
