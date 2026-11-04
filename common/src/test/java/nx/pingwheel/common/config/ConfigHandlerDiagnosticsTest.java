package nx.pingwheel.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.apache.logging.log4j.LogManager;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Unmodifiable;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ConfigHandlerDiagnosticsTest {

	private static final String VERSION = "1.0.0-pfi-beta1";

	@Test void serverLegacyLoadFailureIsErrorWithCompleteCauseAndResetsDefaults(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"rateLimit\":\"bad-rate-limit-sentinel\"}");
		try (var logs = new Capture()) {
			var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
			handler.load();

			assertEquals(new ServerConfig(), handler.getConfig());
			var persisted = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			assertEquals(VERSION, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
			assertEquals(new ServerConfig().getRateLimit(), persisted.get("rateLimit").getAsInt());
			var failures = logs.operation("site=config-io").stream()
				.filter(event -> event.getLevel() == Level.ERROR).toList();
			assertEquals(1, failures.size());
			String message = failures.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("bad-rate-limit-sentinel"), message);
			assertTrue(message.contains("NumberFormatException"), message);
		}
	}

	@Test void normalLoadFailureDoesNotDisclosePathOrThrowable(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("private-config-path.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"rateLimit\":\"private-value\"}");
		try (var logs = new Capture()) {
			Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, LoggingProfile.NORMAL);
			new ConfigHandler<>(ServerConfig.class, path, VERSION).load();
			var events = logs.operation("site=config-io").stream().filter(event -> event.getLevel() == Level.ERROR).toList();
			assertEquals(1, events.size());
			String message = events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("handling=load-failed"), message);
			assertFalse(message.contains(path.toString()), message);
			assertFalse(message.contains("private-value"), message);
			assertFalse(message.contains("NumberFormatException"), message);
		}
	}

	@Test void configuredRuntimePublishesLoadedAndReplacementProfilesWithoutUpdateCallback(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("profile.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"loggingProfile\":\"DEBUG\"}");
		var runtime = ConfigRuntime.of(DiagnosticSide.CLIENT, ClientConfig::getLoggingProfile,
			profile -> Diagnostics.setProfile(DiagnosticSide.CLIENT, profile));
		LoggingProfile previousClient = Diagnostics.profile(DiagnosticSide.CLIENT);
		LoggingProfile previousServer = Diagnostics.profile(DiagnosticSide.SERVER);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION, runtime);
		try {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			handler.load();
			assertEquals(LoggingProfile.DEBUG, Diagnostics.profile(DiagnosticSide.CLIENT));
			assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.SERVER));
			var loaded = handler.getConfig();
			loaded.setLoggingProfile(LoggingProfile.DEEP);
			handler.saveSafely();
			assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.CLIENT));
			handler.resetToDefaults();
			assertEquals(new ClientConfig().getLoggingProfile(), Diagnostics.profile(DiagnosticSide.CLIENT));
			Path invalid = directory.resolve("invalid-profile.json");
			Files.writeString(invalid, "{broken");
			var recoveryHandler = new ConfigHandler<>(ClientConfig.class, invalid, VERSION, runtime);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			recoveryHandler.load();
			assertEquals(new ClientConfig().getLoggingProfile(), Diagnostics.profile(DiagnosticSide.CLIENT));
		} finally {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousClient);
			Diagnostics.setProfile(DiagnosticSide.SERVER, previousServer);
		}
	}

	@Test void futureServerConfigWarnsAndRemainsProtected(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("future-server.json");
		byte[] original = ("{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"rateLimit\":99}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		try (var logs = new Capture()) {
			var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
			handler.load();

			assertEquals(new ServerConfig(), handler.getConfig());
			assertFalse(handler.saveSafely());
			handler.resetToDefaults();
			assertEquals(new ServerConfig(), handler.getConfig());
			assertArrayEquals(original, Files.readAllBytes(path));
			var warnings = logs.operation("site=config-future-version").stream()
				.filter(event -> event.getLevel() == Level.WARN).toList();
			assertEquals(1, warnings.size());
			String message = warnings.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("config-future-version"), message);
			assertTrue(message.contains("defaults in memory"), message);
			assertTrue(message.contains("original preserved"), message);
			assertEquals(1, logs.operation("site=config-save").stream()
				.filter(event -> event.getLevel() == Level.DEBUG).count());
		}
	}

	@Test void loadFailureKeepsLongCauseWhileRecoveryCommentStaysSafe(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("validation.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"reject\":true}");
		try (var logs = new Capture()) {
			var handler = new ConfigHandler<>(ThrowingConfig.class, path, VERSION);
			handler.load();
			assertFalse(handler.getConfig().reject);
			String[] lines = Files.readString(path).split("\\R", 4);
			assertEquals("// Error reason: parser failure", lines[1]);
			assertTrue(lines[1].length() < 240);
			assertFalse(lines[1].contains("root-cause-sentinel"));
			assertFullReport(logs, "config-io", "load");
		}
	}

	@Test void failedBackupKeepsFullBoundedCauseAndPreservationLock(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		byte[] original = "{broken".getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		try (var logs = new Capture()) {
			var handler = new ConfigHandler<>(ClientConfig.class, path, (source, backup, bytes) -> {
				throw failure("backup");
			}, VERSION);
			handler.load();
			assertArrayEquals(original, Files.readAllBytes(path));
			assertFalse(handler.saveSafely());
			handler.resetToDefaults();
			assertArrayEquals(original, Files.readAllBytes(path));
			assertEquals(new ClientConfig(), handler.getConfig());
			assertFullReport(logs, "config-io", "backup");
			assertEquals(1, logs.events().stream().filter(event -> event.getLevel() == Level.WARN
				&& event.getMessage().getFormattedMessage().contains("backup-failed")).count());
		}
	}

	@Test void saveAndResetFailuresRetainCauseWithoutTruncatingToRecoveryCommentLimit(@TempDir Path directory) throws IOException {
		Path save = directory.resolve("save.json");
		Path reset = directory.resolve("reset.json");
		byte[] original = "{broken".getBytes(StandardCharsets.UTF_8);
		Files.write(reset, original);
		try (var logs = new Capture()) {
			var saving = new ConfigHandler<>(ClientConfig.class, save, (source, backup, bytes) -> fail("no backup on save"),
				VERSION, (target, serialized) -> { throw failure("save"); });
			assertFalse(saving.saveSafely());
			assertFalse(Files.exists(save));
			var resetting = new ConfigHandler<>(ClientConfig.class, reset,
				(source, backup, bytes) -> Files.write(backup, bytes), VERSION,
				(target, serialized) -> { throw failure("reset"); });
			resetting.load();
			assertArrayEquals(original, Files.readAllBytes(reset));
			assertFalse(resetting.saveSafely());
			assertFullReport(logs, "config-io", "save");
			assertFullReport(logs, "config-io", "reset");
		}
	}

	private static IOException failure(String stage) {
		return new IOException(stage + "-" + "x".repeat(400) + "-message-tail",
			new IllegalStateException(stage + "-root-cause-sentinel"));
	}

	private static void assertFullReport(Capture logs, String operation, String stage) {
		Level expected = stage.equals("load") ? Level.ERROR : Level.WARN;
		var failures = logs.operation(operation).stream().filter(event -> event.getLevel() == expected
			&& event.getMessage().getFormattedMessage().contains(stage + "-failed")).toList();
		assertEquals(1, failures.size());
		var event = failures.getFirst();
		assertEquals(expected, event.getLevel());
		assertNull(event.getThrown());
		String message = event.getMessage().getFormattedMessage();
		assertTrue(message.length() > 240, message);
		assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS, "one bounded diagnostic envelope");
		assertTrue(message.contains("-message-tail"), message);
		assertTrue(message.contains(stage + "-root-cause-sentinel"), message);
	}

	@NotNullByDefault
	private static final class Capture implements AutoCloseable {
		private static final String NAME = "pingforit.bootstrap";
		private final Logger logger = (Logger) LogManager.getLogger(NAME);
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final LoggerConfig previous = configuration.getLoggers().get(NAME);
		private final List<LogEvent> captured = new ArrayList<>();
		private final AbstractAppender appender = new AbstractAppender("config-handler-test", null, null, true, Property.EMPTY_ARRAY) {
			@Override public void append(LogEvent event) { captured.add(event.toImmutable()); }
		};
		private final LoggingProfile previousProfile = Diagnostics.profile(DiagnosticSide.BOOTSTRAP);
		Capture() {
			appender.start();
			LoggerConfig replacement = new LoggerConfig(NAME, Level.ALL, false);
			replacement.addAppender(appender, Level.ALL, null);
			configuration.removeLogger(NAME);
			configuration.addLogger(NAME, replacement);
			context.updateLoggers();
			Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, LoggingProfile.DEEP);
		}
		@Unmodifiable List<LogEvent> operation(String text) { return captured.stream()
			.filter(event -> event.getMessage().getFormattedMessage().contains(text)).toList(); }
		@Unmodifiable List<LogEvent> events() { return List.copyOf(captured); }
		@Override public void close() {
			Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, previousProfile);
			configuration.removeLogger(NAME);
			if (previous != null) configuration.addLogger(NAME, previous);
			context.updateLoggers();
			appender.stop();
		}
	}

	@NotNullByDefault
	public static final class ThrowingConfig implements IConfig {
		public boolean reject;
		public ThrowingConfig() {}
		@Override public void validate() {
			if (reject) throw new IllegalStateException("load-" + "x".repeat(400) + "-message-tail",
				new IllegalArgumentException("load-root-cause-sentinel"));
		}
		@Override public void onUpdate() {}
		@Override public boolean recoverInvalidOnLoad() { return true; }
	}
}
