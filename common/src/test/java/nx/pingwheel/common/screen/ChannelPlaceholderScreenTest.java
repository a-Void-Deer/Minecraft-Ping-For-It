package nx.pingwheel.common.screen;

import net.minecraft.SharedConstants;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.scores.PlayerTeam;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.config.ChannelMode;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.config.ServerConfigSnapshot;
import nx.pingwheel.common.integration.TeamContext;
import nx.pingwheel.common.network.ServerConfigRequestC2SPacket;
import nx.pingwheel.common.resource.LanguageUtils;
import nx.pingwheel.common.screen.SettingsNavigationModel.Category;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The channel hint follows the received authoritative mode without replacing a compatible field. */
@NotNullByDefault
class ChannelPlaceholderScreenTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void openingTheRootDoesNotRequestUntilTheClientChannelPageOpens() throws Exception {
		withScreen(false, true, (screen, player) -> {
			assertTrue(screen.requests.isEmpty(), "opening the root must not prefetch server settings");
			screen.tick();
			assertTrue(screen.requests.isEmpty(), "an idle root page must not request server settings");

			openChannelPage(screen);

			assertEquals(1, screen.requests.size(), "the channel page needs the shared server snapshot");
			long requestId = screen.requests.getFirst().requestId();
			assertTrue(requestId > 0L);

			screen.tick();

			assertEquals(1, screen.requests.size(), "an in-flight session must not send a duplicate request");
			assertNoHint(hint(channelField(screen)));
		});
	}

	@Test
	void globalSnapshotRefreshesTheExistingHintWithoutReplacingDraftText() throws Exception {
		withScreen(true, true, (screen, player) -> {
			openChannelPage(screen);
			EditBox channel = channelField(screen);
			assertFalse(hint(channel).getContents() instanceof TranslatableContents,
				"no received authority means no allowed hint");
			channel.setValue("local-draft");
			channel.setCursorPosition(2);

			screen.onServerConfigSnapshot(screen.requests.getFirst().requestId(),
				new ServerConfigSnapshot(true, ChannelMode.GLOBAL, true, 1000, 5));

			assertSame(channel, channelField(screen), "the response must update the open field, not rebuild it");
			assertGlobalHint(hint(channel));
			assertEquals("local-draft", channel.getValue(), "the response must keep the draft text");
			assertEquals(2, channel.getCursorPosition(), "the response must keep the caret");
		});
	}

	@Test
	void autoSnapshotUsesTheTeamHintAndIgnoresAnEditedServerDraft() throws Exception {
		withScreen(true, true, (screen, player) -> {
			openChannelPage(screen);
			EditBox channel = channelField(screen);

			screen.onServerConfigSnapshot(screen.requests.getFirst().requestId(),
				new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5));

			assertTeamSyncedHint(hint(channel));

			ServerSettingsModel serverSettings = serverSettings(screen);
			assertEquals(ChannelMode.AUTO, serverSettings.authoritativeDefaultChannelMode());
			serverSettings.cycleDefaultChannelMode();
			assertEquals(ChannelMode.DISABLED, serverSettings.defaultChannelMode());
			screen.tick();

			assertSame(channel, channelField(screen), "the draft edit must not rebuild the open field");
			assertTeamSyncedHint(hint(channel));
		});
	}

	@Test
	void autoHintFollowsTeamJoinAndLeaveWithoutReplacingTheFieldOrRequestingAgain() throws Exception {
		withScreen(false, true, (screen, player) -> {
			openChannelPage(screen);
			EditBox channel = channelField(screen);
			screen.onServerConfigSnapshot(screen.requests.getFirst().requestId(),
				new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5));
			assertNoHint(hint(channel));
			channel.setValue("draft");
			channel.setCursorPosition(1);

			player.team = allocate(PlayerTeam.class);
			screen.tick();

			assertSame(channel, channelField(screen), "a team change must not rebuild the open field");
			assertTeamSyncedHint(hint(channel));
			assertEquals("draft", channel.getValue(), "a team change must keep the draft text");
			assertEquals(1, channel.getCursorPosition(), "a team change must keep the caret");
			assertEquals(1, screen.requests.size(), "a team change must not request another snapshot");

			player.team = null;
			screen.tick();

			assertSame(channel, channelField(screen));
			assertNoHint(hint(channel));
			assertEquals("draft", channel.getValue());
			assertEquals(1, channel.getCursorPosition());
			assertEquals(1, screen.requests.size());
		});
	}

	@Test
	void globalHintIgnoresTeamMembershipChanges() throws Exception {
		withScreen(false, true, (screen, player) -> {
			openChannelPage(screen);
			EditBox channel = channelField(screen);
			screen.onServerConfigSnapshot(screen.requests.getFirst().requestId(),
				new ServerConfigSnapshot(true, ChannelMode.GLOBAL, true, 1000, 5));
			assertGlobalHint(hint(channel));

			player.team = allocate(PlayerTeam.class);
			screen.tick();

			assertSame(channel, channelField(screen));
			assertGlobalHint(hint(channel));
			assertEquals(1, screen.requests.size(), "a team change must not request another snapshot");
		});
	}

	@Test
	void readOnlySnapshotStillSuppliesTheAuthoritativeHint() throws Exception {
		withScreen(true, false, (screen, player) -> {
			openChannelPage(screen);
			EditBox channel = channelField(screen);
			screen.onServerConfigSnapshot(screen.requests.getFirst().requestId(),
				new ServerConfigSnapshot(false, ChannelMode.GLOBAL, true, 1000, 5));

			assertSame(channel, channelField(screen));
			assertGlobalHint(hint(channel));
		});
	}

	@Test
	void disconnectRemovesTheReceivedAuthorityHint() throws Exception {
		withScreen(false, true, (screen, player) -> {
			openChannelPage(screen);
			EditBox channel = channelField(screen);
			screen.onServerConfigSnapshot(screen.requests.getFirst().requestId(),
				new ServerConfigSnapshot(true, ChannelMode.GLOBAL, true, 1000, 5));
			assertGlobalHint(hint(channel));

			screen.onServerDisconnected();

			assertSame(channel, channelField(screen), "a client channel page stays assembled on disconnect");
			assertNoHint(hint(channel));
		});
	}

	private static void openChannelPage(SettingsScreen screen) {
		widgets(screen).stream().filter(Button.class::isInstance).map(Button.class::cast)
			.filter(button -> hasKey(button, "settings.pingforit.category."
				+ Category.CHANNEL_NOTICES.id() + ".button"))
			.findFirst().orElseThrow().onPress();
	}

	private static EditBox channelField(SettingsScreen screen) {
		return widgets(screen).stream().filter(EditBox.class::isInstance)
			.map(EditBox.class::cast).findFirst().orElseThrow();
	}

	private static Component hint(EditBox field) throws ReflectiveOperationException {
		Field hint = EditBox.class.getDeclaredField("hint");
		hint.setAccessible(true);
		return Objects.requireNonNull((Component) hint.get(field));
	}

	private static void assertGlobalHint(Component hint) {
		TranslatableContents contents = assertTranslatable(hint);
		assertEquals(LanguageUtils.of("value", "global").getKey(), contents.getKey());
		assertEquals(0, contents.getArgs().length);
	}

	private static void assertTeamSyncedHint(Component hint) {
		TranslatableContents contents = assertTranslatable(hint);
		assertEquals(LanguageUtils.settings("channel").path("placeholder").getKey(), contents.getKey());
		assertEquals(1, contents.getArgs().length);
		Component team = assertInstanceOf(Component.class, contents.getArgs()[0]);
		assertEquals(LanguageUtils.of("value", TeamContext.VANILLA_TEAM.toString()).getKey(),
			assertTranslatable(team).getKey());
	}

	private static void assertNoHint(Component hint) {
		assertFalse(hint.getContents() instanceof TranslatableContents);
		assertTrue(hint.getString().isEmpty());
	}

	private static TranslatableContents assertTranslatable(Component component) {
		assertInstanceOf(TranslatableContents.class, component.getContents());
		return (TranslatableContents) component.getContents();
	}

	private static SettingsScreen.SettingsOptionsList optionsList(SettingsScreen screen) {
		return screen.children().stream().filter(SettingsScreen.SettingsOptionsList.class::isInstance)
			.map(SettingsScreen.SettingsOptionsList.class::cast).findFirst().orElseThrow();
	}

	private static @Unmodifiable List<AbstractWidget> widgets(SettingsScreen screen) {
		List<?> entries = optionsList(screen).children();
		return entries.stream().map(ContainerEventHandler.class::cast)
			.flatMap(entry -> entry.children().stream())
			.map(AbstractWidget.class::cast).toList();
	}

	private static boolean hasKey(AbstractWidget widget, String key) {
		return widget.getMessage().getContents() instanceof TranslatableContents contents
			&& contents.getKey().equals(key);
	}

	private static ServerSettingsModel serverSettings(SettingsScreen screen) throws ReflectiveOperationException {
		Field field = SettingsScreen.class.getDeclaredField("serverSettings");
		field.setAccessible(true);
		return Objects.requireNonNull((ServerSettingsModel) field.get(screen));
	}

	private static void withScreen(boolean hasTeam, boolean privileged, ScreenCheck check) throws Exception {
		var client = allocate(Minecraft.class);
		var player = allocate(FixtureLocalPlayer.class);
		player.team = hasTeam ? allocate(PlayerTeam.class) : null;
		player.privileged = privileged;
		client.player = player;
		client.setLastInputType(InputType.KEYBOARD_ARROW);

		var instanceField = Minecraft.class.getDeclaredField("instance");
		instanceField.setAccessible(true);
		@Nullable Object previousClient = instanceField.get(null);
		@Nullable Minecraft previousGame = CommonClient.Game;
		var screen = new HeadlessSettingsScreen(new ClientConfig());
		try {
			instanceField.set(null, client);
			CommonClient.Game = client;
			screen.initialize(client);
			check.run(screen, player);
		} finally {
			screen.removed();
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
		void run(HeadlessSettingsScreen screen, FixtureLocalPlayer player) throws Exception;
	}

	@NotNullByDefault
	private static final class HeadlessSettingsScreen extends SettingsScreen {
		private final List<ServerConfigRequestC2SPacket> requests = new ArrayList<>();

		private HeadlessSettingsScreen(ClientConfig config) {
			super(() -> config, null);
		}

		private void initialize(Minecraft client) {
			this.minecraft = client;
			this.font = new MeasuredFont();
			this.width = 640;
			this.height = 360;
			// Assemble every production widget without Screen.init's native narrator/window setup.
			super.init();
		}

		@Override
		boolean hasLiveServerConnection() {
			return true;
		}

		@Override
		void sendServerConfigRequest(ServerConfigRequestC2SPacket packet) {
			this.requests.add(packet);
		}
	}

	@NotNullByDefault
	private static final class FixtureLocalPlayer extends LocalPlayer {
		private @Nullable PlayerTeam team;
		private boolean privileged;

		private FixtureLocalPlayer() {
			super(null, null, null, null, null, false, false);
			throw new AssertionError("allocate without a client level");
		}

		@Override
		public @Nullable PlayerTeam getTeam() {
			return this.team;
		}

		@Override
		public boolean hasPermissions(int level) {
			return this.privileged;
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
		public String plainSubstrByWidth(String text, int maxWidth) {
			return this.plainSubstrByWidth(text, maxWidth, false);
		}

		@Override
		public String plainSubstrByWidth(String text, int maxWidth, boolean reverse) {
			// EditBox scrolling measures both directions without loading native glyphs.
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
	}
}
