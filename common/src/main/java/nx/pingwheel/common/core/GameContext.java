package nx.pingwheel.common.core;

import lombok.Getter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;

import nx.pingwheel.common.domain.EntityLocator;

import java.util.Optional;
import java.util.UUID;

import static nx.pingwheel.common.CommonClient.Game;

public class GameContext {
	private GameContext() {}

	@Getter
	private static int dimension = 0;
	private static ClientLevel lastWorld = null;

	public static void updateDimension() {
		if (Game.level == null || lastWorld == Game.level) {
			return;
		}

		lastWorld = Game.level;
		dimension = lastWorld.dimension().location().hashCode();
	}

	public static Entity getEntity(UUID uuid) {
		if (Game.level == null) {
			return null;
		}

		for (var entity : Game.level.entitiesForRendering()) {
			if (entity.getUUID().equals(uuid)) {
				return entity;
			}
		}

		return null;
	}

	/**
	 * Resolves a client entity using the locator representation captured on the
	 * wire. UUIDs retain the existing rendering-entity scan; runtime ids use the
	 * client level's integer lookup and are accepted only for experience orbs.
	 */
	public static Entity getEntity(EntityLocator locator) {
		if (Game.level == null || locator == null) {
			return null;
		}

		return switch (locator) {
			case EntityLocator.UUID uuid -> getEntity(uuid.value());
			case EntityLocator.RuntimeId runtimeId -> {
				Entity entity = Game.level.getEntity(runtimeId.value());
				yield entity instanceof ExperienceOrb ? entity : null;
			}
		};
	}

	public static Optional<String> getCurrentServerIp() {
		if (Game == null) return Optional.empty();

		var currenServer = Game.getCurrentServer();
		if (currenServer == null) return Optional.empty();

		return Optional.of(currenServer.ip);
	}
}
