package nx.pingwheel.common.config;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import nx.pingwheel.common.diagnostics.DiagnosticSide;
import nx.pingwheel.common.diagnostics.LoggingProfile;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Filesystem and final-model DEBUG normalization boundaries; no sampling or HUD rendering evidence. */
@NotNullByDefault
class MonitorConfigPersistenceTest {
	private static final String VERSION = "0.5.0-pfi-beta1";

	@Test
	void serverDefaultsAndFinalJsonProfileAreIndependentOfMemberOrder() {
		var fresh = new ServerConfig();
		assertFalse(fresh.isPreviewMonitorEnabled());
		assertFalse(fresh.isTrackingMonitorEnabled());
		for (String members : List.of("", "\"previewMonitorEnabled\":null,\"trackingMonitorEnabled\":null,")) {
			var config = new Gson().fromJson("{" + members + "\"loggingProfile\":\"DEBUG\"}", ServerConfig.class);
			config.validate();
			assertFalse(config.isPreviewMonitorEnabled());
			assertFalse(config.isTrackingMonitorEnabled());
		}
		for (boolean preview : new boolean[] {false, true}) {
			for (boolean tracking : new boolean[] {false, true}) {
				String monitors = "\"previewMonitorEnabled\":" + preview + ",\"trackingMonitorEnabled\":" + tracking;
				for (String json : List.of("{\"loggingProfile\":\"DEBUG\"," + monitors + "}",
					"{" + monitors + ",\"loggingProfile\":\"DEBUG\"}")) {
					var config = new Gson().fromJson(json, ServerConfig.class);
					config.validate();
					assertEquals(preview, config.isPreviewMonitorEnabled());
					assertEquals(tracking, config.isTrackingMonitorEnabled());
				}
			}
		}
		for (String profile : List.of("\"NORMAL\"", "\"DEEP\"", "null", "\"unknown\"")) {
			var config = new Gson().fromJson("{\"previewMonitorEnabled\":true,\"trackingMonitorEnabled\":true,"
				+ "\"loggingProfile\":" + profile + "}", ServerConfig.class);
			config.validate();
			assertFalse(config.isPreviewMonitorEnabled());
			assertFalse(config.isTrackingMonitorEnabled());
			config.setLoggingProfile(LoggingProfile.DEBUG);
			assertFalse(config.isPreviewMonitorEnabled());
			assertFalse(config.isTrackingMonitorEnabled());
		}
	}

	@Test
	void serverSettersClearOnExitRejectOutsideDebugAndDoNotRestore() {
		var config = new ServerConfig();
		config.setPreviewMonitorEnabled(true);
		config.setTrackingMonitorEnabled(true);
		assertFalse(config.isPreviewMonitorEnabled());
		assertFalse(config.isTrackingMonitorEnabled());
		for (LoggingProfile profile : List.of(LoggingProfile.NORMAL, LoggingProfile.DEEP)) {
			config.setLoggingProfile(LoggingProfile.DEBUG);
			config.setPreviewMonitorEnabled(true);
			config.setTrackingMonitorEnabled(true);
			config.setLoggingProfile(profile);
			assertFalse(config.isPreviewMonitorEnabled());
			assertFalse(config.isTrackingMonitorEnabled());
			config.setLoggingProfile(LoggingProfile.DEBUG);
			assertFalse(config.isPreviewMonitorEnabled());
			assertFalse(config.isTrackingMonitorEnabled());
		}
	}

	@Test
	void serverSameVersionNoOpAndRealSaveRoundTripAllFourChoices(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		String original = "{\"pingforit-version\":\"" + VERSION + "\",\"rateLimit\":19}";
		Files.writeString(path, original, StandardCharsets.UTF_8);
		var handler = new ConfigHandler<>(ServerConfig.class, path, VERSION);
		handler.load();
		assertTrue(handler.saveSafely());
		assertEquals(original, Files.readString(path));
		for (boolean preview : new boolean[] {false, true}) {
			for (boolean tracking : new boolean[] {false, true}) {
				handler.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
				handler.getConfig().setPreviewMonitorEnabled(preview);
				handler.getConfig().setTrackingMonitorEnabled(tracking);
				assertTrue(handler.saveSafely());
				handler.load();
				assertEquals(preview, handler.getConfig().isPreviewMonitorEnabled());
				assertEquals(tracking, handler.getConfig().isTrackingMonitorEnabled());
				assertEquals(19, handler.getConfig().getRateLimit());
			}
		}
		handler.getConfig().setLoggingProfile(LoggingProfile.NORMAL);
		assertTrue(handler.saveSafely());
		handler.load();
		handler.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
		assertFalse(handler.getConfig().isPreviewMonitorEnabled());
		assertFalse(handler.getConfig().isTrackingMonitorEnabled());
	}

