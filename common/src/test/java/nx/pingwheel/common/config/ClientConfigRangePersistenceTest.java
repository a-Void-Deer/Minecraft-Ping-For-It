package nx.pingwheel.common.config;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NotNullByDefault
class ClientConfigRangePersistenceTest {
	private static final String VERSION = "1.0.0-pfi-beta1";

	@Test
	void negativeRangeLoadsAsDefaultWithoutSameVersionRewriteUntilARealChange(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("pingforit.json");
		byte @Unmodifiable [] original = ("{\"pingforit-version\":\"" + VERSION
			+ "\",\"pingDistance\":-1,\"raycastDistance\":0,\"pingVolume\":42,"
			+ "\"wheelHoldMillis\":210}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);

		handler.load();

		ClientConfig defaults = new ClientConfig();
		ClientConfig loaded = handler.getConfig();
		assertEquals(defaults.getPingDistance(), loaded.getPingDistance());
		assertEquals(0, loaded.getRaycastDistance());
		assertEquals(42, loaded.getPingVolume());
		assertEquals(210, loaded.getWheelHoldMillis());
		assertArrayEquals(original, Files.readAllBytes(path),
			"a same-version load must not rewrite the source");

		assertTrue(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(path),
			"an untouched sanitized model must not rewrite the source");

		loaded.setPingVolume(43);
		assertTrue(handler.saveSafely());

		var persisted = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(defaults.getPingDistance(), persisted.get("pingDistance").getAsInt());
		assertEquals(0, persisted.get("raycastDistance").getAsInt());
		assertEquals(43, persisted.get("pingVolume").getAsInt());

		var reloaded = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		reloaded.load();
		assertEquals(defaults.getPingDistance(), reloaded.getConfig().getPingDistance());
		assertEquals(0, reloaded.getConfig().getRaycastDistance());
		assertEquals(43, reloaded.getConfig().getPingVolume());
	}

	@Test
	void zeroAndUncappedNonNegativeRangeValuesRemainValidThroughFileSave(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("pingforit.json");
		byte @Unmodifiable [] original = ("{\"pingforit-version\":\"" + VERSION
			+ "\",\"pingDistance\":0,\"raycastDistance\":" + Integer.MAX_VALUE
			+ ",\"pingVolume\":42}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);

		handler.load();

		assertEquals(0, handler.getConfig().getPingDistance());
		assertEquals(Integer.MAX_VALUE, handler.getConfig().getRaycastDistance());
		assertArrayEquals(original, Files.readAllBytes(path));

		handler.getConfig().setPingVolume(43);
		assertTrue(handler.saveSafely());

		var persisted = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(0, persisted.get("pingDistance").getAsInt());
		assertEquals(Integer.MAX_VALUE, persisted.get("raycastDistance").getAsInt());
		assertEquals(43, persisted.get("pingVolume").getAsInt());
	}
}
