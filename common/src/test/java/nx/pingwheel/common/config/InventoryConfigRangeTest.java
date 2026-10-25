package nx.pingwheel.common.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import nx.pingwheel.common.config.InventoryConfigValues.Field;
import nx.pingwheel.common.config.InventoryConfigValues.Value;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Independent field boundaries: persisted repair versus strict administration, without a UI grid. */
@NotNullByDefault
class InventoryConfigRangeTest {

	private static Stream<Arguments> integerFields() {
		return Stream.of(
			Arguments.of(Field.PHYSICAL_SLOTS_PER_TICK, "physicalSlotsPerTick", 16, 1024, 17),
			Arguments.of(Field.PENDING_MEMORY_MIB, "pendingMemoryMiB", 16, 1024, 65),
			Arguments.of(Field.PREVIEW_PERIOD_TICKS, "preview.periodTicks", 1, 10, 7),
			Arguments.of(Field.PREVIEW_MAX_VARIANTS_PER_CLIENT_PERIOD, "preview.maxVariantsPerClientPeriod", 16, 1024, 17),
			Arguments.of(Field.PREVIEW_MAX_SLOTS_PER_CLIENT, "preview.maxSlotsPerClient", 16, 1024, 67),
			Arguments.of(Field.PREVIEW_MAX_SLOTS_SERVER, "preview.maxSlotsServer", 16, 1024, 513),
			Arguments.of(Field.PREVIEW_MAX_TARGETS_PER_CLIENT, "preview.maxTargetsPerClient", 1, 8, 3),
			Arguments.of(Field.TRACKING_PERIOD_TICKS, "tracking.periodTicks", 1, 10, 7),
			Arguments.of(Field.TRACKING_MAX_VARIANTS_PER_TARGET, "tracking.maxVariantsPerTarget", 1, 32, 9),
			Arguments.of(Field.TRACKING_MAX_SLOTS_PER_TARGET, "tracking.maxSlotsPerTarget", 16, 1024, 513),
			Arguments.of(Field.TRACKING_MAX_SLOTS_SERVER, "tracking.maxSlotsServer", 128, 16384, 129),
			Arguments.of(Field.TRACKING_RESYNC_MIN_PERIODS, "tracking.resyncMinPeriods", 0, 10, 7),
			Arguments.of(Field.TRACKING_HEARTBEAT_PERIODS, "tracking.heartbeatPeriods", 0, 32, 17),
			Arguments.of(Field.TRACKING_GRACE_PERIODS, "tracking.gracePeriods", 1, 32, 17));
	}

	@ParameterizedTest
	@MethodSource("integerFields")
	void fileClampsBothBoundsButAdministrationRejectsWithoutMutation(
		Field field, String path, int minimum, int maximum, int offGrid, @TempDir Path directory) throws IOException {
		var current = new ServerConfigSnapshot(true, ChannelMode.AUTO, true, 1000, 5, 23);
		for (boolean unlimited : new boolean[] {false, true}) {
			if (unlimited && !field.supportsUnlimited()) continue;
			for (int stored : new int[] {minimum - 1, maximum + 1}) {
				Value supplied = Value.limit(unlimited, stored);
				assertFalse(field.isSafe(supplied));
				var invalid = InventoryConfigValues.defaults().with(field, supplied);
				var update = new ServerConfigUpdate(field.mask() | ServerConfigUpdate.RATE_LIMIT,
					ChannelMode.AUTO, true, 1000, 99, 23, invalid);
				var result = ServerConfigUpdateService.apply(true, current, update);
				assertFalse(result.applied());
				assertEquals(current, result.snapshot());

				var loaded = load(directory.resolve(unlimited + "-" + stored + ".json"), path,
					field.supportsUnlimited(), unlimited, stored);
				assertEquals(Value.limit(unlimited, stored < minimum ? minimum : maximum), loaded.value(field));
			}
		}
	}

	@ParameterizedTest
	@MethodSource("integerFields")
	void endpointsAndInRangeNonStepIntegersRemainExactInFilesAndAdministration(
		Field field, String path, int minimum, int maximum, int offGrid, @TempDir Path directory) throws IOException {
		for (boolean unlimited : new boolean[] {false, true}) {
			if (unlimited && !field.supportsUnlimited()) continue;
			for (int stored : new int[] {minimum, offGrid, maximum}) {
				Value supplied = Value.limit(unlimited, stored);
				assertTrue(field.isSafe(supplied));
				assertEquals(supplied, field.parse(Integer.toString(stored), unlimited).orElseThrow());
				var inventory = InventoryConfigValues.defaults().with(field, supplied);
				assertEquals(inventory, InventoryConfigValues.from(inventory.toSettings()));
				assertEquals(supplied, load(directory.resolve(unlimited + "-" + stored + ".json"), path,
					field.supportsUnlimited(), unlimited, stored).value(field));
			}
		}
	}

	private static InventoryConfigValues load(Path file, String path, boolean limit, boolean unlimited, int stored)
		throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("pingforit-version", "0.5.0-pfi-beta1");
		root.addProperty("rateLimit", 19);
		JsonObject inventory = new JsonObject();
		root.add("inventory", inventory);
		JsonObject parent = inventory;
		String[] parts = path.split("\\.");
		for (int index = 0; index < parts.length - 1; index++) {
			JsonObject child = new JsonObject();
			parent.add(parts[index], child);
			parent = child;
		}
		JsonElement value = new JsonPrimitive(stored);
		if (limit) {
			JsonObject object = new JsonObject();
			object.addProperty("unlimited", unlimited);
			object.add("value", value);
			value = object;
		}
		parent.add(parts[parts.length - 1], value);
		byte[] original = (root + "\n").getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);
		var handler = new ConfigHandler<>(ServerConfig.class, file, "0.5.0-pfi-beta1");
		handler.load();
		assertEquals(19, handler.getConfig().getRateLimit());
		assertArrayEquals(original, Files.readAllBytes(file), "range repair alone must not schedule shape writeback");
		return Objects.requireNonNull(InventoryConfigValues.from(handler.getConfig().getInventory()));
	}
}
