package nx.pingwheel.common.screen;

import com.mojang.blaze3d.platform.Window;
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
import nx.pingwheel.common.config.ConfigHandler;
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
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless mounted-widget seams with a physical modifier-key read; this is not a live-window or macOS claim. */
@NotNullByDefault
class LoggingProfileScreenTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void navigationAndResizeDoNotCarryTheTemporaryAltCandidate() throws Exception {
		var config = new ClientConfig();
		LoggingProfile previousRuntime = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			withScreen(config, (screen, client) -> {
				openCategory(screen, Category.CLIENT_DEBUG);
				LoggingProfileButton button = loggingProfileButton(screen);

				PhysicalAltKeys.hold(true);
				button.onClick(0, 0);
				button.onClick(0, 0);
				PhysicalAltKeys.hold(false);
				assertEquals(LoggingProfile.DEBUG, config.getLoggingProfile());
				assertEquals("Logging Detail: Debug", button.getMessage().getString());

				backButton(screen).onPress();
				selectScope(screen, 1);
				openCategory(screen, Category.SERVER_DEBUG);
				backButton(screen).onPress();
				selectScope(screen, 0);
				openCategory(screen, Category.CLIENT_DEBUG);
				screen.resizeTo(640, 360);

				LoggingProfileButton reopened = loggingProfileButton(screen);
				assertEquals("Logging Detail: Debug", reopened.getMessage().getString(),
					"the selected Debug value remains truthful after navigation and resize");

				reopened.onClick(0, 0);
				assertEquals(LoggingProfile.NORMAL, config.getLoggingProfile());
				reopened.onClick(0, 0);
				assertEquals(LoggingProfile.DEEP, config.getLoggingProfile(),
					"a released Alt candidate is not retained by the page or screen session");

				PhysicalAltKeys.hold(true);
				reopened.onClick(0, 0);
				assertEquals(LoggingProfile.DEBUG, config.getLoggingProfile(),
					"a later Alt press still adds Debug for that press only");
			});
		} finally {
			PhysicalAltKeys.hold(false);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousRuntime);
		}
	}

	@Test
	void mountedRowReadsPhysicalAltAndDoesNotPersistAnUnlock() throws Exception {
		var config = new ClientConfig();
		LoggingProfile previousRuntime = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			withScreen(config, (screen, client) -> {
				openCategory(screen, Category.CLIENT_DEBUG);
				LoggingProfileButton button = loggingProfileButton(screen);

				PhysicalAltKeys.hold(true);
				button.onClick(0, 0);
				assertEquals(LoggingProfile.DEEP, config.getLoggingProfile());
				assertEquals("Logging Detail: Detailed", button.getMessage().getString());

				button.onClick(0, 0);
				assertEquals(LoggingProfile.DEBUG, config.getLoggingProfile());
				assertEquals("Logging Detail: Debug", button.getMessage().getString());

				PhysicalAltKeys.hold(false);
				assertEquals("Logging Detail: Debug", button.getMessage().getString(),
					"releasing Alt changes no value and no caption");

				button.onClick(0, 0);
				assertEquals(LoggingProfile.NORMAL, config.getLoggingProfile());
				button.onClick(0, 0);
				assertEquals(LoggingProfile.DEEP, config.getLoggingProfile(),
					"the physical Alt press left no persistent candidate");
			});
		} finally {
			PhysicalAltKeys.hold(false);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousRuntime);
		}
	}

	@Test
	void loggingRowsExposeShortScopeHelpWithoutUnlockOrDebugHints() throws Exception {
		withScreen(new ClientConfig(), (screen, client) -> {
			openCategory(screen, Category.CLIENT_DEBUG);
			LoggingProfileButton clientRow = loggingProfileButton(screen);
			assertTrue(clientRow.getMessage().getString().contains("Logging Detail"));
			String clientHelp = tooltipText(clientRow);
			assertTrue(clientHelp.contains("Client log detail."));
			assertTrue(clientHelp.contains("Applies immediately."));
			assertNoUnlockHint(clientHelp);

			backButton(screen).onPress();
			selectScope(screen, 1);
			ServerSettingsModel model = serverSettings(screen);
			model.setClientPermission(true);
			screen.onServerConfigSnapshot(model.beginSessionIfNeeded(), snapshot(true, LoggingProfile.NORMAL));
			openCategory(screen, Category.SERVER_DEBUG);
			LoggingProfileButton serverRow = loggingProfileButton(screen);
			assertTrue(serverRow.getMessage().getString().contains("Server Logging Detail"));
			String serverHelp = tooltipText(serverRow);
			assertTrue(serverHelp.contains("Server log detail."));
			assertTrue(serverHelp.contains("Submitted when settings close"));
			assertNoUnlockHint(serverHelp);
		});
	}

	@Test
	void clientRowShowsARealDebugValueAndAppliesClickChangesToConfigAndRuntime() throws Exception {
		var config = new ClientConfig();
		config.setLoggingProfile(LoggingProfile.DEBUG);
		config.setMonitorHudEnabled(true);
		config.getMonitorMetrics().setSelected("summary.processing", false);
		LoggingProfile previousRuntime = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			withScreen(config, (screen, client) -> {
				openCategory(screen, Category.CLIENT_DEBUG);
				LoggingProfileButton button = loggingProfileButton(screen);
				assertEquals("Logging Detail: Debug", button.getMessage().getString(),
					"a current Debug value stays visible without holding Alt");

				button.onPress();
				assertEquals(LoggingProfile.NORMAL, config.getLoggingProfile());
				assertEquals(LoggingProfile.NORMAL, Diagnostics.profile(DiagnosticSide.CLIENT));
				assertFalse(config.isMonitorHudEnabled());
				assertFalse(config.getMonitorMetrics().isSelected("summary.processing"));

				button.onPress();
				assertEquals(LoggingProfile.DEEP, config.getLoggingProfile());
				assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.CLIENT));
				assertFalse(config.isMonitorHudEnabled());

				PhysicalAltKeys.hold(true);
				button.onPress();
				assertEquals(LoggingProfile.DEBUG, config.getLoggingProfile());
				assertEquals(LoggingProfile.DEBUG, Diagnostics.profile(DiagnosticSide.CLIENT),
					"the client setter immediately publishes the selected profile");
				assertFalse(config.isMonitorHudEnabled(), "re-entering DEBUG never restores the HUD");
				assertFalse(config.getMonitorMetrics().isSelected("summary.processing"));
			});
		} finally {
			PhysicalAltKeys.hold(false);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousRuntime);
		}
	}

	@Test
	void loggingRowFollowsTheHandlersReplacementConfigAfterAStaleMigrationSave(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, "{\"pingforit-version\":\"0.4.0-pfi-beta1\",\"loggingProfile\":\"NORMAL\"}\n");
		ConfigHandler<ClientConfig> handler = newClientHandler(path);
		handler.load();
		ClientConfig constructionTimeConfig = handler.getConfig();
		assertEquals(LoggingProfile.NORMAL, constructionTimeConfig.getLoggingProfile());
		LoggingProfile previousRuntime = Diagnostics.profile(DiagnosticSide.CLIENT);
		try {
			withScreen(constructionTimeConfig, handler::getConfig, (screen, client) -> {
				openCategory(screen, Category.CLIENT_DEBUG);
				LoggingProfileButton button = loggingProfileButton(screen);
				assertEquals("Logging Detail: Normal", button.getMessage().getString());

				Files.writeString(path,
					"{\"pingforit-version\":\"0.5.0-pfi-beta1\",\"loggingProfile\":\"DEEP\"}\n");
				assertFalse(handler.saveSafely(), "the stale migration must fail its guarded save");
				ClientConfig replacement = handler.getConfig();
				assertNotSame(constructionTimeConfig, replacement,
					"the guarded reload must replace the handler-owned config object");
				assertEquals(LoggingProfile.DEEP, replacement.getLoggingProfile());
				assertEquals(LoggingProfile.NORMAL, constructionTimeConfig.getLoggingProfile(),
					"the construction-time fixture is not the handler's current object");
				assertSame(screen, client.screen, "the screen remains open after the guarded save failure");
				assertSame(button, loggingProfileButton(screen),
					"the same mounted logging button is exercised across the replacement");
				assertEquals("Logging Detail: Normal", button.getMessage().getString(),
					"the same mounted button is exercised across the replacement");

				button.onPress();
				assertEquals(LoggingProfile.NORMAL, replacement.getLoggingProfile(),
					"the getter read the replacement handler config");
				assertEquals(LoggingProfile.NORMAL, constructionTimeConfig.getLoggingProfile(),
					"the stale construction-time config was not edited");
				assertEquals(LoggingProfile.NORMAL, Diagnostics.profile(DiagnosticSide.CLIENT));

				button.onPress();
				assertEquals(LoggingProfile.DEEP, replacement.getLoggingProfile(),
					"the setter wrote the replacement handler config");
				assertEquals(LoggingProfile.NORMAL, constructionTimeConfig.getLoggingProfile(),
					"the stale construction-time config still has no edit");
				assertEquals("Logging Detail: Detailed", button.getMessage().getString());
				assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.CLIENT),
					"the replacement config's selected profile is published immediately");

				PhysicalAltKeys.hold(true);
				button.onPress();
				assertEquals(LoggingProfile.DEBUG, replacement.getLoggingProfile(),
					"the temporarily available Debug candidate also writes the replacement handler config");
				assertEquals(LoggingProfile.NORMAL, constructionTimeConfig.getLoggingProfile());
				assertEquals("Logging Detail: Debug", button.getMessage().getString());
				assertEquals(LoggingProfile.DEBUG, Diagnostics.profile(DiagnosticSide.CLIENT));
			});
		} finally {
			PhysicalAltKeys.hold(false);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousRuntime);
		}
	}

	@Test
	void serverRowEditsOnlyTheDraftAndNeverPublishesTheRuntimeProfile() throws Exception {
		LoggingProfile previousServer = Diagnostics.profile(DiagnosticSide.SERVER);
		try {
			withScreen(new ClientConfig(), (screen, client) -> {
				ServerSettingsModel model = openServerDebug(screen, snapshot(true, LoggingProfile.DEEP));
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

				PhysicalAltKeys.hold(true);
				button.onPress();
				assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
				assertEquals(LoggingProfile.DEBUG, model.updatePlan().orElseThrow().loggingProfile());
				assertEquals(previousServer, Diagnostics.profile(DiagnosticSide.SERVER),
					"the temporary Debug candidate still stays in the draft");
			});
		} finally {
			PhysicalAltKeys.hold(false);
			Diagnostics.setProfile(DiagnosticSide.SERVER, previousServer);
		}
	}

	@Test
	void readOnlyServerRowShowsDebugWithoutEditing() throws Exception {
		withScreen(new ClientConfig(), (screen, client) -> {
			ServerSettingsModel model = openServerDebug(screen, snapshot(false, LoggingProfile.DEBUG));
			LoggingProfileButton button = loggingProfileButton(screen);
			assertEquals("Server Logging Detail: Debug", button.getMessage().getString(),
				"a read-only viewer sees the authoritative Debug value");
			assertFalse(button.active, "a read-only viewer cannot activate the row");

			PhysicalAltKeys.hold(true);
			button.onPress();
			button.onClick(0, 0);

			assertEquals(LoggingProfile.DEBUG, model.loggingProfile());
			assertFalse(model.dirty());
			assertTrue(model.updatePlan().isEmpty());
		});
	}

	private static void assertNoUnlockHint(String help) {
		String normalized = help.toLowerCase(Locale.ROOT);
		assertFalse(normalized.contains("unlock"));
		assertFalse(normalized.contains("shift"));
		assertFalse(normalized.contains("alt"));
		assertFalse(normalized.contains("debug"));
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

	private static ServerSettingsModel openServerDebug(
		SettingsScreen screen,
		ServerConfigSnapshot snapshot
	) {
		selectScope(screen, 1);
		var model = serverSettings(screen);
		model.setClientPermission(true);
		screen.onServerConfigSnapshot(model.beginSessionIfNeeded(), snapshot);
		openCategory(screen, Category.SERVER_DEBUG);
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

	@SuppressWarnings("unchecked")
	private static ConfigHandler<ClientConfig> newClientHandler(Path path) throws Exception {
		Class<?> backupWriterType = Class.forName("nx.pingwheel.common.config.BackupWriter");
		Class<?> atomicWriterType = Class.forName("nx.pingwheel.common.config.AtomicConfigWriter");
		Object backupWriter = java.lang.reflect.Proxy.newProxyInstance(
			ConfigHandler.class.getClassLoader(), new Class<?>[] {backupWriterType},
			(proxy, method, arguments) -> null);
		Object failingWriter = java.lang.reflect.Proxy.newProxyInstance(
			ConfigHandler.class.getClassLoader(), new Class<?>[] {atomicWriterType},
			(proxy, method, arguments) -> {
				throw new IOException("injected migration write failure");
			});
		var constructor = ConfigHandler.class.getDeclaredConstructor(
			Class.class, Path.class, backupWriterType, String.class, atomicWriterType);
		constructor.setAccessible(true);
		return (ConfigHandler<ClientConfig>) constructor.newInstance(
			ClientConfig.class, path, backupWriter, "0.5.0-pfi-beta1", failingWriter);
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

	private static void withScreen(ClientConfig config, ScreenCheck check) throws Exception {
		withScreen(config, () -> config, check);
	}

	private static void withScreen(
		ClientConfig config,
		Supplier<ClientConfig> loggingConfig,
		ScreenCheck check
	) throws Exception {
		var client = allocate(HeadlessMinecraft.class);
		client.setLastInputType(InputType.KEYBOARD_ARROW);
		var window = allocate(Window.class);
		setField(Window.class, window, "window", 1L);
		setField(Minecraft.class, client, "window", window);
		var instanceField = Minecraft.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		@Nullable Object previousClient = instanceField.get(null);
		@Nullable Minecraft previousGame = CommonClient.Game;
		Language previousLanguage = Language.getInstance();
		var currentScreenField = SettingsScreen.class.getDeclaredField("currentSettingsScreen");
		currentScreenField.setAccessible(true);
		@Nullable Object previousScreen = currentScreenField.get(null);
		var screen = new TestSettingsScreen(config, loggingConfig);
		try (var ignored = new PhysicalAltKeys()) {
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

	private static void setField(Class<?> owner, Object instance, String name, Object value)
		throws ReflectiveOperationException {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(instance, value);
	}

	@NotNullByDefault
	@FunctionalInterface
	private interface ScreenCheck {
		void run(TestSettingsScreen screen, HeadlessMinecraft client) throws Exception;
	}

	@NotNullByDefault
	private static final class TestSettingsScreen extends SettingsScreen {
		private TestSettingsScreen(ClientConfig config, Supplier<ClientConfig> loggingConfig) {
			super(config, null, loggingConfig);
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
	}

	@NotNullByDefault
	private static final class HeadlessMinecraft extends Minecraft {
		private HeadlessMinecraft() {
			super(null);
			throw new AssertionError("allocate without starting Minecraft");
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

	/** Native Screen.hasAltDown asks GLFW; intercept that read without creating a live window. */
	@NotNullByDefault
	private static final class PhysicalAltKeys implements AutoCloseable {
		// A JVM may inline GLFW's final function pointer. Keep one bounded callback
		// alive for the test process even after restoring the original pointer.
		private static final PressedKeyCallback CALLBACK = new PressedKeyCallback();
		private static volatile boolean held;
		private final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		private final Object unsafe;
		private final Object functionBase;
		private final long functionOffset;
		private final long previousFunction;

		private PhysicalAltKeys() throws ReflectiveOperationException {
			var unsafeField = this.unsafeClass.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			this.unsafe = Objects.requireNonNull(unsafeField.get(null));
			java.lang.reflect.Field function = Class.forName("org.lwjgl.glfw.GLFW$Functions")
				.getDeclaredField("GetKey");
			this.functionBase = Objects.requireNonNull(
				this.unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field.class)
					.invoke(this.unsafe, function));
			this.functionOffset = (long) this.unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field.class)
				.invoke(this.unsafe, function);
			this.previousFunction = function.getLong(null);
			this.writeFunction(CALLBACK.address);
		}

		private static void hold(boolean value) {
			held = value;
		}

		private void writeFunction(long address) throws ReflectiveOperationException {
			this.unsafeClass.getMethod("putLongVolatile", Object.class, long.class, long.class)
				.invoke(this.unsafe, this.functionBase, this.functionOffset, address);
		}

		@Override
		public void close() throws ReflectiveOperationException {
			held = false;
			this.writeFunction(this.previousFunction);
		}
	}

	@NotNullByDefault
	private static final class PressedKeyCallback implements CallbackI {
		private final FFICIF callInterface = FFICIF.calloc();
		private final PointerBuffer argumentTypes = MemoryUtil.memAllocPointer(2);
		private final long address;

		private PressedKeyCallback() {
			this.argumentTypes.put(0, LibFFI.ffi_type_pointer.address())
				.put(1, LibFFI.ffi_type_sint32.address());
			assertEquals(LibFFI.FFI_OK,
				LibFFI.ffi_prep_cif(this.callInterface, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_sint32,
					this.argumentTypes));
			this.address = CallbackI.super.address();
		}

		@Override
		public FFICIF getCallInterface() {
			return this.callInterface;
		}

		@Override
		public void callback(long result, long arguments) {
			int key = MemoryUtil.memGetInt(MemoryUtil.memGetAddress(arguments + Pointer.POINTER_SIZE));
			boolean alt = key == 342 || key == 346;
			MemoryUtil.memPutInt(result, PhysicalAltKeys.held && alt ? 1 : 0);
		}
	}
}
