package nx.pingwheel.common.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigHandlerResetTest {

	@Test
	void resetReplacesTheWholeConfigAndPersistsDefaults(@TempDir Path tempDir) throws IOException {
		Path configPath = tempDir.resolve("client.json");
		ConfigHandler<ClientConfig> handler = new ConfigHandler<>(ClientConfig.class, configPath);
		ClientConfig changed = handler.getConfig();
		changed.setPingVolume(1);
		changed.setDirectionIndicatorVisible(false);
		changed.setWheelFontSize(450);
		changed.setWheelTargetFontSize(20);
		changed.setLongPressCompatibilityMode(true);
		changed.setLongPressCompatibilitySliceMillis(100);
		handler.save();

		handler.resetToDefaults();

		ClientConfig reset = handler.getConfig();
		assertNotSame(changed, reset);
		ClientConfig defaults = new ClientConfig();
		assertEquals(defaults, reset);
		assertEquals(100, reset.getPingVolume());
		assertTrue(reset.isDirectionIndicatorVisible());
		assertEquals(ClientConfigBounds.DEFAULT_WHEEL_FONT_SIZE, reset.getWheelFontSize());
		assertEquals(ClientConfigBounds.DEFAULT_WHEEL_TARGET_FONT_SIZE, reset.getWheelTargetFontSize());
		assertFalse(reset.isLongPressCompatibilityMode());
		assertEquals(ClientConfigBounds.DEFAULT_LONG_PRESS_COMPATIBILITY_SLICE_MILLIS,
			reset.getLongPressCompatibilitySliceMillis());

		com.google.gson.Gson gson = new com.google.gson.Gson();
		ClientConfig persistedConfig = gson.fromJson(Files.readString(configPath), ClientConfig.class);
		assertEquals(defaults, persistedConfig);

		// A stale settings screen may still hold the pre-reset object. Saving the
		// handler after such a mutation must not write that object back.
		changed.setPingVolume(2);
		handler.save();
		persistedConfig = gson.fromJson(Files.readString(configPath), ClientConfig.class);
		assertEquals(defaults, persistedConfig);
	}
}