	@Test
	void loadTimeTemporaryNormalPublicationDoesNotMutateEitherFinalModel(@TempDir Path directory) throws IOException {
		Path serverPath = directory.resolve("server.json");
		Files.writeString(serverPath, "{\"pingforit-version\":\"" + VERSION
			+ "\",\"previewMonitorEnabled\":true,\"trackingMonitorEnabled\":true,\"loggingProfile\":\"DEBUG\"}");
		var serverProfiles = new ArrayList<LoggingProfile>();
		var server = new ConfigHandler<>(ServerConfig.class, serverPath, VERSION,
			ConfigRuntime.of(DiagnosticSide.SERVER, ServerConfig::getLoggingProfile, serverProfiles::add));
		serverProfiles.clear();
		server.load();
		assertEquals(LoggingProfile.NORMAL, serverProfiles.getFirst());
		assertEquals(LoggingProfile.DEBUG, serverProfiles.getLast());
		assertTrue(server.getConfig().isPreviewMonitorEnabled());
		assertTrue(server.getConfig().isTrackingMonitorEnabled());

		Path clientPath = directory.resolve("client.json");
		Files.writeString(clientPath, "{\"pingforit-version\":\"" + VERSION
			+ "\",\"monitorHudEnabled\":true,\"loggingProfile\":\"DEBUG\"}");
		var clientProfiles = new ArrayList<LoggingProfile>();
		var client = new ConfigHandler<>(ClientConfig.class, clientPath, VERSION,
			ConfigRuntime.of(DiagnosticSide.CLIENT, ClientConfig::getLoggingProfile, clientProfiles::add));
		clientProfiles.clear();
		client.load();
		assertEquals(LoggingProfile.NORMAL, clientProfiles.getFirst());
		assertEquals(LoggingProfile.DEBUG, clientProfiles.getLast());
		assertTrue(client.getConfig().isMonitorHudEnabled());
	}

	@Test
	void failedServerSaveKeepsAppliedStateAndFutureVersionCannotBeOverwritten(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("server.json");
		String original = "{\"pingforit-version\":\"" + VERSION + "\",\"loggingProfile\":\"DEBUG\"}";
		Files.writeString(path, original);
		var handler = new ConfigHandler<>(ServerConfig.class, path,
			(source, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(target, serialized) -> { throw new IOException("write denied"); });
		handler.load();
		handler.getConfig().setPreviewMonitorEnabled(true);
		handler.getConfig().setTrackingMonitorEnabled(true);
		assertFalse(handler.saveSafely());
		assertTrue(handler.getConfig().isPreviewMonitorEnabled());
		assertTrue(handler.getConfig().isTrackingMonitorEnabled());
		assertEquals(original, Files.readString(path));
		handler.getConfig().setLoggingProfile(LoggingProfile.NORMAL);
		assertFalse(handler.saveSafely());
		assertFalse(handler.getConfig().isPreviewMonitorEnabled());
		assertFalse(handler.getConfig().isTrackingMonitorEnabled());
		assertEquals(original, Files.readString(path));

		byte @Unmodifiable [] future = "{\"pingforit-version\":\"99.0.0-pfi-beta1\",\"loggingProfile\":\"DEBUG\",\"previewMonitorEnabled\":true}"
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, future);
		handler.load();
		assertFalse(handler.getConfig().isPreviewMonitorEnabled());
		handler.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
		handler.getConfig().setPreviewMonitorEnabled(true);
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertFalse(handler.getConfig().isPreviewMonitorEnabled());
		assertFalse(handler.getConfig().isTrackingMonitorEnabled());
		assertArrayEquals(future, Files.readAllBytes(path));
	}

