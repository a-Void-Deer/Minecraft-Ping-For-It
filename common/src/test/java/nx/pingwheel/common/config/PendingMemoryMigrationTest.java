package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class PendingMemoryMigrationTest {
	private static final String VERSION = "0.5.0-pfi-beta1";

	@ParameterizedTest
	@ValueSource(strings = {"0.5.0-pfi-beta1", "0.4.0-pfi-beta1"})
	void onlyLegacyMemoryScalarNormalizesAndUnknownInventoryMembersSurvive(String storedVersion, @TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, scalarFile(storedVersion, 65), StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		assertEquals(65, handler.getConfig().getInventory().getPendingMemoryMiB().getValue());
		assertFalse(handler.getConfig().getInventory().getPendingMemoryMiB().isUnlimited());
		JsonObject root = root(path);
		assertEquals(VERSION, root.get("pingforit-version").getAsString());
		assertEquals(19, root.get("rateLimit").getAsInt());
		assertTrue(root.get("unknownRoot").isJsonNull());
		JsonObject inventory = root.getAsJsonObject("inventory");
		assertEquals(JsonParser.parseString("{\"unlimited\":false,\"value\":65}"), inventory.get("pendingMemoryMiB"));
		assertEquals(JsonParser.parseString("{\"kept\":[1,null]}"), inventory.get("unknownInventory"));
		assertTrue(inventory.getAsJsonObject("preview").get("unknownPreview").isJsonNull());
		assertEquals("tracking", inventory.getAsJsonObject("tracking").get("unknownTracking").getAsString());
		assertEquals("cap", inventory.getAsJsonObject("physicalSlotsPerTick").get("unknownCap").getAsString());
		byte[] normalized = Files.readAllBytes(path);
		var reloaded = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		reloaded.load();
		assertArrayEquals(normalized, Files.readAllBytes(path));
		assertEquals(65, reloaded.getConfig().getInventory().getPendingMemoryMiB().getValue());
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 15, 1025, Integer.MAX_VALUE})
	void scalarMigrationPersistsTheClampedFiniteValue(int stored, @TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, scalarFile(VERSION, stored), StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		int expected = stored < 16 ? 16 : 1024;
		assertEquals(expected, handler.getConfig().getInventory().getPendingMemoryMiB().getValue());
		assertEquals(expected, root(path).getAsJsonObject("inventory").getAsJsonObject("pendingMemoryMiB").get("value").getAsInt());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", ",\"pendingMemoryMiB\":null", ",\"pendingMemoryMiB\":{\"unlimited\":true,\"value\":65}"})
	void missingNullAndNewObjectUseTypedDefaultsWithoutShapeRewrite(String member, @TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"" + VERSION
			+ "\",\"inventory\":{\"unknown\":true" + member + "}}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		@Nullable IntLimit memory = handler.getConfig().getInventory().getPendingMemoryMiB();
		assertNotNull(memory);
		if (member.contains("unlimited")) {
			assertTrue(memory.isUnlimited());
			assertEquals(65, memory.getValue());
			assertEquals(4294967296L, handler.getConfig().getInventory().pendingMemoryBytes());
		} else {
			assertFalse(memory.isUnlimited());
			assertEquals(new InventorySettings().getPendingMemoryMiB(), memory);
		}
		assertArrayEquals(original, Files.readAllBytes(path));
		assertTrue(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.getConfig().setRateLimit(29);
		assertTrue(handler.saveSafely());
		assertEquals(memory.isUnlimited(), root(path).getAsJsonObject("inventory")
			.getAsJsonObject("pendingMemoryMiB").get("unlimited").getAsBoolean());
		var reloaded = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		reloaded.load();
		assertEquals(memory, reloaded.getConfig().getInventory().getPendingMemoryMiB());
	}

	@Test
	void scalarAcceptanceDoesNotSpreadToOtherIntLimitFields(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION
			+ "\",\"rateLimit\":19,\"inventory\":{\"physicalSlotsPerTick\":65,\"pendingMemoryMiB\":65}}\n");
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		assertEquals(new ServerConfig().getRateLimit(), handler.getConfig().getRateLimit(),
			"another scalar limit must still take the server invalid-file recovery path");
	}

	@Test
	void failedWritebackRetainsLoadedMemoryAndRetriesWithUnknownMembers(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = scalarFile(VERSION, 65).getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		AtomicBoolean fail = new AtomicBoolean(true);
		var handler = failingHandler(path, fail);
		handler.load();
		assertArrayEquals(original, Files.readAllBytes(path));
		assertEquals(65, handler.getConfig().getInventory().getPendingMemoryMiB().getValue());
		handler.getConfig().getInventory().setPendingMemoryMiB(IntLimit.unlimited(67));
		fail.set(false);
		assertTrue(handler.saveSafely());
		JsonObject inventory = root(path).getAsJsonObject("inventory");
		assertEquals(JsonParser.parseString("{\"unlimited\":true,\"value\":67}"), inventory.get("pendingMemoryMiB"));
		assertEquals(JsonParser.parseString("{\"kept\":[1,null]}"), inventory.get("unknownInventory"));
	}

	@ParameterizedTest
	@ValueSource(strings = {VERSION, "9.0.0-pfi-beta1"})
	void failedPendingWritebackCannotOverwriteAnExternalReplacement(String replacementVersion, @TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("server.json");
		Files.writeString(path, scalarFile(VERSION, 65), StandardCharsets.UTF_8);
		AtomicBoolean fail = new AtomicBoolean(true);
		var handler = failingHandler(path, fail);
		handler.load();
		byte[] external = ("{\"pingforit-version\":\"" + replacementVersion
			+ "\",\"rateLimit\":29,\"inventory\":{\"pendingMemoryMiB\":{\"unlimited\":true,\"value\":69}}}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, external);
		fail.set(false);
		assertFalse(handler.saveSafely());
		assertArrayEquals(external, Files.readAllBytes(path));
		if (replacementVersion.equals(VERSION)) {
			assertEquals(29, handler.getConfig().getRateLimit());
			assertEquals(IntLimit.unlimited(69), handler.getConfig().getInventory().getPendingMemoryMiB());
		} else {
			handler.getConfig().getInventory().setPendingMemoryMiB(71);
			assertFalse(handler.saveSafely());
			handler.resetToDefaults();
			assertArrayEquals(external, Files.readAllBytes(path));
		}
	}

	@Test
	void futureScalarIsProtectedBeforeNormalization(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		byte[] original = scalarFile("9.0.0-pfi-beta1", 65).getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		assertArrayEquals(original, Files.readAllBytes(path));
		handler.getConfig().getInventory().setPendingMemoryMiB(IntLimit.unlimited(67));
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	private static ConfigHandler<ServerConfig> failingHandler(Path path, AtomicBoolean fail) {
		return new ConfigHandler<>(ServerConfig.class, path, (source, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(destination, serialized) -> {
				if (fail.get()) throw new IOException("injected normalization write failure");
				Files.writeString(destination, serialized, StandardCharsets.UTF_8);
			});
	}

	private static JsonObject root(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String scalarFile(String version, int value) {
		return "{\"pingforit-version\":\"" + version + "\",\"rateLimit\":19,\"unknownRoot\":null,\"inventory\":{"
			+ "\"pendingMemoryMiB\":" + value + ",\"unknownInventory\":{\"kept\":[1,null]},"
			+ "\"physicalSlotsPerTick\":{\"unlimited\":false,\"value\":67,\"unknownCap\":\"cap\"},"
			+ "\"preview\":{\"unknownPreview\":null},\"tracking\":{\"unknownTracking\":\"tracking\"}}}\n";
	}
}
