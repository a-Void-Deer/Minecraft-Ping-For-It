package nx.pingwheel.common.screen;

import net.minecraft.SharedConstants;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ServerConfigSnapshot;
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

import static org.junit.jupiter.api.Assertions.*;

/** Assembled production widgets and native resize, with deterministic measurements and no rendering or window. */
@NotNullByDefault
class SettingsScreenResizeTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void clientOverviewResetRecoversAfterNativeResizeWithoutRebuildingThePage() throws Exception {
		withScreen(640, 360, screen -> {
			Button reset = resetButton(screen);
			var list = optionsList(screen);
			assertTrue(reset.visible);
			assertTrue(reset.active);

			screen.resizeTo(400, 70);

			assertTrue(screen.layout.getFooterHeight() < reset.getHeight(), "the short footer cannot contain Reset");
			assertFalse(reset.visible);
			assertFalse(reset.active);
			assertNull(reset.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));

			screen.resizeTo(640, 360);

			assertSame(list, optionsList(screen), "native resize must retain the page rather than rebuilding it");
			assertSame(reset, resetButton(screen));
			assertTrue(reset.visible, "Reset must recover from a geometry-only hidden state");
			assertTrue(reset.active, "Reset availability must follow its recovered visibility");
			assertNotNull(reset.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));
			assertTrue(reset.getY() >= screen.height - screen.layout.getFooterHeight());
			assertTrue(reset.getBottom() <= screen.height);
		});
	}

	@Test
	void resizeNeverOffersResetOnTheServerOverviewOrEitherScopesLeafPages() throws Exception {
		withScreen(640, 360, screen -> {
			selectScope(screen, 1);
			assertResetHiddenAcrossResize(screen);
			openCategory(screen, Category.SERVER_PRESENTATION);
			assertResetHiddenAcrossResize(screen);
			backButton(screen).onPress();
			selectScope(screen, 0);
			assertTrue(resetButton(screen).visible);
			openCategory(screen, Category.TARGET_SELECTION);
			assertResetHiddenAcrossResize(screen);
			backButton(screen).onPress();
			assertTrue(resetButton(screen).visible, "returning to the client overview restores its action");
			assertTrue(resetButton(screen).active);
		});
	}

	@Test
	void retainedSubgroupHeadingKeepsFullTextHelpWhenNativeResizeMakesItClip() throws Exception {
		withScreen(426, 240, screen -> {
			selectScope(screen, 1);
			ServerSettingsModel model = serverSettings(screen);
			screen.onServerConfigSnapshot(model.beginSessionIfNeeded(),
				new ServerConfigSnapshot(false, ChannelMode.AUTO, true, 1000, 5, 30));
			openCategory(screen, Category.CHANNEL_PLAYERS);
			var list = optionsList(screen);
			String headingKey = SettingsCategoryCatalog.sections(Category.CHANNEL_PLAYERS).getFirst().headingKey();
			StringWidget heading = heading(screen, headingKey);
			Component fullText = heading.getMessage();
			assertTrue(screen.measure(fullText) <= heading.getWidth(), "the heading initially fits without clipping");

			screen.resizeTo(200, 480);

			assertSame(list, optionsList(screen));
			assertSame(heading, heading(screen, headingKey), "native resize reflows the original heading");
			assertTrue(screen.measure(fullText) > heading.getWidth(), "the retained heading now needs native clipping");
			assertEquals(fullText, tooltipMessage(assertInstanceOf(Tooltip.class, heading.getTooltip())),
				"a heading that fitted when created still needs complete help after shrinking");
			assertFalse(heading.active);
			assertNull(heading.nextFocusPath(new FocusNavigationEvent.TabNavigation(true)));

			screen.resizeTo(426, 240);
			assertSame(heading, heading(screen, headingKey));
			assertEquals(fullText, heading.getMessage());
			assertTrue(screen.measure(fullText) <= heading.getWidth());
		});
	}

	@Test
	void serverPresentationHeadingRetainsItsDedicatedHelpInsteadOfGenericFullTextHelp() throws Exception {
		withScreen(426, 240, screen -> {
			selectScope(screen, 1);
			openCategory(screen, Category.SERVER_PRESENTATION);
			String headingKey = SettingsCategoryCatalog.sections(Category.SERVER_PRESENTATION).getFirst().headingKey();
			StringWidget heading = heading(screen, headingKey);
			Component expectedHelp = Component.translatable("settings.pingforit.presentation.server.tooltip");
			assertEquals(expectedHelp, tooltipMessage(assertInstanceOf(Tooltip.class, heading.getTooltip())));

			screen.resizeTo(200, 480);
			screen.resizeTo(426, 240);

			assertSame(heading, heading(screen, headingKey));
			assertEquals(expectedHelp, tooltipMessage(assertInstanceOf(Tooltip.class, heading.getTooltip())));
			assertFalse(heading.active);
		});
	}

	private static void assertResetHiddenAcrossResize(HeadlessSettingsScreen screen) {
		Button reset = resetButton(screen);
		assertFalse(reset.visible);
		assertFalse(reset.active);
		screen.resizeTo(400, 70);
		screen.resizeTo(640, 360);
		assertSame(reset, resetButton(screen));
		assertFalse(reset.visible, "geometry recovery must not grant a Reset action to this page");
		assertFalse(reset.active);
	}

	private static Button resetButton(SettingsScreen screen) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> hasKey(button, "settings.pingforit.reset_all")).findFirst().orElseThrow();
	}

	private static Button backButton(SettingsScreen screen) {
		return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> button.getMessage().equals(CommonComponents.GUI_BACK)).findFirst().orElseThrow();
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

	private static StringWidget heading(SettingsScreen screen, String key) {
		return widgets(screen).stream().filter(StringWidget.class::isInstance).map(StringWidget.class::cast)
			.filter(widget -> hasKey(widget, key)).findFirst().orElseThrow();
	}

	private static boolean hasKey(AbstractWidget widget, String key) {
		return widget.getMessage().getContents() instanceof TranslatableContents contents && contents.getKey().equals(key);
	}

	private static SettingsScreen.SettingsOptionsList optionsList(SettingsScreen screen) {
		return screen.children().stream().filter(SettingsScreen.SettingsOptionsList.class::isInstance)
			.map(SettingsScreen.SettingsOptionsList.class::cast).findFirst().orElseThrow();
	}

	private static @Unmodifiable List<AbstractWidget> widgets(SettingsScreen screen) {
		List<?> entries = optionsList(screen).children();
		return entries.stream().map(ContainerEventHandler.class::cast).flatMap(entry -> entry.children().stream())
			.map(AbstractWidget.class::cast).toList();
	}

	/** Only snapshot-request setup is injected; navigation, assembly and resize use the production paths. */
	private static ServerSettingsModel serverSettings(SettingsScreen screen) throws ReflectiveOperationException {
		var field = SettingsScreen.class.getDeclaredField("serverSettings");
		field.setAccessible(true);
		return Objects.requireNonNull((ServerSettingsModel) field.get(screen));
	}

	private static Component tooltipMessage(Tooltip tooltip) {
		var narration = new TooltipNarration();
		tooltip.updateNarration(narration);
		return narration.message;
	}

	private static void withScreen(int width, int height, ScreenCheck check) throws Exception {
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		var unsafeField = unsafe.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		var client = (Minecraft) unsafe.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), Minecraft.class);
		client.setLastInputType(InputType.KEYBOARD_ARROW);
		var instanceField = Minecraft.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		@Nullable Object previousClient = instanceField.get(null);
		@Nullable Minecraft previousGame = CommonClient.Game;
		Language previousLanguage = Language.getInstance();
		var currentScreenField = SettingsScreen.class.getDeclaredField("currentSettingsScreen");
		currentScreenField.setAccessible(true);
		@Nullable Object previousScreen = currentScreenField.get(null);
		var language = new EnglishLanguage(previousLanguage);
		var screen = new HeadlessSettingsScreen();
		try {
			instanceField.set(null, client);
			CommonClient.Game = client;
			Language.inject(language);
			screen.initialize(client, width, height);
			check.run(screen);
		} finally {
			screen.removed();
			currentScreenField.set(null, previousScreen);
			Language.inject(previousLanguage);
			CommonClient.Game = previousGame;
			instanceField.set(null, previousClient);
		}
	}

	@NotNullByDefault
	@FunctionalInterface
	private interface ScreenCheck {
		void run(HeadlessSettingsScreen screen) throws Exception;
	}

	@NotNullByDefault
	private static final class HeadlessSettingsScreen extends SettingsScreen {
		private HeadlessSettingsScreen() {
			super(new ClientConfig(), null);
		}

		private void initialize(Minecraft client, int width, int height) {
			this.minecraft = client;
			this.font = new MeasuredFont();
			this.width = width;
			this.height = height;
			// Assemble every production widget without Screen.init's native narrator/window setup.
			super.init();
		}

		private void resizeTo(int width, int height) {
			this.resize(this.minecraft, width, height);
		}

		private int measure(Component text) {
			return this.font.width(text);
		}
	}

	@NotNullByDefault
	private static final class MeasuredFont extends Font {
		private MeasuredFont() {
			super(ignored -> { throw new AssertionError("the fixture must not load or render native glyphs"); }, false);
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
			this.message = contents;
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
			var translations = new HashMap<String, String>();
			try (@Nullable InputStream stream = SettingsScreenResizeTest.class.getResourceAsStream("/assets/pingforit/lang/en_us.json")) {
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
}
