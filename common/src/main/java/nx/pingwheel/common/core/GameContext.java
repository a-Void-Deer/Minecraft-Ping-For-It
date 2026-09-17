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
	private static final RenderEntityLookupCache<ClientLevel, Entity> RENDER_ENTITY_LOOKUP_CACHE =
		new RenderEntityLookupCache<>(new RenderEntityLookupCache.Access<>() {
			@Override
			public Iterable<Entity> entitiesForRendering(ClientLevel world) {
				return world.entitiesForRendering();
			}

			@Override
			public Entity getById(ClientLevel world, int id) {
				return world.getEntity(id);
			}

			@Override
			public UUID uuid(Entity entity) {
				return entity.getUUID();
			}

			@Override
			public int id(Entity entity) {
				return entity.getId();
			}

			@Override
			public boolean removed(Entity entity) {
				return entity.isRemoved();
			}
		});

	public static void updateDimension() {
		if (Game.level == null || lastWorld == Game.level) {
			return;
		}

		lastWorld = Game.level;
		dimension = lastWorld.dimension().location().hashCode();
	}

	/**
	 * Performs a fresh UUID scan of the current client render collection.
	 * Non-render callers use this API so their live lookup semantics never depend
	 * on a render-pass cache.
	 */
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
	 * wire. UUIDs retain the fresh rendering-entity scan; runtime ids use the
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

	/** Starts the independent render-world-pass UUID lookup epoch. */
	public static void beginRenderEntityLookupFrame() {
		RENDER_ENTITY_LOOKUP_CACHE.beginFrame(Game == null ? null : Game.level);
	}

	/** Drops all render-only cached world and entity references. */
	public static void clearRenderEntityLookupCache() {
		RENDER_ENTITY_LOOKUP_CACHE.clear();
	}

	/**
	 * Resolves an entity for a render consumer. UUID locators share the current
	 * render-pass cache; runtime-ID experience-orb lookup remains direct.
	 */
	public static Entity getEntityForRender(EntityLocator locator) {
		if (Game == null || Game.level == null || locator == null) {
			return null;
		}

		return switch (locator) {
			case EntityLocator.UUID uuid -> getEntityForRender(uuid.value());
			case EntityLocator.RuntimeId runtimeId -> {
				Entity entity = Game.level.getEntity(runtimeId.value());
				yield entity instanceof ExperienceOrb ? entity : null;
			}
		};
	}

	/** Resolves a UUID for a render consumer through the current pass cache. */
	public static Entity getEntityForRender(UUID uuid) {
		if (Game == null || Game.level == null) {
			return null;
		}

		return RENDER_ENTITY_LOOKUP_CACHE.findUuid(Game.level, uuid);
	}

	public static Optional<String> getCurrentServerIp() {
		if (Game == null) return Optional.empty();

		var currenServer = Game.getCurrentServer();
		if (currenServer == null) return Optional.empty();

		return Optional.of(currenServer.ip);
	}
}
