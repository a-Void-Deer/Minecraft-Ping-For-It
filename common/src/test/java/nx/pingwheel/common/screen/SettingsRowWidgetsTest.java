package nx.pingwheel.common.screen;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Settings UI contract: geometry changes the original native controls' hit/focus
 * bounds, not their values, callbacks or read-only state. These are headless
 * Button seams, not a rendered OptionsList or screen-dispatch fixture.
 */
@NotNullByDefault
class SettingsRowWidgetsTest {
	@Test
	void singletonFillsBothContainerEdgesAndMovesNativeHitAndFocusBoundsWithoutPressing() {
		var presses = new AtomicInteger();
		var button = button("Singleton", -35, 17, 150, presses);
		var message = button.getMessage();
		var content = SettingsScreenLayout.contentFor(640);
		assertEquals(0, presses.get(), "native construction must not invoke the action");
		assertTrue(button.isMouseOver(-34, 18));

		SettingsRowWidgets.position(content, 71, List.of(button));

		assertEquals(content.left(), button.getX());
		assertEquals(content.left() + content.width(), button.getRight());
		assertEquals(71, button.getY());
		assertEquals(20, button.getHeight());
		assertTrue(button.visible);
		assertSame(message, button.getMessage());
		assertNativeBounds(button, new ScreenRectangle(content.left(), 71, content.width(), 20));
		assertFalse(button.isMouseOver(-34, 18), "the old native position must no longer be a hit");
		@Nullable ComponentPath focus = button.nextFocusPath(new FocusNavigationEvent.TabNavigation(true));
		assertNotNull(focus);
		assertSame(button, focus.component(), "native focus must still target the original widget");
		focus.applyFocus(true);
		assertTrue(button.isFocused());
		assertEquals(0, presses.get(), "positioning and acquiring focus are not input actions");
		button.onPress();
		assertEquals(1, presses.get(), "the retained native button must still own its callback");
	}

	@Test
	void pairUsesEqualNonOverlappingNativeBoundsAcrossTheWholeContainer() {
		var leftPresses = new AtomicInteger();
		var rightPresses = new AtomicInteger();
		var left = button("Left", -35, 17, 150, leftPresses);
		var right = button("Right", 125, 17, 310, rightPresses);
		var content = SettingsScreenLayout.contentFor(640);
		assertEquals(0, leftPresses.get());
		assertEquals(0, rightPresses.get());

		SettingsRowWidgets.position(content, 91, List.of(left, right));

		assertEquals(content.left(), left.getX());
		assertEquals(content.left() + content.width(), right.getRight());
		assertEquals(left.getWidth(), right.getWidth());
		assertTrue(left.getWidth() > 0);
		assertEquals(10, right.getX() - left.getRight(), "the native pair keeps its inter-control gap");
		assertEquals(91, left.getY());
		assertEquals(left.getY(), right.getY());
		assertEquals(20, left.getHeight());
		assertEquals(left.getHeight(), right.getHeight());
		assertNativeBounds(left, new ScreenRectangle(content.left(), 91, left.getWidth(), 20));
		assertNativeBounds(right, new ScreenRectangle(right.getX(), 91, right.getWidth(), 20));
		assertFalse(left.getRectangle().overlaps(right.getRectangle()));
		assertFalse(left.isMouseOver(right.getX() + 1, 92));
		assertFalse(right.isMouseOver(left.getX() + 1, 92));
		assertFalse(left.isMouseOver(right.getX() - 1, 92));
		assertFalse(right.isMouseOver(right.getX() - 1, 92), "the gap must not activate either control");
		assertEquals(0, leftPresses.get());
		assertEquals(0, rightPresses.get());
		left.onPress();
		right.onPress();
		assertEquals(1, leftPresses.get());
		assertEquals(1, rightPresses.get());
	}

