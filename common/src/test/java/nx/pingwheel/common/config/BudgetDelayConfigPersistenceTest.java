package nx.pingwheel.common.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BudgetDelayConfigPersistenceTest {
	private static final String VERSION = "0.5.0-pfi-beta1";

	@ParameterizedTest
	@ValueSource(strings = {"", ",\"budgetDelayNotificationsEnabled\":null", ",\"budgetDelayNotificationsEnabled\":true"})
	void missingNullOrTrueUsesEnabledDefaultWithoutSameVersionRewrite(String member, @TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"" + VERSION + "\",\"rateLimit\":17,"
			+ "\"syncDuration\":23,\"inventory\":{\"pendingMemoryMiB\":{\"unlimited\":false,\"value\":65}},\"unknown\":{\"retained\":true}"
			+ member + "}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();

		assertTrue(handler.getConfig().isBudgetDelayNotificationsEnabled());
		assertEquals(17, handler.getConfig().getRateLimit());
		assertEquals(23, handler.getConfig().getSyncDuration());
		assertEquals(65, handler.getConfig().getInventory().getPendingMemoryMiB().getValue());
		assertArrayEquals(original, Files.readAllBytes(path));
		assertTrue(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	void explicitFalseLoadsWithoutRewriteAndRoundTripsAnOrdinarySave(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"" + VERSION
			+ "\",\"budgetDelayNotificationsEnabled\":false,\"rateLimit\":17,\"pingDistance\":256}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		assertFalse(handler.getConfig().isBudgetDelayNotificationsEnabled());
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.getConfig().setRateLimit(19);
		assertTrue(handler.saveSafely());
		var persisted = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertFalse(persisted.get("budgetDelayNotificationsEnabled").getAsBoolean());
		assertEquals(VERSION, persisted.get("pingforit-version").getAsString());
		assertEquals(256, persisted.get("pingDistance").getAsInt());
		var reloaded = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		reloaded.load();
		assertFalse(reloaded.getConfig().isBudgetDelayNotificationsEnabled());
		assertEquals(19, reloaded.getConfig().getRateLimit());
		assertEquals(256, reloaded.getConfig().getPingDistance());
		reloaded.getConfig().setBudgetDelayNotificationsEnabled(true);
		assertTrue(reloaded.saveSafely());
		var enabled = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		enabled.load();
		assertTrue(enabled.getConfig().isBudgetDelayNotificationsEnabled());
		assertEquals(19, enabled.getConfig().getRateLimit());
		assertEquals(256, enabled.getConfig().getPingDistance());
	}

	@Test
	void existingOlderMigrationRetainsFalseAndUnrelatedRawData(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, "{\"pingforit-version\":\"0.2.0-pfi-beta1\",\"pingDuration\":23,"
			+ "\"budgetDelayNotificationsEnabled\":false,\"rateLimit\":17,\"unknown\":{\"retained\":[1,2]}}\n",
			StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertFalse(handler.getConfig().isBudgetDelayNotificationsEnabled());
		assertFalse(root.get("budgetDelayNotificationsEnabled").getAsBoolean());
		assertEquals(23, root.get("syncDuration").getAsInt());
		assertFalse(root.has("pingDuration"));
		assertEquals(17, root.get("rateLimit").getAsInt());
		assertEquals(JsonParser.parseString("{\"retained\":[1,2]}"), root.get("unknown"));
		assertEquals(VERSION, root.get("pingforit-version").getAsString());
	}

	@Test
	void futureVersionProtectionKeepsExactSourceThroughSaveAndReset(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"9.0.0-pfi-beta1\",\"budgetDelayNotificationsEnabled\":false,"
			+ "\"rateLimit\":17,\"future\":{\"untouched\":true}}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		assertTrue(handler.getConfig().isBudgetDelayNotificationsEnabled());
		handler.getConfig().setBudgetDelayNotificationsEnabled(false);
		assertFalse(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.resetToDefaults();
		assertTrue(handler.getConfig().isBudgetDelayNotificationsEnabled());
		assertFalse(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path));
	}
}
