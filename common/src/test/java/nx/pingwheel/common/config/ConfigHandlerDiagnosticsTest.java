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
import org.jetbrains.annotations.Nullable;
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
			var failures = logs.operation("site=config-load handling=defaults-written").stream()
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
			var events = logs.operation("site=config-load handling=defaults-written").stream()
				.filter(event -> event.getLevel() == Level.ERROR).toList();
			assertEquals(1, events.size());
			String message = events.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("handling=defaults-written"), message);
			assertFalse(message.contains(path.toString()), message);
			assertFalse(message.contains("private-value"), message);
			assertFalse(message.contains("NumberFormatException"), message);
		}
	}

	@Test void serverRecoveryReportsDefaultsInMemoryWithoutTouchingTheInvalidOriginal(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"" + VERSION
			+ "\",\"rateLimit\":\"bad-rate-limit-sentinel\"}").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		try (var logs = new Capture()) {
			var handler = new ConfigHandler<>(ServerConfig.class, path, (source, backup, bytes) -> {
				throw new AssertionError("no backup on server recovery");
			}, VERSION, (target, serialized) -> { throw failure("save"); });
			handler.load();

			assertEquals(new ServerConfig(), handler.getConfig());
			assertArrayEquals(original, Files.readAllBytes(path));

			var recovery = logs.operation("site=config-load handling=defaults-in-memory").stream()
				.filter(event -> event.getLevel() == Level.ERROR).toList();
			assertEquals(1, recovery.size());
			assertTrue(recovery.getFirst().getMessage().getFormattedMessage()
				.contains("bad-rate-limit-sentinel"));
			assertFullReport(logs, "config-io", "save-failed", "save-root-cause-sentinel");
		}
	}

	@Test void recoveryHashNoopPreservesInvalidBytesAndOnUpdateContract(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("hash-noop.json");
		byte[] broken = "{broken".getBytes(StandardCharsets.UTF_8);
		CountingConfig.ON_UPDATE.set(0);
		java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
		var handler = new ConfigHandler<>(CountingConfig.class, path,
			(source, backup, bytes) -> { throw new AssertionError("no backup on server recovery"); },
			VERSION,
			(target, serialized) -> {
				Files.writeString(target, serialized + System.lineSeparator(), StandardCharsets.UTF_8);
				writes.incrementAndGet();
			});
		handler.save();
		assertEquals(1, writes.get());
		assertEquals(1, CountingConfig.ON_UPDATE.get());
		Files.write(path, broken);

		try (var logs = new Capture()) {
			Diagnostics.setProfile(DiagnosticSide.BOOTSTRAP, LoggingProfile.NORMAL);
			handler.load();
			assertEquals(1, writes.get(), "the non-force save must keep its hash-equal no-write behavior");
			assertArrayEquals(broken, Files.readAllBytes(path));
			assertEquals(1, CountingConfig.ON_UPDATE.get(), "a no-op save must not invoke onUpdate");
			var recovery = logs.operation("site=config-load handling=defaults-in-memory").stream()
				.filter(event -> event.getLevel() == Level.ERROR).toList();
			assertEquals(1, recovery.size());
			assertFalse(recovery.getFirst().getMessage().getFormattedMessage().contains(path.toString()));
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

	@Test void saveEntryPublishesInMemoryProfileBeforeHashAndWriteOutcomes(@TempDir Path directory) throws IOException {
		var runtime = ConfigRuntime.of(DiagnosticSide.CLIENT, ClientConfig::getLoggingProfile,
			profile -> Diagnostics.setProfile(DiagnosticSide.CLIENT, profile));
		LoggingProfile previousClient = Diagnostics.profile(DiagnosticSide.CLIENT);
		LoggingProfile previousServer = Diagnostics.profile(DiagnosticSide.SERVER);
		try {
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			Path noopPath = directory.resolve("noop-profile.json");
			var noop = new ConfigHandler<>(ClientConfig.class, noopPath, VERSION, runtime);
			noop.getConfig().setLoggingProfile(LoggingProfile.DEEP);
			assertTrue(noop.saveSafely());
			byte[] written = Files.readAllBytes(noopPath);
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.NORMAL);
			assertTrue(noop.saveSafely());
			assertArrayEquals(written, Files.readAllBytes(noopPath));
			assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.CLIENT));

			Path failedPath = directory.resolve("failed-profile.json");
			var failing = new ConfigHandler<>(ClientConfig.class, failedPath,
				(source, backup, bytes) -> { throw new AssertionError("no backup on save"); },
				VERSION,
				(target, serialized) -> { throw new IOException("deterministic save failure"); },
				runtime);
			failing.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
			assertFalse(failing.saveSafely());
			assertFalse(Files.exists(failedPath));
			assertEquals(LoggingProfile.DEBUG, Diagnostics.profile(DiagnosticSide.CLIENT));
			assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.SERVER));
		} finally {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, previousClient);
			Diagnostics.setProfile(DiagnosticSide.SERVER, previousServer);
		}
	}

	@Test void futureProtectedConfigPublishesDefaultProfileThroughSaveAndReset(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("future-profile.json");
		byte[] original = ("{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"loggingProfile\":\"DEBUG\"}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var runtime = ConfigRuntime.of(DiagnosticSide.CLIENT, ClientConfig::getLoggingProfile,
			profile -> Diagnostics.setProfile(DiagnosticSide.CLIENT, profile));
		LoggingProfile previousClient = Diagnostics.profile(DiagnosticSide.CLIENT);
		LoggingProfile previousServer = Diagnostics.profile(DiagnosticSide.SERVER);
		try {
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			Diagnostics.setProfile(DiagnosticSide.SERVER, LoggingProfile.DEEP);
			var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION, runtime);
			handler.load();
			assertEquals(new ClientConfig().getLoggingProfile(), Diagnostics.profile(DiagnosticSide.CLIENT));
			assertArrayEquals(original, Files.readAllBytes(path));

			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEEP);
			assertFalse(handler.saveSafely());
			assertEquals(new ClientConfig().getLoggingProfile(), Diagnostics.profile(DiagnosticSide.CLIENT));
			Diagnostics.setProfile(DiagnosticSide.CLIENT, LoggingProfile.DEBUG);
			handler.resetToDefaults();
			assertEquals(new ClientConfig().getLoggingProfile(), Diagnostics.profile(DiagnosticSide.CLIENT));
			assertArrayEquals(original, Files.readAllBytes(path));
			assertEquals(LoggingProfile.DEEP, Diagnostics.profile(DiagnosticSide.SERVER));
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
			assertEquals(2, logs.operation("site=config-save").stream()
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
			assertFullReport(logs, "config-load", "defaults-written", "load-root-cause-sentinel");
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
			assertFullReport(logs, "config-io", "backup-failed-original-protected", "backup-root-cause-sentinel");
			assertEquals(1, logs.events().stream().filter(event -> event.getLevel() == Level.WARN
				&& event.getMessage().getFormattedMessage().contains("backup-failed")).count());
			assertEquals(1, logs.events().stream().filter(event -> event.getLevel() == Level.ERROR
				&& event.getMessage().getFormattedMessage().contains("site=config-load handling=defaults-in-memory"))
				.count());
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
			assertFullReport(logs, "config-io", "save-failed", "save-root-cause-sentinel");
			assertFullReport(logs, "config-io", "reset-write-failed-backup-retained", "reset-root-cause-sentinel");
			assertEquals(1, logs.events().stream().filter(event -> event.getLevel() == Level.ERROR
				&& event.getMessage().getFormattedMessage().contains("site=config-load handling=defaults-in-memory"))
				.count());
		}
	}

	private static IOException failure(String stage) {
		return new IOException(stage + "-" + "x".repeat(400) + "-message-tail",
			new IllegalStateException(stage + "-root-cause-sentinel"));
	}

	private static void assertFullReport(Capture logs, String site, String handling, String rootSentinel) {
		Level expected = site.equals("config-load") ? Level.ERROR : Level.WARN;
		var failures = logs.events().stream().filter(event -> event.getLevel() == expected
			&& event.getMessage().getFormattedMessage().contains("site=" + site)
			&& event.getMessage().getFormattedMessage().contains("handling=" + handling)).toList();
		assertEquals(1, failures.size());
		var event = failures.getFirst();
		assertEquals(expected, event.getLevel());
		assertNull(event.getThrown());
		String message = event.getMessage().getFormattedMessage();
		assertTrue(message.length() > 240, message);
		assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS, "one bounded diagnostic envelope");
		assertTrue(message.contains("-message-tail"), message);
		assertTrue(message.contains(rootSentinel), message);
	}

	@NotNullByDefault
	static final class Capture implements AutoCloseable {
		private static final String NAME = "pingforit.bootstrap";
		private final Logger logger = (Logger) LogManager.getLogger(NAME);
		private final LoggerContext context = logger.getContext();
		private final Configuration configuration = context.getConfiguration();
		private final @Nullable LoggerConfig previous = configuration.getLoggers().get(NAME);
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
		void level(Level level) {
			configuration.getLoggerConfig(NAME).setLevel(level);
			context.updateLoggers();
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
	public static final class CountingConfig implements IConfig {
		private static final java.util.concurrent.atomic.AtomicInteger ON_UPDATE =
			new java.util.concurrent.atomic.AtomicInteger();
		public CountingConfig() {}
		@Override public void validate() {}
		@Override public void onUpdate() { ON_UPDATE.incrementAndGet(); }
		@Override public int hashCode() { return 1; }
		@Override public boolean equals(@Nullable Object other) { return other instanceof CountingConfig; }
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
