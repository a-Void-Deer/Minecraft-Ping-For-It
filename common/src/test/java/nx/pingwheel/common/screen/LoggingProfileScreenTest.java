package nx.pingwheel.common.screen;

import net.minecraft.SharedConstants;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.InventoryConfigValues;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless root-session seams; this does not claim GLFW or live window input coverage. */
@NotNullByDefault
class LoggingProfileScreenTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void unlockFlagsSurviveNavigationAndResizeAndRemainScopeIndependent() throws Exception {
		withScreen(new ClientConfig(), null, (screen, client) -> {
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);

			selectScope(screen, 1);
			openCategory(screen, Category.PERFORMANCE);
			backButton(screen).onPress();
			selectScope(screen, 0);
			openCategory(screen, Category.GEOMETRY_CONFIG);
			screen.resizeTo(640, 360);

			assertTrue(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
			assertFalse(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.SERVER));

			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.SERVER);
			selectScope(screen, 1);

			assertTrue(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
			assertTrue(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.SERVER));
		});
	}

	@Test
	void successfulRootLeaveClearsBothUnlockFlags() throws Exception {
		var parent = new TestParent();
		withScreen(new ClientConfig(), parent, (screen, client) -> {
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.SERVER);
			screen.saveSucceeds = true;

			screen.onClose();

			assertSame(parent, client.screen);
			assertFalse(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
			assertFalse(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.SERVER));
		});
	}

	@Test
	void failedRootSaveKeepsTheScreenAndItsUnlockFlags() throws Exception {
		withScreen(new ClientConfig(), new TestParent(), (screen, client) -> {
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);
			screen.saveSucceeds = false;

			screen.onClose();

			assertSame(screen, client.screen);
			assertTrue(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
		});
	}

	@Test
	void cancellingTheResetConfirmationKeepsTheSession() throws Exception {
		withScreen(new ClientConfig(), new TestParent(), (screen, client) -> {
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);

			screen.handleResetConfirmation(false);

			assertSame(screen, client.screen);
			assertTrue(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
		});
	}

	@Test
	void plainScreenRemovalDoesNotClearTheSession() throws Exception {
		withScreen(new ClientConfig(), new TestParent(), (screen, client) -> {
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);

			screen.removed();

			assertTrue(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
		});
	}

	@Test
	void confirmedResetClearsTheOldSessionAndTheReplacementStartsLocked() throws Exception {
		withScreen(new ClientConfig(), new TestParent(), (screen, client) -> {
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);
			screen.loggingProfileSession().unlock(LoggingProfileSession.Side.SERVER);

			screen.handleResetConfirmation(true);

			assertTrue(screen.resetRecreated);
			assertFalse(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
			assertFalse(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.SERVER));
			var replacement = assertInstanceOf(TestSettingsScreen.class, client.screen);
			assertFalse(replacement.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.CLIENT));
			assertFalse(replacement.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.SERVER));
		});
	}

	@Test
	void loggingRowsExposeNativeTooltipNarrationWithTheUnlockHint() throws Exception {
		withScreen(new ClientConfig(), null, (screen, client) -> {
			openCategory(screen, Category.GEOMETRY_CONFIG);
			LoggingProfileButton clientRow = loggingProfileButton(screen);
			assertTrue(clientRow.getMessage().getString().contains("Logging Detail"));
			String clientHelp = tooltipText(clientRow);
			assertTrue(clientHelp.contains("Client diagnostic logging detail"));
			assertTrue(clientHelp.contains("Shift+left-click only unlocks the Debug candidate"));

			backButton(screen).onPress();
			selectScope(screen, 1);
			ServerSettingsModel model = serverSettings(screen);
			model.setClientPermission(true);
			screen.onServerConfigSnapshot(model.beginSessionIfNeeded(), snapshot(true, LoggingProfile.NORMAL));
			openCategory(screen, Category.PERFORMANCE);
			LoggingProfileButton serverRow = loggingProfileButton(screen);
			assertTrue(serverRow.getMessage().getString().contains("Server Logging Detail"));
			String serverHelp = tooltipText(serverRow);
			assertTrue(serverHelp.contains("Server diagnostic logging detail"));
			assertTrue(serverHelp.contains("Shift+left-click only unlocks the Debug candidate"));
		});
	}

	@Test
	void clientRowShowsARealDebugValueAndAppliesClickChangesToConfigAndRuntime() throws Exception {
		var config = new ClientConfig();
		config.setLoggingProfile(LoggingProfile.DEBUG);
		LoggingProfile previousRuntime = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			withScreen(config, null, (screen, client) -> {
				openCategory(screen, Category.GEOMETRY_CONFIG);
				LoggingProfileButton button = loggingProfileButton(screen);
				assertEquals("Logging Detail: Debug", button.getMessage().getString(),
					"a current Debug value stays visible while the row is locked");

				button.onPress();
				assertEquals(LoggingProfile.NORMAL, config.getLoggingProfile());
				assertEquals(LoggingProfile.NORMAL, Diagnostics.profile(DiagnosticSide.CLIENT));

				button.onPress();
				assertEquals(LoggingProfile.DEEP, config.getLoggingProfile());
				assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.CLIENT));

				button.onPress();
				assertEquals(LoggingProfile.NORMAL, config.getLoggingProfile(),
					"the locked row skips Debug after leaving it");
				assertEquals(LoggingProfile.NORMAL, Diagnostics.profile(DiagnosticSide.CLIENT));

				screen.loggingProfileSession().unlock(LoggingProfileSession.Side.CLIENT);
				button.onPress();
				button.onPress();

				assertEquals(LoggingProfile.DEBUG, config.getLoggingProfile());
				assertEquals(LoggingProfile.DEBUG, Diagnostics.profile(DiagnosticSide.CLIENT),
					"the client setter immediately publishes the selected profile");
			});
		} finally {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousRuntime);
		}
	}

	@Test
	void serverRowEditsOnlyTheDraftAndNeverPublishesTheRuntimeProfile() throws Exception {
		LoggingProfile previousServer = Diagnostics.profile(DiagnosticSide.SERVER);
		try {
			withScreen(new ClientConfig(), null, (screen, client) -> {
				ServerSettingsModel model = openServerPerformance(screen, snapshot(true, LoggingProfile.DEEP));
				LoggingProfileButton button = loggingProfileButton(screen);
				assertEquals("Server Logging Detail: Detailed", button.getMessage().getString());

				button.onPress();
				assertEquals(LoggingProfile.NORMAL, model.loggingProfile());
				assertEquals(LoggingProfile.NORMAL, model.updatePlan().orElseThrow().loggingProfile());
				assertTrue(model.dirty());
				assertEquals(previousServer, Diagnostics.profile(DiagnosticSide.SERVER),
					"server edits stay in the draft and never publish a server runtime profile");

				button.onPress();
				assertEquals(LoggingProfile.DEEP, model.loggingProfile());
				assertFalse(model.dirty());

				button.onPress();
				assertEquals(LoggingProfile.NORMAL, model.loggingProfile());
				screen.loggingProfileSession().unlock(LoggingProfileSession.Side.SERVER);
				button.onPress();
				button.onPress();
				assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
				assertEquals(LoggingProfile.DEBUG, model.updatePlan().orElseThrow().loggingProfile());
			});
		} finally {
			Diagnostics.setProfile(DiagnosticSide.SERVER, previousServer);
		}
	}

	@Test
	void readOnlyServerRowShowsDebugWithoutEditingOrUnlocking() throws Exception {
		withScreen(new ClientConfig(), null, (screen, client) -> {
			ServerSettingsModel model = openServerPerformance(screen, snapshot(false, LoggingProfile.DEBUG));
			LoggingProfileButton button = loggingProfileButton(screen);
			assertEquals("Server Logging Detail: Debug", button.getMessage().getString(),
				"a read-only viewer sees the authoritative Debug value");
			assertFalse(button.active, "a read-only viewer cannot activate or Shift-unlock the row");

			button.onPress();

			assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
			assertFalse(model.dirty());
			assertFalse(screen.loggingProfileSession().isUnlocked(LoggingProfileSession.Side.SERVER));
		});
	}

	private static ServerConfigSnapshot snapshot(boolean canEdit, LoggingProfile profile) {
		return new ServerConfigSnapshot(
			canEdit,
			ChannelMode.AUTO,
			true,
			1000,
			5,
			7,
			InventoryConfigValues.defaults(),
			true,
			profile);
	}

	private static ServerSettingsModel openServerPerformance(
		SettingsScreen screen,
		ServerConfigSnapshot snapshot
	) {
		selectScope(screen, 1);
		var model = serverSettings(screen);
		model.setClientPermission(true);
		screen.onServerConfigSnapshot(model.beginSessionIfNeeded(), snapshot);
		openCategory(screen, Category.PERFORMANCE);
		return model;
	}

	private static ServerSettingsModel serverSettings(SettingsScreen screen) {
		try {
			var field = SettingsScreen.class.getDeclaredField("serverSettings");
			field.setAccessible(true);
			return Objects.requireNonNull((ServerSettingsModel) field.get(screen));
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(error);
		}
	}

	private static LoggingProfileButton loggingProfileButton(SettingsScreen screen) {
		return widgets(screen).stream().filter(LoggingProfileButton.class::isInstance)
			.map(LoggingProfileButton.class::cast).findFirst().orElseThrow();
	}

	private static String tooltipText(AbstractWidget widget) {
		var tooltip = assertInstanceOf(Tooltip.class, widget.getTooltip());
		var narration = new TooltipNarration();
		tooltip.updateNarration(narration);
		return narration.message.getString();
	}

	private static void selectScope(SettingsScreen screen, int index) {
		screen.children().stream().filter(TabNavigationBar.class::isInstance).map(TabNavigationBar.class::cast)
			.findFirst().orElseThrow().selectTab(index, false);
	}

	private static void openCategory(SettingsScreen screen, Category category) {
		widgets(screen).stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> hasKey(button, "settings.pingforit.category." + category.id() + ".button"))
			.findFirst().orElseThrow().onPress();
	}

	private static Button backButton(SettingsScreen screen) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> button.getMessage().equals(net.minecraft.network.chat.CommonComponents.GUI_BACK))
			.findFirst().orElseThrow();
	}

	private static boolean hasKey(AbstractWidget widget, String key) {
		return widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
			&& contents.getKey().equals(key);
	}

	private static SettingsScreen.SettingsOptionsList optionsList(SettingsScreen screen) {
		return screen.children().stream().filter(SettingsScreen.SettingsOptionsList.class::isInstance)
			.map(SettingsScreen.SettingsOptionsList.class::cast).findFirst().orElseThrow();
	}

	private static @Unmodifiable List<AbstractWidget> widgets(SettingsScreen screen) {
		List<?> entries = optionsList(screen).children();
		return entries.stream().map(ContainerEventHandler.class::cast)
			.flatMap(entry -> entry.children().stream()).map(AbstractWidget.class::cast).toList();
	}

	private static void withScreen(
		ClientConfig config,
		@Nullable Screen parent,
		ScreenCheck check
	) throws Exception {
		var client = allocate(HeadlessMinecraft.class);
		client.setLastInputType(InputType.KEYBOARD_ARROW);
		var instanceField = Minecraft.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		@Nullable Object previousClient = instanceField.get(null);
		@Nullable Minecraft previousGame = CommonClient.Game;
		Language previousLanguage = Language.getInstance();
		var currentScreenField = SettingsScreen.class.getDeclaredField("currentSettingsScreen");
		currentScreenField.setAccessible(true);
		@Nullable Object previousScreen = currentScreenField.get(null);
		var screen = new TestSettingsScreen(config, parent);
		try {
			instanceField.set(null, client);
			CommonClient.Game = client;
			Language.inject(new EnglishLanguage(previousLanguage));
			screen.initialize(client);
			check.run(screen, client);
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

	@NotNullByDefault
	@FunctionalInterface
	private interface ScreenCheck {
		void run(TestSettingsScreen screen, HeadlessMinecraft client) throws Exception;
	}

	@NotNullByDefault
	private static final class TestParent extends Screen {
		private TestParent() {
			super(Component.literal("parent"));
		}
	}

	@NotNullByDefault
	private static final class TestSettingsScreen extends SettingsScreen {
		private boolean saveSucceeds = true;
		private boolean resetRecreated;

		private TestSettingsScreen(ClientConfig config, @Nullable Screen parent) {
			super(config, parent);
		}

		private void initialize(Minecraft client) {
			this.minecraft = client;
			client.screen = this;
			this.font = new MeasuredFont();
			this.width = 426;
			this.height = 240;
			super.init();
		}

		private void resizeTo(int width, int height) {
			this.resize(this.minecraft, width, height);
		}

		@Override
		boolean persistSettings() {
			return this.saveSucceeds;
		}

		@Override
		void recreateAfterClientReset() {
			this.resetRecreated = true;
			this.minecraft.setScreen(new TestSettingsScreen(new ClientConfig(), null));
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
			this.screen = screen;
			if (screen instanceof TestSettingsScreen test) {
				test.initialize(this);
			}
		}
	}

	@NotNullByDefault
	private static final class MeasuredFont extends Font {
		private MeasuredFont() {
			super(ignored -> {
				throw new AssertionError("the fixture must not load or render native glyphs");
			}, false);
		}

		@Override
		public int width(String text) {
			return text.codePointCount(0, text.length()) * 6;
		}

		@Override
		public int width(FormattedText text) {
			return this.width(text.getString());
		}
	}

	@NotNullByDefault
	private static final class TooltipNarration implements NarrationElementOutput {
		private Component message = Component.empty();

		@Override
		public void add(NarratedElementType type, NarrationThunk<?> contents) {
			throw new AssertionError("the native tooltip should narrate its Component directly");
		}

		@Override
		public void add(NarratedElementType type, Component contents) {
			this.message = this.message.copy().append(contents);
		}

		@Override
		public NarrationElementOutput nest() {
			return this;
		}
	}

	@NotNullByDefault
	private static final class EnglishLanguage extends Language {
		private final Language fallback;
		private final @Unmodifiable Map<String, String> translations;

		private EnglishLanguage(Language fallback) throws IOException {
			this.fallback = fallback;
			var loaded = new HashMap<String, String>();
			try (@Nullable InputStream stream = LoggingProfileScreenTest.class
				.getResourceAsStream("/assets/pingforit/lang/en_us.json")) {
				assertNotNull(stream);
				Language.loadFromJson(stream, loaded::put);
			}
			this.translations = Map.copyOf(loaded);
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
}
