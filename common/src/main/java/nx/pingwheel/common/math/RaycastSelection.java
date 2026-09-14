package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.phys.HitResult;

/**
 * Detailed press-time raycast selection.
 *
 * <p>The optional local hit is populated only when the selected final hit is
 * an owned entity narrowphase result. A world hit or legacy entity-AABB hit
 * deliberately has no local-geometry metadata.</p>
 */
public record RaycastSelection(HitResult hitResult, Optional<EntityLocalHit> entityLocalHit) {

	public RaycastSelection {
		Objects.requireNonNull(hitResult, "hitResult");
		Objects.requireNonNull(entityLocalHit, "entityLocalHit");

		if (entityLocalHit.isPresent() && hitResult.getType() != HitResult.Type.ENTITY) {
			throw new IllegalArgumentException("only an entity hit can retain local geometry metadata");
		}
	}

	public static RaycastSelection withoutLocalGeometry(HitResult hitResult) {
		return new RaycastSelection(hitResult, Optional.empty());
	}
}
