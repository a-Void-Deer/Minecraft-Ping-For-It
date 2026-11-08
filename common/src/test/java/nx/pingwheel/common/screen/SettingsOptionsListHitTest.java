package nx.pingwheel.common.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises the real list hit-test path, not only the pure geometry helpers.
 *
 * <p>1.20.1 {@code AbstractSelectionList.getEntryAtPosition} centers the row
 * with an unshifted origin, while the inherited {@code getRowLeft()} adds two
 * pixels. This test adds real entries to a real {@code SettingsOptionsList} and
 * proves that the drawn row origin, the native entry hit range, and the
 * outward scrollbar band all agree.</p>
 */
class SettingsOptionsListHitTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 300;
	private static final int TOP = 57;
	private static final int BOTTOM = 230;

	@Test
	void rowOriginMatchesTheNativeEntryHitRange() {
		SettingsScreen.SettingsOptionsList list = listWithRows(20);

		assertEquals(45, list.getRowLeft());
		assertEquals(45 + 310, list.getRowRight());
		assertEquals(361, list.getScrollbarPosition());

		int rowY = 61 + 5;

		// The native right edge is inclusive; the scrollbar starts at 361.
		assertNotNull(list.entryAt(354.0D, rowY));
		assertNotNull(list.entryAt(355.0D, rowY));
		assertNull(list.entryAt(356.0D, rowY));
		assertNull(list.entryAt(361.0D, rowY));
	}

	@Test
	void fullyCoveredListHasNoScrollbarHitBand() {
		SettingsScreen.SettingsOptionsList list = listWithRows(1);

		assertEquals(WIDTH, list.getScrollbarPosition());
		assertNotNull(list.entryAt(354.0D, 61 + 5));
		assertNull(list.entryAt(390.0D, 61 + 5));
	}

	private static SettingsScreen.SettingsOptionsList listWithRows(int rows) {
		SettingsScreen.SettingsOptionsList list =
			new SettingsScreen.SettingsOptionsList(null, WIDTH, HEIGHT, TOP, BOTTOM, 25);
		list.updateSize(WIDTH, HEIGHT, TOP, BOTTOM);

		for (int index = 0; index < rows; index++) {
			list.addSmall(new DummyWidget(), null);
		}

		return list;
	}

	private static final class DummyWidget extends AbstractWidget {
		private DummyWidget() {
			super(0, 0, 10, 10, Component.empty());
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narration) {
		}
	}
}
