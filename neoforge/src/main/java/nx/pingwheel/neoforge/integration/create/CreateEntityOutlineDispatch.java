package nx.pingwheel.neoforge.integration.create;

import java.util.Objects;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.math.EntityFrameResolution;
import nx.pingwheel.common.math.EntityParentFrame;
import org.jetbrains.annotations.NotNullByDefault;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

/**
 * Create-free placement seam for the optional Create entity-outline dispatcher.
 *
 * <p>A retained rotor keeps its parent's full placement at the interpolated
 * native anchor, while the parent rotation alone orients the renderer-local
 * model. The supplied sink receives the one prepared dispatcher call: the pose
 * stack already contains the anchor translation and parent rotation, and a
 * contained entity's coordinate argument is zero so the dispatcher does not
 * add that translation a second time. World and unrelated entities retain the
 * original camera-relative native coordinates and identity pose.</p>
 */
@NotNullByDefault
final class CreateEntityOutlineDispatch {

	@FunctionalInterface
	@NotNullByDefault
	interface Sink {
		void render(double x, double y, double z, float yaw, float partialTick, PoseStack poseStack);
	}

	@NotNullByDefault
	enum Result { DISPATCHED, FRAME_UNAVAILABLE }

	private CreateEntityOutlineDispatch() {}

	static Result dispatch(
		Vec3 nativeAnchor,
		Vec3 camera,
		float yaw,
		float partialTick,
		EntityFrameResolution resolution,
		Sink sink
	) {
		Objects.requireNonNull(nativeAnchor, "nativeAnchor");
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(resolution, "resolution");
		Objects.requireNonNull(sink, "sink");
		if (!Float.isFinite(yaw)) {
			throw new IllegalArgumentException("yaw must be finite");
		}
		if (resolution.status() == EntityFrameResolution.Status.UNAVAILABLE) {
			return Result.FRAME_UNAVAILABLE;
		}

		PoseStack poseStack = new PoseStack();
		if (resolution.status() == EntityFrameResolution.Status.CONTAINED) {
			EntityParentFrame frame = resolution.frame().orElseThrow(
				() -> new IllegalArgumentException("contained resolution must carry a parent frame"));
			Vec3 translation = finite(frame.toWorld(nativeAnchor).subtract(camera));
			Quaterniond rotation = frame.rotationCopy();
			poseStack.translate(translation.x, translation.y, translation.z);
			poseStack.mulPose(new Quaternionf(
				(float) rotation.x, (float) rotation.y, (float) rotation.z, (float) rotation.w));
			sink.render(0.0, 0.0, 0.0, yaw, partialTick, poseStack);
			return Result.DISPATCHED;
		}

		if (resolution.status() != EntityFrameResolution.Status.WORLD) {
			throw new IllegalArgumentException("unsupported entity frame status");
		}
		Vec3 translation = finite(nativeAnchor.subtract(camera));
		sink.render(translation.x, translation.y, translation.z, yaw, partialTick, poseStack);
		return Result.DISPATCHED;
	}

	private static Vec3 finite(Vec3 value) {
		if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
			throw new IllegalArgumentException("camera-relative placement must be finite");
		}
		return value;
	}
}
