package nx.pingwheel.common.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence boundaries for the additive inventory object and the client
 * spatial-selector object: missing keys gain defaults without rewriting an
 * untouched file, existing-version migration writes the additive defaults
 * while preserving user data, malformed numbers clamp without resetting the
 * configuration, and the future-version guard still protects the file.
 */
class InventorySettingsPersistenceTest {

	private static final String CURRENT_VERSION = "0.4.0-pfi-beta1";

	@Test
	void currentVersionServerFileWithoutInventoryGetsDefaultsWithoutRewrite(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"" + CURRENT_VERSION
			+ "\",\"rateLimit\":3,\"unknown\":{\"source\":\"legacy\"}}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(3, handler.getConfig().getRateLimit());
		InventorySettings inventory = handler.getConfig().getInventory();
		assertNotNull(inventory);
		assertTrue(inventory.getPreview().getPeriodTicks() > 0);
		assertTrue(inventory.getTracking().isHeartbeatEnabled());
		assertArrayEquals(original, Files.readAllBytes(configPath));
	}

	@Test
	void olderVersionMigrationWritesAdditiveInventoryDefaultsAndKeepsUserData(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"0.2.0-pfi-beta1\",\"pingDuration\":23,\"rateLimit\":3,\"unknown\":true}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(23, handler.getConfig().getSyncDuration());
		JsonObject persisted = readRoot(configPath);
		assertEquals(CURRENT_VERSION, persisted.get(ConfigVersionUpdater.VERSION_KEY).getAsString());
		assertEquals(23, persisted.get("syncDuration").getAsInt());
		assertEquals(3, persisted.get("rateLimit").getAsInt());
		assertTrue(persisted.get("unknown").getAsBoolean());

		JsonObject inventory = persisted.getAsJsonObject("inventory");
		assertNotNull(inventory);
		assertTrue(inventory.has("pendingMemoryMiB"));
		assertTrue(inventory.getAsJsonObject("physicalSlotsPerTick").has("unlimited"));
		JsonObject preview = inventory.getAsJsonObject("preview");
		JsonObject tracking = inventory.getAsJsonObject("tracking");
		assertTrue(preview.has("maxSlotsPerClient"));
		assertTrue(preview.has("clientByteMultiplier"));
		assertTrue(preview.has("globalByteMultiplier"));
		assertTrue(tracking.has("heartbeatPeriods"));
		assertTrue(tracking.has("streamByteMultiplier"));
		assertTrue(tracking.has("snapshotByteMultiplier"));
		assertTrue(tracking.has("globalByteMultiplier"));
		assertFalse(inventory.has("clientByteMultiplier"));
		assertFalse(inventory.has("globalByteMultiplier"));
		assertFalse(hasBrokenBackup(tempDir));
	}

