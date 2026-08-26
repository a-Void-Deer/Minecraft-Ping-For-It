package nx.pingwheel.common.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigSerializationTest {
	private final Gson gson = new Gson();

	@Test
	void legacyPingDurationLoadsIntoSyncDurationAndNewSavesUseSyncDuration() {
		ServerConfig config = gson.fromJson("{\"pingDuration\":23}", ServerConfig.class);

		assertEquals(23, config.getSyncDuration());

		String serialized = gson.toJson(config);
		assertTrue(serialized.contains("\"syncDuration\":23"));
		assertFalse(serialized.contains("\"pingDuration\""));
	}

	@Test
	void syncDurationValidationRetainsTheExistingBounds() {
		ServerConfig config = gson.fromJson("{\"syncDuration\":0}", ServerConfig.class);
		config.validate();
		assertEquals(ServerConfigBounds.MIN_PING_DURATION, config.getSyncDuration());

		config.setSyncDuration(61);
		config.validate();
		assertEquals(ServerConfigBounds.MAX_PING_DURATION, config.getSyncDuration());
	}
}
