package nx.pingwheel.common.marker;

import java.util.Objects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;

import nx.pingwheel.common.domain.EntityLocator;

/**
 * Server-only lookup adapter shared by authoritative target validation and
 * authoritative target naming.
 *
 * <p>The helper owns the locator policy: UUIDs use the existing UUID lookup,
 * runtime ids use the server integer lookup and are valid only for experience
 * orbs. Callers must inspect {@link Result#outcome()} before reading the entity
 * or normalized locator.
 */
public final class MinecraftServerEntityLookup {

	private MinecraftServerEntityLookup() {}

	public static Result find(ServerLevel level, EntityLocator requested) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(requested, "requested");

		Entity entity = switch (requested) {
			case EntityLocator.UUID uuid -> level.getEntity(uuid.value());
			case EntityLocator.RuntimeId runtimeId -> level.getEntity(runtimeId.value());
		};

		boolean present = entity != null;
		boolean experienceOrb = entity instanceof ExperienceOrb;
		ServerEntityLocatorPolicy.Outcome outcome = ServerEntityLocatorPolicy.classify(
			requested,
			present,
			present && entity.isAlive(),
			present && entity.isRemoved(),
			experienceOrb);

		if (outcome != ServerEntityLocatorPolicy.Outcome.ACCEPTED) {
			return new Result(requested, null, null, outcome);
		}

		EntityLocator normalized = ServerEntityLocatorPolicy.normalize(
			experienceOrb,
			entity.getUUID(),
			entity.getId());

		return new Result(requested, entity, normalized, outcome);
	}

	public record Result(
		EntityLocator requested,
		Entity entity,
		EntityLocator normalized,
		ServerEntityLocatorPolicy.Outcome outcome
	) {
		public Result {
			Objects.requireNonNull(requested, "requested");
			Objects.requireNonNull(outcome, "outcome");

			if (outcome == ServerEntityLocatorPolicy.Outcome.ACCEPTED) {
				Objects.requireNonNull(entity, "entity");
				Objects.requireNonNull(normalized, "normalized");
			} else if (entity != null || normalized != null) {
				throw new IllegalArgumentException("rejected lookup result must not carry an entity");
			}
		}

		public boolean accepted() {
			return outcome == ServerEntityLocatorPolicy.Outcome.ACCEPTED;
		}
	}
}
