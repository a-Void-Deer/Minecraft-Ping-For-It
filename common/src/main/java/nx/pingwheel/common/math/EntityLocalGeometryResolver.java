package nx.pingwheel.common.math;

import net.minecraft.world.entity.Entity;

/**
 * Loader-facing narrowphase callback for one owned entity type.
 *
 * <p>Implementations receive the exact press-time request and must return a
 * normalized local hit when they can resolve their owned geometry. A callback
 * must not attempt a legacy entity AABB fallback; the common raycast preserves
 * that fallback only for entities without an owner.</p>
 */
@FunctionalInterface
public interface EntityLocalGeometryResolver {

	EntityLocalGeometryResult trace(Entity candidate, EntityLocalRaycastRequest request);
}
