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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ConfigHandler;
import nx.pingwheel.common.config.EntityBlockRenderMode;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import nx.pingwheel.common.config.PlayerInfoMode;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.config.SpatialSelectorSettings;
import nx.pingwheel.common.config.TeamColorMode;
import nx.pingwheel.common.network.ServerConfigUpdateC2SPacket;
import nx.pingwheel.common.presentation.client.ServerPresentationPolicyState;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

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
			long dirty = model.dirtyFields();
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

	@Test
	void guardedOpenFileFailureRefreshesMountedValueWithoutOpeningOrLeaving(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var failWrites = new AtomicBoolean(true);
		var handler = loadedClientHandler(path, failWrites, """
			{"pingforit-version":"0.4.0-pfi-beta1","entityBlockRenderMode":"ALL"}
			""");
		ClientConfig original = handler.getConfig();
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			keyboardOpenCategory(screen, Category.GEOMETRY_CONFIG);
			var originalOption = cycleButton(screen, EntityBlockRenderMode.ALL);
			String replacement = """
				{"pingforit-version":"0.5.0-pfi-beta1","entityBlockRenderMode":"COMPATIBLE"}
				""";
			Files.writeString(path, replacement);
			click(screen, pageWidget(screen, "open_client_config", Button.class));

			assertSame(screen, Minecraft.getInstance().screen, "the file action must not use the root exit dialog");
			assertEquals(1, screen.saveAttempts);
			assertEquals(0, screen.fileOpens);
			assertEquals(replacement, Files.readString(path));
			var newOption = cycleButton(screen, EntityBlockRenderMode.COMPATIBLE);
			assertNotSame(originalOption, newOption, "even the already-mounted page must display the reloaded value");
			keyboardFocus(screen, newOption);
			assertTrue(screen.keyPressed(32, 0, 0));
			assertEquals(EntityBlockRenderMode.VOXEL_SHAPE_ONLY, handler.getConfig().getEntityBlockRenderMode());
			assertEquals(EntityBlockRenderMode.ALL, original.getEntityBlockRenderMode());

			failWrites.set(false);
			click(screen, pageWidget(screen, "open_client_config", Button.class));
			assertNull(Minecraft.getInstance().screen);
			assertEquals(2, screen.saveAttempts);
			assertEquals(1, screen.fileOpens);
			handler.load();
			assertEquals(EntityBlockRenderMode.VOXEL_SHAPE_ONLY, handler.getConfig().getEntityBlockRenderMode());
		});
	}

	@Test
	void mountedDragAndChannelInputRebindBeforeEditingAReplacement(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var handler = loadedClientHandler(path, new AtomicBoolean(true), """
			{"pingforit-version":"0.4.0-pfi-beta1","channel":"old","spatialSelector":{"deadzone":18}}
			""");
		ClientConfig original = handler.getConfig();
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			keyboardOpenCategory(screen, Category.INPUT_INTERACTION);
			String deadzone = "spatial_selector.deadzone";
			var oldSlider = pageWidget(screen, deadzone, AbstractSliderButton.class);
			keyboardFocus(screen, oldSlider);
			Minecraft.getInstance().setLastInputType(InputType.MOUSE);
			int x = oldSlider.getX() + 5;
			int y = oldSlider.getY() + oldSlider.getHeight() / 2;
			assertTrue(screen.mouseClicked(x, y, 0));
			assertTrue(screen.isDragging());
			int oldValue = original.getSpatialSelector().getDeadzone();

			Files.writeString(path, """
				{"pingforit-version":"0.5.0-pfi-beta1","channel":"new","longPressCompatibilityMode":true,
				 "doubleClickDangerAngleDegrees":0,"spatialSelector":{"deadzone":24}}
				""");
			assertFalse(handler.saveSafely(), "guarded writeback must decline the replaced file");
			ClientConfig replacement = handler.getConfig();
			assertNotSame(original, replacement);
			int savesBeforeRefresh = fixture.options().saves;
			assertFalse(screen.mouseDragged(x + 25, y, 0, 25, 0), "a drag started on the old config may not resume");
			assertGestureCancelled(screen);
			var newSlider = pageWidget(screen, deadzone, AbstractSliderButton.class);
			assertNotSame(oldSlider, newSlider);
			assertTrue(newSlider.getMessage().getString().contains("24"));
			assertFocused(screen, newSlider);
			assertFalse(pageWidget(screen, "spatial_selector.gesture_skip_hold_millis", AbstractSliderButton.class).active);
			assertFalse(pageWidget(screen, "double_click_danger_angle_degrees", AbstractSliderButton.class).active);
			assertEquals(24, replacement.getSpatialSelector().getDeadzone());
			assertEquals(savesBeforeRefresh, fixture.options().saves, "invalidating the old tree must not replay slider edits");

			int right = newSlider.getRight() - 4;
			assertTrue(screen.mouseClicked(right, newSlider.getY() + 10, 0));
			screen.mouseReleased(right, newSlider.getY() + 10, 0);
			assertEquals(SpatialSelectorSettings.MAX_DEADZONE, replacement.getSpatialSelector().getDeadzone());
			assertEquals(oldValue, original.getSpatialSelector().getDeadzone());

			assertTrue(screen.keyPressed(256, 0, 0));
			releaseEscape(screen);
			assertSame(screen, Minecraft.getInstance().screen);
			assertEquals(0, screen.saveAttempts, "leaf Escape is Back, not local persistence");
			keyboardOpenCategory(screen, Category.CHANNEL_NOTICES);
			var oldChannel = pageWidget(screen, "channel", EditBox.class);
			assertEquals("new", oldChannel.getValue());
			keyboardFocus(screen, oldChannel);
			Files.writeString(path, """
				{"pingforit-version":"0.5.0-pfi-beta1","channel":"external"}
				""");
			handler.load();
			assertTrue(screen.charTyped('x', 0));
			var newChannel = pageWidget(screen, "channel", EditBox.class);
			assertNotSame(oldChannel, newChannel);
			assertEquals("externalx", newChannel.getValue());
			assertEquals("externalx", handler.getConfig().getChannel());
			assertEquals("new", replacement.getChannel());
		});
	}

	@Test
	void ordinaryRootSaveFailureCanContinueAndRetryWithoutLosingDraftOrListener(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		String source = """
			{"pingforit-version":"0.5.0-pfi-beta1","pingSize":100}
			""";
		var failWrites = new AtomicBoolean(true);
		var handler = loadedClientHandler(path, failWrites, source);
		var parent = new ConfirmScreen(ignored -> {}, Component.literal("Parent"), Component.empty());
		handler.getConfig().setPingSize(150);
		withScreen(handler, parent, fixture -> {
			var screen = fixture.screen();
			var client = Minecraft.getInstance();
			ServerSettingsModel model = editableServerOverview(screen, InventoryConfigValues.defaults());
			model.setRateLimitText("9");
			screen.liveConnection = true;
			var list = optionsList(screen);
			click(screen, primaryButton(screen, CommonComponents.GUI_DONE));
			var first = assertInstanceOf(ConfirmScreen.class, client.screen);
			assertEquals(1, screen.saveAttempts);
			assertEquals(source, Files.readString(path));
			assertTrue(screen.serverUpdates.isEmpty(), "local failure must stop before the server commit");
			client.setLastInputType(InputType.KEYBOARD_ARROW);
			pressEscape(first);
			assertSame(screen, client.screen, "a fresh Escape can cancel a confirmation opened by mouse Done");
			pressEscape(screen);
			assertEquals(1, screen.saveAttempts, "repeats of that Escape cannot try to close the restored screen");
			releaseEscape(screen);
			assertSame(list, optionsList(screen));
			assertEquals("9", model.rateLimitText());
			assertTrue(model.dirty());
			assertTrue(listensToPresentationState(screen));

			client.setLastInputType(InputType.KEYBOARD_ARROW);
			pressEscape(screen);
			var second = assertInstanceOf(ConfirmScreen.class, client.screen);
			assertSame(screenButton(second, "settings.pingforit.save_failed.continue"), second.getFocused());
			click(second, screenButton(second, "settings.pingforit.save_failed.continue"));
			assertSame(screen, client.screen, "mouse Continue Editing can return while the opening Escape is still held");
			pressEscape(screen);
			assertSame(screen, client.screen, "mouse Continue Editing must not drop the held-key fence");
			releaseEscape(screen);
			assertEquals(2, screen.saveAttempts);
			assertTrue(model.dirty());
			assertTrue(listensToPresentationState(screen));

			failWrites.set(false);
			click(screen, primaryButton(screen, CommonComponents.GUI_DONE));
			assertSame(parent, client.screen);
			assertEquals(3, screen.saveAttempts);
			assertEquals(9, screen.serverUpdates.getFirst().update().rateLimit());
			assertEquals(1, screen.serverUpdates.size(), "successful close sends exactly once and immediately returns");
			assertFalse(listensToPresentationState(screen));
			handler.load();
			assertEquals(150, handler.getConfig().getPingSize());
		});
	}

	@Test
	void heldEscapeCannotContinueOrRetryAfterGuardedReloadUntilReleased(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var failWrites = new AtomicBoolean(true);
		var handler = loadedClientHandler(path, failWrites, """
			{"pingforit-version":"0.4.0-pfi-beta1","pingSize":100}
			""");
		ClientConfig original = handler.getConfig();
		String replacement = """
			{"pingforit-version":"0.5.0-pfi-beta1","pingSize":170}
			""";
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			var client = Minecraft.getInstance();
			var model = editableServerOverview(screen, InventoryConfigValues.defaults());
			model.setRateLimitText("9");
			screen.liveConnection = true;
			Files.writeString(path, replacement);
			failWrites.set(false);

			pressEscape(screen);
			var dialog = assertInstanceOf(ConfirmScreen.class, client.screen);
			assertNotSame(original, handler.getConfig(), "the guard must reload, not fail the actual writer");
			assertEquals(170, handler.getConfig().getPingSize());
			for (int i = 0; i < 3; i++) {
				pressEscape(dialog);
				assertSame(dialog, client.screen, "repeat from the opening press cannot cancel the dialog");
			}
			assertEquals(1, screen.saveAttempts);
			assertTrue(screen.serverUpdates.isEmpty());

			releaseEscape(dialog);
			pressEscape(dialog);
			assertSame(screen, client.screen, "a separate press can return to editing");
			for (int i = 0; i < 3; i++) {
				pressEscape(screen);
				assertSame(screen, client.screen, "repeat from the cancelling press cannot retry the close");
			}
			assertEquals(1, screen.saveAttempts);
			assertTrue(model.dirty());
			assertTrue(screen.serverUpdates.isEmpty());
			assertEquals(replacement, Files.readString(path));

			releaseEscape(screen);
			pressEscape(screen);
			assertNull(client.screen, "only a new press can close once the local config is usable");
			assertEquals(2, screen.saveAttempts);
			assertEquals(1, screen.serverUpdates.size());
			assertEquals(9, screen.serverUpdates.getFirst().rateLimit());
		});
	}

	@Test
	void confirmationDecisionIsSpentBeforeReentryAndCannotBeAppliedToAnotherDialog(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var handler = loadedClientHandler(path, new AtomicBoolean(true), """
			{"pingforit-version":"0.5.0-pfi-beta1","pingSize":100}
			""");
		handler.getConfig().setPingSize(150);
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			var client = (HeadlessMinecraft) Minecraft.getInstance();
			var model = editableServerOverview(screen, InventoryConfigValues.defaults());
			model.setRateLimitText("9");
			screen.liveConnection = true;
			click(screen, primaryButton(screen, CommonComponents.GUI_DONE));
			var first = assertInstanceOf(ConfirmScreen.class, client.screen);
			var firstContinue = screenButton(first, "settings.pingforit.save_failed.continue");
			var firstExit = screenButton(first, "settings.pingforit.save_failed.exit");
			client.beforeScreenChange = () -> {
				assertSame(first, client.screen);
				firstExit.onPress();
				assertSame(first, client.screen, "the decision is already spent before a screen-change hook can reenter");
			};
			click(first, firstContinue);
			assertSame(screen, client.screen);
			assertEquals("9", model.rateLimitText());

			click(screen, primaryButton(screen, CommonComponents.GUI_DONE));
			var second = assertInstanceOf(ConfirmScreen.class, client.screen);
			assertNotSame(first, second);
			firstExit.onPress();
			firstContinue.onPress();
			assertSame(second, client.screen, "late buttons from the first dialog have no decision on the second");
			assertEquals("9", model.rateLimitText());
			assertTrue(model.dirty());
			assertEquals(2, screen.saveAttempts);
			assertTrue(screen.serverUpdates.isEmpty());

			click(second, screenButton(second, "settings.pingforit.save_failed.exit"));
			assertNull(client.screen, "the second dialog still owns its own valid decision");
			assertFalse(model.dirty());
			assertEquals(2, screen.saveAttempts);
			assertTrue(screen.serverUpdates.isEmpty());
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void saveFailureConfirmationCanRetryVetoedNavigationWithoutAnotherSaveOrUpdate(boolean exit, @TempDir Path directory)
		throws Exception {
		Path path = directory.resolve("client.json");
		var handler = loadedClientHandler(path, new AtomicBoolean(true), """
			{"pingforit-version":"0.5.0-pfi-beta1","pingSize":100}
			""");
		handler.getConfig().setPingSize(150);
		var parent = new ConfirmScreen(ignored -> {}, Component.literal("Parent"), Component.empty());
		withScreen(handler, parent, fixture -> {
			var screen = fixture.screen();
			var client = (HeadlessMinecraft) Minecraft.getInstance();
			var model = editableServerOverview(screen, InventoryConfigValues.defaults());
			model.setRateLimitText("9");
			screen.liveConnection = true;
			click(screen, primaryButton(screen, CommonComponents.GUI_DONE));
			var dialog = assertInstanceOf(ConfirmScreen.class, client.screen);
			var exitButton = screenButton(dialog, "settings.pingforit.save_failed.exit");
			var continueButton = screenButton(dialog, "settings.pingforit.save_failed.continue");

			client.vetoNextScreenChange = true;
			client.beforeScreenChange = () -> {
				(exit ? continueButton : exitButton).onPress();
				assertSame(dialog, client.screen, "a different choice cannot reenter the attempt that is being vetoed");
			};
			if (exit) click(dialog, exitButton);
			else pressEscape(dialog);
			assertSame(dialog, client.screen, "the veto keeps the same active dialog without calling removed");
			assertTrue(model.dirty());
			assertTrue(listensToPresentationState(screen));
			assertEquals(1, screen.saveAttempts);
			assertTrue(screen.serverUpdates.isEmpty());

			if (exit) {
				click(dialog, exitButton);
				assertSame(parent, client.screen);
				assertFalse(model.dirty(), "abandon the draft only after the successful exit");
			} else {
				pressEscape(dialog);
				assertSame(dialog, client.screen, "an Escape repeat is not a fresh decision after a veto");
				releaseEscape(dialog);
				pressEscape(dialog);
				assertSame(screen, client.screen);
				pressEscape(screen);
				assertSame(screen, client.screen, "the successful retry still transfers the Escape fence");
				releaseEscape(screen);
				assertEquals("9", model.rateLimitText());
				assertTrue(model.dirty());
			}
			assertEquals(1, screen.saveAttempts);
			assertTrue(screen.serverUpdates.isEmpty());
		});
	}

	@Test
	void resetConfirmationRetriesOnlyNavigationWhenResetAlreadyApplied(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var handler = loadedClientHandler(path, new AtomicBoolean(false), """
			{"pingforit-version":"0.5.0-pfi-beta1","pingSize":150}
			""");
		ClientConfig original = handler.getConfig();
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			var client = (HeadlessMinecraft) Minecraft.getInstance();
			click(screen, screenButton(screen, "settings.pingforit.reset_all"));
			var dialog = assertInstanceOf(ConfirmScreen.class, client.screen);
			var yes = screenButton(dialog, "gui.yes");
			client.vetoNextScreenChange = true;
			click(dialog, yes);
			assertSame(dialog, client.screen);
			assertEquals(1, screen.resetAttempts);
			ClientConfig resetConfig = handler.getConfig();
			assertNotSame(original, resetConfig);
			assertEquals(new ClientConfig().getPingSize(), resetConfig.getPingSize());
			String resetFile = Files.readString(path);
			resetConfig.setPingSize(160);

			click(dialog, yes);
			assertNotSame(screen, assertInstanceOf(SettingsScreen.class, client.screen), "retry must still recreate the session");
			assertEquals(1, screen.resetAttempts, "retry must not apply or save the reset again");
			assertSame(resetConfig, handler.getConfig());
			assertEquals(160, resetConfig.getPingSize());
			assertEquals(resetFile, Files.readString(path));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"pingforit-version\":\"10.0.0-pfi-beta1\"}", "broken client config"})
	void protectedRootEscapeCanExitToNullWithoutAnotherSaveOrServerUpdate(String protectedSource, @TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var handler = loadedClientHandler(path, new AtomicBoolean(false), protectedSource);
		handler.getConfig().setPingSize(150);
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			var model = editableServerOverview(screen, InventoryConfigValues.defaults());
			model.setRateLimitText("9");
			screen.liveConnection = true;
			pressEscape(screen);
			var dialog = assertInstanceOf(ConfirmScreen.class, Minecraft.getInstance().screen);
			releaseEscape(dialog);
			assertEquals(1, screen.saveAttempts);
			assertTrue(model.dirty());
			assertTrue(listensToPresentationState(screen));

			click(dialog, screenButton(dialog, "settings.pingforit.save_failed.exit"));
			assertNull(Minecraft.getInstance().screen);
			assertEquals(1, screen.saveAttempts, "abandoning the failed close may not attempt another save");
			assertEquals(0, fixture.options().saves);
			assertTrue(screen.serverUpdates.isEmpty());
			assertFalse(model.dirty());
			assertFalse(listensToPresentationState(screen));
			var session = assertInstanceOf(Reference.class, getField(SettingsScreen.class, null, "currentSettingsScreen"));
			assertNotSame(screen, session.get());
			assertEquals(protectedSource, Files.readString(path));
			assertEquals(150, handler.getConfig().getPingSize(), "leaving does not undo changes already in memory");
		});
	}

	@Test
	void successfulLocalSaveStillRoutesAnInvalidServerDraftToItsFirstField(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		var handler = loadedClientHandler(path, new AtomicBoolean(false), """
			{"pingforit-version":"0.5.0-pfi-beta1","pingSize":100}
			""");
		handler.getConfig().setPingSize(150);
		withScreen(handler, null, fixture -> {
			var screen = fixture.screen();
			var model = editableServerOverview(screen, InventoryConfigValues.defaults());
			model.setRateLimitText("");
			model.setSyncDurationText("");
			screen.liveConnection = true;
			click(screen, primaryButton(screen, CommonComponents.GUI_DONE));

			assertSame(screen, Minecraft.getInstance().screen, "invalid server input must not show the local failure dialog");
			assertEquals(1, screen.saveAttempts);
			assertTrue(screen.serverUpdates.isEmpty());
			assertTrue(model.dirty());
			assertFocused(screen, pageWidget(screen, "rate_limit", EditBox.class));
			assertNotNull(primaryButton(screen, CommonComponents.GUI_BACK));
			handler.load();
			assertEquals(150, handler.getConfig().getPingSize(), "local persistence precedes server validation");
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
		pressEscape(confirmation);
		assertSame(screen, Minecraft.getInstance().screen, "Escape cancels through the real Reset callback");
		releaseEscape(screen);
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
		var inventory = InventoryConfigValues.defaults()
			.with(Field.DISPLAY_BYTE_MULTIPLIER, new Value(false, new BigDecimal("7.5")))
			.with(Field.FRAME_BYTE_MULTIPLIER, new Value(false, BigDecimal.ONE));
		var model = editableServerOverview(screen, inventory);
		keyboardOpenCategory(screen, Category.PERFORMANCE);
		return model;
	}

	private static void pressEscape(Screen screen) {
		assertTrue(screen.keyPressed(GLFW_KEY_ESCAPE, 0, 0));
	}

	private static void releaseEscape(Screen screen) {
		screen.keyReleased(GLFW_KEY_ESCAPE, 0, 0);
	}

	private static ServerSettingsModel editableServerOverview(SettingsScreen screen, InventoryConfigValues inventory)
		throws ReflectiveOperationException {
		screen.children().stream().filter(TabNavigationBar.class::isInstance).map(TabNavigationBar.class::cast)
			.findFirst().orElseThrow().selectTab(1, false);
		var model = assertInstanceOf(ServerSettingsModel.class, getField(SettingsScreen.class, screen, "serverSettings"));
		// Inject the accepted permission/snapshot seam, not a connection or server transport.
		model.setClientPermission(true);
		screen.onServerConfigSnapshot(model.beginSessionIfNeeded(),
			new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 30, inventory));
		assertTrue(model.canEdit());
		return model;
	}

	private static ConfigHandler<ClientConfig> loadedClientHandler(Path path, AtomicBoolean failWrites, String source)
		throws Exception {
		Files.writeString(path, source);
		var handler = LoggingProfileScreenTest.newClientHandler(path, failWrites);
		handler.load();
		return handler;
	}

	private static boolean listensToPresentationState(SettingsScreen screen) throws ReflectiveOperationException {
		var state = CommonClient.INSTANCE.getServerPresentationPolicyState();
		var listener = Objects.requireNonNull(getField(SettingsScreen.class, screen, "presentationPolicyListener"));
		return assertInstanceOf(List.class, getField(ServerPresentationPolicyState.class, state, "listeners")).contains(listener);
	}

	private static Button primaryButton(SettingsScreen screen, Component caption) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> button.getMessage().equals(caption)).findFirst().orElseThrow();
	}

	private static Button screenButton(Screen screen, String translationKey) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> hasKey(button, translationKey)).findFirst().orElseThrow();
	}

	private static <T extends AbstractWidget> T pageWidget(SettingsScreen screen, String id, Class<T> widgetType) {
		return widgets(screen).stream().filter(widgetType::isInstance).map(widgetType::cast)
			.filter(widget -> hasKey(widget, "settings.pingforit." + id)).findFirst().orElseThrow();
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

	private static void click(Screen screen, AbstractWidget widget) {
		Minecraft.getInstance().setLastInputType(InputType.MOUSE);
		double x = widget.getX() + widget.getWidth() / 2.0;
		double y = widget.getY() + widget.getHeight() / 2.0;
		assertTrue(screen.mouseClicked(x, y, 0));
		@Nullable Screen releaseTarget = Minecraft.getInstance().screen;
		if (releaseTarget != null) releaseTarget.mouseReleased(x, y, 0);
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
		withScreen(null, null, check);
	}

	private static void withScreen(@Nullable ConfigHandler<ClientConfig> handler, @Nullable Screen parent, ScreenCheck check)
		throws Exception {
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
		var screen = new HeadlessSettingsScreen(config, handler, parent);
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
			client.beforeScreenChange = null;
			client.vetoNextScreenChange = false;
			client.setScreen(null);
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

	private static @Nullable Object getField(Class<?> owner, @Nullable Object instance, String name) throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(instance);
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
		private final @Nullable ConfigHandler<ClientConfig> handler;
		private final List<ServerConfigUpdateC2SPacket> serverUpdates = new ArrayList<>();
		private boolean liveConnection;
		private int saveAttempts;
		private int resetAttempts;
		private int fileOpens;

		private HeadlessSettingsScreen(ClientConfig config, @Nullable ConfigHandler<ClientConfig> handler, @Nullable Screen parent) {
			super(handler == null ? () -> config : handler::getConfig, parent);
			this.handler = handler;
		}

		private void initialize(Minecraft client) {
			client.setScreen(this);
		}

		@Override
		boolean saveClientConfig() {
			this.saveAttempts++;
			// Exercise the real handler without publishing a channel packet to a live game.
			@Nullable Minecraft previous = CommonClient.Game;
			try {
				CommonClient.Game = null;
				return Objects.requireNonNull(this.handler).saveSafely();
			} finally {
				CommonClient.Game = previous;
			}
		}

		@Override
		void resetClientConfig() {
			this.resetAttempts++;
			@Nullable Minecraft previous = CommonClient.Game;
			try {
				CommonClient.Game = null;
				Objects.requireNonNull(this.handler).resetToDefaults();
			} finally {
				CommonClient.Game = previous;
			}
		}

		@Override
		boolean hasLiveServerConnection() {
			return this.liveConnection;
		}

		@Override
		void sendServerConfigUpdate(ServerConfigUpdateC2SPacket packet) {
			this.serverUpdates.add(packet);
		}

		@Override
		void openClientConfigFile() {
			assertNull(Minecraft.getInstance().screen, "the file opens only after leaving Settings");
			this.fileOpens++;
		}
	}

	@NotNullByDefault
	private static final class HeadlessMinecraft extends Minecraft {
		private @Nullable Runnable beforeScreenChange;
		private boolean vetoNextScreenChange;

		private HeadlessMinecraft() {
			super(null);
			throw new AssertionError("allocate without starting Minecraft");
		}

		@Override
		public void setScreen(@Nullable Screen screen) {
			final @Nullable Runnable hook = this.beforeScreenChange;
			final boolean veto = this.vetoNextScreenChange;
			this.beforeScreenChange = null;
			this.vetoNextScreenChange = false;
			if (hook != null) hook.run();
			// Forge/NeoForge's opening event can cancel without calling removed().
			if (veto) return;
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
		public String plainSubstrByWidth(String text, int maxWidth) {
			return this.plainSubstrByWidth(text, maxWidth, false);
		}

		@Override
		public String plainSubstrByWidth(String text, int maxWidth, boolean reverse) {
			// EditBox scrolls in both directions. Find the longest fitting prefix or
			// suffix using the same measurements without splitting a surrogate pair.
			if (maxWidth <= 0) return "";
			int start = 0;
			int end = text.length();
			while (start < end) {
				String candidate = text.substring(start, end);
				if (this.width(candidate) <= maxWidth) return candidate;
				if (reverse) start = text.offsetByCodePoints(start, 1);
				else end = text.offsetByCodePoints(end, -1);
			}
			return "";
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
