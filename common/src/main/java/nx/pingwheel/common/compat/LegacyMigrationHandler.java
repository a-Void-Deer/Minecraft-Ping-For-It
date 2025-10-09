package nx.pingwheel.common.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import nx.pingwheel.common.platform.IPlatformContextService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static nx.pingwheel.common.CommonClient.Game;
import static nx.pingwheel.common.Global.LOGGER;
import static nx.pingwheel.common.Global.MOD_ID;
import static nx.pingwheel.common.util.InputUtils.*;

// TODO: remove this class a few versions/months down the line
// introduced with 1.12.0 on 09.10.2025
@SuppressWarnings("StringConcatenationArgumentToLogCall")
public class LegacyMigrationHandler {

	private static boolean gameOptionsSaveNeeded = false;

	public static void migrateConfig(String configExtension) {
		var legacyConfigPath = IPlatformContextService.INSTANCE.resolveConfigDir("ping-wheel" + configExtension);
		var newConfigPath = IPlatformContextService.INSTANCE.resolveConfigDir(MOD_ID + configExtension);

		if (!Files.exists(legacyConfigPath)) return;

		try {
			Files.move(legacyConfigPath, newConfigPath, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("Renamed legacy config: %s -> %s".formatted(legacyConfigPath, newConfigPath));
		} catch (IOException e) {
			LOGGER.error("Failed to rename legacy config: %s".formatted(e));
		}
	}

	public static void migrateKeyMappings() {
		var optionsPath = Minecraft.getInstance().gameDirectory.toPath().resolve("options.txt");

		if (!Files.exists(optionsPath)) {
			LOGGER.warn("Unable to find game options for migration routine");
			return;
		}

		try {
			var lines = Files.readAllLines(optionsPath);

			for (String line : lines) {
				if (!line.startsWith("key_ping-wheel.key")) continue;

				var keyString = line.split(":")[1];
				var keyKey = InputConstants.getKey(keyString);

				if (line.startsWith("key_ping-wheel.key.ping-location")) {
					KEY_BINDING_PING.setKey(keyKey);
					LOGGER.info("Migrated KEY_BINDING_PING: %s".formatted(keyKey));
				} else if (line.startsWith("key_ping-wheel.key.open-settings")) {
					KEY_BINDING_SETTINGS.setKey(keyKey);
					LOGGER.info("Migrated KEY_BINDING_SETTINGS: %s".formatted(keyKey));
				} else if (line.startsWith("key_ping-wheel.key.name-labels")) {
					KEY_BINDING_NAME_LABELS.setKey(keyKey);
					LOGGER.info("Migrated KEY_BINDING_NAME_LABELS: %s".formatted(keyKey));
				}

				gameOptionsSaveNeeded = true;
			}
		} catch (IOException e) {
			LOGGER.error("Failed to read options.txt to transfer legacy keybinds: %s".formatted(e));
		}
	}

	public static void saveGameOptionsIfNeeded() {
		if (!gameOptionsSaveNeeded || Game == null) return;

		Game.options.save();
		gameOptionsSaveNeeded = false;
	}
}