	@Test
	void explicitInventoryValuesRoundTripAndUnlimitedSerializesExplicitly(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		assertTrue(handler.saveSafely());

		ServerConfig config = handler.getConfig();
		config.getInventory().getPhysicalSlotsPerTick().setUnlimited(true);
		config.getInventory().getPreview().getClientByteMultiplier().setValue(new BigDecimal("0.5"));
		config.getInventory().getPreview().getGlobalByteMultiplier().setValue(new BigDecimal("2"));
		config.getInventory().getTracking().getStreamByteMultiplier().setValue(new BigDecimal("0.25"));
		config.getInventory().getTracking().getStreamByteMultiplier().setUnlimited(true);
		config.getInventory().getTracking().getSnapshotByteMultiplier().setValue(new BigDecimal("4"));
		config.getInventory().getTracking().getGlobalByteMultiplier().setValue(new BigDecimal("0.125"));
		config.getInventory().getTracking().setHeartbeatPeriods(0);
		assertTrue(handler.saveSafely());

		JsonObject inventory = readRoot(configPath).getAsJsonObject("inventory");
		assertTrue(inventory.getAsJsonObject("physicalSlotsPerTick").get("unlimited").getAsBoolean());
		JsonObject preview = inventory.getAsJsonObject("preview");
		JsonObject tracking = inventory.getAsJsonObject("tracking");
		assertEquals("0.5", preview.getAsJsonObject("clientByteMultiplier").get("value").getAsString());
		assertFalse(preview.getAsJsonObject("clientByteMultiplier").get("unlimited").getAsBoolean());
		assertEquals("2", preview.getAsJsonObject("globalByteMultiplier").get("value").getAsString());
		assertEquals("0.25", tracking.getAsJsonObject("streamByteMultiplier").get("value").getAsString());
		assertTrue(tracking.getAsJsonObject("streamByteMultiplier").get("unlimited").getAsBoolean());
		assertEquals("4", tracking.getAsJsonObject("snapshotByteMultiplier").get("value").getAsString());
		assertEquals("0.125", tracking.getAsJsonObject("globalByteMultiplier").get("value").getAsString());
		assertEquals(0, tracking.get("heartbeatPeriods").getAsInt());

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		InventorySettings loaded = reloaded.getConfig().getInventory();
		assertTrue(loaded.getPhysicalSlotsPerTick().isUnlimited());
		assertTrue(loaded.getTracking().getStreamByteMultiplier().isUnlimited());
		assertFalse(loaded.getPreview().getClientByteMultiplier().isUnlimited());
		assertEquals(0, loaded.getTracking().getHeartbeatPeriods());
		assertFalse(loaded.getTracking().isHeartbeatEnabled());
		assertEquals(0, new BigDecimal("0.5")
			.compareTo(loaded.getPreview().getClientByteMultiplier().getValue()));
		assertEquals(0, new BigDecimal("2")
			.compareTo(loaded.getPreview().getGlobalByteMultiplier().getValue()));
		assertEquals(0, new BigDecimal("0.25")
			.compareTo(loaded.getTracking().getStreamByteMultiplier().getValue()));
		assertEquals(0, new BigDecimal("4")
			.compareTo(loaded.getTracking().getSnapshotByteMultiplier().getValue()));
		assertEquals(0, new BigDecimal("0.125")
			.compareTo(loaded.getTracking().getGlobalByteMultiplier().getValue()));
	}

	@Test
	void outOfRangeInventoryValuesClampWithoutResettingTheConfig(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"inventory\":{"
				+ "\"physicalSlotsPerTick\":{\"unlimited\":false,\"value\":-5},"
				+ "\"pendingMemoryMiB\":0,"
				+ "\"preview\":{\"periodTicks\":0,"
				+ "\"clientByteMultiplier\":{\"unlimited\":false,\"value\":0.01},"
				+ "\"globalByteMultiplier\":{\"unlimited\":false,\"value\":9999}},"
				+ "\"tracking\":{\"periodTicks\":-2,\"heartbeatPeriods\":99,\"gracePeriods\":0,\"resyncMinPeriods\":0,"
				+ "\"streamByteMultiplier\":{\"unlimited\":false,\"value\":0.01},"
				+ "\"snapshotByteMultiplier\":{\"unlimited\":false,\"value\":9999},"
				+ "\"globalByteMultiplier\":{\"unlimited\":false,\"value\":0.01}}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(3, handler.getConfig().getRateLimit());
		InventorySettings inventory = handler.getConfig().getInventory();
		InventorySettings.Preview preview = inventory.getPreview();
		InventorySettings.Tracking tracking = inventory.getTracking();
		assertEquals(InventoryLimits.MIN_FINITE_LIMIT, inventory.getPhysicalSlotsPerTick().getValue());
		assertEquals(InventoryLimits.MIN_PENDING_MEMORY_MIB, inventory.getPendingMemoryMiB());
		assertEquals(InventoryLimits.MIN_PERIOD_TICKS, preview.getPeriodTicks());
		assertEquals(InventoryLimits.MIN_PERIOD_TICKS, tracking.getPeriodTicks());
		assertEquals(InventoryLimits.MAX_HEARTBEAT_PERIODS, tracking.getHeartbeatPeriods());
		assertEquals(InventoryLimits.MIN_GRACE_PERIODS, tracking.getGracePeriods());
		assertEquals(InventoryLimits.MIN_RESYNC_PERIODS, tracking.getResyncMinPeriods());
		assertEquals(0, ByteMultiplierGrid.CLIENT.minimum()
			.compareTo(preview.getClientByteMultiplier().getValue()));
		assertEquals(0, ByteMultiplierGrid.GLOBAL.maximum()
			.compareTo(preview.getGlobalByteMultiplier().getValue()));
		assertEquals(0, ByteMultiplierGrid.CLIENT.minimum()
			.compareTo(tracking.getStreamByteMultiplier().getValue()));
		assertEquals(0, ByteMultiplierGrid.CLIENT.maximum()
			.compareTo(tracking.getSnapshotByteMultiplier().getValue()));
		assertEquals(0, ByteMultiplierGrid.GLOBAL.minimum()
			.compareTo(tracking.getGlobalByteMultiplier().getValue()));
		assertFalse(hasBrokenBackup(tempDir));
	}

