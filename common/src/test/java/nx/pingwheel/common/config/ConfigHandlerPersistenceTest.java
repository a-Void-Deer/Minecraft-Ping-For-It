package nx.pingwheel.common.config;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@NotNullByDefault
class ConfigHandlerPersistenceTest {

	private static final String VERSION = "1.0.0-pfi-beta1";

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void collidingServerRateEditIsRetriedAndBecomesANoopOnlyAfterWriting(boolean notify, @TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("server.json");
		// Explicit legal rate values that collide under ServerConfig's real hash (not defaults).
		RateValues before = new RateValues(1000, 5);
		RateValues after = new RateValues(999, 64);
		byte @Unmodifiable [] original = """
			{ "pingforit-version": "%s", "msToRegenerate": %d, "rateLimit": %d,
			  "syncDuration": -1, "unknown": null }
			""".formatted(VERSION, before.interval(), before.limit()).getBytes(StandardCharsets.UTF_8);
		Files.write(path, original);
		List<SaveEvent> events = new ArrayList<>();
		var handler = new ConfigHandler<>(RecordingServerConfig.class, path,
			(source, backup, bytes) -> fail("no backup for a valid configuration"), VERSION,
			(target, serialized) -> {
				boolean firstWrite = events.stream().noneMatch(event -> event.operation().equals("write"));
				events.add(new SaveEvent("write", RateValues.fromJson(serialized)));
				if (firstWrite) throw new IOException("deterministic first write failure");
				Files.writeString(target, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		var loaded = handler.getConfig();
		loaded.recordUpdate = () -> events.add(new SaveEvent("update", RateValues.from(loaded)));
		assertEquals(before, RateValues.from(loaded));
		assertTrue(loaded.getSyncDuration() > 0, "the loaded model was clamped, not the original file");
		assertTrue(handler.saveSafely());
		assertTrue(events.isEmpty(), "an untouched normalized model neither notifies nor writes");
		assertArrayEquals(original, Files.readAllBytes(path));

		int originalHash = loaded.hashCode();
		loaded.setMsToRegenerate(after.interval());
		loaded.setRateLimit(after.limit());
		assertNotEquals(before, RateValues.from(loaded));
		assertEquals(originalHash, loaded.hashCode(), "fixture must exercise an actual hash collision");

		SaveEvent update = new SaveEvent("update", after);
		SaveEvent write = new SaveEvent("write", after);
		assertFalse(notify ? handler.saveSafely() : handler.saveWithoutUpdate(), "the failing write was attempted");
		assertEquals(notify ? List.of(update, write) : List.of(write), events);
		assertArrayEquals(original, Files.readAllBytes(path));
		assertEquals(after, RateValues.from(loaded));

		assertTrue(notify ? handler.saveSafely() : handler.saveWithoutUpdate(), "unchanged retry must still write");
		@Unmodifiable List<SaveEvent> expectedEvents = notify ? List.of(update, write, update, write) : List.of(write, write);
		assertEquals(expectedEvents, events);
		byte @Unmodifiable [] saved = Files.readAllBytes(path);
		assertTrue(handler.saveSafely());
		assertEquals(expectedEvents, events, "the successful write installed the new no-op baseline");
		assertArrayEquals(saved, Files.readAllBytes(path));
		var reloaded = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		reloaded.load();
		assertEquals(after, RateValues.from(reloaded.getConfig()));
	}

	@Test
	void persistsDistinctSubmenuRadiusScalesWithTheSameDoubleValue(@TempDir Path directory) {
		Path path = directory.resolve("client-precise-radius.json");
		BigDecimal before = new BigDecimal("2.50000000000000001");
		BigDecimal after = new BigDecimal("2.50000000000000002");
		assertNotEquals(0, before.compareTo(after));
		assertEquals(before.doubleValue(), after.doubleValue());

		var initial = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		Objects.requireNonNull(initial.getConfig().getSpatialSelector()).setSubmenuRadiusScale(before);
		assertTrue(initial.saveSafely());

		var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		handler.load();
		var selector = Objects.requireNonNull(handler.getConfig().getSpatialSelector());
		assertEquals(before, selector.getSubmenuRadiusScale());
		selector.setSubmenuRadiusScale(after);
		assertTrue(handler.saveSafely());

		var reloaded = new ConfigHandler<>(ClientConfig.class, path, VERSION);
		reloaded.load();
		assertEquals(after, Objects.requireNonNull(reloaded.getConfig().getSpatialSelector()).getSubmenuRadiusScale());
	}

	@Test
	void successfulWriteUsesTheModelAfterOnUpdateAsItsNoopBaseline(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("callback-edit.json");
		Files.writeString(path, "{\"pingforit-version\":\"" + VERSION + "\",\"rateLimit\":11}");
		List<Integer> attemptedLimits = new ArrayList<>();
		var handler = new ConfigHandler<>(RecordingServerConfig.class, path,
			(source, backup, bytes) -> fail("no backup for a valid configuration"), VERSION,
			(target, serialized) -> {
				attemptedLimits.add(RateValues.fromJson(serialized).limit());
				Files.writeString(target, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		var loaded = handler.getConfig();
		loaded.setRateLimit(12);
		loaded.recordUpdate = () -> loaded.setRateLimit(13);

		assertTrue(handler.saveSafely());
		assertEquals(List.of(13), attemptedLimits, "the write must see the callback's final values");
		assertEquals(13, RateValues.fromJson(Files.readString(path)).limit());
		assertTrue(handler.saveSafely());
		assertEquals(List.of(13), attemptedLimits, "the final model must not be written again");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void legacyRecoveryWritesDefaultsWithoutAnEqualKnownModel(boolean differentBaseline, @TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("broken-legacy.json");
		var handler = new ConfigHandler<>(LegacyRecoveryConfig.class, path, VERSION);
		var defaults = new LegacyRecoveryConfig();
		assertEquals(0, defaults.hashCode(), "the real empty-string hash collides with the old uninitialized hash");
		if (differentBaseline) {
			handler.getConfig().label = "\0";
			assertNotEquals(defaults, handler.getConfig());
			assertEquals(defaults.hashCode(), handler.getConfig().hashCode(), "distinct persisted values also collide");
			assertTrue(handler.saveSafely());
		}
		Files.writeString(path, "{broken");

		handler.load();

		assertEquals(defaults, handler.getConfig());
		var persisted = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(VERSION, persisted.get("pingforit-version").getAsString());
		assertEquals(defaults.label, persisted.get("label").getAsString());
	}

	@NotNullByDefault
	private record RateValues(int interval, int limit) {
		private static RateValues from(ServerConfig config) {
			return new RateValues(config.getMsToRegenerate(), config.getRateLimit());
		}

		private static RateValues fromJson(String serialized) {
			var root = JsonParser.parseString(serialized).getAsJsonObject();
			return new RateValues(root.get("msToRegenerate").getAsInt(), root.get("rateLimit").getAsInt());
		}
	}

	@NotNullByDefault
	private record SaveEvent(String operation, RateValues rates) {}

	/** Uses the real rate model and hash, but records notifications without a live server. */
	@NotNullByDefault
	public static final class RecordingServerConfig extends ServerConfig {
		private transient Runnable recordUpdate = () -> {};

		public RecordingServerConfig() {}

		@Override public void onUpdate() {
			recordUpdate.run();
		}
	}

	@NotNullByDefault
	public static final class LegacyRecoveryConfig implements IConfig {
		private String label = "";

		public LegacyRecoveryConfig() {}

		@Override public void validate() {}
		@Override public void onUpdate() {}
		@Override public int hashCode() { return label.hashCode(); }
		@Override public boolean equals(@Nullable Object other) {
			return other instanceof LegacyRecoveryConfig config && label.equals(config.label);
		}
	}
}
