package nx.pingwheel.common.screen;

import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real native list dispatch/reflow, without a window, renderer, networking, or config persistence. */
@NotNullByDefault
class SettingsOptionsListTest {
	@Test
	void overviewPairsStackInRowMajorOrderAndPairAgainWhenTheViewportWidens() throws ReflectiveOperationException {
		withClient(client -> {
			var list = list(client);
			var presses = new AtomicInteger();
			@Unmodifiable List<Button> buttons = List.of(button("A", presses), button("B", presses),
				button("C", presses), button("D", presses), button("E", presses), button("F", presses), button("G", presses));
			list.addSmall(buttons.get(0), buttons.get(1));
			list.addSmall(buttons.get(2), buttons.get(3));
			list.addSmall(buttons.get(4), buttons.get(5));
			list.addSmall(buttons.get(6), null);
			assertEquals(List.of(List.of(buttons.get(0), buttons.get(1)), List.of(buttons.get(2), buttons.get(3)),
				List.of(buttons.get(4), buttons.get(5)), List.of(buttons.get(6))), rows(list));

			list.updateSizeAndPosition(320, 100, 10);

			assertEquals(7, list.children().size());
			assertEquals(buttons, entries(list).stream().map(entry -> entry.children().getFirst()).toList());
			for (int index = 0; index < buttons.size(); index++) {
				assertEquals(296, buttons.get(index).getWidth(), "each stacked control fills the available column");
				assertEquals(12, buttons.get(index).getX());
				if (index > 0) assertTrue(buttons.get(index).getY() > buttons.get(index - 1).getY());
			}
			list.updateSizeAndPosition(426, 100, 10);
			assertEquals(4, list.children().size());
			assertEquals(150, buttons.getFirst().getWidth());
			assertEquals(buttons.get(0).getY(), buttons.get(1).getY());
			assertEquals(310, buttons.getLast().getWidth(), "the odd final category remains a singleton");
			assertEquals(0, presses.get());
		});
	}

	@Test
	void editorReflowRetainsTextSelectionFocusDraggingScrollAndTheSameNativeWidgets() throws ReflectiveOperationException {
		withClient(client -> {
			var list = list(client);
			var edits = new AtomicInteger();
			var presses = new AtomicInteger();
			var field = new PresentationSelectorEditBox(null, 0, 0, 150, 20, Component.literal("Selector"));
			field.setMaxLength(128);
			field.setValue("minecraft:entity.*");
			field.setCursorPosition(4);
			field.setHighlightPos(12);
			field.setResponder(ignored -> edits.incrementAndGet());
			var add = button("Add", presses);
			list.addSmall(button("Allow", presses), button("Block", presses));
			list.addSmall(field, add);
			for (int index = 0; index < 10; index++) list.addSmall(button("Following", presses), null);
			focus(list, field);
			list.setDragging(true);
			focusedEntry(list).setDragging(true);
			list.setClampedScrollAmount(25);

			for (int width : new int[]{320, 426, 320, 426}) {
				list.updateSizeAndPosition(width, 100, 10);
				assertSame(field, focusedEntry(list).getFocused());
				assertTrue(field.isFocused());
				assertTrue(list.isDragging());
				assertTrue(focusedEntry(list).isDragging());
				assertEquals(25.0, list.getScrollAmount());
				assertEquals("minecraft:entity.*", field.getValue());
				assertEquals(4, field.trackedCursor());
				assertEquals(12, field.trackedHighlight());
				if (width == 320) {
					assertEquals(List.of(field), entries(list).get(2).children());
					assertEquals(List.of(add), entries(list).get(3).children());
					assertEquals(296, field.getWidth());
				} else {
					assertEquals(List.of(field, add), entries(list).get(1).children());
				}
			}
			assertEquals(0, edits.get(), "resize must not invoke even a text responder");
			assertEquals(0, presses.get());
		});
	}