	@Test
	void futureVersionInventoryFileIsNeverRewrittenAndDefaultsStayInMemory(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"3.0.0-pfi-beta1\",\"inventory\":{"
			+ "\"physicalSlotsPerTick\":{\"unlimited\":true,\"value\":7},"
			+ "\"tracking\":{\"heartbeatPeriods\":0}},\"future\":true}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertTrue(handler.getConfig().getInventory().getTracking().isHeartbeatEnabled());
		assertArrayEquals(original, Files.readAllBytes(configPath));
		assertFalse(handler.saveSafely());
		assertFalse(hasBrokenBackup(tempDir));
	}

	@Test
	void currentVersionClientFileWithoutSpatialSelectorGetsDefaultsWithoutRewrite(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		byte[] original = ("{\"pingforit-version\":\"" + CURRENT_VERSION
			+ "\",\"pingVolume\":37,\"unknown\":true}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(37, handler.getConfig().getPingVolume());
		SpatialSelectorSettings selector = handler.getConfig().getSpatialSelector();
		assertNotNull(selector);
		assertFalse(selector.isHoverEnabled());
		assertTrue(selector.getTargetGlide().compareTo(SpatialSelectorSettings.MIN_TARGET_GLIDE) >= 0);
		assertArrayEquals(original, Files.readAllBytes(configPath));
	}

	@Test
	void clientSpatialSelectorValuesClampWithoutResettingUnrelatedFields(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"pingVolume\":37,\"spatialSelector\":{"
				+ "\"targetGlide\":9,\"hoverEnabled\":true,\"hoverMillis\":10}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(37, handler.getConfig().getPingVolume());
		SpatialSelectorSettings selector = handler.getConfig().getSpatialSelector();
		assertEquals(0, SpatialSelectorSettings.MAX_TARGET_GLIDE.compareTo(selector.getTargetGlide()));
		assertEquals(SpatialSelectorSettings.MIN_HOVER_MILLIS, selector.getHoverMillis());
		assertTrue(selector.isHoverEnabled());
		assertFalse(hasBrokenBackup(tempDir));
	}

	@Test
	void clientSpatialSelectorSerializesTheConfirmedKeys(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		assertTrue(handler.saveSafely());

		JsonObject selector = readRoot(configPath).getAsJsonObject("spatialSelector");
		assertNotNull(selector);
		assertTrue(selector.has("targetGlide"));
		assertTrue(selector.has("hoverEnabled"));
		assertTrue(selector.has("hoverMillis"));
	}

	private static JsonObject readRoot(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static boolean hasBrokenBackup(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			return paths.anyMatch(path -> path.getFileName().toString().contains(".broken-"));
		}
	}
}
