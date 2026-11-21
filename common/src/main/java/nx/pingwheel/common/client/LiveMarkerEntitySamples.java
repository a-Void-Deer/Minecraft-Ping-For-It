package nx.pingwheel.common.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import nx.pingwheel.common.client.marker.ClientMarker;
import nx.pingwheel.common.client.marker.EntityMarkerPoint;
import nx.pingwheel.common.domain.EntityLocator;
import nx.pingwheel.common.domain.Target;
import nx.pingwheel.common.interaction.cancel.WorldVector;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Capture-local live marker samples for cancellation candidates.
 *
 * <p>One call resolves each distinct requested UUID from a single pass over
 * the current level's rendering entities and stops as soon as each UUID has
 * its first raw match. Only available live points are stored; the sample map
 * and its entity references never survive the call, so a later capture in the
 * same tick observes movement, spawns and removals. A marker collection with
 * no UUID target does not touch the rendering collection. Runtime-id locators
 * remain direct level integer lookups outside this batch.
 */
@NotNullByDefault
final class LiveMarkerEntitySamples {

	private LiveMarkerEntitySamples() {}

	/**
	 * Samples each distinct UUID target once. A UUID whose first raw match is
	 * absent or removed has no map entry; each owning marker then applies its
	 * own presentation or authoritative-anchor fallback. A later duplicate
	 * entity with the same UUID is never substituted for that first match.
	 */
	static @Unmodifiable Map<UUID, WorldVector> sampleUuids(
		ClientLevel level,
		@Unmodifiable List<ClientMarker> markers
	) {
		Set<UUID> remaining = new HashSet<>();

		for (ClientMarker marker : markers) {
			if (marker.target() instanceof Target.EntityTarget target
				&& target.locator() instanceof EntityLocator.UUID uuid) {
				remaining.add(uuid.value());
			}
		}

		if (remaining.isEmpty()) {
			return Map.of();
		}

		Map<UUID, WorldVector> samples = new HashMap<>();

		for (Entity entity : level.entitiesForRendering()) {
			UUID entityId = entity.getUUID();

			if (!remaining.remove(entityId)) {
				continue;
			}

			sample(entity).ifPresent(point -> samples.put(entityId, point));

			if (remaining.isEmpty()) {
				break;
			}
		}

		return Collections.unmodifiableMap(samples);
	}

	/** The trusted displayed top-center of one live entity, or no live point. */
	static Optional<WorldVector> sample(@Nullable Entity entity) {
		if (entity == null || entity.isRemoved()) {
			return Optional.empty();
		}

		@Nullable Vec3 topCenter = EntityMarkerPoint.forLiveEntity(entity, 1.0F);
		return topCenter == null ? Optional.empty()
			: Optional.of(new WorldVector(topCenter.x, topCenter.y, topCenter.z));
	}
}