	@Test
	void resizeMeasuresCurrentMessagesAndTheUntrimmedSelectorRatherThanPreviousPhysicalRows() throws ReflectiveOperationException {
		withClient(client -> {
			var list = list(client);
			var presses = new AtomicInteger();
			var left = button("Left", presses);
			var right = button("Right", presses);
			var label = button("Selector", presses);
			var remove = button("Remove", presses);
			list.addSmall(left, right);
			list.addSmall(label, remove);
			Component fullLabel = Component.literal("minecraft:entity.some_very_long_field_name");
			list.truncateLabel(label, fullLabel);
			left.setMessage(Component.literal("A much longer translated caption for this setting"));
			assertEquals(2, list.children().size(), "a message update alone must not reflow mid-input");

			list.updateSizeAndPosition(426, 100, 10);

			assertEquals(List.of(List.of(left), List.of(right), List.of(label), List.of(remove)), rows(list));
			assertEquals(310, left.getWidth());
			assertEquals(fullLabel.getString(), label.getMessage().getString(), "the full-width label recovers its original text");
			left.setMessage(Component.literal("Left"));
			list.updateSizeAndPosition(426, 100, 10);
			assertEquals(List.of(List.of(left, right), List.of(label), List.of(remove)), rows(list));
			assertEquals(0, presses.get());
		});
	}

