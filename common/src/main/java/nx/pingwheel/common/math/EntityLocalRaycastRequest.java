package nx.pingwheel.common.math;

import java.util.Objects;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Immutable live-game context supplied once to an owned-entity narrowphase.
 *
 * <p>The request intentionally keeps the original caller endpoints instead of
 * reconstructing a segment from a captured direction. {@link #pointAt(double)}
 * is therefore the only point construction used by the common raycast after a
 * local callback supplies a normalized parameter.</p>
 */
public record EntityLocalRaycastRequest(
	Vec3 start,
	Vec3 end,
	RaycastPolicy policy,
	float partialTick,
	CollisionContext collisionContext,
	Vec3 cameraFeetPosition
) {

	public EntityLocalRaycastRequest {
		requireFinite(start, "start");
		requireFinite(end, "end");
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(collisionContext, "collisionContext");
		requireFinite(cameraFeetPosition, "cameraFeetPosition");

		if (!Float.isFinite(partialTick)) {
			throw new IllegalArgumentException("partialTick must be finite");
		}
	}

	/** Returns the exact point on the original unnormalized segment at {@code t}. */
	public Vec3 pointAt(double t) {
		if (!Double.isFinite(t)) {
			throw new IllegalArgumentException("t must be finite");
		}

		return start.add(end.subtract(start).scale(t));
	}

	public boolean hasNonDegenerateSegment() {
		return start.distanceToSqr(end) > 0.0;
	}

	private static void requireFinite(Vec3 value, String name) {
		Objects.requireNonNull(value, name);

		if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
			throw new IllegalArgumentException(name + " must contain only finite coordinates");
		}
	}
}
