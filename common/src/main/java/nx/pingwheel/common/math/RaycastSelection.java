package nx.pingwheel.common.math;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Detailed press-time raycast selection.
 *
 * <p>The optional local hit is populated only when the selected final hit is
 * an owned entity narrowphase result. A world hit or legacy entity-AABB hit
 * deliberately has no local-geometry metadata.</p>
 */
public record RaycastSelection(HitResult hitResult, Optional<EntityLocalHit> entityLocalHit,
	Optional<Vec3> worldPoint, Optional<SableBlockHitFrame> blockFrame) {

	/** Legacy callers already supply world-space hits. Production plot clips use the explicit constructor. */
	public RaycastSelection(HitResult hitResult, Optional<EntityLocalHit> entityLocalHit) {
		this(hitResult, entityLocalHit, Optional.of(hitResult.getLocation()), Optional.empty());
	}

	public RaycastSelection {
		Objects.requireNonNull(hitResult, "hitResult");
		Objects.requireNonNull(entityLocalHit, "entityLocalHit");
		Objects.requireNonNull(worldPoint, "worldPoint");
		Objects.requireNonNull(blockFrame, "blockFrame");
		if (hitResult.getType() != HitResult.Type.BLOCK
			&& !worldPoint.equals(Optional.of(hitResult.getLocation()))) {
			throw new IllegalArgumentException("entity and miss hits must carry their world point");
		}
		worldPoint.ifPresent(point -> {
			if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
				throw new IllegalArgumentException("world point must be finite");
			}
		});
		if (blockFrame.isPresent() && (!(hitResult instanceof BlockHitResult block)
			|| !blockFrame.orElseThrow().binds(block)
			|| !worldPoint.equals(Optional.of(blockFrame.orElseThrow().frame().toWorld(block.getLocation()))))) {
			throw new IllegalArgumentException("block frame must bind the native hit and its world point");
		}

		if (entityLocalHit.isPresent() && hitResult.getType() != HitResult.Type.ENTITY) {
			throw new IllegalArgumentException("only an entity hit can retain local geometry metadata");
		}
	}

	public static RaycastSelection withoutLocalGeometry(HitResult hitResult) {
		return new RaycastSelection(hitResult, Optional.empty());
	}
}