	@Test
	void clientFinalProfileMemberOrderAndHudExitRetainSelections() {
		assertFalse(new ClientConfig().isMonitorHudEnabled());
		String choices = "\"monitorMetrics\":{\"overrides\":{\"summary.processing\":false,"
			+ "\"local.preview.timing.apply.max\":true}},\"monitorHudEnabled\":true";
		for (String raw : List.of("{\"loggingProfile\":\"DEBUG\"," + choices + "}",
			"{" + choices + ",\"loggingProfile\":\"DEBUG\"}")) {
			var config = new Gson().fromJson(raw, ClientConfig.class);
			config.validate();
			assertTrue(config.isMonitorHudEnabled());
			var selectors = config.getMonitorMetrics();
			assertFalse(selectors.isSelected("summary.processing"));
			assertTrue(selectors.isSelected("local.preview.timing.apply.max"));
			config.setLoggingProfile(LoggingProfile.DEEP);
			assertFalse(config.isMonitorHudEnabled());
			assertSame(selectors, config.getMonitorMetrics());
			config.setMonitorHudEnabled(true);
			assertFalse(config.isMonitorHudEnabled());
			config.setLoggingProfile(LoggingProfile.DEBUG);
			assertFalse(config.isMonitorHudEnabled());
			config.setMonitorHudEnabled(true);
			assertTrue(config.isMonitorHudEnabled());
			config.setMonitorHudEnabled(false);
			assertFalse(config.getMonitorMetrics().isSelected("summary.processing"));
			assertTrue(config.getMonitorMetrics().isSelected("local.preview.timing.apply.max"));
		}
		for (String profile : List.of("\"NORMAL\"", "\"DEEP\"", "null", "\"unknown\"")) {
			var config = new Gson().fromJson("{" + choices + ",\"loggingProfile\":" + profile + "}", ClientConfig.class);
			config.validate();
			assertFalse(config.isMonitorHudEnabled());
			assertTrue(config.getMonitorMetrics().isSelected("local.preview.timing.apply.max"));
		}
	}

	@Test
	void clientAdditiveDefaultsNoOpSaveAndSelectorOnlyEditsRoundTrip(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		for (String member : List.of("", ",\"monitorMetrics\":null", ",\"monitorMetrics\":{}",
			",\"monitorHudEnabled\":null,\"monitorMetrics\":{\"overrides\":null}")) {
			String original = "{\"pingforit-version\":\"" + VERSION + "\",\"pingVolume\":37" + member + "}";
			Files.writeString(path, original);
			var handler = new ConfigHandler<>(ClientConfig.class, path, VERSION);
			handler.load();
			assertFalse(handler.getConfig().isMonitorHudEnabled());
			assertTrue(handler.getConfig().getMonitorMetrics().isSelected("summary.processing"));
			assertTrue(handler.saveSafely());
			assertEquals(original, Files.readString(path));
			handler.getConfig().getMonitorMetrics().setSelected("summary.processing", false);
			handler.getConfig().getMonitorMetrics().setSelected("local.preview.timing.apply.max", true);
			assertTrue(handler.saveSafely(), "selector content participates in the config hash");
			handler.load();
			assertFalse(handler.getConfig().getMonitorMetrics().isSelected("summary.processing"));
			assertTrue(handler.getConfig().getMonitorMetrics().isSelected("local.preview.timing.apply.max"));
			assertEquals(37, handler.getConfig().getPingVolume());
			handler.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
			handler.getConfig().setMonitorHudEnabled(true);
			assertTrue(handler.saveSafely());
			handler.load();
			assertTrue(handler.getConfig().isMonitorHudEnabled());
			handler.getConfig().setLoggingProfile(LoggingProfile.NORMAL);
			assertTrue(handler.saveSafely());
			handler.load();
			handler.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
			assertFalse(handler.getConfig().isMonitorHudEnabled());
			assertTrue(handler.getConfig().getMonitorMetrics().isSelected("local.preview.timing.apply.max"));
			var stale = handler.getConfig().getMonitorMetrics();
			handler.resetToDefaults();
			assertNotSame(stale, handler.getConfig().getMonitorMetrics());
			stale.setSelected("summary.memory", false);
			assertTrue(handler.saveSafely());
			handler.load();
			assertTrue(handler.getConfig().getMonitorMetrics().isSelected("summary.memory"));
		}
	}

