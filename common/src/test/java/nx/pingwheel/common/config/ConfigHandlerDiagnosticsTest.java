package nx.pingwheel.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import nx.pingwheel.common.diagnostics.Diagnostics;
import nx.pingwheel.common.testing.DiagnosticLogCapture;
import nx.pingwheel.common.util.SafeExceptionReport;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ConfigHandlerDiagnosticsTest {

	private static final String VERSION = "1.0.0-pfi-beta1";

	@Test void serverLegacyLoadFailureIsErrorWithCompleteCauseAndResetsDefaults(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"rateLimit\":\"bad-rate-limit-sentinel\"}");
		try (var logs = new DiagnosticLogCapture()) {
			setDiagnosticThreshold(Level.ERROR);
			var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
			handler.load();

			assertEquals(new ServerConfig(), handler.getConfig());
			var persisted = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			assertEquals(VERSION, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
			assertEquals(new ServerConfig().getRateLimit(), persisted.get("rateLimit").getAsInt());
			var failures = logs.operation("config/load").stream()
				.filter(event -> event.getLevel() == Level.ERROR).toList();
			assertEquals(1, failures.size());
			String message = failures.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("bad-rate-limit-sentinel"), message);
			assertTrue(message.contains("NumberFormatException"), message);
		}
	}

	@Test void futureServerConfigWarnsAndRemainsProtected(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("future-server.json");
		byte[] original = ("{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"rateLimit\":99}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		try (var logs = new DiagnosticLogCapture()) {
			setDiagnosticThreshold(Level.WARN);
			var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
			handler.load();

			assertEquals(new ServerConfig(), handler.getConfig());
			setDiagnosticThreshold(Level.DEBUG);
			assertFalse(handler.saveSafely());
			handler.resetToDefaults();
			assertEquals(new ServerConfig(), handler.getConfig());
			assertArrayEquals(original, Files.readAllBytes(path));
			var warnings = logs.operation("config/load").stream()
				.filter(event -> event.getLevel() == Level.WARN).toList();
			assertEquals(1, warnings.size());
			String message = warnings.getFirst().getMessage().getFormattedMessage();
			assertTrue(message.contains("future-version"), message);
			assertTrue(message.contains("defaults in memory"), message);
			assertTrue(message.contains("original file preserved"), message);
			assertEquals(1, logs.operation("config/save").stream()
				.filter(event -> event.getLevel() == Level.DEBUG).count());
		}
	}

	@Test void loadFailureKeepsLongCauseWhileRecoveryCommentStaysSafe(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("validation.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"reject\":true}");
		try (var logs = new DiagnosticLogCapture()) {
			var handler = new ConfigHandler<>(ThrowingConfig.class, path, VERSION);
			handler.load();
			assertFalse(handler.getConfig().reject);
			String[] lines = Files.readString(path).split("\\R", 4);
			assertEquals("// Error reason: parser failure", lines[1]);
			assertTrue(lines[1].length() < 240);
			assertFalse(lines[1].contains("root-cause-sentinel"));
			assertFullReport(logs, "config/load", "load");
		}
	}

	@Test void failedBackupKeepsFullBoundedCauseAndPreservationLock(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		byte[] original = "{broken".getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		try (var logs = new DiagnosticLogCapture()) {
			var handler = new ConfigHandler<>(ClientConfig.class, path, (source, backup, bytes) -> {
				throw failure("backup");
			}, VERSION);
			handler.load();
			assertArrayEquals(original, Files.readAllBytes(path));
			assertFalse(handler.saveSafely());
			handler.resetToDefaults();
			assertArrayEquals(original, Files.readAllBytes(path));
			assertEquals(new ClientConfig(), handler.getConfig());
			assertFullReport(logs, "config/backup", "backup");
			assertEquals(1, logs.events().stream().filter(event -> event.getLevel() == Level.WARN
				&& event.getMessage().getFormattedMessage().contains("config/backup")).count());
		}
	}

	@Test void saveAndResetFailuresRetainCauseWithoutTruncatingToRecoveryCommentLimit(@TempDir Path directory) throws IOException {
		Path save = directory.resolve("save.json");
		Path reset = directory.resolve("reset.json");
		byte[] original = "{broken".getBytes(StandardCharsets.UTF_8);
		Files.write(reset, original);
		try (var logs = new DiagnosticLogCapture()) {
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
			assertFullReport(logs, "config/save", "save");
			assertFullReport(logs, "config/reset", "reset");
		}
	}

	private static IOException failure(String stage) {
		return new IOException(stage + "-" + "x".repeat(400) + "-message-tail",
			new IllegalStateException(stage + "-root-cause-sentinel"));
	}

	private static void setDiagnosticThreshold(Level level) {
		Logger logger = (Logger) Diagnostics.logger();
		LoggerContext context = logger.getContext();
		context.getConfiguration().getLoggerConfig(logger.getName()).setLevel(level);
		context.updateLoggers();
	}

	private static void assertFullReport(DiagnosticLogCapture logs, String operation, String stage) {
		var failures = logs.operation(operation).stream().filter(event -> event.getLevel() == Level.WARN).toList();
		assertEquals(1, failures.size());
		var event = failures.getFirst();
		assertEquals(Level.WARN, event.getLevel());
		assertNull(event.getThrown());
		String message = event.getMessage().getFormattedMessage();
		assertTrue(message.length() > 240, message);
		assertTrue(message.length() <= SafeExceptionReport.MAX_CHARACTERS, "one bounded diagnostic envelope");
		assertTrue(message.contains("-message-tail"), message);
		assertTrue(message.contains(stage + "-root-cause-sentinel"), message);
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
