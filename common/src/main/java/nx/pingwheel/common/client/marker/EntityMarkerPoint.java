package nx.pingwheel.common.client.marker;

import java.util.Objects;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The single marker point shown for a live entity target: the top-center of
 * its current bounding box, i.e. its interpolated position lifted by the
 * bounding box Y size.
 *
 * <p>Both rendering ({@link MarkerView}) and cancellation candidate selection
 * use this point so the displayed marker and the cancelable point always
 * agree while the entity is live.
 */
public final class EntityMarkerPoint {

	private EntityMarkerPoint() {}

	/**
	 * The top-center point {@code basePosition + (0, boundingBoxYSize, 0)}.
	 */
	public static Vec3 topCenter(Vec3 basePosition, double boundingBoxYSize) {
		Objects.requireNonNull(basePosition, "basePosition");
		return basePosition.add(0.0, boundingBoxYSize, 0.0);
	}

	/**
	 * The displayed marker point of a live entity: its {@code tickDelta}
	 * interpolated position lifted by the current bounding box Y size.
	 */
	public static Vec3 forLiveEntity(Entity entity, float tickDelta) {
		Objects.requireNonNull(entity, "entity");
		return topCenter(entity.getPosition(tickDelta), entity.getBoundingBox().getYsize());
	}
}