	@Test
	void guardedSelectorWritebackPreservesUnknownMembersAndClearsRevertedKnownOverrides(@TempDir Path directory)
		throws IOException {
		Path path = directory.resolve("client.json");
		String original = "{\"pingforit-version\":\"0.4.0-pfi-beta1\",\"loggingProfile\":\"DEBUG\","
			+ "\"monitorHudEnabled\":true,\"unknown\":[null,1],\"monitorMetrics\":{\"future\":{\"raw\":null},"
			+ "\"overrides\":{\"summary.processing\":false,\"local.preview.timing.apply.max\":true,"
			+ "\"future.metric\":{\"raw\":null}}}}";
		Files.writeString(path, original);
		var fail = new AtomicBoolean(true);
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, backup, bytes) -> Files.write(backup, bytes), VERSION, (target, serialized) -> {
				if (fail.get()) throw new IOException("write denied");
				Files.writeString(target, serialized, StandardCharsets.UTF_8);
			});
		handler.load();
		assertTrue(handler.getConfig().isMonitorHudEnabled());
		assertFalse(handler.getConfig().getMonitorMetrics().isSelected("summary.processing"));
		assertFalse(handler.getConfig().getMonitorMetrics().isSelected("future.metric"));
		assertEquals(original, Files.readString(path));
		handler.getConfig().getMonitorMetrics().setSelected("summary.processing", true);
		handler.getConfig().getMonitorMetrics().setSelected("local.preview.timing.apply.max", false);
		fail.set(false);
		assertTrue(handler.saveSafely());
		var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
		assertEquals(JsonParser.parseString("[null,1]"), root.get("unknown"));
		var selector = root.getAsJsonObject("monitorMetrics");
		assertEquals(JsonParser.parseString("{\"raw\":null}"), selector.get("future"));
		assertEquals(JsonParser.parseString("{\"raw\":null}"), selector.getAsJsonObject("overrides").get("future.metric"));
		assertFalse(selector.getAsJsonObject("overrides").has("summary.processing"));
		assertFalse(selector.getAsJsonObject("overrides").has("local.preview.timing.apply.max"));
		handler.load();
		assertTrue(handler.getConfig().getMonitorMetrics().isSelected("summary.processing"));
		assertFalse(handler.getConfig().getMonitorMetrics().isSelected("local.preview.timing.apply.max"));
		assertTrue(handler.getConfig().isMonitorHudEnabled());
	}

	@Test
	void selectorFutureProtectionAndPendingChangedSourceGuardPreserveExactBytes(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		Files.writeString(path, "{\"pingforit-version\":\"0.4.0-pfi-beta1\",\"loggingProfile\":\"DEBUG\","
			+ "\"monitorHudEnabled\":true,\"monitorMetrics\":{\"overrides\":{\"summary.memory\":false}}}");
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(target, serialized) -> { throw new IOException("write denied"); });
		handler.load();
		assertFalse(handler.getConfig().getMonitorMetrics().isSelected("summary.memory"));
		byte @Unmodifiable [] future = ("{\"pingforit-version\":\"99.0.0-pfi-beta1\",\"monitorHudEnabled\":true,"
			+ "\"monitorMetrics\":{\"overrides\":{\"summary.memory\":false},\"unknown\":null}}")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(path, future);
		assertFalse(handler.saveSafely());
		assertFalse(handler.getConfig().isMonitorHudEnabled());
		assertTrue(handler.getConfig().getMonitorMetrics().isSelected("summary.memory"));
		handler.getConfig().setLoggingProfile(LoggingProfile.DEBUG);
		handler.getConfig().setMonitorHudEnabled(true);
		handler.getConfig().getMonitorMetrics().setSelected("summary.memory", false);
		assertFalse(handler.saveSafely());
		handler.resetToDefaults();
		assertFalse(handler.getConfig().isMonitorHudEnabled());
		assertArrayEquals(future, Files.readAllBytes(path));
	}

	@Test
	void clientFailedSaveKeepsLiveHudAndPreferencesWithoutRollingBack(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("client.json");
		String original = "{\"pingforit-version\":\"" + VERSION + "\",\"loggingProfile\":\"DEBUG\"}";
		Files.writeString(path, original);
		var handler = new ConfigHandler<>(ClientConfig.class, path,
			(source, backup, bytes) -> Files.write(backup, bytes), VERSION,
			(target, serialized) -> { throw new IOException("write denied"); });
		handler.load();
		handler.getConfig().setMonitorHudEnabled(true);
		handler.getConfig().getMonitorMetrics().setSelected("summary.processing", false);
		assertFalse(handler.saveSafely());
		assertTrue(handler.getConfig().isMonitorHudEnabled());
		assertFalse(handler.getConfig().getMonitorMetrics().isSelected("summary.processing"));
		assertEquals(original, Files.readString(path));
	}
}
