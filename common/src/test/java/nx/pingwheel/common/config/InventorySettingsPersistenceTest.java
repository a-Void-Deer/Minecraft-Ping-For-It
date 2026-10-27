package nx.pingwheel.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence boundaries for the additive inventory object and the client
 * spatial-selector object: missing keys gain defaults without rewriting an
 * untouched file, a cap object whose finite value member is absent or null
 * restores the owning field's model default while an explicit non-positive
 * value still clamps, existing-version migration writes the additive defaults
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
		config.getInventory().setPendingMemoryMiB(IntLimit.unlimited(65));
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
		assertEquals(65, inventory.getAsJsonObject("pendingMemoryMiB").get("value").getAsInt());
		assertTrue(inventory.getAsJsonObject("pendingMemoryMiB").get("unlimited").getAsBoolean());
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
		assertEquals(IntLimit.unlimited(65), loaded.getPendingMemoryMiB());
		assertEquals(4294967296L, loaded.pendingMemoryBytes());
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
				+ "\"pendingMemoryMiB\":{\"unlimited\":false,\"value\":0},"
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
		assertEquals(16, inventory.getPhysicalSlotsPerTick().getValue());
		assertEquals(InventoryLimits.MIN_PENDING_MEMORY_MIB, inventory.getPendingMemoryMiB().getValue());
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

	private static final @Unmodifiable List<NamedCap> INVENTORY_LIMITS = List.of(
		namedCap("physicalSlotsPerTick", InventorySettings::getPhysicalSlotsPerTick, "physicalSlotsPerTick"),
		namedCap("pendingMemoryMiB", InventorySettings::getPendingMemoryMiB, "pendingMemoryMiB"),
		namedCap("preview.maxVariantsPerClientPeriod",
			settings -> settings.getPreview().getMaxVariantsPerClientPeriod(),
			"preview", "maxVariantsPerClientPeriod"),
		namedCap("preview.maxSlotsPerClient",
			settings -> settings.getPreview().getMaxSlotsPerClient(),
			"preview", "maxSlotsPerClient"),
		namedCap("preview.maxSlotsServer",
			settings -> settings.getPreview().getMaxSlotsServer(),
			"preview", "maxSlotsServer"),
		namedCap("preview.maxTargetsPerClient",
			settings -> settings.getPreview().getMaxTargetsPerClient(),
			"preview", "maxTargetsPerClient"),
		namedCap("tracking.maxVariantsPerTarget",
			settings -> settings.getTracking().getMaxVariantsPerTarget(),
			"tracking", "maxVariantsPerTarget"),
		namedCap("tracking.maxSlotsPerTarget",
			settings -> settings.getTracking().getMaxSlotsPerTarget(),
			"tracking", "maxSlotsPerTarget"),
		namedCap("tracking.maxSlotsServer",
			settings -> settings.getTracking().getMaxSlotsServer(),
			"tracking", "maxSlotsServer"));

	private static Stream<Arguments> inventoryLimits() {
		return INVENTORY_LIMITS.stream().map(cap -> Arguments.of(cap.name(), cap.cap()));
	}

	private static NamedCap namedCap(
		String name, Function<InventorySettings, IntLimit> read, String... path) {
		return new NamedCap(name, new CapPath(List.of(path), read));
	}

	private record NamedCap(String name, CapPath cap) {}

	private record CapPath(@Unmodifiable List<String> path, Function<InventorySettings, IntLimit> read) {

		JsonObject inventoryHolding(JsonElement cap) {
			JsonObject root = new JsonObject();
			JsonObject current = root;
			for (int index = 0; index < path.size() - 1; index++) {
				JsonObject next = new JsonObject();
				current.add(path.get(index), next);
				current = next;
			}
			current.add(path.get(path.size() - 1), cap);
			return root;
		}
	}

	private static JsonObject limitShape(boolean unlimited, @Nullable Integer value) {
		JsonObject shape = new JsonObject();
		shape.addProperty("unlimited", unlimited);
		if (value != null) shape.addProperty("value", value);
		return shape;
	}

	private static String serverFile(JsonObject inventory) {
		JsonObject root = new JsonObject();
		root.addProperty(ConfigVersionUpdater.VERSION_KEY, CURRENT_VERSION);
		root.addProperty("rateLimit", 3);
		root.add("inventory", inventory);
		return root + System.lineSeparator();
	}

	private static ConfigHandler<ServerConfig> loadServer(Path configPath, byte[] serialized) throws IOException {
		Files.write(configPath, serialized);
		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		return handler;
	}

	@ParameterizedTest(name = "missing value {0} restores the field model default")
	@MethodSource("inventoryLimits")
	void missingValueMemberRestoresTheFieldModelDefault(String name, CapPath cap, @TempDir Path tempDir)
		throws IOException {
		Path configPath = tempDir.resolve("server.json");
		byte[] original = serverFile(cap.inventoryHolding(limitShape(false, null)))
			.getBytes(StandardCharsets.UTF_8);
		ConfigHandler<ServerConfig> handler = loadServer(configPath, original);

		IntLimit expected = cap.read().apply(new InventorySettings());
		IntLimit loaded = cap.read().apply(handler.getConfig().getInventory());
		assertTrue(expected.getValue() > minimumFor(name),
			"the model default must be distinguishable from the explicit-minimum clamp");
		assertFalse(loaded.isUnlimited());
		assertEquals(expected.getValue(), loaded.getValue());

		for (NamedCap other : INVENTORY_LIMITS) {
			if (other.name().equals(name)) {
				continue;
			}
			IntLimit otherLoaded = other.cap().read().apply(handler.getConfig().getInventory());
			assertFalse(otherLoaded.isUnlimited(), other.name());
			assertEquals(other.cap().read().apply(new InventorySettings()).getValue(),
				otherLoaded.getValue(), other.name());
		}

		assertArrayEquals(original, Files.readAllBytes(configPath), "an untouched file must not be rewritten");
		assertTrue(handler.saveSafely());
		assertArrayEquals(original, Files.readAllBytes(configPath), "a no-op save must not rewrite the file");
		assertFalse(hasBrokenBackup(tempDir));
	}

	@ParameterizedTest(name = "absent unlimited or empty object {0}")
	@MethodSource("inventoryLimits")
	void absentUnlimitedMemberDefaultsFalseAndEmptyObjectGainsTheFieldDefault(
		String name, CapPath cap, @TempDir Path tempDir) throws IOException {
		JsonObject explicitValue = new JsonObject();
		int explicitFinite = cap.read().apply(new InventorySettings()).getValue();
		explicitValue.addProperty("value", explicitFinite);
		ConfigHandler<ServerConfig> explicitHandler = loadServer(tempDir.resolve("explicit.json"),
			serverFile(cap.inventoryHolding(explicitValue)).getBytes(StandardCharsets.UTF_8));
		IntLimit explicit = cap.read().apply(explicitHandler.getConfig().getInventory());
		assertFalse(explicit.isUnlimited());
		assertEquals(explicitFinite, explicit.getValue());

		ConfigHandler<ServerConfig> emptyHandler = loadServer(tempDir.resolve("empty.json"),
			serverFile(cap.inventoryHolding(new JsonObject())).getBytes(StandardCharsets.UTF_8));
		IntLimit empty = cap.read().apply(emptyHandler.getConfig().getInventory());
		assertFalse(empty.isUnlimited());
		assertEquals(cap.read().apply(new InventorySettings()).getValue(), empty.getValue());
	}

	@ParameterizedTest(name = "explicit non-positive {0} still clamps")
	@MethodSource("inventoryLimits")
	void explicitZeroAndNegativeValuesStillClampToTheFiniteMinimum(
		String name, CapPath cap, @TempDir Path tempDir) throws IOException {
		for (int stored : new int[] {0, -7}) {
			Path configPath = tempDir.resolve("stored-" + stored + ".json");
			byte[] original = serverFile(cap.inventoryHolding(limitShape(false, stored)))
				.getBytes(StandardCharsets.UTF_8);
			ConfigHandler<ServerConfig> handler = loadServer(configPath, original);
			IntLimit loaded = cap.read().apply(handler.getConfig().getInventory());
			assertFalse(loaded.isUnlimited());
			assertTrue(cap.read().apply(new InventorySettings()).getValue() > minimumFor(name));
			assertEquals(minimumFor(name), loaded.getValue());
			assertArrayEquals(original, Files.readAllBytes(configPath));
		}
	}

	@ParameterizedTest(name = "unlimited without value {0} keeps unlimited and the model default")
	@MethodSource("inventoryLimits")
	void unlimitedWithoutValueKeepsUnlimitedAndRestoresTheFieldModelDefault(
		String name, CapPath cap, @TempDir Path tempDir) throws IOException {
		ConfigHandler<ServerConfig> handler = loadServer(tempDir.resolve("server.json"),
			serverFile(cap.inventoryHolding(limitShape(true, null))).getBytes(StandardCharsets.UTF_8));

		IntLimit loaded = cap.read().apply(handler.getConfig().getInventory());
		assertTrue(loaded.isUnlimited());
		assertEquals(cap.read().apply(new InventorySettings()).getValue(), loaded.getValue());
	}

	@ParameterizedTest(name = "null object or null member {0} uses the field model default")
	@MethodSource("inventoryLimits")
	void nullLimitObjectAndNullValueMemberUseTheFieldModelDefault(
		String name, CapPath cap, @TempDir Path tempDir) throws IOException {
		int modelDefault = cap.read().apply(new InventorySettings()).getValue();

		ConfigHandler<ServerConfig> objectHandler = loadServer(tempDir.resolve("null-object.json"),
			serverFile(cap.inventoryHolding(JsonNull.INSTANCE)).getBytes(StandardCharsets.UTF_8));
		IntLimit nullObject = cap.read().apply(objectHandler.getConfig().getInventory());
		assertFalse(nullObject.isUnlimited());
		assertEquals(modelDefault, nullObject.getValue());

		JsonObject nullValueMember = limitShape(false, null);
		nullValueMember.add("value", JsonNull.INSTANCE);
		ConfigHandler<ServerConfig> memberHandler = loadServer(tempDir.resolve("null-member.json"),
			serverFile(cap.inventoryHolding(nullValueMember)).getBytes(StandardCharsets.UTF_8));
		IntLimit nullMember = cap.read().apply(memberHandler.getConfig().getInventory());
		assertFalse(nullMember.isUnlimited());
		assertEquals(modelDefault, nullMember.getValue());
	}

	@Test
	void missingValueMaterializesOnExplicitSaveWithoutTouchingSiblingCaps(@TempDir Path tempDir)
		throws IOException {
		Path configPath = tempDir.resolve("server.json");
		JsonObject inventory = new JsonObject();
		JsonObject physical = new JsonObject();
		physical.addProperty("unlimited", false);
		inventory.add("physicalSlotsPerTick", physical);
		JsonObject preview = new JsonObject();
		preview.add("maxSlotsPerClient", limitShape(false, 67));
		inventory.add("preview", preview);
		JsonObject tracking = new JsonObject();
		tracking.add("maxSlotsServer", limitShape(false, 0));
		inventory.add("tracking", tracking);
		byte[] original = serverFile(inventory).getBytes(StandardCharsets.UTF_8);
		ConfigHandler<ServerConfig> handler = loadServer(configPath, original);

		InventorySettings loaded = handler.getConfig().getInventory();
		int physicalDefault = new InventorySettings().getPhysicalSlotsPerTick().getValue();
		assertEquals(physicalDefault, loaded.getPhysicalSlotsPerTick().getValue());
		assertEquals(67, loaded.getPreview().getMaxSlotsPerClient().getValue());
		assertEquals(128, loaded.getTracking().getMaxSlotsServer().getValue());
		assertArrayEquals(original, Files.readAllBytes(configPath));

		handler.getConfig().setRateLimit(9);
		assertTrue(handler.saveSafely());

		JsonObject persisted = readRoot(configPath);
		assertEquals(9, persisted.get("rateLimit").getAsInt());
		JsonObject persistedInventory = persisted.getAsJsonObject("inventory");
		assertEquals(physicalDefault,
			persistedInventory.getAsJsonObject("physicalSlotsPerTick").get("value").getAsInt());
		assertEquals(67, persistedInventory.getAsJsonObject("preview")
			.getAsJsonObject("maxSlotsPerClient").get("value").getAsInt());
		assertEquals(128, persistedInventory.getAsJsonObject("tracking")
			.getAsJsonObject("maxSlotsServer").get("value").getAsInt());

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertEquals(physicalDefault, reloaded.getConfig().getInventory().getPhysicalSlotsPerTick().getValue());
		assertEquals(67, reloaded.getConfig().getInventory().getPreview().getMaxSlotsPerClient().getValue());
	}

	@Test
	void sizeScaleScalarsRoundTripAndMissingKeysGainDefaultsWithoutRewrite(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		byte[] original = ("{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3}\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		InventorySettings loaded = handler.getConfig().getInventory();
		assertEquals(0, BigDecimal.ONE.compareTo(loaded.getDisplayByteMultiplier()));
		assertEquals(0, BigDecimal.ONE.compareTo(loaded.getFrameByteMultiplier()));
		assertEquals(4096, loaded.effectiveDisplayBytes());
		assertEquals(32768, loaded.effectiveFrameBytes());
		assertArrayEquals(original, Files.readAllBytes(configPath));

		loaded.setDisplayByteMultiplier(new BigDecimal("2.5"));
		loaded.setFrameByteMultiplier(new BigDecimal("4.25"));
		assertTrue(handler.saveSafely());
		JsonObject inventory = readRoot(configPath).getAsJsonObject("inventory");
		assertEquals(0, new BigDecimal("2.5").compareTo(inventory.get("displayByteMultiplier").getAsBigDecimal()));
		assertEquals(0, new BigDecimal("4.25").compareTo(inventory.get("frameByteMultiplier").getAsBigDecimal()));

		ConfigHandler<ServerConfig> reloaded = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertEquals(0, new BigDecimal("2.5")
			.compareTo(reloaded.getConfig().getInventory().getDisplayByteMultiplier()));
		assertEquals(0, new BigDecimal("4.25")
			.compareTo(reloaded.getConfig().getInventory().getFrameByteMultiplier()));
	}

	@Test
	void nullAndOutOfRangeSizeScalarsNormalizeWithFramePriority(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"rateLimit\":3,\"inventory\":{"
				+ "\"displayByteMultiplier\":32,\"frameByteMultiplier\":1,"
				+ "\"pendingMemoryMiB\":{\"unlimited\":false,\"value\":67}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		InventorySettings inventory = handler.getConfig().getInventory();
		assertEquals(0, new BigDecimal("7.5").compareTo(inventory.getDisplayByteMultiplier()));
		assertEquals(0, BigDecimal.ONE.compareTo(inventory.getFrameByteMultiplier()));
		assertEquals(67, inventory.getPendingMemoryMiB().getValue());
		assertEquals(30720, inventory.effectiveDisplayBytes());
		assertEquals(32768, inventory.effectiveFrameBytes());

		Path nullPath = tempDir.resolve("null-server.json");
		Files.writeString(nullPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"inventory\":{"
				+ "\"displayByteMultiplier\":null,\"frameByteMultiplier\":null}}\n",
			StandardCharsets.UTF_8);
		ConfigHandler<ServerConfig> nullHandler = new ConfigHandler<>(ServerConfig.class, nullPath, CURRENT_VERSION);
		nullHandler.load();
		assertEquals(0, BigDecimal.ONE.compareTo(nullHandler.getConfig().getInventory().getDisplayByteMultiplier()));
		assertEquals(0, BigDecimal.ONE.compareTo(nullHandler.getConfig().getInventory().getFrameByteMultiplier()));
	}

	@Test
	void unlimitedWrappedSizeScalarIsRejectedAsAnInvalidFile(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("server.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"inventory\":{"
				+ "\"displayByteMultiplier\":{\"unlimited\":true,\"value\":4}}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ServerConfig> handler = new ConfigHandler<>(ServerConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(0, BigDecimal.ONE.compareTo(handler.getConfig().getInventory().getDisplayByteMultiplier()));
		assertEquals(0, BigDecimal.ONE.compareTo(handler.getConfig().getInventory().getFrameByteMultiplier()));
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
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE
			.compareTo(selector.getSubmenuRadiusScale()));
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, selector.getTrailLength());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, selector.getTrailOpacity());
		assertArrayEquals(original, Files.readAllBytes(configPath));
	}

	@Test
	void clientSpatialSelectorValuesClampWithoutResettingUnrelatedFields(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		Files.writeString(configPath,
			"{\"pingforit-version\":\"" + CURRENT_VERSION + "\",\"pingVolume\":37,\"spatialSelector\":{"
				+ "\"submenuRadiusScale\":9,\"hoverEnabled\":true,\"hoverMillis\":10}}\n",
			StandardCharsets.UTF_8);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();

		assertEquals(37, handler.getConfig().getPingVolume());
		SpatialSelectorSettings selector = handler.getConfig().getSpatialSelector();
		assertEquals(0, SpatialSelectorSettings.MAX_SUBMENU_RADIUS_SCALE
			.compareTo(selector.getSubmenuRadiusScale()));
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
		assertFalse(selector.has("targetGlide"), "fresh serialization does not create the removed preference");
		assertTrue(selector.has("hoverEnabled"));
		assertTrue(selector.has("hoverMillis"));
		assertTrue(selector.has("preciseCapturePeriodTicks"));
		assertEquals(SpatialSelectorSettings.DEFAULT_PRECISE_CAPTURE_PERIOD_TICKS,
			selector.get("preciseCapturePeriodTicks").getAsInt());
		assertTrue(selector.has("submenuRadiusScale"));
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE
			.compareTo(selector.get("submenuRadiusScale").getAsBigDecimal()));
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_LENGTH, selector.get("trailLength").getAsInt());
		assertEquals(SpatialSelectorSettings.DEFAULT_TRAIL_OPACITY, selector.get("trailOpacity").getAsInt());
		assertTrue(selector.has("contentIntentEnabled"));
		assertFalse(selector.get("contentIntentEnabled").getAsBoolean());
	}

	@Test
	void clientTrailPreferencesClampOnLoadAndPersistWithoutResettingAppearance(@TempDir Path tempDir) throws IOException {
		for (int[] values : new int[][] {
			{Integer.MIN_VALUE, Integer.MAX_VALUE, SpatialSelectorSettings.MIN_TRAIL_LENGTH, SpatialSelectorSettings.MAX_TRAIL_OPACITY},
			{Integer.MAX_VALUE, Integer.MIN_VALUE, SpatialSelectorSettings.MAX_TRAIL_LENGTH, 0}
		}) {
			Path configPath = tempDir.resolve("client.json");
			byte[] original = ("{\"pingforit-version\":\"" + CURRENT_VERSION
				+ "\",\"pingVolume\":37,\"wheelOpacity\":35,\"wheelTargetOpacity\":45,\"spatialSelector\":{"
				+ "\"showTrail\":false,\"rootDistance\":90,\"trailLength\":" + values[0]
				+ ",\"trailOpacity\":" + values[1] + "}}\n").getBytes(StandardCharsets.UTF_8);
			Files.write(configPath, original);
			var handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
			handler.load();
			SpatialSelectorSettings selector = handler.getConfig().getSpatialSelector();
			assertEquals(values[2], selector.getTrailLength());
			assertEquals(values[3], selector.getTrailOpacity());
			assertEquals(90, selector.getRootDistance());
			assertFalse(selector.isShowTrail());
			assertEquals(37, handler.getConfig().getPingVolume());
			assertEquals(35, handler.getConfig().getWheelOpacity());
			assertEquals(45, handler.getConfig().getWheelTargetOpacity());
			assertArrayEquals(original, Files.readAllBytes(configPath));
			handler.getConfig().setPingVolume(38);
			assertTrue(handler.saveSafely());
			JsonObject persisted = readRoot(configPath).getAsJsonObject("spatialSelector");
			assertEquals(values[2], persisted.get("trailLength").getAsInt());
			assertEquals(values[3], persisted.get("trailOpacity").getAsInt());
			var reloaded = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
			reloaded.load();
			assertEquals(selector.snapshot(), reloaded.getConfig().getSpatialSelector().snapshot());
		}
		assertFalse(hasBrokenBackup(tempDir));
	}

	@Test
	void clientSubmenuRadiusScaleRoundTripsThroughTheHandler(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		byte[] original = ("{\"pingforit-version\":\"" + CURRENT_VERSION
			+ "\",\"pingVolume\":37,\"spatialSelector\":{\"rootDistance\":90}}\n").getBytes(StandardCharsets.UTF_8);
		Files.write(configPath, original);

		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		handler.load();
		assertEquals(0, SpatialSelectorSettings.DEFAULT_SUBMENU_RADIUS_SCALE
			.compareTo(handler.getConfig().getSpatialSelector().getSubmenuRadiusScale()));
		assertArrayEquals(original, Files.readAllBytes(configPath));

		handler.getConfig().getSpatialSelector().setSubmenuRadiusScale(new BigDecimal("2.5"));
		assertTrue(handler.saveSafely());
		JsonObject selector = readRoot(configPath).getAsJsonObject("spatialSelector");
		assertEquals(0, new BigDecimal("2.5").compareTo(selector.get("submenuRadiusScale").getAsBigDecimal()));

		ConfigHandler<ClientConfig> reloaded = new ConfigHandler<>(ClientConfig.class, configPath, CURRENT_VERSION);
		reloaded.load();
		assertEquals(0, new BigDecimal("2.5").compareTo(
			reloaded.getConfig().getSpatialSelector().getSubmenuRadiusScale()));
	}

	private static JsonObject readRoot(Path path) throws IOException {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/** Independent persisted field minima, not the implementation clamp helper. */
	private static int minimumFor(String name) {
		return switch (name) {
			case "tracking.maxSlotsServer" -> 128;
			case "preview.maxTargetsPerClient", "tracking.maxVariantsPerTarget" -> 1;
			default -> 16;
		};
	}

	private static boolean hasBrokenBackup(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			return paths.anyMatch(path -> path.getFileName().toString().contains(".broken-"));
		}
	}
}