	@Test
	void narrowSingletonReplacesTheNativeFixedOffsetAndResizesAgainWithoutLosingFocus() {
		var presses = new AtomicInteger();
		// Native OptionsList.Entry starts at viewport / 2 - 155: -35 at 240 px.
		var button = button("Narrow", -35, 57, 150, presses);
		var narrow = SettingsScreenLayout.contentFor(240);
		assertTrue(button.isMouseOver(-34, 58));
		button.setFocused(true);

		SettingsRowWidgets.position(narrow, 57, List.of(button));

		assertEquals(narrow.left(), button.getX());
		assertEquals(narrow.left() + narrow.width(), button.getRight());
		assertTrue(button.getX() >= 0);
		assertTrue(button.getRight() <= 240);
		assertNativeBounds(button, new ScreenRectangle(narrow.left(), 57, narrow.width(), 20));
		assertFalse(button.isMouseOver(-34, 58), "native fixed-offset hits must move with the control");
		assertTrue(button.isFocused());

		var wide = SettingsScreenLayout.contentFor(800);
		SettingsRowWidgets.position(wide, 117, List.of(button));

		assertNativeBounds(button, new ScreenRectangle(wide.left(), 117, wide.width(), 20));
		assertFalse(button.isMouseOver(narrow.left() + 1, 58));
		assertTrue(button.isFocused());
		assertEquals(0, presses.get(), "repeated resize/reposition must not perform an edit");
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2})
	void zeroWidthRowsAreHiddenAndInertThenRecoverWithoutChangingEditability(int widgetCount) {
		var presses = new AtomicInteger();
		var editable = button("Editable", -35, 17, 150, presses);
		var readOnly = button("Read only", 125, 17, 150, presses);
		readOnly.active = false;
		@Unmodifiable List<Button> widgets = widgetCount == 1 ? List.of(editable) : List.of(editable, readOnly);
		SettingsRowWidgets.position(SettingsScreenLayout.contentFor(640), 71, widgets);
		SettingsRowWidgets.position(SettingsScreenLayout.contentFor(0), 101, widgets);

		for (Button widget : widgets) {
			assertEquals(0, widget.getWidth());
			assertEquals(101, widget.getY());
			assertEquals(20, widget.getHeight());
			assertFalse(widget.visible);
			assertFalse(widget.isMouseOver(widget.getX(), 102));
			assertFalse(widget.getRectangle().containsPoint(widget.getX(), 102));
			assertNull(widget.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));
			assertFalse(widget.mouseClicked(widget.getX(), 102, 0));
			assertFalse(widget.keyPressed(32, 0, 0));
		}
		assertTrue(editable.active, "zero width hides a widget; it must not revoke editability");
		assertFalse(readOnly.active);
		assertEquals(0, presses.get());

		SettingsRowWidgets.position(SettingsScreenLayout.contentFor(640), 131, widgets);

		for (Button widget : widgets) {
			assertTrue(widget.visible, "a restored viewport must restore the original control");
			assertTrue(widget.getWidth() > 0);
			assertEquals(131, widget.getY());
			assertEquals(20, widget.getHeight());
		}
		assertTrue(editable.active);
		assertTrue(editable.isMouseOver(editable.getX() + 1, 132));
		assertNotNull(editable.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));
		assertFalse(readOnly.active);
		if (widgetCount == 2) {
			assertFalse(readOnly.isMouseOver(readOnly.getX() + 1, 132));
			assertNull(readOnly.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));
			assertFalse(readOnly.mouseClicked(readOnly.getX() + 1, 132, 0));
			assertFalse(readOnly.keyPressed(32, 0, 0));
		}
		assertEquals(0, presses.get());
	}

	@Test
	void pairEligibilityUsesBothCurrentNativeMessagesWithoutActivatingTheControls() {
		var presses = new AtomicInteger();
		var left = button("Left", -35, 17, 150, presses);
		var right = button("Right", 125, 17, 150, presses);
		var content = SettingsScreenLayout.contentFor(640);
		// Deterministic measured widths exercise the helper's measurement bridge,
		// not Minecraft font metrics, translated rendering or screen-level stacking.
		ToIntFunction<AbstractWidget> measure = widget -> widget.getMessage().getString().length() * 8;
		assertTrue(SettingsRowWidgets.fitsPair(content, left, right, measure));

		right.setMessage(Component.literal("Long right label ".repeat(20)));
		assertFalse(SettingsRowWidgets.fitsPair(content, left, right, measure));
		right.setMessage(Component.literal("Right"));
		left.setMessage(Component.literal("Long left label ".repeat(20)));
		assertFalse(SettingsRowWidgets.fitsPair(content, left, right, measure));
		left.setMessage(Component.literal("Left"));
		assertTrue(SettingsRowWidgets.fitsPair(content, left, right, measure));
		assertFalse(SettingsRowWidgets.fitsPair(SettingsScreenLayout.contentFor(240), left, right, measure));
		assertEquals(0, presses.get());
	}

	private static Button button(String label, int x, int y, int width, AtomicInteger presses) {
		return Button.builder(Component.literal(label), button -> presses.incrementAndGet())
			.bounds(x, y, width, 20).build();
	}

	private static void assertNativeBounds(Button button, ScreenRectangle expected) {
		assertTrue(button.active);
		assertTrue(button.visible);
		assertEquals(expected, button.getRectangle(), "native focus bounds must follow the row geometry");
		assertTrue(button.getRectangle().containsPoint(expected.left(), expected.top()));
		assertTrue(button.isMouseOver(expected.left(), expected.top()));
		assertTrue(button.isMouseOver(expected.right() - 1, expected.bottom() - 1));
		assertFalse(button.isMouseOver(expected.left() - 1, expected.top()));
		assertFalse(button.isMouseOver(expected.right(), expected.top()));
		assertFalse(button.isMouseOver(expected.left(), expected.top() - 1));
		assertFalse(button.isMouseOver(expected.left(), expected.bottom()));
	}
}
