package nx.pingwheel.common.screen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsListFocusTest {
	private record Row(String widget, int top, int height) {}

	@Test
	void mouseValidationFromBottomExplicitlyRevealsTheFirstInvalidRowAfterDeferredRebuild() {
		var port = new Viewport(false);
		var entries = List.of(new Row("physical", 20, 20), new Row("grace", 1000, 20));
		var dispatch = new DeferredActionCoordinator();
		dispatch.dispatch(() -> {
			dispatch.runAfterDispatch(() -> {
				port.events.add("rebuild");
				port.scroll = 900; // Retained bottom viewport restored by the Performance rebuild.
				SettingsListFocus.focus(entries, row -> row.widget().equals("physical"),
					port::focus, port::ensureVisible, true);
			});
			assertEquals(900, port.scroll);
			assertNull(port.focused);
			return null;
		});
		assertEquals(List.of("rebuild", "focus", "reveal"), port.events);
		assertEquals("physical", port.focused.widget());
		assertTrue(port.visible(port.focused));
		assertEquals(20, port.scroll);
	}

	@Test
	void normalMouseNavigationFocusKeepsItsRetainedBottomViewport() {
		var port = new Viewport(false);
		var row = new Row("physical", 20, 20);
		assertTrue(SettingsListFocus.focus(List.of(row), ignored -> true, port::focus, port::ensureVisible, false));
		assertEquals(List.of("focus"), port.events);
		assertEquals(900, port.scroll);
		assertFalse(port.visible(row));
	}

	@Test
	void validationAlreadyVisibleDoesNotResetTheViewportAndUnknownWidgetDoesNothing() {
		var port = new Viewport(false);
		var row = new Row("snapshot", 960, 20);
		assertTrue(SettingsListFocus.focus(List.of(row), ignored -> true, port::focus, port::ensureVisible, true));
		assertEquals(900, port.scroll);
		assertTrue(port.visible(row));
		port.events.clear();
		assertFalse(SettingsListFocus.focus(List.of(row), ignored -> false, port::focus, port::ensureVisible, true));
		assertTrue(port.events.isEmpty());
		assertEquals(900, port.scroll);
	}

	@Test
	void explicitRevealAlsoHandlesLowerRowsAndKeyboardOriginWithoutATopReset() {
		var port = new Viewport(true);
		port.scroll = 0;
		var row = new Row("grace", 1000, 20);
		SettingsListFocus.focus(List.of(row), ignored -> true, port::focus, port::ensureVisible, true);
		assertEquals(860, port.scroll);
		assertTrue(port.visible(row));
	}

	/** Native-equivalent focus port: only keyboard focus auto-scrolls; explicit reveal is unconditional. */
	private static final class Viewport {
		private final boolean keyboard;
		private final int height = 160;
		private final List<String> events = new ArrayList<>();
		private int scroll = 900;
		private Row focused;
		private Viewport(boolean keyboard) { this.keyboard = keyboard; }
		private void focus(Row row) {
			events.add("focus");
			focused = row;
			if (keyboard) reveal(row);
		}
		private void ensureVisible(Row row) {
			events.add("reveal");
			reveal(row);
		}
		private void reveal(Row row) {
			if (row.top() < scroll) scroll = row.top();
			else if (row.top() + row.height() > scroll + height) scroll = row.top() + row.height() - height;
		}
		private boolean visible(Row row) {
			return row.top() >= scroll && row.top() + row.height() <= scroll + height;
		}
	}
}