	@ParameterizedTest
	@CsvSource({"0, 100", "24, 100", "32, 100", "426, 0", "426, 19"})
	void unusableListClearsNativeSliderFocusAndDraggingAndFencesEveryInputRoute(int width, int height)
		throws ReflectiveOperationException {
		withClient(client -> {
			var list = list(client);
			var slider = new NativeSlider();
			list.addSmall(slider, null);
			focus(list, slider);
			assertTrue(list.keyPressed(32, 0, 0));
			list.setDragging(true);
			focusedEntry(list).setDragging(true);
			double initial = slider.fraction();

			list.updateSizeAndPosition(width, height, 10);

			assertFalse(list.hasUsableViewport());
			assertNull(list.getFocused());
			assertFalse(slider.isFocused());
			assertFalse(slider.visible);
			assertTrue(slider.active, "geometry must not revoke permission-derived editability");
			assertFalse(list.isDragging());
			assertFalse(entries(list).getFirst().isDragging());
			assertNull(list.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));
			assertNull(list.getCurrentFocusPath());
			// Even an attempted stale focus restoration must not reach the native slider.
			entries(list).getFirst().setFocused(slider);
			list.setFocused(entries(list).getFirst());
			assertNull(list.getFocused());
			assertFalse(list.keyPressed(32, 0, 0));
			assertFalse(list.keyPressed(262, 0, 0));
			assertFalse(list.keyReleased(262, 0, 0));
			assertFalse(list.charTyped('x', 0));
			assertFalse(list.mouseClicked(12, 15, 0));
			assertFalse(list.mouseDragged(100, 15, 0, 50, 0));
			assertFalse(list.mouseReleased(100, 15, 0));
			assertFalse(list.mouseScrolled(12, 15, 0, -1));
			assertEquals(initial, slider.fraction());
			assertEquals(0, slider.edits.get());

			list.updateSizeAndPosition(426, 100, 10);
			assertTrue(slider.visible);
			assertTrue(slider.active);
			focus(list, slider);
			assertTrue(list.keyPressed(32, 0, 0));
			assertTrue(list.keyPressed(262, 0, 0));
			assertTrue(slider.fraction() > initial, "the gate must reopen for normal native input");
			assertEquals(1, slider.edits.get());
		});
	}

	@Test
	void nativeSliderActuallyDemonstratesTheHiddenKeyHazardAndTheListAlsoRespectsReadOnlyState()
		throws ReflectiveOperationException {
		var unguarded = new NativeSlider();
		SettingsRowWidgets.position(SettingsScreenLayout.contentFor(0), 10, List.of(unguarded));
		assertTrue(unguarded.keyPressed(32, 0, 0));
		assertTrue(unguarded.keyPressed(262, 0, 0));
		assertNotEquals(0.5, unguarded.fraction(), "visible=false is not a native slider keyboard gate");

		withClient(client -> {
			var list = list(client);
			var slider = new NativeSlider();
			list.addSmall(slider, null);
			focus(list, slider);
			slider.active = false;
			assertFalse(list.keyPressed(32, 0, 0));
			assertFalse(list.keyPressed(262, 0, 0));
			list.updateSizeAndPosition(0, 100, 10);
			list.updateSizeAndPosition(426, 100, 10);
			assertFalse(slider.active);
			assertEquals(0.5, slider.fraction());
			assertEquals(0, slider.edits.get());
		});
	}

	@Test
	void unchangedNativeEntryPreservesSliderAdjustmentModeAndARealKeyStillEditsAfterReflow() throws ReflectiveOperationException {
		withClient(client -> {
			var list = list(client);
			var slider = new NativeSlider();
			list.addSmall(slider, null);
			list.addSmall(button("Left", new AtomicInteger()), button("Right", new AtomicInteger()));
			focus(list, slider);
			ContainerEventHandler entry = focusedEntry(list);
			assertTrue(list.keyPressed(32, 0, 0));

			list.updateSizeAndPosition(320, 100, 10);

			assertSame(entry, list.getFocused());
			assertEquals(0, slider.edits.get());
			assertTrue(list.keyPressed(262, 0, 0), "resize must not exit native adjustment mode");
			assertEquals(1, slider.edits.get());
		});
	}

	private static SettingsScreen.SettingsOptionsList list(Minecraft client) {
		var list = new SettingsScreen.SettingsOptionsList(client, 426, new HeadlessScreen(),
			text -> text.getString().length() * 6,
			(text, width) -> Component.literal(text.getString().substring(0, Math.min(text.getString().length(), width / 6))));
		list.updateSizeAndPosition(426, 100, 10);
		return list;
	}

	private static @Unmodifiable List<@Unmodifiable List<GuiEventListener>> rows(SettingsScreen.SettingsOptionsList list) {
		return entries(list).stream().map(entry -> List.<GuiEventListener>copyOf(entry.children())).toList();
	}

	private static @Unmodifiable List<ContainerEventHandler> entries(SettingsScreen.SettingsOptionsList list) {
		List<?> entries = list.children();
		return entries.stream().map(ContainerEventHandler.class::cast).toList();
	}

	private static ContainerEventHandler focusedEntry(SettingsScreen.SettingsOptionsList list) {
		return assertInstanceOf(ContainerEventHandler.class, list.getFocused());
	}

	private static Button button(String name, AtomicInteger presses) {
		return Button.builder(Component.literal(name), ignored -> presses.incrementAndGet()).bounds(0, 0, 150, 20).build();
	}

	private static void focus(SettingsScreen.SettingsOptionsList list, AbstractWidget widget) {
		var entry = entries(list).stream().filter(row -> row.children().contains(widget)).findFirst().orElseThrow();
		entry.setFocused(widget);
		list.setFocused(entry);
	}

	/** Native focus requires only input provenance; never create the Minecraft window/lifecycle. */
	private static void withClient(Consumer<Minecraft> body) throws ReflectiveOperationException {
		Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		var client = (Minecraft) unsafeClass.getMethod("allocateInstance", Class.class)
			.invoke(unsafeField.get(null), Minecraft.class);
		client.setLastInputType(InputType.KEYBOARD_ARROW);
		var instanceField = Minecraft.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		@Nullable Object previous = instanceField.get(null);
		instanceField.set(null, client);
		try {
			body.accept(client);
		} finally {
			instanceField.set(null, previous);
		}
	}

	@NotNullByDefault
	private static final class HeadlessScreen extends OptionsSubScreen {
		private HeadlessScreen() {
			super(null, null, Component.literal("Settings"));
			this.width = 426;
			this.height = 240;
		}

		@Override
		protected void addOptions() {
		}
	}

	/** Inherits the actual vanilla key/drag logic, including its missing visible/active checks. */
	@NotNullByDefault
	private static final class NativeSlider extends AbstractSliderButton {
		private final AtomicInteger edits = new AtomicInteger();

		private NativeSlider() {
			super(0, 0, 150, 20, Component.literal("Native slider"), 0.5);
		}

		private double fraction() {
			return this.value;
		}

		@Override
		protected void updateMessage() {
		}

		@Override
		protected void applyValue() {
			this.edits.incrementAndGet();
		}
	}
}
