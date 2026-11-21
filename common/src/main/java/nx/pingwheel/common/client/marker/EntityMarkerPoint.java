package nx.pingwheel.common.client.marker;

import java.util.Objects;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.integration.sable.client.SableClientEntityFrames;
import nx.pingwheel.common.math.EntityFrameResolution;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * The single marker point shown for a live entity target: the top-center of
 * its current native bounding box, i.e. its interpolated base position
 * projected into the current render world and lifted by the unrotated,
 * unscaled bounding box Y size.
 *
 * <p>Both rendering ({@link MarkerView}) and cancellation candidate selection
 * use this point so the displayed marker and the cancelable point always
 * agree while the entity is live. Missing or untrusted render frames produce
 * no live point, allowing the marker tracker to retain its existing fallback.
 */
@NotNullByDefault
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
	 * interpolated native base position projected through the optional render
	 * parent frame, then lifted by the current native bounding box Y size.
	 *
	 * @return the trusted world point, or {@code null} when no render frame is
	 *         available or the frame cannot produce a finite world point
	 */
	public static @Nullable Vec3 forLiveEntity(Entity entity, float tickDelta) {
		return forLiveEntity(entity, tickDelta, SableClientEntityFrames::render);
	}

	/**
	 * Render-frame seam kept package-private so tests exercise the production
	 * interpolation, projection and height arithmetic without loading an
	 * optional Sable render pose.
	 */
	static @Nullable Vec3 forLiveEntity(Entity entity, float tickDelta, RenderFrame renderFrame) {
		Objects.requireNonNull(entity, "entity");
		Objects.requireNonNull(renderFrame, "renderFrame");

		EntityFrameResolution resolution;

		try {
			resolution = Objects.requireNonNull(renderFrame.render(entity, tickDelta), "renderFrame");
		} catch (RuntimeException | LinkageError unavailable) {
			return null;
		}

		if (resolution.status() == EntityFrameResolution.Status.UNAVAILABLE) {
			return null;
		}

		try {
			Vec3 nativeBase = entity.getPosition(tickDelta);
			Vec3 worldBase = resolution.status() == EntityFrameResolution.Status.WORLD
				? nativeBase
				: resolution.frame().orElseThrow().toWorld(nativeBase);
			Vec3 point = topCenter(worldBase, entity.getBoundingBox().getYsize());
			return finite(point) ? point : null;
		} catch (RuntimeException unavailable) {
			return null;
		}
	}

	private static boolean finite(Vec3 position) {
		return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
	}

	@FunctionalInterface
	@NotNullByDefault
	interface RenderFrame {
		EntityFrameResolution render(Entity entity, float partialTick);
	}
}
