package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import nx.pingwheel.common.CommonClient;
import nx.pingwheel.common.command.ClientCommandBuilder;
import nx.pingwheel.common.topology.DoubleChestFixture;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class ClientChannelCommandTest {
	private static final String VERSION = "1.0.0-pfi-beta1";
	private static final String OLDER_VERSION = "0.4.0-pfi-beta1";
	private static final String FUTURE_VERSION = "99.0.0-pfi-beta1";
	private static final String CONNECTED_ADDRESS = "play.example.test:25565";
	private static final String GET_DEFAULT_KEY = "command.pingforit.channel.get.response.default";
	private static final String GET_RESPONSE_KEY = "command.pingforit.channel.get.response";
	private static final String SET_DEFAULT_KEY = "command.pingforit.channel.set.response.default";
	private static final String SET_RESPONSE_KEY = "command.pingforit.channel.set.response";
	private static final String SET_REJECT_KEY = "command.pingforit.channel.set.reject";
	private static final String SET_FAILURE_KEY = "command.pingforit.channel.set.failure";
	private static final @Unmodifiable List<String> LOCALES = List.of(
		"de_de", "en_us", "es_ar", "fr_fr", "pl_pl", "tr_tr", "zh_cn", "zh_tw");
	private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:\\d+\\$)?[sdf]");

	private @Nullable Minecraft previousGame;

	@BeforeEach
	void clearGame() {
		previousGame = CommonClient.Game;
		CommonClient.Game = null;
	}

	@AfterEach
	void restoreGame() {
		CommonClient.Game = previousGame;
	}

	@Test
	void getReportsTheDefaultChannelBeforeOneIsSet(@TempDir Path directory) throws Exception {
		ConfigHandler<ClientConfig> handler = clientHandler(directory.resolve("client.json"));
		handler.load();

		CommandResult result = execute(handler, "pingforit channel");

		assertEquals(1, result.result());
		assertTrue(result.response().success());
		assertEquals(GET_DEFAULT_KEY, responseKey(result.response().message()));
		assertEquals("", handler.getConfig().getChannel());
	}

	@Test
	void getTreatsANullStoredChannelAsTheDefault(@TempDir Path directory) throws Exception {
		ConfigHandler<ClientConfig> handler = clientHandler(directory.resolve("client.json"));
		handler.load();
		handler.getConfig().setChannel(null);

		CommandResult result = execute(handler, "pingforit channel");

		assertEquals(1, result.result());
		assertTrue(result.response().success());
		assertEquals(GET_DEFAULT_KEY, responseKey(result.response().message()));
	}

	@Test
	void setPersistsTheChannelAndGetReportsIt(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		ConfigHandler<ClientConfig> handler = clientHandler(path);
		handler.load();

		CommandResult set = execute(handler, "pingforit channel \"trade\"");

		assertEquals(1, set.result());
		assertTrue(set.response().success());
		assertEquals(SET_RESPONSE_KEY, responseKey(set.response().message()));
		assertEquals("trade", responseArgument(set.response(), 0).getString());
		assertEquals("trade", handler.getConfig().getChannel());
		assertEquals("trade", readRoot(path).get("channel").getAsString());

		ConfigHandler<ClientConfig> reloaded = clientHandler(path);
		reloaded.load();
		CommandResult get = execute(reloaded, "pingforit channel");
		assertEquals(1, get.result());
		assertTrue(get.response().success());
		assertEquals(GET_RESPONSE_KEY, responseKey(get.response().message()));
		assertEquals("trade", responseArgument(get.response(), 0).getString());
		assertEquals("trade", reloaded.getConfig().getChannel());
	}

	@Test
	void emptyChannelSwitchesBackToTheDefault(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		ConfigHandler<ClientConfig> handler = clientHandler(path);
		handler.load();
		execute(handler, "pingforit channel \"trade\"");

		CommandResult result = execute(handler, "pingforit channel \"\"");

		assertEquals(1, result.result());
		assertTrue(result.response().success());
		assertEquals(SET_DEFAULT_KEY, responseKey(result.response().message()));
		assertEquals("", handler.getConfig().getChannel());
		assertEquals("", readRoot(path).get("channel").getAsString());
	}

	@Test
	void overlongChannelIsRejectedWithoutChangingMemoryOrDisk(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		ConfigHandler<ClientConfig> handler = clientHandler(path);
		handler.load();
		byte @Unmodifiable [] original = Files.readAllBytes(path);
		String overlong = "x".repeat(ClientConfig.MAX_CHANNEL_LENGTH + 1);

		CommandResult result = execute(handler, "pingforit channel \"" + overlong + "\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_REJECT_KEY, responseKey(result.response().message()));
		assertEquals("", handler.getConfig().getChannel());
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void settingTheSameChannelSucceedsWithoutRewritingTheFile(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, config(VERSION, "same"), StandardCharsets.UTF_8);
		AtomicInteger writes = new AtomicInteger();
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> failBackup(),
			VERSION,
			(destination, serialized) -> {
				writes.incrementAndGet();
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		byte @Unmodifiable [] original = Files.readAllBytes(path);

		CommandResult result = execute(handler, "pingforit channel \"same\"");

		assertEquals(1, result.result());
		assertTrue(result.response().success());
		assertEquals(SET_RESPONSE_KEY, responseKey(result.response().message()));
		assertEquals(0, writes.get(), "an unchanged channel must not be rewritten");
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void futureVersionProtectionRejectsTheEditWithoutChangingMemoryOrDisk(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, config(FUTURE_VERSION, "disk"), StandardCharsets.UTF_8);
		byte @Unmodifiable [] original = Files.readAllBytes(path);
		ConfigHandler<ClientConfig> handler = clientHandler(path);
		handler.load();
		handler.getConfig().setChannel("baseline");

		CommandResult result = execute(handler, "pingforit channel \"new\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_FAILURE_KEY, responseKey(result.response().message()));
		assertEquals("baseline", handler.getConfig().getChannel(),
			"protected refusal must not run the edit");
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void invalidFileProtectionRejectsTheEditWithoutChangingMemoryOrDisk(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		byte @Unmodifiable [] original = ("{\"pingforit-version\":\"" + VERSION + "\",\"channel\":}")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> {
				throw new IOException("injected backup failure");
			},
			VERSION);
		handler.load();
		handler.getConfig().setChannel("baseline");

		CommandResult result = execute(handler, "pingforit channel \"new\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_FAILURE_KEY, responseKey(result.response().message()));
		assertEquals("baseline", handler.getConfig().getChannel(),
			"protected refusal must not run the edit");
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void ordinaryWriteFailureKeepsTheAppliedChannelAndReportsItWasNotSaved(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, config(VERSION, "old"), StandardCharsets.UTF_8);
		byte @Unmodifiable [] original = Files.readAllBytes(path);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> failBackup(),
			VERSION,
			(destination, serialized) -> {
				throw new IOException("injected write failure");
			});
		handler.load();

		CommandResult result = execute(handler, "pingforit channel \"new\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_FAILURE_KEY, responseKey(result.response().message()));
		assertEquals("new", handler.getConfig().getChannel(),
			"onUpdate may already have notified the server, so the applied value must not be rolled back");
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void connectedServerEditPersistsTheRememberedAddressAndNotifiesTheAppliedChannel(@TempDir Path directory)
		throws Exception {
		Path path = directory.resolve("client.json");
		ConfigHandler<ClientConfig> handler = clientHandler(path);
		handler.load();
		RecordingClientConfig recording = useRecordingConfig(handler);
		CommonClient.Game = connectedGame(CONNECTED_ADDRESS);

		CommandResult result = execute(handler, "pingforit channel \"trade\"");

		assertEquals(1, result.result());
		assertTrue(result.response().success());
		assertEquals(SET_RESPONSE_KEY, responseKey(result.response().message()));
		assertEquals("trade", handler.getConfig().getChannel());
		assertEquals("trade", recording.updateNotifications.get(0),
			"the save notification must see the applied channel");
		JsonObject saved = readRoot(path);
		assertEquals("", saved.get("channel").getAsString(),
			"a connected-server edit must not overwrite the disconnected fallback");
		assertEquals("trade", saved.getAsJsonObject("serverChannels")
			.get(CONNECTED_ADDRESS).getAsString());

		ConfigHandler<ClientConfig> reloaded = clientHandler(path);
		reloaded.load();
		assertEquals("trade", reloaded.getConfig().getChannel(),
			"the remembered per-server channel must survive a reload");
	}

	@Test
	void connectedServerWriteFailureKeepsTheAppliedRememberedChannel(@TempDir Path directory) throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, config(VERSION, ""), StandardCharsets.UTF_8);
		byte @Unmodifiable [] original = Files.readAllBytes(path);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> failBackup(),
			VERSION,
			(destination, serialized) -> {
				throw new IOException("injected write failure");
			});
		handler.load();
		RecordingClientConfig recording = useRecordingConfig(handler);
		CommonClient.Game = connectedGame(CONNECTED_ADDRESS);

		CommandResult result = execute(handler, "pingforit channel \"new\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_FAILURE_KEY, responseKey(result.response().message()));
		assertEquals("new", handler.getConfig().getChannel(),
			"an ordinary write failure must keep the applied per-server value");
		assertEquals("new", recording.updateNotifications.get(0),
			"the update notification runs before the failed write");
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void pendingMigrationReloadsASameVersionReplacementWithoutRestoringTheOldChannel(@TempDir Path directory)
		throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, config(OLDER_VERSION, "old"), StandardCharsets.UTF_8);
		AtomicBoolean denyWrites = new AtomicBoolean(true);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> failBackup(),
			VERSION,
			(destination, serialized) -> {
				if (denyWrites.get()) throw new IOException("migration write denied");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		assertEquals("old", handler.getConfig().getChannel());
		denyWrites.set(false);

		byte @Unmodifiable [] replacement = config(VERSION, "external").getBytes(StandardCharsets.UTF_8);
		Files.write(path, replacement);

		CommandResult result = execute(handler, "pingforit channel \"new\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_FAILURE_KEY, responseKey(result.response().message()));
		assertEquals("external", handler.getConfig().getChannel(),
			"the replacement object must not receive the old in-memory channel");
		assertArrayEquals(replacement, Files.readAllBytes(path));
	}

	@Test
	void pendingMigrationReloadsAFutureReplacementWithoutRestoringTheOldChannel(@TempDir Path directory)
		throws Exception {
		Path path = directory.resolve("client.json");
		Files.writeString(path, config(OLDER_VERSION, "old"), StandardCharsets.UTF_8);
		AtomicBoolean denyWrites = new AtomicBoolean(true);
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(
			ClientConfig.class,
			path,
			(source, backup, bytes) -> failBackup(),
			VERSION,
			(destination, serialized) -> {
				if (denyWrites.get()) throw new IOException("migration write denied");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		assertEquals("old", handler.getConfig().getChannel());
		denyWrites.set(false);

		byte @Unmodifiable [] replacement = config(FUTURE_VERSION, "future").getBytes(StandardCharsets.UTF_8);
		Files.write(path, replacement);

		CommandResult result = execute(handler, "pingforit channel \"new\"");

		assertEquals(0, result.result());
		assertFalse(result.response().success());
		assertEquals(SET_FAILURE_KEY, responseKey(result.response().message()));
		assertEquals("", handler.getConfig().getChannel(),
			"the protected replacement defaults must not receive the old in-memory channel");
		assertArrayEquals(replacement, Files.readAllBytes(path));
	}

	@Test
	void bundledLocalesContainTheChannelFailureMessage() throws IOException {
		for (String locale : LOCALES) {
			JsonObject language = readLanguage(locale);
			assertTrue(language.has(SET_FAILURE_KEY), locale + " must define " + SET_FAILURE_KEY);
			String message = language.get(SET_FAILURE_KEY).getAsString();
			assertFalse(message.isBlank(), locale + " failure message must not be blank");
			assertFalse(FORMAT_SPECIFIER.matcher(message).find(),
				locale + " failure message must not require arguments");
		}
	}

	private static ConfigHandler<ClientConfig> clientHandler(Path path) {
		return new ConfigHandler<>(ClientConfig.class, path, VERSION);
	}

	/**
	 * Installs a recording model while keeping the handler's production client
	 * config type, so the normal client serialization path still applies.
	 */
	private static RecordingClientConfig useRecordingConfig(ConfigHandler<ClientConfig> handler)
		throws ReflectiveOperationException {
		RecordingClientConfig config = new RecordingClientConfig();
		Field field = ConfigHandler.class.getDeclaredField("config");
		field.setAccessible(true);
		field.set(handler, config);
		return config;
	}

	private static Minecraft connectedGame(String address) {
		ConnectedGame game = DoubleChestFixture.allocate(ConnectedGame.class);
		ServerData server = DoubleChestFixture.allocate(ServerData.class);
		server.ip = address;
		game.currentServer = server;
		return game;
	}

	private static CommandResult execute(ConfigHandler<ClientConfig> handler, String command) throws Exception {
		List<CommandResponse> responses = new ArrayList<>();
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		dispatcher.register(ClientCommandBuilder.<Object>build(
			(context, success, message) -> responses.add(new CommandResponse(success, message)),
			handler));
		int result = dispatcher.execute(command, new Object());
		assertEquals(1, responses.size(), () -> "expected one response for: " + command);
		return new CommandResult(result, responses.get(0));
	}

	private static String responseKey(Component response) {
		TranslatableContents contents = assertInstanceOf(TranslatableContents.class, response.getContents());
		return contents.getKey();
	}

	private static Component responseArgument(CommandResponse response, int index) {
		TranslatableContents contents = assertInstanceOf(
			TranslatableContents.class, response.message().getContents());
		return assertInstanceOf(Component.class, contents.getArgs()[index]);
	}

	private static JsonObject readRoot(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static JsonObject readLanguage(String locale) throws IOException {
		String resource = "assets/pingforit/lang/" + locale + ".json";
		try (InputStream input = Objects.requireNonNull(
			ClientChannelCommandTest.class.getClassLoader().getResourceAsStream(resource), resource)) {
			return JsonParser.parseString(
				new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String config(String version, String channel) {
		return "{\"pingforit-version\":\"" + version + "\",\"channel\":\"" + channel + "\"}\n";
	}

	private static void failBackup() {
		throw new AssertionError("a valid configuration must not enter invalid-file recovery");
	}

	@NotNullByDefault
	private static final class ConnectedGame extends Minecraft {
		private @Nullable ServerData currentServer;

		@SuppressWarnings("unused")
		private ConnectedGame() {
			super(null);
		}

		@Override
		public @Nullable ServerData getCurrentServer() {
			return currentServer;
		}
	}

	/** Records the ordinary update notification without reaching the network transport. */
	@NotNullByDefault
	private static final class RecordingClientConfig extends ClientConfig {
		private final List<@Nullable String> updateNotifications = new ArrayList<>();

		@Override
		public void onUpdate() {
			updateNotifications.add(getChannel());
		}
	}

	@NotNullByDefault
	private record CommandResponse(boolean success, MutableComponent message) {}

	@NotNullByDefault
	private record CommandResult(int result, CommandResponse response) {}
}
