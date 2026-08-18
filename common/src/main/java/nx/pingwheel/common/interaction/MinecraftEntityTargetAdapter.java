package nx.pingwheel.common.interaction;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.ExperienceOrb;

import nx.pingwheel.common.domain.EntityCaptureMetadata;
import nx.pingwheel.common.domain.EntityLocator;

/**
 * Minecraft 1.21.1 entity-target adapter used at ray-hit capture and by the
 * entity outline render hook.
 *
 * <p>Ray hits on an {@link EnderDragonPart} are canonicalized to the parent
 * dragon before any identity or type data is read. Experience orbs use their
 * synchronized runtime id because the client does not receive a stable orb
 * UUID in its spawn packet; every other entity uses its UUID.
 */
public final class MinecraftEntityTargetAdapter {

	private MinecraftEntityTargetAdapter() {}

	/**
	 * Extracts the canonical entity, locator, type snapshot, and privacy-safe
	 * capture metadata from one ray-hit entity.
	 */
	public static CapturedEntity capture(Entity hitEntity) {
		Objects.requireNonNull(hitEntity, "hitEntity");

		Entity canonicalEntity = canonicalEntity(hitEntity);
		EntityLocator locator = locatorForCanonical(canonicalEntity);
		var typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(canonicalEntity.getType());

		return new CapturedEntity(
			canonicalEntity,
			locator,
			Optional.ofNullable(typeKey).map(Object::toString),
			new EntityCaptureMetadata(
				locator.kind(),
				hitEntity instanceof EnderDragonPart));
	}

	/**
	 * Returns the canonical entity used for marker tracking and rendering.
	 */
	public static Entity canonicalEntity(Entity entity) {
		Objects.requireNonNull(entity, "entity");

		if (entity instanceof EnderDragonPart part) {
			return Objects.requireNonNull(part.parentMob, "part.parentMob");
		}

		return entity;
	}

	/**
	 * Derives the render/marker locator for the canonical entity represented by
	 * {@code entity}. Runtime ids are validated by {@link EntityLocator}.
	 */
	public static EntityLocator locatorFor(Entity entity) {
		return locatorForCanonical(canonicalEntity(entity));
	}

	private static EntityLocator locatorForCanonical(Entity canonicalEntity) {
		if (canonicalEntity instanceof ExperienceOrb) {
			return EntityLocator.runtimeId(canonicalEntity.getId());
		}

		return EntityLocator.uuid(canonicalEntity.getUUID());
	}

	/**
	 * Immutable adapter output. The entity reference is only used on the game
	 * thread; the locator, type id, and metadata are the frozen capture values.
	 */
	public record CapturedEntity(
		Entity canonicalEntity,
		EntityLocator locator,
		Optional<String> entityTypeId,
		EntityCaptureMetadata metadata
	) {
		public CapturedEntity {
			Objects.requireNonNull(canonicalEntity, "canonicalEntity");
			Objects.requireNonNull(locator, "locator");
			Objects.requireNonNull(entityTypeId, "entityTypeId");
			Objects.requireNonNull(metadata, "metadata");
		}
	}
}
