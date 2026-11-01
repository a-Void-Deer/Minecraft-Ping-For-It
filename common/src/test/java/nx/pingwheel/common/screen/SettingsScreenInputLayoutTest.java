package nx.pingwheel.common.screen;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.SharedConstants;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.InputType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Production screen/native input with bundled Turkish text and supplied metrics, not live fonts or a window. */
@NotNullByDefault
@ResourceLock("minecraft.client")
@ResourceLock("minecraft.language")
class SettingsScreenInputLayoutTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void completedMouseGestureStacksCurrentTurkishCaptionsAndPairsTheSameControlsAgainWithoutResize() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			openMarkers(screen);
			var list = optionsList(screen);
			var player = cycleButton(screen, PlayerInfoMode.HOLD);
			var team = cycleButton(screen, TeamColorMode.FULL);
			@Unmodifiable List<AbstractWidget> original = widgets(screen);
			assertEquals("Oyuncu Bilgisi: Basılı Tut", player.getMessage().getString());
			assertEquals("Takım Rengi: Tam", team.getMessage().getString());
			assertPaired(screen, player, team);
			list.setClampedScrollAmount(25);
			assertEquals(25.0, list.getScrollAmount());

			click(screen, team);
			assertEquals(TeamColorMode.DISABLED, fixture.config().getTeamColorMode());
			click(screen, team);
			assertEquals(TeamColorMode.PING_ONLY, fixture.config().getTeamColorMode());
			assertPaired(screen, player, team);
			double x = team.getX() + team.getWidth() / 2.0;
			double y = team.getY() + team.getHeight() / 2.0;
			fixture.options().onSave = () -> assertPaired(screen, player, team);

			assertTrue(screen.mouseClicked(x, y, 0));

			assertEquals(TeamColorMode.LABELS_ONLY, fixture.config().getTeamColorMode());
			assertEquals("Takım Rengi: Yalnızca Etiketler", team.getMessage().getString());
			assertPaired(screen, player, team, "native callbacks and a still-held gesture must not reflow the list");
			assertTrue(screen.isDragging());
			screen.mouseReleased(x, y, 0);
			assertFalse(screen.isDragging());
			assertStacked(screen, player, team);
			assertSame(list, optionsList(screen));
			assertEquals(original, widgets(screen), "reflow must retain every widget in its original order");
			assertFocused(screen, team);
			assertEquals(25.0, list.getScrollAmount(), "a new physical row must not reset the retained viewport");
			assertEquals(3, fixture.config().teamEdits);
			assertEquals(3, fixture.options().saves);

			fixture.options().onSave = null;
			list.setClampedScrollAmount(50);
			click(screen, team);

			assertEquals(TeamColorMode.FULL, fixture.config().getTeamColorMode());
			assertEquals(PlayerInfoMode.HOLD, fixture.config().getPlayerInfoMode());
			assertPaired(screen, player, team);
			assertFocused(screen, team);
			assertEquals(original, widgets(screen));
			assertTrue(list.getScrollAmount() > 0, "re-pairing may clamp scroll to the shorter list, not reset it");
			assertEquals(4, fixture.config().teamEdits, "geometry must not invoke another option setter");
			assertEquals(4, fixture.options().saves, "geometry must not invoke another native save callback");
		});
	}

	@Test
	void keyboardActivationReflowsOnlyAfterOutermostDispatchAndRetainsFocusScrollAndOneEditPerPress() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			openMarkers(screen);
			var list = optionsList(screen);
			var player = cycleButton(screen, PlayerInfoMode.HOLD);
			var team = cycleButton(screen, TeamColorMode.FULL);
			Minecraft.getInstance().setLastInputType(InputType.KEYBOARD_ARROW);
			focus(screen, team);
			list.setClampedScrollAmount(25);
			fixture.options().onSave = () -> {
				if (fixture.config().getTeamColorMode() == TeamColorMode.LABELS_ONLY) {
					assertPaired(screen, player, team);
					assertFalse(screen.keyPressed(0, 0, 0), "a nested native dispatch is not a completed outer gesture");
					assertPaired(screen, player, team, "the nested dispatch must not reflow its caller's row");
				}
			};

			for (TeamColorMode expected : List.of(TeamColorMode.DISABLED, TeamColorMode.PING_ONLY, TeamColorMode.LABELS_ONLY)) {
				assertTrue(screen.keyPressed(32, 0, 0));
				assertEquals(expected, fixture.config().getTeamColorMode());
				screen.keyReleased(32, 0, 0);
				assertFocused(screen, team);
			}

			assertStacked(screen, player, team);
			assertEquals(25.0, list.getScrollAmount());
			assertEquals(3, fixture.config().teamEdits);
			assertEquals(3, fixture.options().saves);
			assertTrue(screen.keyPressed(257, 0, 0));
			assertEquals(TeamColorMode.FULL, fixture.config().getTeamColorMode());
			assertPaired(screen, player, team);
			assertFocused(screen, team);
			assertEquals(25.0, list.getScrollAmount());
			assertEquals(4, fixture.config().teamEdits);
			assertEquals(4, fixture.options().saves);
		});
	}

	@Test
	void completedWheelDispatchRechecksProgrammaticCaptionsWithoutTurningListScrollIntoAnEnumEdit() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			openMarkers(screen);
			var list = optionsList(screen);
			var player = cycleButton(screen, PlayerInfoMode.HOLD);
			var team = cycleButton(screen, TeamColorMode.FULL);
			focus(screen, team);
			list.setClampedScrollAmount(25);

			team.setValue(TeamColorMode.LABELS_ONLY);
			assertPaired(screen, player, team, "a programmatic message change alone must not reflow during input");
			assertTrue(screen.mouseScrolled(team.getX() + 5, team.getY() + 5, 0, -1));

			assertStacked(screen, player, team);
			assertFocused(screen, team);
			assertTrue(list.getScrollAmount() > 25, "the native list still owns wheel scrolling");
			team.setValue(TeamColorMode.FULL);
			assertTrue(screen.mouseScrolled(player.getX() + 5, player.getY() + 5, 0, 1));
			assertPaired(screen, player, team);
			assertFocused(screen, team);
			assertEquals(TeamColorMode.FULL, fixture.config().getTeamColorMode());
			assertEquals(0, fixture.config().teamEdits);
			assertEquals(0, fixture.options().saves);
		});
	}

	@Test
	void captionReflowWaitsForSliderReleaseAndDoesNotReplayTheDragEdit() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			openMarkers(screen);
			var player = cycleButton(screen, PlayerInfoMode.HOLD);
			var team = cycleButton(screen, TeamColorMode.FULL);
			var slider = widgets(screen).stream().filter(AbstractSliderButton.class::isInstance)
				.map(AbstractSliderButton.class::cast)
				.filter(widget -> hasKey(widget, "settings.pingforit.ping_size")).findFirst().orElseThrow();
			ContainerEventHandler sliderEntry = entryFor(screen, slider);
			team.setValue(TeamColorMode.LABELS_ONLY);
			assertPaired(screen, player, team);
			fixture.options().onSave = () -> assertPaired(screen, player, team);
			double x = slider.getX() + 5;
			double y = slider.getY() + slider.getHeight() / 2.0;
			Minecraft.getInstance().setLastInputType(InputType.MOUSE);

			assertTrue(screen.mouseClicked(x, y, 0));
			assertTrue(screen.isDragging());
			assertPaired(screen, player, team);
			assertFalse(screen.keyPressed(0, 0, 0));
			assertPaired(screen, player, team, "keyboard dispatch during a mouse drag must not move its geometry");
			int clickValue = fixture.config().getPingSize();
			double draggedX = slider.getX() + slider.getWidth() * 0.75;
			assertTrue(screen.mouseDragged(draggedX, y, 0, draggedX - x, 0));
			assertTrue(fixture.config().getPingSize() > clickValue, "the native slider must still receive its drag");
			assertPaired(screen, player, team);
			int draggedValue = fixture.config().getPingSize();
			int savesBeforeRelease = fixture.options().saves;

			screen.mouseReleased(draggedX, y, 0);

			assertStacked(screen, player, team);
			assertSame(sliderEntry, entryFor(screen, slider), "unchanged entries retain the native slider's input state");
			assertFocused(screen, slider);
			assertEquals(draggedValue, fixture.config().getPingSize());
			assertEquals(savesBeforeRelease, fixture.options().saves, "release-time layout must not repeat an edit");
			assertEquals(TeamColorMode.FULL, fixture.config().getTeamColorMode());
			assertEquals(0, fixture.config().teamEdits, "programmatic displayed values remain display-only");
		});
	}

	@Test
	void cancellingResetAfterReleasingOnTheConfirmationDoesNotBlockLaterKeyboardCaptionReflow() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			cancelResetAfterReleaseOnConfirmation(screen);
			keyboardOpenCategory(screen, Category.MARKER_DISPLAY);
			var player = cycleButton(screen, PlayerInfoMode.HOLD);
			var team = cycleButton(screen, TeamColorMode.FULL);
			keyboardFocus(screen, team);
			var list = optionsList(screen);
			list.setClampedScrollAmount(25);

			for (TeamColorMode expected : List.of(TeamColorMode.DISABLED, TeamColorMode.PING_ONLY, TeamColorMode.LABELS_ONLY)) {
				assertTrue(screen.keyPressed(32, 0, 0));
				screen.keyReleased(32, 0, 0);
				assertEquals(expected, fixture.config().getTeamColorMode());
			}

			assertStacked(screen, player, team);
			assertFocused(screen, team);
			assertEquals(25.0, list.getScrollAmount());
			assertGestureCancelled(screen);
			assertTrue(screen.keyPressed(32, 0, 0));
			assertPaired(screen, player, team);
			assertFocused(screen, team);
			assertEquals(TeamColorMode.FULL, fixture.config().getTeamColorMode());
			assertEquals(4, fixture.config().teamEdits);
			assertEquals(4, fixture.options().saves, "cancelling Reset and recovering geometry must not save or edit values");
		});
	}

	@Test
	void cancellingResetDoesNotSuppressPendingKeyboardSizeSliderRefresh() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			cancelResetAfterReleaseOnConfirmation(screen);
			ServerSettingsModel model = openEditablePerformance(screen);
			var frame = sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER);
			var display = sizeSlider(screen, Field.DISPLAY_BYTE_MULTIPLIER);
			keyboardFocus(screen, frame);
			BigDecimal initial = frame.currentValue();
			for (int press = 0; press < 20 && frame.currentValue().equals(initial); press++) {
				assertTrue(screen.keyPressed(263, 0, 0));
			}

			assertTrue(frame.currentValue().compareTo(initial) < 0, "native keyboard input must change the size draft");
			var refreshed = sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER);
			assertNotSame(frame, refreshed, "the completed key edit must refresh dependent controls without a mouse release");
			assertNotSame(display, sizeSlider(screen, Field.DISPLAY_BYTE_MULTIPLIER));
			assertNotNull(model.sizeAdjustment(), "lowering the frame reconciles the initially full display");
			assertTrue(widgets(screen).stream().anyMatch(widget -> hasKey(widget, "settings.pingforit.inventory.size_adjusted")));
			assertEquals(Field.FRAME_BYTE_MULTIPLIER.mask() | Field.DISPLAY_BYTE_MULTIPLIER.mask(), model.dirtyFields());
			assertFocused(screen, refreshed);
			assertGestureCancelled(screen);
			BigDecimal lowered = refreshed.currentValue();
			for (int press = 0; press < 20 && sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER).currentValue().equals(lowered); press++) {
				assertTrue(screen.keyPressed(262, 0, 0), "the replacement must carry keyboard adjustment mode");
			}
			assertEquals(initial, sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER).currentValue());
			assertEquals(0, fixture.options().saves, "ordinary server drafts are not sent or saved by layout refresh");
		});
	}

	@Test
	void suspendingAPendingSizeSliderDragCancelsAllGestureOwnersAndRefreshesAfterReturnWithoutAnotherEdit() throws Exception {
		withScreen(fixture -> {
			var screen = fixture.screen();
			ServerSettingsModel model = openEditablePerformance(screen);
			var frame = sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER);
			var list = optionsList(screen);
			keyboardFocus(screen, frame);
			Minecraft.getInstance().setLastInputType(InputType.MOUSE);
			double x = frame.getX() + 5;
			double y = frame.getY() + frame.getHeight() / 2.0;
			assertTrue(screen.mouseClicked(x, y, 0));
			assertTrue(screen.isDragging());
			assertTrue(list.isDragging());
			assertTrue(entryFor(screen, frame).isDragging());
			assertSame(frame, sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER), "the pending edit must not rebuild mid-drag");
			assertFalse(screen.keyPressed(0, 0, 0));
			assertSame(frame, sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER), "an intervening key must not finish a live size-slider drag");
			assertNotNull(model.sizeAdjustment());
			String frameDraft = model.inventoryText(Field.FRAME_BYTE_MULTIPLIER);
			String displayDraft = model.inventoryText(Field.DISPLAY_BYTE_MULTIPLIER);
			int dirty = model.dirtyFields();
			var client = Minecraft.getInstance();
			client.setScreen(new ConfirmScreen(ignored -> client.setScreen(screen), Component.literal("Pause"), Component.literal("Return")));
			assertGestureCancelled(screen);
			assertFocused(screen, frame);
			var confirmation = assertInstanceOf(ConfirmScreen.class, client.screen);
			confirmation.mouseReleased(x, y, 0);
			assertTrue(confirmation.keyPressed(256, 0, 0));
			assertSame(screen, client.screen);
			assertSame(list, optionsList(screen));
			assertSame(frame, sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER), "suspension itself must not rebuild the retained page");

			assertFalse(screen.keyPressed(0, 0, 0));

			assertNotSame(frame, sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER), "a suspended pending gesture must not wait for a lost release");
			assertGestureCancelled(screen);
			assertFocused(screen, sizeSlider(screen, Field.FRAME_BYTE_MULTIPLIER));
			assertFalse(screen.mouseDragged(x + 30, y, 0, 30, 0), "a stale list/entry drag must not resume after return");
			assertEquals(frameDraft, model.inventoryText(Field.FRAME_BYTE_MULTIPLIER));
			assertEquals(displayDraft, model.inventoryText(Field.DISPLAY_BYTE_MULTIPLIER));
			assertEquals(dirty, model.dirtyFields());
			assertEquals(0, fixture.options().saves);
		});
	}

	private static void cancelResetAfterReleaseOnConfirmation(SettingsScreen screen) {
		var list = optionsList(screen);
		@Unmodifiable List<AbstractWidget> original = widgets(screen);
		var reset = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(widget -> hasKey(widget, "settings.pingforit.reset_all")).findFirst().orElseThrow();
		double x = reset.getX() + reset.getWidth() / 2.0;
		double y = reset.getY() + reset.getHeight() / 2.0;
		assertTrue(screen.mouseClicked(x, y, 0));
		var confirmation = assertInstanceOf(ConfirmScreen.class, Minecraft.getInstance().screen);
		confirmation.mouseReleased(x, y, 0);
		Minecraft.getInstance().setLastInputType(InputType.KEYBOARD_ARROW);
		assertTrue(confirmation.keyPressed(256, 0, 0));
		assertSame(screen, Minecraft.getInstance().screen, "Escape cancels through the real Reset callback");
		assertSame(list, optionsList(screen), "native Screen.init must reposition the initialized screen, not recreate it");
		assertEquals(original, widgets(screen));
		assertSame(reset, screen.getFocused(), "gesture cancellation must not discard the retained native focus");
	}

	private static void assertGestureCancelled(SettingsScreen screen) {
		assertFalse(screen.isDragging());
		assertFalse(optionsList(screen).isDragging());
		assertTrue(entries(screen).stream().noneMatch(ContainerEventHandler::isDragging));
	}

	private static ServerSettingsModel openEditablePerformance(SettingsScreen screen) throws ReflectiveOperationException {
		screen.children().stream().filter(TabNavigationBar.class::isInstance).map(TabNavigationBar.class::cast)
			.findFirst().orElseThrow().selectTab(1, false);
		var field = SettingsScreen.class.getDeclaredField("serverSettings");
		field.setAccessible(true);
		var model = Objects.requireNonNull((ServerSettingsModel) field.get(screen));
		// Inject the accepted permission/snapshot seam, not a connection or server transport.
		model.setClientPermission(true);
		var inventory = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("7.5")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, BigDecimal.ONE));
		screen.onServerConfigSnapshot(model.beginSessionIfNeeded(),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 30, inventory));
		assertTrue(model.canEdit());
		keyboardOpenCategory(screen, Category.PERFORMANCE);
		return model;
	}

	private static InventorySizeOption.InventorySizeSlider sizeSlider(SettingsScreen screen, Field field) {
		String key = "settings.pingforit." + SettingsCategoryCatalog.settings(Category.PERFORMANCE).stream()
			.filter(setting -> setting.inventoryField() == field).findFirst().orElseThrow().id();
		return widgets(screen).stream().filter(InventorySizeOption.InventorySizeSlider.class::isInstance)
			.map(InventorySizeOption.InventorySizeSlider.class::cast).filter(widget -> hasKey(widget, key)).findFirst().orElseThrow();
	}

	private static void keyboardOpenCategory(SettingsScreen screen, Category category) {
		var button = widgets(screen).stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(widget -> hasKey(widget, "settings.pingforit.category." + category.id() + ".button")).findFirst().orElseThrow();
		keyboardFocus(screen, button);
		assertTrue(screen.keyPressed(32, 0, 0));
		screen.keyReleased(32, 0, 0);
	}

	private static void keyboardFocus(SettingsScreen screen, AbstractWidget widget) {
		Minecraft.getInstance().setLastInputType(InputType.KEYBOARD_TAB);
		for (int press = 0; press < 100 && !widget.isFocused(); press++) {
			screen.keyPressed(258, 0, 0);
			screen.keyReleased(258, 0, 0);
		}
		assertTrue(widget.isFocused(), "native Tab traversal must reach the intended control");
	}

	private static void openMarkers(SettingsScreen screen) {
		var button = widgets(screen).stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(widget -> hasKey(widget, "settings.pingforit.category.display.button")).findFirst().orElseThrow();
		click(screen, button);
	}

	private static void click(SettingsScreen screen, AbstractWidget widget) {
		Minecraft.getInstance().setLastInputType(InputType.MOUSE);
		double x = widget.getX() + widget.getWidth() / 2.0;
		double y = widget.getY() + widget.getHeight() / 2.0;
		assertTrue(screen.mouseClicked(x, y, 0));
		screen.mouseReleased(x, y, 0);
	}

	private static boolean hasKey(AbstractWidget widget, String key) {
		return widget.getMessage().getContents() instanceof TranslatableContents contents && contents.getKey().equals(key);
	}

	private static <E extends Enum<E>> CycleButton<E> cycleButton(SettingsScreen screen, E value) {
		CycleButton<?> button = widgets(screen).stream().filter(CycleButton.class::isInstance).map(CycleButton.class::cast)
			.filter(widget -> widget.getValue() == value).findFirst().orElseThrow();
		@SuppressWarnings("unchecked") CycleButton<E> typed = (CycleButton<E>) button;
		return typed;
	}

	private static void assertPaired(SettingsScreen screen, AbstractWidget left, AbstractWidget right) {
		assertPaired(screen, left, right, "both current captions fit native half-width controls");
	}

	private static void assertPaired(SettingsScreen screen, AbstractWidget left, AbstractWidget right, String reason) {
		assertEquals(List.of(left, right), entryFor(screen, left).children(), reason);
		assertEquals(150, left.getWidth());
		assertEquals(150, right.getWidth());
		assertEquals(left.getY(), right.getY());
		assertTrue(left.getRight() < right.getX());
	}

	private static void assertStacked(SettingsScreen screen, AbstractWidget left, AbstractWidget right) {
		@Unmodifiable List<ContainerEventHandler> entries = entries(screen);
		int index = entries.indexOf(entryFor(screen, left));
		assertEquals(List.of(left), entries.get(index).children());
		assertEquals(List.of(right), entries.get(index + 1).children(), "stacking preserves the declared pair's order");
		assertEquals(310, left.getWidth());
		assertEquals(310, right.getWidth());
		assertEquals(left.getX(), right.getX());
		assertTrue(left.getBottom() <= right.getY());
	}

	private static void focus(SettingsScreen screen, AbstractWidget widget) {
		ContainerEventHandler entry = entryFor(screen, widget);
		entry.setFocused(widget);
		optionsList(screen).setFocused(entry);
		screen.setFocused(optionsList(screen));
	}

	private static void assertFocused(SettingsScreen screen, AbstractWidget widget) {
		assertSame(optionsList(screen), screen.getFocused());
		assertSame(entryFor(screen, widget), optionsList(screen).getFocused());
		assertSame(widget, entryFor(screen, widget).getFocused());
		assertTrue(widget.isFocused());
	}

	private static ContainerEventHandler entryFor(SettingsScreen screen, AbstractWidget widget) {
		return entries(screen).stream().filter(entry -> entry.children().contains(widget)).findFirst().orElseThrow();
	}

	private static SettingsScreen.SettingsOptionsList optionsList(SettingsScreen screen) {
		return screen.children().stream().filter(SettingsScreen.SettingsOptionsList.class::isInstance)
			.map(SettingsScreen.SettingsOptionsList.class::cast).findFirst().orElseThrow();
	}

	private static @Unmodifiable List<ContainerEventHandler> entries(SettingsScreen screen) {
		List<?> entries = optionsList(screen).children();
		return entries.stream().map(ContainerEventHandler.class::cast).toList();
	}

	private static @Unmodifiable List<AbstractWidget> widgets(SettingsScreen screen) {
		return entries(screen).stream().flatMap(entry -> entry.children().stream()).map(AbstractWidget.class::cast).toList();
	}

	private static void withScreen(ScreenCheck check) throws Exception {
		var client = allocate(HeadlessMinecraft.class);
		client.setLastInputType(InputType.MOUSE);
		setField(Minecraft.class, client, "running", true);
		var options = allocate(HeadlessOptions.class);
		setField(Options.class, options, "keyPlayerList", allocate(HeadlessPlayerListKey.class));
		setField(Minecraft.class, client, "options", options);
		setField(Minecraft.class, client, "font", new MeasuredFont());
		setField(Minecraft.class, client, "narrator", allocate(HeadlessNarrator.class));
		var sounds = allocate(SoundManager.class);
		setField(SoundManager.class, sounds, "soundEngine", allocate(SoundEngine.class));
		setField(Minecraft.class, client, "soundManager", sounds);
		var window = allocate(Window.class);
		// A non-null token satisfies LWJGL's pointer check; GetKey below is a fixture callback, never GLFW.
		setField(Window.class, window, "window", 1L);
		setField(Minecraft.class, client, "window", window);
		var config = new CountingConfig();
		config.setPlayerInfoMode(PlayerInfoMode.HOLD);
		config.setTeamColorMode(TeamColorMode.FULL);
		config.teamEdits = 0;
		var screen = new HeadlessSettingsScreen(config);
		var instanceField = Minecraft.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		@Nullable Object previousClient = instanceField.get(null);
		@Nullable Minecraft previousGame = CommonClient.Game;
		Language previousLanguage = Language.getInstance();
		var currentScreenField = SettingsScreen.class.getDeclaredField("currentSettingsScreen");
		currentScreenField.setAccessible(true);
		@Nullable Object previousScreen = currentScreenField.get(null);
		try (var ignored = new ReleasedModifierKeys()) {
			instanceField.set(null, client);
			CommonClient.Game = client;
			Language.inject(new TurkishLanguage(previousLanguage));
			screen.initialize(client);
			check.run(new Fixture(screen, config, options));
		} finally {
			screen.removed();
			currentScreenField.set(null, previousScreen);
			Language.inject(previousLanguage);
			CommonClient.Game = previousGame;
			instanceField.set(null, previousClient);
		}
	}

	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), type));
	}

	private static void setField(Class<?> owner, Object instance, String name, Object value) throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(instance, value);
	}

	@NotNullByDefault
	@FunctionalInterface
	private interface ScreenCheck {
		void run(Fixture fixture) throws Exception;
	}

	@NotNullByDefault
	private record Fixture(HeadlessSettingsScreen screen, CountingConfig config, HeadlessOptions options) {
	}

	@NotNullByDefault
	private static final class HeadlessSettingsScreen extends SettingsScreen {
		private HeadlessSettingsScreen(ClientConfig config) {
			super(config, null);
		}

		private void initialize(Minecraft client) {
			client.setScreen(this);
		}
	}

	@NotNullByDefault
	private static final class HeadlessMinecraft extends Minecraft {
		private HeadlessMinecraft() {
			super(null);
			throw new AssertionError("allocate without starting Minecraft");
		}

		@Override
		public void setScreen(@Nullable Screen screen) {
			// Preserve Minecraft.setScreen's screen lifecycle, including native
			// Screen.init's retained-instance path; omit GPU, mouse/window and title work.
			final @Nullable Screen previous = this.screen;
			if (previous != null) previous.removed();
			this.screen = screen;
			if (screen != null) {
				screen.added();
				screen.init(this, 426, 240);
			}
		}
	}

	@NotNullByDefault
	private static final class HeadlessNarrator extends GameNarrator {
		private HeadlessNarrator() {
			super(null);
			throw new AssertionError("allocate without loading text-to-speech");
		}

		@Override
		public boolean isActive() {
			return false;
		}
	}

	@NotNullByDefault
	private static final class CountingConfig extends ClientConfig {
		private int teamEdits;

		@Override
		public void setTeamColorMode(@Nullable TeamColorMode mode) {
			super.setTeamColorMode(mode);
			this.teamEdits++;
		}
	}

	@NotNullByDefault
	private static final class HeadlessOptions extends Options {
		private int saves;
		private @Nullable Runnable onSave;

		private HeadlessOptions() {
			super(null, new File("."));
			throw new AssertionError("allocate without the native Options lifecycle");
		}

		@Override
		public void save() {
			this.saves++;
			if (this.onSave != null) this.onSave.run();
		}
	}

	@NotNullByDefault
	private static final class HeadlessPlayerListKey extends KeyMapping {
		private HeadlessPlayerListKey() {
			super("key.playerlist", 258, "key.categories.multiplayer");
			throw new AssertionError("allocate without registering a fixture key binding globally");
		}

		@Override
		public String getName() {
			return "key.playerlist";
		}

		@Override
		public Component getTranslatedKeyMessage() {
			return Component.translatable("key.keyboard.tab");
		}
	}

	@NotNullByDefault
	private static final class MeasuredFont extends Font {
		private MeasuredFont() {
			super(ignored -> { throw new AssertionError("the fixture must not load or render native glyphs"); }, false);
		}

		@Override
		public int width(String text) {
			// Supplied threshold measurements are the regression input, not a claim of loading the bitmap font.
			return switch (text) {
				case "Oyuncu Bilgisi: Basılı Tut" -> 118;
				case "Takım Rengi: Tam" -> 79;
				case "Takım Rengi: Yalnızca Etiketler" -> 148;
				default -> text.codePointCount(0, text.length()) * 5;
			};
		}

		@Override
		public int width(FormattedText text) {
			return this.width(text.getString());
		}

		@Override
		public int width(FormattedCharSequence text) {
			var plain = new StringBuilder();
			text.accept((index, style, codePoint) -> {
				plain.appendCodePoint(codePoint);
				return true;
			});
			return this.width(plain.toString());
		}

		@Override
		public @Unmodifiable List<FormattedCharSequence> split(FormattedText text, int width) {
			// Confirmation layout needs measurements, but line wrapping/rendering is not under test.
			return List.of(Language.getInstance().getVisualOrder(text));
		}
	}

	@NotNullByDefault
	private static final class TurkishLanguage extends Language {
		private final Language fallback;
		private final @Unmodifiable Map<String, String> translations;

		private TurkishLanguage(Language fallback) throws IOException {
			this.fallback = fallback;
			var translations = new HashMap<String, String>();
			try (@Nullable InputStream stream = SettingsScreenInputLayoutTest.class.getResourceAsStream("/assets/pingforit/lang/tr_tr.json")) {
				assertNotNull(stream);
				Language.loadFromJson(stream, translations::put);
			}
			this.translations = Map.copyOf(translations);
		}

		@Override
		public String getOrDefault(String key, String defaultValue) {
			return this.translations.getOrDefault(key, this.fallback.getOrDefault(key, defaultValue));
		}

		@Override
		public boolean has(String key) {
			return this.translations.containsKey(key) || this.fallback.has(key);
		}

		@Override
		public boolean isDefaultRightToLeft() {
			return false;
		}

		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text) {
			return this.fallback.getVisualOrder(text);
		}
	}

	/** Native CycleButton asks Screen for physical Shift; intercept just that read without creating a GLFW window. */
	@NotNullByDefault
	private static final class ReleasedModifierKeys implements AutoCloseable {
		// A JVM may inline GLFW's final function pointer. Keep one bounded callback
		// alive for the test process even after restoring the original pointer.
		private static final ReleasedKeyCallback CALLBACK = new ReleasedKeyCallback();
		private final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		private final Object unsafe;
		private final Object functionBase;
		private final long functionOffset;
		private final long previousFunction;

		private ReleasedModifierKeys() throws ReflectiveOperationException {
			var unsafeField = this.unsafeClass.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			this.unsafe = Objects.requireNonNull(unsafeField.get(null));
			java.lang.reflect.Field function = Class.forName("org.lwjgl.glfw.GLFW$Functions").getDeclaredField("GetKey");
			this.functionBase = Objects.requireNonNull(this.unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field.class).invoke(this.unsafe, function));
			this.functionOffset = (long) this.unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field.class).invoke(this.unsafe, function);
			this.previousFunction = function.getLong(null);
			this.writeFunction(CALLBACK.address);
		}

		private void writeFunction(long address) throws ReflectiveOperationException {
			this.unsafeClass.getMethod("putLongVolatile", Object.class, long.class, long.class)
				.invoke(this.unsafe, this.functionBase, this.functionOffset, address);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			this.writeFunction(this.previousFunction);
		}
	}

	@NotNullByDefault
	private static final class ReleasedKeyCallback implements CallbackI {
		private final FFICIF callInterface = FFICIF.calloc();
		private final PointerBuffer argumentTypes = MemoryUtil.memAllocPointer(2);
		private final long address;

		private ReleasedKeyCallback() {
			this.argumentTypes.put(0, LibFFI.ffi_type_pointer.address()).put(1, LibFFI.ffi_type_sint32.address());
			assertEquals(LibFFI.FFI_OK,
				LibFFI.ffi_prep_cif(this.callInterface, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_sint32, this.argumentTypes));
			this.address = CallbackI.super.address();
		}

		@Override
		public FFICIF getCallInterface() {
			return this.callInterface;
		}

		@Override
		public void callback(long result, long arguments) {
			MemoryUtil.memPutAddress(result, 0L);
		}
	}
}
